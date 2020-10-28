package com.btcontract.wallet.steps

import android.widget.{CheckBox, LinearLayout, TextView}
import ernestoyaquello.com.verticalstepperform.Step.IsDataValid
import ernestoyaquello.com.verticalstepperform.Step
import com.btcontract.wallet.FirebirdActivity
import com.btcontract.wallet.ln.crypto.Tools
import com.btcontract.wallet.R
import android.view.View


class OpenWallet(host: FirebirdActivity, title: String, account: SetupAccount) extends Step[Boolean](title, true) { me =>
  var restoreExistingAccountDisabled: TextView = _
  var restoreExistingAccount: CheckBox = _

  override def createStepContentLayout: View = {
    val view = host.getLayoutInflater.inflate(R.layout.frag_step_open, null).asInstanceOf[LinearLayout]
    restoreExistingAccountDisabled = view.findViewById(R.id.restoreExistingAccountDisabled).asInstanceOf[TextView]
    restoreExistingAccount = view.findViewById(R.id.restoreExistingAccount).asInstanceOf[CheckBox]
    view
  }

  override def isStepDataValid(stepData: Boolean) = new IsDataValid(true, new String)
  override def getStepData: Boolean = restoreExistingAccount.isChecked
  override def getStepDataAsHumanReadableString: String = new String

  override def onStepOpened(animated: Boolean): Unit =
    account.getStepData.accountCheckBlock match {
      case Some(explanationResId) =>
        restoreExistingAccountDisabled.setText(host getString explanationResId)
        restoreExistingAccountDisabled.setVisibility(View.VISIBLE)
        restoreExistingAccount setEnabled false
        restoreExistingAccount setChecked false

      case None =>
        restoreExistingAccountDisabled.setVisibility(View.GONE)
        restoreExistingAccount setEnabled true
    }

  override def onStepClosed(animated: Boolean): Unit = Tools.none
  override def onStepMarkedAsCompleted(animated: Boolean): Unit = Tools.none
  override def onStepMarkedAsUncompleted(animated: Boolean): Unit = Tools.none
  override def restoreStepData(stepData: Boolean): Unit = Tools.none
}