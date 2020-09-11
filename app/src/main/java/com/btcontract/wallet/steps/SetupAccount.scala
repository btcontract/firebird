package com.btcontract.wallet.steps

import android.view.View
import com.btcontract.wallet.R
import android.widget.LinearLayout
import com.btcontract.wallet.WalletActivity
import com.btcontract.wallet.ln.StorageFormat
import com.btcontract.wallet.ln.crypto.Tools.none
import ernestoyaquello.com.verticalstepperform.Step
import ernestoyaquello.com.verticalstepperform.Step.IsDataValid

class SetupAccount(host: WalletActivity, title: String) extends Step[StorageFormat](title, false) {
  override def createStepContentLayout: View = {
    val view = host.getLayoutInflater.inflate(R.layout.frag_step_account, null).asInstanceOf[LinearLayout]
    getStepData
    view
  }

  override def getStepData: StorageFormat = null
  override def getStepDataAsHumanReadableString: String = null
  override def isStepDataValid(stepData: StorageFormat): IsDataValid = new IsDataValid(false, new String)

  override def onStepOpened(animated: Boolean): Unit = none

  override def onStepClosed(animated: Boolean): Unit = none
  override def onStepMarkedAsCompleted(animated: Boolean): Unit = none
  override def onStepMarkedAsUncompleted(animated: Boolean): Unit = none
  override def restoreStepData(stepData: StorageFormat): Unit = none
}
