package com.btcontract.wallet.ln

import fr.acinq.eclair._
import com.btcontract.wallet.ln.crypto.Tools._
import com.btcontract.wallet.ln.PaymentMaster._
import fr.acinq.eclair.router.Router.{ChannelDesc, Route, RouteParams, RouteRequest, RouteResponse}
import fr.acinq.eclair.{CltvExpiry, MilliSatoshi, ShortChannelId}

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import com.btcontract.wallet.ln.ChannelListener.{Malfunction, Transition}
import com.btcontract.wallet.ln.HostedChannel.{OPEN, SLEEPING, SUSPENDED}
import com.btcontract.wallet.ln.crypto.{CMDAddImpossible, CanBeRepliedTo, StateMachine}
import fr.acinq.eclair.router.Graph.GraphStructure.GraphEdge
import fr.acinq.eclair.wire.UpdateFulfillHtlc
import fr.acinq.eclair.channel.CMD_ADD_HTLC
import fr.acinq.bitcoin.Crypto.PublicKey
import java.util.concurrent.Executors

import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.bitcoin.ByteVector32
import scodec.bits.ByteVector


sealed trait PaymentFailure {
  def route: Route
}

case class LocalFailure(route: Route, throwable: Throwable) extends PaymentFailure
case class RemoteFailure(route: Route, packet: Sphinx.DecryptedFailurePacket) extends PaymentFailure
case class UnreadableRemoteFailure(route: Route) extends PaymentFailure

sealed trait PartStatus { me =>
  def tuple = Tuple2(partId, me)
  def partId: ByteVector
}

case class WaitForChannel(partId: ByteVector, exceptChanId: Set[ByteVector32], amount: MilliSatoshi) extends PartStatus
case class WaitForRoute(partId: ByteVector, targetChannel: HostedChannel, amount: MilliSatoshi) extends PartStatus
case class InFlight(partId: ByteVector, route: Route, attemptsLeft: Int, cmd: CMD_ADD_HTLC) extends PartStatus

case class PaymentSenderData(parts: Map[ByteVector, PartStatus],
                             failures: Vector[PaymentFailure],
                             cmd: CMD_SEND_MPP)

case class PaymentMasterData(payments: Map[ByteVector32, PaymentSender],
                             chanFailedAtAmount: Map[ChannelDesc, MilliSatoshi] = Map.empty withDefaultValue Long.MaxValue.msat,
                             nodeFailedWithUnknownUpdateTimes: Map[PublicKey, Int] = Map.empty withDefaultValue 0,
                             chanFailedTimes: Map[ChannelDesc, Int] = Map.empty withDefaultValue 0)

case class CMD_SEND_MPP(paymentHash: ByteVector32, paymentSecret: ByteVector32, targetNodeId: PublicKey,
                        totalAmount: MilliSatoshi, targetExpiry: CltvExpiry, assistedEdges: Set[GraphEdge],
                        routeParams: RouteParams)

object PaymentMaster {
  val INIT = "state-init"
  val PENDING = "state-pending"
  val ABORTED = "state-aborted"
  val SUCCEEDED = "state-succeeded"

  val EXPECTING_PAYMENTS = "state-expecting-payments"
  val WAITING_FOR_ROUTE = "state-waiting-for-route"

  val CMDChanGotOnline = "cmd-chan-got-online"
  val CMDAskForRoute = "cmd-ask-for-route"
}

class PaymentSender(master: PaymentMaster) extends StateMachine[PaymentSenderData] {
  // No separate stacking thread, all calles are executed within PaymentMaster context
  become(null, INIT)

  def doProcess(change: Any): Unit = (change, state) match {
    case (cmd: CMD_SEND_MPP, INIT) => ???
    case (CMDAskForRoute, INIT | PENDING) => ???
    case (CMDChanGotOnline, INIT | PENDING) => ???
  }

  def isSplittable(amount: MilliSatoshi): Boolean = {
    amount / 2 > master.cm.pf.routerConf.mppMinPartAmount
  }

  def split(amount: MilliSatoshi): (MilliSatoshi, MilliSatoshi) = {
    // Siple division may be uneven, ensure parts add up by subtracting
    val part1: MilliSatoshi = amount / 2
    val part2 = amount - part1
    part1 -> part2
  }
}

class PaymentMaster(val cm: ChannelMaster) extends StateMachine[PaymentMasterData] with CanBeRepliedTo with ChannelListener { me =>
  implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def process(changeMessage: Any): Unit = scala.concurrent.Future(me doProcess changeMessage)
  become(PaymentMasterData(Map.empty), EXPECTING_PAYMENTS)

  def doProcess(change: Any): Unit = (change, state) match {
    // GUARD: we can't have multiple top-level payments with same hash
    case (cmd: CMD_SEND_MPP, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) if !data.payments.contains(cmd.paymentHash) =>
      // Load graph with assisted edges right away so it can find paths to destination, edges will stay at runtime
      for (assistedGraphEdge <- cmd.assistedEdges) cm.pf process assistedGraphEdge
      makeSender(new PaymentSender(me), cmd)
      relayToAll(CMDAskForRoute)

    case (CMDChanGotOnline, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      // Payments may have awaiting parts due to offline channels
      relayToAll(CMDChanGotOnline)

    case (CMDAskForRoute, EXPECTING_PAYMENTS) =>
      // IMPLICIT GUARD: ignore in other states
      relayToAll(CMDAskForRoute)

    case (request: RouteRequest, EXPECTING_PAYMENTS) =>
      // IMPLICIT GUARD: ignore in other states, payment will be able to re-send later
      val nodes = data.nodeFailedWithUnknownUpdateTimes collect { case nodeId \ times if times >= cm.pf.routerConf.maxNodeFailures => nodeId }
      val chansAmount = data.chanFailedTimes collect { case chanDesc \ times if times >= cm.pf.routerConf.maxChannelFailures => chanDesc }
      val chansTimes = data.chanFailedAtAmount collect { case chanDesc \ amount if amount >= request.amount => chanDesc }
      val request1 = request.copy(ignoreNodes = nodes.toSet, ignoreChannels = chansAmount.toSet ++ chansTimes)
      cm.pf process Tuple2(me, request1)
      become(data, WAITING_FOR_ROUTE)

    case (response: RouteResponse, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) => relayTo(response.paymentHash, response)

    case (fulfill: UpdateFulfillHtlc, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) => relayTo(fulfill.paymentHash, fulfill)

    case (chanError: CMDAddImpossible, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) => relayTo(chanError.cmd.paymentHash, chanError)

    case (malform: MalformAndAdd, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) => relayTo(malform.ourAdd.paymentHash, malform)

    case (fail: FailAndAdd, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) => relayTo(fail.ourAdd.paymentHash, fail)
  }

  // Executed in channelContext
  override def stateUpdated(hc: HostedCommits): Unit = {
    hc.localSpec.remoteMalformed.foreach(process)
    hc.localSpec.remoteFailed.foreach(process)
  }

  override def fulfillReceived(fulfill: UpdateFulfillHtlc): Unit = me process fulfill
  override def onException: PartialFunction[Malfunction, Unit] = { case (_, error: CMDAddImpossible) => me process error }
  override def onBecome: PartialFunction[Transition, Unit] = { case (_, _, SLEEPING | SUSPENDED, OPEN) => me process CMDChanGotOnline }

  private def relayTo(hash: ByteVector32, change: Any): Unit = {
    // Stage 1: payment is given a chance to update its state here
    data.payments.get(hash).foreach(_ doProcess change)
    // Stage 2: ask for new routes in next pass
    me process CMDAskForRoute
  }

  private def relayToAll(change: Any): Unit = {
    data.payments.values.foreach(_ doProcess change)
  }

  private def makeSender(sender: PaymentSender, cmd: CMD_SEND_MPP): Unit = {
    val payments1 = data.payments.updated(cmd.paymentHash, sender)
    become(data.copy(payments = payments1), state)
    sender doProcess cmd
  }

  private def getUsedCapacities = {
    ???
  }
}
