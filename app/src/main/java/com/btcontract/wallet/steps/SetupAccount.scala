package com.btcontract.wallet.steps

import android.view.View
import com.btcontract.wallet.R
import android.widget.{ArrayAdapter, LinearLayout}
import com.ornach.nobobutton.NoboButton
import com.btcontract.wallet.WalletActivity
import com.btcontract.wallet.ln.StorageFormat
import com.btcontract.wallet.ln.crypto.Tools.none
import ernestoyaquello.com.verticalstepperform.Step
import ernestoyaquello.com.verticalstepperform.Step.IsDataValid
import org.bitcoinj.crypto.MnemonicCode


class SetupAccount(host: WalletActivity, title: String) extends Step[StorageFormat](title, false) {

  override def createStepContentLayout: View = {
    val view = host.getLayoutInflater.inflate(R.layout.frag_step_account, null).asInstanceOf[LinearLayout]
    val useRecoveryPhrase = view.findViewById(R.id.useRecoveryPhrase).asInstanceOf[NoboButton]
    val useEmailPassword = view.findViewById(R.id.useEmailPassword).asInstanceOf[NoboButton]

    useRecoveryPhrase setOnClickListener host.onButtonTap {
      val mnemonicWrap = host.getLayoutInflater.inflate(R.layout.frag_mnemonic, null).asInstanceOf[LinearLayout]
      val mnemonicInput = mnemonicWrap.findViewById(R.id.restoreCode).asInstanceOf[com.hootsuite.nachos.NachoTextView]
      mnemonicInput.addChipTerminator(' ', com.hootsuite.nachos.terminator.ChipTerminatorHandler.BEHAVIOR_CHIPIFY_TO_TERMINATOR)
      mnemonicInput.addChipTerminator(',', com.hootsuite.nachos.terminator.ChipTerminatorHandler.BEHAVIOR_CHIPIFY_TO_TERMINATOR)
      mnemonicInput.addChipTerminator('\n', com.hootsuite.nachos.terminator.ChipTerminatorHandler.BEHAVIOR_CHIPIFY_TO_TERMINATOR)
      mnemonicInput setAdapter new ArrayAdapter(host, android.R.layout.simple_list_item_1, MnemonicCode.INSTANCE.getWordList)
      mnemonicInput setDropDownBackgroundResource R.color.button_material_dark

      def getMnemonic: String = mnemonicInput.getText.toString.trim.toLowerCase.replaceAll("[^a-zA-Z0-9']+", " ")

      val bld = host.titleBodyAsViewBuilder(host.str2View(host getString R.string.action_recovery_phrase), mnemonicWrap)
      host.mkCheckForm(_.dismiss, none, bld, R.string.dialog_ok, R.string.dialog_cancel)
    }

    view
  }

  override def getStepData: StorageFormat = null
  override def getStepDataAsHumanReadableString: String = null
  override def isStepDataValid(stepData: StorageFormat): IsDataValid =
    new IsDataValid(true, new String)

  override def onStepOpened(animated: Boolean): Unit = none
  override def onStepClosed(animated: Boolean): Unit = none
  override def onStepMarkedAsCompleted(animated: Boolean): Unit = none
  override def onStepMarkedAsUncompleted(animated: Boolean): Unit = none
  override def restoreStepData(stepData: StorageFormat): Unit = none
}
