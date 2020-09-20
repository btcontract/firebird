package com.btcontract.wallet.steps

import android.view.View
import com.btcontract.wallet.R
import android.widget.LinearLayout
import com.btcontract.wallet.FirebirdActivity
import com.btcontract.wallet.ln.crypto.Tools
import ernestoyaquello.com.verticalstepperform.Step
import ernestoyaquello.com.verticalstepperform.Step.IsDataValid


class OpenWallet(host: FirebirdActivity, title: String) extends Step[Boolean](title, true) { me =>
  var restoringExistingAccount: Boolean = false

  override def createStepContentLayout: View = {
    val view = host.getLayoutInflater.inflate(R.layout.frag_step_open, null).asInstanceOf[LinearLayout]
    view
  }

  override def getStepData: Boolean = restoringExistingAccount
  override def getStepDataAsHumanReadableString: String = new String
  override def isStepDataValid(stepData: Boolean): IsDataValid = new IsDataValid(true, new String)

  override def onStepOpened(animated: Boolean): Unit = Tools.none
  override def onStepClosed(animated: Boolean): Unit = Tools.none
  override def onStepMarkedAsCompleted(animated: Boolean): Unit = Tools.none
  override def onStepMarkedAsUncompleted(animated: Boolean): Unit = Tools.none
  override def restoreStepData(stepData: Boolean): Unit = Tools.none
}