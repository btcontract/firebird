package com.btcontract.wallet.ln

import fr.acinq.eclair._
import com.btcontract.wallet.ln.crypto.Tools._
import com.btcontract.wallet.ln.PaymentMaster._
import fr.acinq.eclair.router.Router.{Route, RouteParams, RouteRequest, RouteResponse}
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


sealed trait PaymentFailure { def route: Route }
case class LocalFailure(route: Route, throwable: Throwable) extends PaymentFailure
case class RemoteFailure(route: Route, packet: Sphinx.DecryptedFailurePacket) extends PaymentFailure
case class UnreadableRemoteFailure(route: Route) extends PaymentFailure

sealed trait PartStatus
case class WaitForChannel(exceptChanId: Set[ByteVector32], amount: MilliSatoshi) extends PartStatus
case class WaitForRoute(targetChannel: HostedChannel, amount: MilliSatoshi) extends PartStatus
case class InFlight(route: Route, attemptsLeft: Int, cmd: CMD_ADD_HTLC) extends PartStatus

case class PaymentSenderData(parts: Map[ByteVector, PartStatus],
                             failures: Vector[PaymentFailure],
                             cmd: CMD_SEND_MPP)

case class PaymentMasterData(payments: Map[ByteVector32, PaymentMaster#PaymentSender],
                             chanFailedAtAmount: Map[ShortChannelId, MilliSatoshi] = Map.empty withDefaultValue Long.MaxValue.msat,
                             nodeFailedWithUnknownUpdateTimes: Map[PublicKey, Int] = Map.empty withDefaultValue 0,
                             chanFailedTimes: Map[ShortChannelId, Int] = Map.empty withDefaultValue 0)

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
  val CMDAskForRoute = "cmd-ask-for-route"
}

class PaymentMaster(val cm: ChannelMaster) extends StateMachine[PaymentMasterData] with CanBeRepliedTo with ChannelListener { me =>
  implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def process(changeMessage: Any): Unit = scala.concurrent.Future(me doProcess changeMessage)
  become(PaymentMasterData(Map.empty), EXPECTING_PAYMENTS)

  def doProcess(change: Any): Unit = (change, state) match {
    case (cmd: CMD_SEND_MPP, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE)
      // GUARD: we can't have multiple top-level payments with same hash
      if !data.payments.contains(cmd.paymentHash) =>
      makeSender(new PaymentSender, cmd)
      me process CMDAskForRoute

    case (CMDAskForRoute, EXPECTING_PAYMENTS) =>
      // IMPLICIT GUARD: this is ignored in WAITING_FOR_ROUTE
      data.payments.values.foreach(_ doProcess CMDAskForRoute)

    case (request: RouteRequest, EXPECTING_PAYMENTS) =>
      // IMPLICIT GUARD: this is ignored in WAITING_FOR_ROUTE

  }

  // Can only be accessed through master call
  // this ensures sender computations happen in a same thread
  class PaymentSender extends StateMachine[PaymentSenderData] {
    def doProcess(change: Any): Unit = (change, state) match {
      case (cmd: CMD_SEND_MPP, INIT) => ???
      case (CMDAskForRoute, INIT | PENDING) => ???
    }

    become(null, INIT)
  }


  override def fulfillReceived(fulfill: UpdateFulfillHtlc): Unit = me process fulfill
  override def onException: PartialFunction[Malfunction, Unit] = { case (_, error: CMDAddImpossible) => me process error }
  override def onBecome: PartialFunction[Transition, Unit] = { case (_, _, SLEEPING | SUSPENDED, OPEN) => me process CMDAskForRoute }

  private def makeSender(sender: PaymentSender, cmd: CMD_SEND_MPP): Unit = {
    val payments1 = data.payments.updated(cmd.paymentHash, sender)
    become(data.copy(payments = payments1), state)
    sender doProcess cmd
  }

  def isSplittable(amount: MilliSatoshi): Boolean = amount / 2 > cm.pf.routerConf.mppMinPartAmount

  def split(amount: MilliSatoshi): (MilliSatoshi, MilliSatoshi) = {
    // Siple division may be uneven, ensure parts add up by subtracting
    val part1: MilliSatoshi = amount / 2
    val part2 = amount - part1
    part1 -> part2
  }
}
