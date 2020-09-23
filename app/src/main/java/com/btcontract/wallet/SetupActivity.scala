package com.btcontract.wallet

import scala.concurrent.duration._
import com.btcontract.wallet.R.string._
import com.btcontract.wallet.ln.crypto.Tools._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import com.btcontract.wallet.steps.{ChooseProviders, OpenWallet, SetupAccount}
import com.btcontract.wallet.ln.{CommsTower, ConnectionListener, LNParams, NodeAnnouncementExt, RxUtils}
import fr.acinq.eclair.wire.{HostedChannelMessage, InitHostedChannel, InvokeHostedChannel, LastCrossSignedState, NodeAnnouncement}
import ernestoyaquello.com.verticalstepperform.listener.StepperFormListener
import ernestoyaquello.com.verticalstepperform.VerticalStepperFormView
import com.btcontract.wallet.ln.crypto.StateMachine
import android.content.DialogInterface
import java.util.concurrent.Executors
import android.os.Bundle


class SetupActivity extends FirebirdActivity with StepperFormListener { me =>
  lazy val stepper: VerticalStepperFormView = findViewById(R.id.stepper).asInstanceOf[VerticalStepperFormView]
  lazy val chooseProviders: ChooseProviders = new ChooseProviders(me, me getString step_title_choose)
  lazy val setAccount: SetupAccount = new SetupAccount(me, me getString step_title_account)
  lazy val openWallet: OpenWallet = new OpenWallet(me, me getString step_title_open)
  def onCancelledForm: Unit = stepper.cancelFormCompletionOrCancellationAttempt

  def INIT(state: Bundle): Unit = {
    setContentView(R.layout.activity_setup)
    stepper.setup(me, chooseProviders, setAccount, openWallet).init
  }

  def onCompletedForm: Unit = {
    val progressBar = getLayoutInflater.inflate(R.layout.frag_progress_bar, null)
    val dl = new DialogInterface.OnDismissListener { def onDismiss(some: DialogInterface): Unit = onCancelledForm }
    showForm(titleBodyAsViewBuilder(title = null, progressBar).create) setOnDismissListener dl
  }

  // Restore state machine

  type ChanExistenceResult = Option[Boolean]
  val OPERATIONAL = "setup-state-operational"
  val FINALIZED = "setup-state-finalized"

  val CMDTimeoutAccountCheck = "setup-cmd-timeout"
  val CMDCancelAccountCheck = "setup-cmd-cancel"
  val CMDCheck = "setup-cmd-check"
  val CMDStart = "setup-cmd-start"

  case class CheckData(results: Map[NodeAnnouncement, ChanExistenceResult], reconnectAttemptsLeft: Int)
  case class PeerResponse(msg: HostedChannelMessage, worker: CommsTower.Worker)
  case class PeerDisconnected(worker: CommsTower.Worker)

  abstract class AccountCheck extends StateMachine[CheckData] { me =>
    private val hosts = toMapBy[NodeAnnouncement, NodeAnnouncementExt](LNParams.format.outstandingProviders.map(NodeAnnouncementExt), _.na)
    implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
    def process(changeMessage: Any): Unit = scala.concurrent.Future(me doProcess changeMessage)

    lazy private val accountCheckListener = new ConnectionListener {
      override def onDisconnect(worker: CommsTower.Worker): Unit = me process PeerDisconnected(worker)
      override def onHostedMessage(worker: CommsTower.Worker, msg: HostedChannelMessage): Unit = me process PeerResponse(msg, worker)

      override def onOperational(worker: CommsTower.Worker): Unit = {
        val refundScript = LNParams.format.keys.refundAddress(theirNodeId = worker.ann.nodeId)
        val msg = InvokeHostedChannel(LNParams.chainHash, refundScript, LNParams.format.attachedChannelSecret)
        worker.handler process msg
      }
    }

    def onCanNotCheckAccount: Unit
    def onNoAccountFound: Unit
    def onFoundAccount: Unit

    def doProcess(change: Any): Unit = (change, state) match {
      case (PeerDisconnected(worker), OPERATIONAL) if data.reconnectAttemptsLeft > 0 =>
        // Peer has disconnected but we have spare attempts left, don't fail just yet
        RxUtils.ioQueue.delay(3.seconds).foreach(_ => me process worker)

      case (_: PeerDisconnected, OPERATIONAL) =>
        // We've run out of reconnect attempts
        me process CMDTimeoutAccountCheck

      case (worker: CommsTower.Worker, OPERATIONAL) =>
        val data1 = data.copy(reconnectAttemptsLeft = data.reconnectAttemptsLeft - 1)
        CommsTower.listen(Set(accountCheckListener), worker.pkap, worker.ann)
        become(data1, OPERATIONAL)

      case PeerResponse(_: InitHostedChannel, worker) \ OPERATIONAL =>
        val results1 = data.results.updated(worker.ann, false.toSome)
        become(data.copy(results = results1), OPERATIONAL)
        me process CMDCheck

      case PeerResponse(remoteLCSS: LastCrossSignedState, worker) \ OPERATIONAL =>
        val isLocalSigOk = remoteLCSS.verifyRemoteSig(hosts(worker.ann).nodeSpecificPubKey)
        val results1 = data.results.updated(worker.ann, isLocalSigOk.toSome)
        become(data.copy(results = results1), OPERATIONAL)
        me process CMDCheck

      case CMDCheck \ OPERATIONAL =>
        data.results.values.flatten.toSet match {
          case results if results.contains(true) =>
            // At least one peer has confirmed a channel
            me doProcess CMDCancelAccountCheck
            UITask(onFoundAccount).run

          case results if results.size == hosts.size =>
            // All peers replied, none has a channel
            me doProcess CMDCancelAccountCheck
            UITask(onNoAccountFound).run

          // Keep waiting
          case _ =>
        }

      case CMDTimeoutAccountCheck \ OPERATIONAL =>
        // Too much time has passed, disconnect all peers
        hosts.values.foreach(CommsTower forget _.nodeSpecificPkap)
        // Specifically inform user that cheking is not possible
        UITask(onCanNotCheckAccount).run
        become(data, FINALIZED)

      case CMDCancelAccountCheck \ OPERATIONAL =>
        // User has manually cancelled a check, disconnect all peers
        hosts.values.foreach(CommsTower forget _.nodeSpecificPkap)
        become(data, FINALIZED)

      case CMDStart \ null =>
        become(CheckData(hosts.mapValues(_ => None), reconnectAttemptsLeft = 10), OPERATIONAL)
        for (ex <- hosts.values) CommsTower.listen(Set(accountCheckListener), ex.nodeSpecificPkap, ex.na)
        RxUtils.ioQueue.delay(45.seconds).foreach(_ => me process CMDTimeoutAccountCheck)
    }
  }
}