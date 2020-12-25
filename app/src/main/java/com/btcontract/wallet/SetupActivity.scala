package com.btcontract.wallet

import spray.json._
import com.btcontract.wallet.R.string._
import com.btcontract.wallet.ln.crypto.Tools._
import com.btcontract.wallet.ln.utils.ImplicitJsonFormats._
import com.btcontract.wallet.ln.{LNParams, NodeAnnouncementExt}
import com.btcontract.wallet.steps.{ChooseProviders, OpenWallet, SetupAccount}
import ernestoyaquello.com.verticalstepperform.listener.StepperFormListener
import ernestoyaquello.com.verticalstepperform.VerticalStepperFormView
import com.btcontract.wallet.ln.fsm.AccountExistenceCheck
import com.btcontract.wallet.FirebirdActivity.StringOps
import com.btcontract.wallet.lnutils.SQLiteDataBag
import com.btcontract.wallet.ln.utils.Rx
import android.content.DialogInterface
import android.os.Bundle


class SetupActivity extends FirebirdActivity with StepperFormListener { me =>
  lazy val stepper: VerticalStepperFormView = findViewById(R.id.stepper).asInstanceOf[VerticalStepperFormView]
  lazy val providers: ChooseProviders = new ChooseProviders(me, me getString step_title_choose)
  lazy val account: SetupAccount = new SetupAccount(me, me getString step_title_account)
  lazy val open: OpenWallet = new OpenWallet(me, me getString step_title_open, account)
  def onCancelledForm: Unit = stepper.cancelFormCompletionOrCancellationAttempt

  def INIT(state: Bundle): Unit =
    if (WalletApp.isAlive) {
      setContentView(R.layout.activity_setup)
      stepper.setup(me, providers, account, open).init
    } else me exitTo classOf[MainActivity]

  def onCompletedForm: Unit = {
    var proceedTask = UITask(none)
    val progressBar = getLayoutInflater.inflate(R.layout.frag_progress_bar, null)
    val alert = showForm(titleBodyAsViewBuilder(title = null, progressBar).create)

    def cancel: Unit = {
      proceedTask = UITask(none)
      accountCheck process AccountExistenceCheck.CMDCancel
      // Prevent this method from being called again via listener
      alert setOnDismissListener null
      onCancelledForm
      alert.dismiss
    }

    def checkAccount: Unit = {
      proceedTask = UITask(exitToWallet)
      val exts = LNParams.format.outstandingProviders.map(NodeAnnouncementExt)
      accountCheck process AccountExistenceCheck.CMDStart(exts)
    }

    def exitToWallet: Unit = runAnd(alert.dismiss) {
      val jsonFormat = LNParams.format.toJson.compactPrint
      WalletApp.dataBag.put(SQLiteDataBag.LABEL_FORMAT, jsonFormat)
      MainActivity.makeOperational(me, LNParams.format)
    }

    def notifyAccountCheckFailure(message: Int): Runnable = UITask {
      val bld = simpleTextWithNegBuilder(dialog_ok, getString(message).html)
      showForm(bld.create)
      cancel
    }

    lazy val accountCheck =
      // A trick to initialize this val IF needed, AFTER `LNParams.format` is present
      new AccountExistenceCheck(LNParams.format, LNParams.chainHash, LNParams.hcInit) {
        override def onNoAccountFound: Unit = notifyAccountCheckFailure(check_no_account).run
        override def onTimeout: Unit = notifyAccountCheckFailure(check_failed).run
        override def onPresentAccount: Unit = proceedTask.run
      }

    proceedTask = UITask { if (open.getStepData) checkAccount else exitToWallet } // Once format is there, either check an account or go to wallet
    alert setOnDismissListener new DialogInterface.OnDismissListener { def onDismiss(some: DialogInterface): Unit = cancel } // Make the whole process cancellable
    Rx.retry(Rx.ioQueue.map(_ => LNParams.format = account.getStepData toFormat providers.getStepData.set), Rx.pickInc, 4 to 12 by 4).foreach(_ => proceedTask.run)
  }
}