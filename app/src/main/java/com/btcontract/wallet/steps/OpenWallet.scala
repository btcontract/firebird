package com.btcontract.wallet.steps

import android.widget.{CheckBox, LinearLayout}
import ernestoyaquello.com.verticalstepperform.Step.IsDataValid
import ernestoyaquello.com.verticalstepperform.Step
import com.btcontract.wallet.FirebirdActivity
import com.btcontract.wallet.ln.crypto.Tools
import com.btcontract.wallet.R
import android.view.View


class OpenWallet(host: FirebirdActivity, title: String) extends Step[Boolean](title, true) { me =>
  var restoreExistingAccount: CheckBox = _

  override def createStepContentLayout: View = {
    val view = host.getLayoutInflater.inflate(R.layout.frag_step_open, null).asInstanceOf[LinearLayout]
    restoreExistingAccount = view.findViewById(R.id.restoreExistingAccount).asInstanceOf[CheckBox]
    view
  }

  override def isStepDataValid(stepData: Boolean) = new IsDataValid(true, new String)
  override def getStepData: Boolean = restoreExistingAccount.isChecked
  override def getStepDataAsHumanReadableString: String = new String

  override def onStepOpened(animated: Boolean): Unit = Tools.none
  override def onStepClosed(animated: Boolean): Unit = Tools.none
  override def onStepMarkedAsCompleted(animated: Boolean): Unit = Tools.none
  override def onStepMarkedAsUncompleted(animated: Boolean): Unit = Tools.none
  override def restoreStepData(stepData: Boolean): Unit = Tools.none
}