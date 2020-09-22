package com.btcontract.wallet

import com.btcontract.wallet.R.string._
import com.btcontract.wallet.steps.{ChooseProviders, OpenWallet, SetupAccount}
import ernestoyaquello.com.verticalstepperform.listener.StepperFormListener
import ernestoyaquello.com.verticalstepperform.VerticalStepperFormView
import android.content.DialogInterface
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
}
