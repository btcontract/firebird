package com.btcontract.wallet.steps

import com.btcontract.wallet.{R, FirebirdActivity, WalletApp}
import android.text.InputType.{TYPE_CLASS_TEXT, TYPE_TEXT_VARIATION_PASSWORD}
import android.widget.{ArrayAdapter, CheckBox, CompoundButton, EditText, FrameLayout, LinearLayout, TextView}
import ernestoyaquello.com.verticalstepperform.Step.IsDataValid
import com.google.android.material.textfield.TextInputLayout
import android.widget.CompoundButton.OnCheckedChangeListener
import com.ybs.passwordstrengthmeter.PasswordStrength
import ernestoyaquello.com.verticalstepperform.Step
import com.btcontract.wallet.ln.crypto.Tools
import androidx.appcompat.app.AlertDialog
import com.ornach.nobobutton.NoboButton
import org.bitcoinj.crypto.MnemonicCode
import android.view.View
import android.os.Build


trait AccountType { def asString: String }
case object NoAccountType extends AccountType { val asString: String = new String }
case class MnemonicAccount(mnemonic: String) extends AccountType { val asString: String = WalletApp.app getString R.string.action_recovery_phrase }
case class EmailPasswordAccount(email: String, password: String, isRandom: Boolean) extends AccountType { val asString: String = email }

class SetupAccount(host: FirebirdActivity, title: String) extends Step[AccountType](title, false) {
  var chosenAccountType: AccountType = NoAccountType

  class InputWithInfo(parentView: View, viewRes: Int, hintRes: Int, inputType: Int, fillType: String) {
    def setInfo(wrap: PasswordStrength): Unit = Tools.runAnd(info setTextColor wrap.getColor)(info setText wrap.getTextRes)
    def getTrimmedText: String = input.getText.toString.trim

    val view: FrameLayout = parentView.findViewById(viewRes).asInstanceOf[FrameLayout]
    val inputLayout: TextInputLayout = view.findViewById(R.id.inputLayout).asInstanceOf[TextInputLayout]
    val input: EditText = view.findViewById(R.id.input).asInstanceOf[EditText]
    val info: TextView = view.findViewById(R.id.info).asInstanceOf[TextView]

    input.setInputType(inputType)
    inputLayout.setHint(host getString hintRes)
    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
      input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES)
      input.setAutofillHints(fillType)
    }
  }

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

      def maybeProceed(alert: AlertDialog): Unit =
        if (mnemonicInput.getAllChips.size != 12) {
          WalletApp.app.quickToast(R.string.error_short_phrase)
        } else {
          val mnemonic = mnemonicInput.getText.toString.trim.toLowerCase
          val pureMnemonic = mnemonic.replaceAll("[^a-zA-Z0-9']+", " ")
          chosenAccountType = MnemonicAccount(pureMnemonic)
          markAsCompletedOrUncompleted(true)
          getFormView.goToNextStep(true)
          alert.dismiss
        }

      chosenAccountType match { case MnemonicAccount(mnemonic) => mnemonicInput setText mnemonic case _ => }
      val bld = host.titleBodyAsViewBuilder(host.str2View(host getString R.string.action_recovery_phrase), mnemonicWrap)
      host.mkCheckForm(maybeProceed, Tools.none, bld, R.string.dialog_ok, R.string.dialog_cancel)
    }

    useEmailPassword setOnClickListener host.onButtonTap {
      val emailPasswordWrap = host.getLayoutInflater.inflate(R.layout.frag_email_password, null).asInstanceOf[LinearLayout]
      val email = new InputWithInfo(emailPasswordWrap, R.id.email, R.string.action_email_password_hint_email, TYPE_CLASS_TEXT, View.AUTOFILL_HINT_EMAIL_ADDRESS)
      val password = new InputWithInfo(emailPasswordWrap, R.id.password, R.string.action_email_password_hint_password, TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD, View.AUTOFILL_HINT_PASSWORD)
      val confirmPassword = new InputWithInfo(emailPasswordWrap, R.id.confirmPassword, R.string.action_email_password_hint_password_again, TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD, View.AUTOFILL_HINT_PASSWORD)
      val useRandomPassword = emailPasswordWrap.findViewById(R.id.useRandomPassword).asInstanceOf[CheckBox]
      var lastEmailValidation: PasswordStrength = PasswordStrength.EMPTY

      def checkConfirm(any: CharSequence): Unit = {
        val confirmText = confirmPassword.getTrimmedText
        val chunk = password.getTrimmedText.take(confirmText.length)

        val mode =
          if (confirmText.isEmpty || password.getTrimmedText.isEmpty) PasswordStrength.EMPTY
          else if (chunk == confirmText && password.getTrimmedText.length > confirmText.length) PasswordStrength.EMPTY
          else if (chunk == confirmText && password.getTrimmedText.length == confirmText.length) PasswordStrength.MATCH
          else PasswordStrength.MISMATCH

        // Only disable checkbox if pass/confirm is a match and checkbox was not checked already
        val checkboxEnabled = useRandomPassword.isChecked || mode != PasswordStrength.MATCH
        useRandomPassword.setEnabled(checkboxEnabled)
        confirmPassword.setInfo(mode)
      }

      def maybeProceed(alert: AlertDialog): Unit = {
        val passResult = PasswordStrength calculate password.getTrimmedText
        val confirmPasswordValidationPassed = confirmPassword.getTrimmedText == password.getTrimmedText
        val emailValidationPassed = android.util.Patterns.EMAIL_ADDRESS.matcher(email.getTrimmedText).matches
        val passwordValidationPassed = passResult == PasswordStrength.MEDIUM || passResult == PasswordStrength.GOOD
        lastEmailValidation = if (emailValidationPassed) PasswordStrength.EMPTY else PasswordStrength.EMAIL_INVALID
        email.setInfo(lastEmailValidation)
        password.setInfo(passResult)

        if (emailValidationPassed && passwordValidationPassed && confirmPasswordValidationPassed) {
          chosenAccountType = EmailPasswordAccount(email.getTrimmedText, password.getTrimmedText, useRandomPassword.isChecked)
          markAsCompletedOrUncompleted(true)
          getFormView.goToNextStep(true)
          alert.dismiss
        }
      }

      email.input addTextChangedListener host.onTextChange { _ =>
        val emailFine = android.util.Patterns.EMAIL_ADDRESS.matcher(email.getTrimmedText).matches
        if (emailFine) email.setInfo(PasswordStrength.EMPTY) else email.setInfo(lastEmailValidation)
      }

      password.input addTextChangedListener host.onTextChange { _ =>
        password.setInfo(PasswordStrength calculate password.getTrimmedText)
        checkConfirm(null)
      }

      confirmPassword.input addTextChangedListener host.onTextChange(checkConfirm)

      useRandomPassword setOnCheckedChangeListener new OnCheckedChangeListener {
        def onCheckedChanged(checkbox: CompoundButton, isChecked: Boolean): Unit = {
          val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
          val randomPass = List.fill(12)(Tools.random nextInt alphabet.length).map(alphabet).mkString
          val finalPass = if (useRandomPassword.isChecked) randomPass else new String
          confirmPassword.input.setEnabled(!useRandomPassword.isChecked)
          password.input.setEnabled(!useRandomPassword.isChecked)
          confirmPassword.input.setText(finalPass)
          password.input.setText(finalPass)
        }
      }

      chosenAccountType match {
        case EmailPasswordAccount(emailText, passwordText, useRandomPasswordText) =>
          if (useRandomPasswordText) useRandomPassword.setChecked(true)
          else password.input.setText(passwordText)
          email.input.setText(emailText)
        case _ =>
      }

      val bld = host.titleBodyAsViewBuilder(host.str2View(host getString R.string.action_email_password), emailPasswordWrap)
      host.mkCheckForm(maybeProceed, Tools.none, bld, R.string.dialog_ok, R.string.dialog_cancel)
    }

    view
  }

  override def getStepData: AccountType = chosenAccountType
  override def getStepDataAsHumanReadableString: String = chosenAccountType.asString
  override def isStepDataValid(stepData: AccountType): IsDataValid = new IsDataValid(chosenAccountType != NoAccountType, new String)

  override def onStepOpened(animated: Boolean): Unit = Tools.none
  override def onStepClosed(animated: Boolean): Unit = Tools.none
  override def onStepMarkedAsCompleted(animated: Boolean): Unit = Tools.none
  override def onStepMarkedAsUncompleted(animated: Boolean): Unit = Tools.none
  override def restoreStepData(stepData: AccountType): Unit = Tools.none
}
