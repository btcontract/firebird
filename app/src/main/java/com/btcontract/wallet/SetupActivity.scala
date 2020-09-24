package com.btcontract.wallet

import spray.json._
import scala.concurrent.duration._
import com.btcontract.wallet.R.string._
import com.btcontract.wallet.ln.crypto.Tools._
import com.btcontract.wallet.lnutils.ImplicitJsonFormats._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import com.btcontract.wallet.steps.{ChooseProviders, OpenWallet, SetupAccount}
import com.btcontract.wallet.ln.{CommsTower, ConnectionListener, LNParams, NodeAnnouncementExt, RxUtils, SyncMaster}
import fr.acinq.eclair.wire.{HostedChannelMessage, InitHostedChannel, InvokeHostedChannel, LastCrossSignedState, NodeAnnouncement}
import ernestoyaquello.com.verticalstepperform.listener.StepperFormListener
import ernestoyaquello.com.verticalstepperform.VerticalStepperFormView
import com.btcontract.wallet.FirebirdActivity.StringOps
import com.btcontract.wallet.ln.crypto.StateMachine
import com.btcontract.wallet.lnutils.SQliteDataBag
import android.content.DialogInterface
import java.util.concurrent.Executors
import rx.lang.scala.Observable
import android.os.Bundle


class SetupActivity extends FirebirdActivity with StepperFormListener { me =>
  lazy val stepper: VerticalStepperFormView = findViewById(R.id.stepper).asInstanceOf[VerticalStepperFormView]
  lazy val providers: ChooseProviders = new ChooseProviders(me, me getString step_title_choose)
  lazy val account: SetupAccount = new SetupAccount(me, me getString step_title_account)
  lazy val wallet: OpenWallet = new OpenWallet(me, me getString step_title_open)
  def onCancelledForm: Unit = stepper.cancelFormCompletionOrCancellationAttempt

  def INIT(state: Bundle): Unit =
    if (WalletApp.isAlive) {
      setContentView(R.layout.activity_setup)
      stepper.setup(me, providers, account, wallet).init
    } else me exitTo classOf[MainActivity]

  type ChanExistenceResult = Option[Boolean]
  val OPERATIONAL = "setup-state-operational"
  val FINALIZED = "setup-state-finalized"

  val CMDTimeoutAccountCheck = "setup-cmd-timeout"
  val CMDCancelAccountCheck = "setup-cmd-cancel"
  val CMDCarryCheck = "setup-cmd-carry-check"
  val CMDCheck = "setup-cmd-check"

  def onCompletedForm: Unit = {
    var proceedTask = UITask(none)
    val progressBar = getLayoutInflater.inflate(R.layout.frag_progress_bar, null)
    val alert = showForm(titleBodyAsViewBuilder(title = null, progressBar).create)

    def cancel: Unit = {
      accountCheck process CMDCancelAccountCheck
      // Prevent this method from being called again
      alert setOnDismissListener null
      proceedTask = UITask(none)
      onCancelledForm
      alert.dismiss
    }

    def checkAccount: Unit = {
      proceedTask = UITask(exitToWallet)
      accountCheck process CMDCheck
    }

    def exitToWallet: Unit = {
      val json = LNParams.format.toJson.toString
      WalletApp.dataBag.put(SQliteDataBag.LABEL_FORMAT, json)
      MainActivity.makeOperational(me, LNParams.format)
    }

    lazy val accountCheck = new AccountCheck {
      override def onCanNotCheckAccount: Unit = runAnd(me showForm simpleTextWithNegBuilder(dialog_ok, getString(check_failed).html).create)(cancel)
      override def onNoAccountFound: Unit = runAnd(me showForm simpleTextWithNegBuilder(dialog_ok, getString(check_no_account).html).create)(cancel)
      override def onPresentAccount: Unit = proceedTask.run
    }

    // Check all default providers + maybe user scanned external provider if user tries to "restore an account"
    val finalProviders = if (wallet.getStepData) providers.getStepData.set ++ SyncMaster.hostedChanNodes else providers.getStepData.set
    val heavyProcess: Observable[Unit] = RxUtils.ioQueue.map(_ => LNParams.format = account.getStepData toFormat finalProviders)
    alert setOnDismissListener new DialogInterface.OnDismissListener { def onDismiss(some: DialogInterface): Unit = cancel }
    RxUtils.retry(heavyProcess, RxUtils.pickInc, 3 to 9 by 3).foreach(_ => proceedTask.run)
    proceedTask = UITask { if (wallet.getStepData) checkAccount else exitToWallet }
  }

  // Check state machine

  case class CheckData(hosts: Map[NodeAnnouncement, NodeAnnouncementExt],
                       results: Map[NodeAnnouncement, ChanExistenceResult],
                       reconnectAttemptsLeft: Int)

  case class PeerResponse(msg: HostedChannelMessage, worker: CommsTower.Worker)
  case class PeerDisconnected(worker: CommsTower.Worker)

  abstract class AccountCheck extends StateMachine[CheckData] { me =>
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
    def onPresentAccount: Unit

    def doProcess(change: Any): Unit = (change, state) match {
      case PeerDisconnected(worker) \ OPERATIONAL if data.reconnectAttemptsLeft > 0 =>
        become(data.copy(reconnectAttemptsLeft = data.reconnectAttemptsLeft - 1), OPERATIONAL)
        RxUtils.ioQueue.delay(3.seconds).foreach(_ => me process worker)

      case (_: PeerDisconnected, OPERATIONAL) =>
        // We've run out of reconnect attempts
        me process CMDTimeoutAccountCheck

      case (worker: CommsTower.Worker, OPERATIONAL) =>
        // We get delayed worker and use its data to reconnect to peer
        CommsTower.listen(Set(accountCheckListener), worker.pkap, worker.ann)

      case PeerResponse(_: InitHostedChannel, worker) \ OPERATIONAL =>
        val results1 = data.results.updated(worker.ann, false.toSome)
        become(data.copy(results = results1), OPERATIONAL)
        me process CMDCarryCheck

      case PeerResponse(remoteLCSS: LastCrossSignedState, worker) \ OPERATIONAL =>
        val isLocalSigOk = remoteLCSS.verifyRemoteSig(data.hosts(worker.ann).nodeSpecificPubKey)
        val results1 = data.results.updated(worker.ann, isLocalSigOk.toSome)
        become(data.copy(results = results1), OPERATIONAL)
        me process CMDCarryCheck

      case CMDCarryCheck \ OPERATIONAL =>
        data.results.values.flatten.toSet match {
          case results if results.contains(true) =>
            // At least one peer has confirmed a channel
            me doProcess CMDCancelAccountCheck
            UITask(onPresentAccount).run

          case results if results.size == data.hosts.size =>
            // All peers replied, none has a channel
            me doProcess CMDCancelAccountCheck
            UITask(onNoAccountFound).run

          // Keep waiting
          case _ =>
        }

      case CMDTimeoutAccountCheck \ OPERATIONAL =>
        // Too much time has passed, disconnect all peers
        data.hosts.values.foreach(CommsTower forget _.nodeSpecificPkap)
        // Specifically inform user that cheking is not possible
        UITask(onCanNotCheckAccount).run
        become(data, FINALIZED)

      case CMDCancelAccountCheck \ OPERATIONAL =>
        // User has manually cancelled a check, disconnect all peers
        data.hosts.values.foreach(CommsTower forget _.nodeSpecificPkap)
        become(data, FINALIZED)

      case CMDCheck \ null =>
        val hosts = toMapBy[NodeAnnouncement, NodeAnnouncementExt](LNParams.format.outstandingProviders.map(NodeAnnouncementExt), _.na)
        become(CheckData(hosts, results = hosts.mapValues(_ => None), reconnectAttemptsLeft = hosts.size * 4), OPERATIONAL)
        for (ex <- hosts.values) CommsTower.listen(Set(accountCheckListener), ex.nodeSpecificPkap, ex.na)
        RxUtils.ioQueue.delay(30.seconds).foreach(_ => me process CMDTimeoutAccountCheck)
    }
  }
}