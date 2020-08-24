package com.btcontract.wallet.ln

import fr.acinq.eclair._
import com.btcontract.wallet.ln.crypto.Tools._
import com.btcontract.wallet.ln.PaymentMaster._
import fr.acinq.eclair.{CltvExpiry, MilliSatoshi}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import com.btcontract.wallet.ln.ChannelListener.{Malfunction, Transition}
import com.btcontract.wallet.ln.HostedChannel.{OPEN, SLEEPING, SUSPENDED}
import fr.acinq.eclair.router.Graph.GraphStructure.{DescAndCapacity, GraphEdge}
import com.btcontract.wallet.ln.crypto.{CMDAddImpossible, CanBeRepliedTo, StateMachine}
import fr.acinq.eclair.router.Router.{ChannelDesc, Route, RouteParams, RouteRequest, RouteResponse}
import fr.acinq.eclair.wire.UpdateFulfillHtlc
import fr.acinq.eclair.channel.CMD_ADD_HTLC
import fr.acinq.bitcoin.Crypto.PublicKey
import java.util.concurrent.Executors
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.bitcoin.ByteVector32
import scala.collection.mutable
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

case class NodeFailed(nodeId: PublicKey, times: Int)
case class ChannelFailed(dac: DescAndCapacity, amount: MilliSatoshi, times: Int)
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

    case (response: RouteResponse, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      // Switch state to allow new route requests to come through
      relayTo(response.paymentHash, response)
      become(data, EXPECTING_PAYMENTS)
      me process CMDAskForRoute

    case (req: RouteRequest, EXPECTING_PAYMENTS) =>
      // IMPLICIT GUARD: ignore in other states, payment will be able to re-send later
      val currentUsedCapacities: mutable.Map[DescAndCapacity, MilliSatoshi] = getUsedCapacities
      val currentUsedDescs: mutable.Map[ChannelDesc, MilliSatoshi] = for (dac \ used <- currentUsedCapacities) yield dac.desc -> used
      val ignoreChansFailedTimes = data.chanFailedTimes collect { case desc \ times if times >= cm.pf.routerConf.maxChannelFailures => desc }
      val ignoreChansCanNotHandle = currentUsedCapacities collect { case DescAndCapacity(desc, capacity) \ used if used + req.amount >= capacity => desc }
      val ignoreChansFailedAtAmount = data.chanFailedAtAmount collect { case desc \ failedAt if failedAt - currentUsedDescs(desc) - req.reserve <= req.amount => desc }
      val ignoreNodes = data.nodeFailedWithUnknownUpdateTimes collect { case nodeId \ times if times >= cm.pf.routerConf.maxNodeFailures => nodeId }
      val ignoreChans = ignoreChansFailedTimes.toSet ++ ignoreChansCanNotHandle ++ ignoreChansFailedAtAmount
      val request1 = req.copy(ignoreNodes = ignoreNodes.toSet, ignoreChannels = ignoreChans)
      cm.pf process Tuple2(me, request1)
      become(data, WAITING_FOR_ROUTE)

    case (ChannelFailed(descAndCapacity: DescAndCapacity, amount, times), EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      // At this point an affected InFlight statuse was removed by paymentFSM so failedAtAmount = sum(inFlight) + failed amount
      val newFailedAtAmount = data.chanFailedAtAmount(descAndCapacity.desc).min(getUsedCapacities(descAndCapacity) + amount)
      val atTimes1 = data.chanFailedTimes.updated(descAndCapacity.desc, data.chanFailedTimes(descAndCapacity.desc) + times)
      val atAmount1 = data.chanFailedAtAmount.updated(descAndCapacity.desc, newFailedAtAmount)
      become(data.copy(chanFailedAtAmount = atAmount1, chanFailedTimes = atTimes1), state)
      me process CMDAskForRoute

    case (NodeFailed(nodeId, times), EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      val newNodeFailedTimes = data.nodeFailedWithUnknownUpdateTimes(nodeId) + times
      val nodeFailedWithUnknownUpdateTimes1 = data.nodeFailedWithUnknownUpdateTimes.updated(nodeId, newNodeFailedTimes)
      become(data.copy(nodeFailedWithUnknownUpdateTimes = nodeFailedWithUnknownUpdateTimes1), state)
      me process CMDAskForRoute

    case (fulfill: UpdateFulfillHtlc, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      relayTo(fulfill.paymentHash, fulfill)
      me process CMDAskForRoute

    case (chanError: CMDAddImpossible, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      relayTo(chanError.cmd.paymentHash, chanError)
      me process CMDAskForRoute

    case (malform: MalformAndAdd, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      relayTo(malform.ourAdd.paymentHash, malform)
      me process CMDAskForRoute

    case (fail: FailAndAdd, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      relayTo(fail.ourAdd.paymentHash, fail)
      me process CMDAskForRoute
  }

  // Executed in channelContext
  override def stateUpdated(hc: HostedCommits): Unit = {
    hc.localSpec.remoteMalformed.foreach(process)
    hc.localSpec.remoteFailed.foreach(process)
  }

  override def fulfillReceived(fulfill: UpdateFulfillHtlc): Unit = me process fulfill
  override def onException: PartialFunction[Malfunction, Unit] = { case (_, error: CMDAddImpossible) => me process error }
  override def onBecome: PartialFunction[Transition, Unit] = { case (_, _, SLEEPING | SUSPENDED, OPEN) => me process CMDChanGotOnline }

  private def relayToAll(changeMessage: Any): Unit = data.payments.values.foreach(_ doProcess changeMessage)
  private def relayTo(hash: ByteVector32, change: Any): Unit = data.payments.get(hash).foreach(_ doProcess change)

  private def makeSender(sender: PaymentSender, cmd: CMD_SEND_MPP): Unit = {
    val payments1 = data.payments.updated(cmd.paymentHash, sender)
    become(data.copy(payments = payments1), state)
    sender doProcess cmd
  }

  private def getUsedCapacities: mutable.Map[DescAndCapacity, MilliSatoshi] = {
    val accum = mutable.Map.empty[DescAndCapacity, MilliSatoshi] withDefaultValue 0L.msat

    for {
      senderFSM <- data.payments.values
      InFlight(_, route, _, _) <- senderFSM.data.parts.values
      amount \ graphEdge <- route.amountPerGraphEdge
    } accum(graphEdge) += amount
    accum
  }
}
