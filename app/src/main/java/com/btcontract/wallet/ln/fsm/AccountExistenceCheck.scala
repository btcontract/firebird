package com.btcontract.wallet.ln.fsm

import scala.concurrent.duration._
import com.btcontract.wallet.ln.crypto.Tools._
import com.btcontract.wallet.ln.fsm.AccountExistenceCheck._
import fr.acinq.eclair.wire.{HostedChannelMessage, Init, InitHostedChannel, InvokeHostedChannel, LastCrossSignedState, NodeAnnouncement}
import com.btcontract.wallet.ln.{CommsTower, ConnectionListener, NodeAnnouncementExt, StorageFormat}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import com.btcontract.wallet.ln.crypto.StateMachine
import com.btcontract.wallet.ln.utils.Rx
import java.util.concurrent.Executors
import fr.acinq.bitcoin.ByteVector32


object AccountExistenceCheck {
  type ChanExistenceResult = Option[Boolean]
  val OPERATIONAL = "setup-state-operational"
  val FINALIZED = "setup-state-finalized"

  val CMDTimeoutAccountCheck = "setup-cmd-timeout"
  val CMDCancelAccountCheck = "setup-cmd-cancel"
  val CMDCarryCheck = "setup-cmd-carry-check"
  val CMDCheck = "setup-cmd-check"
}

case class CheckData(hosts: Map[NodeAnnouncement, NodeAnnouncementExt], results: Map[NodeAnnouncement, ChanExistenceResult], reconnectAttemptsLeft: Int)
case class PeerResponse(msg: HostedChannelMessage, worker: CommsTower.Worker)
case class PeerDisconnected(worker: CommsTower.Worker)

abstract class AccountExistenceCheck(format: StorageFormat, chainHash: ByteVector32, init: Init) extends StateMachine[CheckData] { me =>
  implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def process(changeMessage: Any): Unit = scala.concurrent.Future(me doProcess changeMessage)

  lazy private val accountCheckListener = new ConnectionListener {
    override def onDisconnect(worker: CommsTower.Worker): Unit = me process PeerDisconnected(worker)
    override def onHostedMessage(worker: CommsTower.Worker, msg: HostedChannelMessage): Unit = me process PeerResponse(msg, worker)

    override def onOperational(worker: CommsTower.Worker, theirInit: Init): Unit = {
      val peerSpecificSecret = format.keys.refundPubKey(theirNodeId = worker.ann.nodeId)
      val peerSpecificRefundPubKey = format.attachedChannelSecret(theirNodeId = worker.ann.nodeId)
      worker.handler process InvokeHostedChannel(chainHash, peerSpecificSecret, peerSpecificRefundPubKey)
    }
  }

  def onCanNotCheckAccount: Unit
  def onNoAccountFound: Unit
  def onPresentAccount: Unit

  def doProcess(change: Any): Unit = (change, state) match {
    case (PeerDisconnected(worker), OPERATIONAL) if data.reconnectAttemptsLeft > 0 =>
      become(data.copy(reconnectAttemptsLeft = data.reconnectAttemptsLeft - 1), OPERATIONAL)
      Rx.ioQueue.delay(3.seconds).foreach(_ => me process worker)

    case (_: PeerDisconnected, OPERATIONAL) =>
      // We've run out of reconnect attempts
      me process CMDTimeoutAccountCheck

    case (worker: CommsTower.Worker, OPERATIONAL) =>
      // We get previously scheduled worker and use its remote peer data to reconnect again
      CommsTower.listen(Set(accountCheckListener), worker.pkap, worker.ann, init)

    case (PeerResponse(_: InitHostedChannel, worker), OPERATIONAL) =>
      val results1 = data.results.updated(worker.ann, false.toSome)
      become(data.copy(results = results1), OPERATIONAL)
      me process CMDCarryCheck

    case (PeerResponse(remoteLCSS: LastCrossSignedState, worker), OPERATIONAL) =>
      val isLocalSigOk = remoteLCSS.verifyRemoteSig(data.hosts(worker.ann).nodeSpecificPubKey)
      val results1 = data.results.updated(worker.ann, isLocalSigOk.toSome)
      become(data.copy(results = results1), OPERATIONAL)
      me process CMDCarryCheck

    case (CMDCarryCheck, OPERATIONAL) =>
      data.results.values.flatten.toSet match {
        case results if results.contains(true) =>
          // At least one peer has confirmed a channel
          me doProcess CMDCancelAccountCheck
          onPresentAccount

        case results if results.size == data.hosts.size =>
          // All peers replied, none has a channel
          me doProcess CMDCancelAccountCheck
          onNoAccountFound

        // Keep waiting
        case _ =>
      }

    case (CMDTimeoutAccountCheck, OPERATIONAL) =>
      // Too much time has passed, disconnect all peers
      data.hosts.values.foreach(CommsTower forget _.nodeSpecificPkap)
      // Specifically inform user that cheking is not possible
      become(data, FINALIZED)
      onCanNotCheckAccount

    case (CMDCancelAccountCheck, OPERATIONAL) =>
      // User has manually cancelled a check, disconnect all peers
      data.hosts.values.foreach(CommsTower forget _.nodeSpecificPkap)
      become(data, FINALIZED)

    case (CMDCheck, null) =>
      val remainingHosts = toMapBy[NodeAnnouncement, NodeAnnouncementExt](format.outstandingProviders.map(NodeAnnouncementExt), _.na)
      become(CheckData(remainingHosts, remainingHosts.mapValues(_ => None), reconnectAttemptsLeft = remainingHosts.size * 4), OPERATIONAL)
      for (ext <- remainingHosts.values) CommsTower.listen(Set(accountCheckListener), ext.nodeSpecificPkap, ext.na, init)
      Rx.ioQueue.delay(30.seconds).foreach(_ => me process CMDTimeoutAccountCheck)
  }
}