package com.btcontract.wallet.ln

import com.btcontract.wallet.ln.PaymentMaster._
import fr.acinq.eclair.{CltvExpiry, MilliSatoshi, ShortChannelId}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import com.btcontract.wallet.ln.ChannelListener.{Malfunction, Transition}
import com.btcontract.wallet.ln.HostedChannel.{OPEN, SLEEPING, SUSPENDED}
import fr.acinq.eclair.router.Router.{Route, RouteParams, RouteRequest, RouteResponse}
import com.btcontract.wallet.ln.crypto.{CMDAddImpossible, CanBeRepliedTo, StateMachine}

import fr.acinq.eclair.router.Graph.GraphStructure.GraphEdge
import com.btcontract.wallet.ln.SyncMaster.ShortChanIdSet
import fr.acinq.eclair.wire.UpdateFulfillHtlc
import fr.acinq.eclair.channel.CMD_ADD_HTLC
import fr.acinq.bitcoin.Crypto.PublicKey
import java.util.concurrent.Executors
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.bitcoin.ByteVector32
import scodec.bits.ByteVector


case class CMD_SEND_MPP(paymentHash: ByteVector32, paymentSecret: ByteVector32, targetNodeId: PublicKey,
                        totalAmount: MilliSatoshi, targetExpiry: CltvExpiry, assistedEdges: Set[GraphEdge],
                        routeParams: RouteParams)

sealed trait PaymentFailure { def route: Route }
case class LocalFailure(route: Route, throwable: Throwable) extends PaymentFailure
case class RemoteFailure(route: Route, packet: Sphinx.DecryptedFailurePacket) extends PaymentFailure
case class UnreadableRemoteFailure(route: Route) extends PaymentFailure

sealed trait PartStatus
case class WaitForChannel(exceptChanId: Set[ByteVector32], amount: MilliSatoshi) extends PartStatus
case class WaitForRoute(forChan: HostedChannel, amount: MilliSatoshi) extends PartStatus
case class InFlight(route: Route, failedChans: ShortChanIdSet) extends PartStatus

case class PaymentSenderData(parts: Map[ByteVector, PartStatus], cmd: CMD_ADD_HTLC)
case class PaymentMasterData(payments: Map[ByteVector32, PaymentSender], currentUsedCapacity: Map[ShortChannelId, MilliSatoshi],
                             chanFailedAtAmount: Map[ShortChannelId, MilliSatoshi], nodeFailedTimes: Map[PublicKey, Int],
                             ignoreNodes: Set[PublicKey] = Set.empty)

object PaymentMaster {
  val INIT = "state-init"
  val PENDING = "state-pending"
  val ABORTED = "state-aborted"
  val SUCCEEDED = "state-succeeded"
  val EXPECTING_PAYMENTS = "state-expecting-payments"
  val WAITING_FOR_ROUTE = "state-waiting-for-route"
  val CMDCanAskRoutes = "cmd-can-ask-routes"
}

class PaymentSender(master: PaymentMaster) extends StateMachine[PaymentSenderData] { me =>
  implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def process(changeMessage: Any): Unit = scala.concurrent.Future(me doProcess changeMessage)
  become(null, INIT)

  def doProcess(change: Any): Unit = ???

  def splittable(wholePaymentAmount: MilliSatoshi): Boolean = {
    wholePaymentAmount / 2 > master.cm.pf.routerConf.mppMinPartAmount
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
  become(PaymentMasterData(Map.empty, Map.empty, Map.empty, Map.empty), EXPECTING_PAYMENTS)

  def doProcess(change: Any): Unit = (change, state) match {
    case (cmd: CMD_SEND_MPP, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE)
      // GUARD: we can't have multiple top-level payments with same hash
      if !data.payments.contains(cmd.paymentHash) =>
      makeSender(new PaymentSender(me), cmd)

    case (routeRequest: RouteRequest, EXPECTING_PAYMENTS) =>
      // IMPLICIT GUARD: disregard this in WAITING_FOR_ROUTE
      cm.pf process Tuple2(me, routeRequest)
      become(data, WAITING_FOR_ROUTE)

    case (response: RouteResponse, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      data.payments.get(response.paymentHash).foreach(_ process response)
      become(data, EXPECTING_PAYMENTS)
      notifyCanAskRoutes
  }


  override def fulfillReceived(fulfill: UpdateFulfillHtlc): Unit = me process fulfill
  override def onException: PartialFunction[Malfunction, Unit] = { case (_, error: CMDAddImpossible) => me process error }
  override def onBecome: PartialFunction[Transition, Unit] = { case (_, _, SLEEPING | SUSPENDED, OPEN) => notifyCanAskRoutes }


  private def notifyCanAskRoutes: Unit = {
    data.payments.values.foreach(_ process CMDCanAskRoutes)
  }

  private def makeSender(sender: PaymentSender, cmd: CMD_SEND_MPP): Unit = {
    val payments1 = data.payments.updated(cmd.paymentHash, sender)
    become(data.copy(payments = payments1), state)
    sender process cmd
  }
}
