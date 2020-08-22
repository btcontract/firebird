package com.btcontract.wallet.ln

import java.util.concurrent.Executors

import com.btcontract.wallet.ln.SyncMaster.ShortChanIdSet
import com.btcontract.wallet.ln.crypto.{CanBeRepliedTo, StateMachine}
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.channel.CMD_ADD_HTLC
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.crypto.Sphinx.PacketAndSecrets
import fr.acinq.eclair.router.Graph.GraphStructure.GraphEdge
import fr.acinq.eclair.{CltvExpiry, MilliSatoshi, ShortChannelId}
import fr.acinq.eclair.router.Router.{Route, RouteParams}
import scodec.bits.ByteVector

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}


sealed trait PaymentFailure {
  def route: Route
}

case class LocalFailure(route: Route, throwable: Throwable) extends PaymentFailure
case class RemoteFailure(route: Route, packet: Sphinx.DecryptedFailurePacket) extends PaymentFailure
case class UnreadableRemoteFailure(route: Route) extends PaymentFailure

sealed trait ShardStatus
case class WaitForChannel(except: Set[HostedChannel], amount: MilliSatoshi) extends ShardStatus
case class WaitForRoutes(partId: ByteVector, channel: HostedChannel, amount: MilliSatoshi) extends ShardStatus
case class InFlight(usedRoute: Route, failedChannels: ShortChanIdSet, cmd: CMD_ADD_HTLC) extends ShardStatus

sealed trait WholePayment {
  def parts: Map[ByteVector, ShardStatus]
  def cmd: CMD_SEND_MPP
}

case class Pending(parts: Map[ByteVector, ShardStatus], cmd: CMD_SEND_MPP) extends WholePayment
case class Aborted(parts: Map[ByteVector, ShardStatus], cmd: CMD_SEND_MPP) extends WholePayment
case class Succeeded(parts: Map[ByteVector, ShardStatus], cmd: CMD_SEND_MPP) extends WholePayment

case class CMD_SEND_MPP(paymentSecret: ByteVector32, targetNodeId: PublicKey, totalAmount: MilliSatoshi,
                        targetExpiry: CltvExpiry, assistedEdges: Set[GraphEdge], routeParams: RouteParams)

case class PaymentSenderData(payments: Map[ByteVector, WholePayment], chanFailedAtAmount: Map[ShortChannelId, MilliSatoshi],
                             nodeFailedTimes: Map[PublicKey, Int], ignoreNodes: Set[PublicKey], currentUsedCapacity: Map[ShortChannelId, MilliSatoshi])

class PaymentSender(cm: ChannelMaster) extends StateMachine[PaymentSenderData] with CanBeRepliedTo { me =>
  implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def process(changeMessage: Any): Unit = scala.concurrent.Future(me doProcess changeMessage)

  def doProcess(change: Any): Unit = (change, data) match {
    case _ => ???
  }
}
