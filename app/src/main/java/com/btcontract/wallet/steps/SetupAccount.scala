package com.btcontract.wallet.steps

import fr.acinq.eclair._
import com.btcontract.wallet.ln.crypto.Tools._
import com.btcontract.wallet.{FirebirdActivity, R, WalletApp}
import android.text.InputType.{TYPE_CLASS_TEXT, TYPE_TEXT_VARIATION_PASSWORD}
import android.widget.{ArrayAdapter, CheckBox, CompoundButton, EditText, FrameLayout, LinearLayout, RadioGroup, TextView}
import com.btcontract.wallet.ln.{LightningNodeKeys, MnemonicExtStorageFormat, PasswordStorageFormat, StorageFormat}
import ernestoyaquello.com.verticalstepperform.Step.IsDataValid
import com.google.android.material.textfield.TextInputLayout
import android.widget.CompoundButton.OnCheckedChangeListener
import com.ybs.passwordstrengthmeter.PasswordStrength
import ernestoyaquello.com.verticalstepperform.Step
import com.btcontract.wallet.WalletApp.SeedWordSeq
import androidx.appcompat.app.AlertDialog
import com.ornach.nobobutton.NoboButton
import org.bitcoinj.crypto.MnemonicCode
import scodec.bits.ByteVector
import android.view.View
import android.os.Build


trait AccountType {
  def toFormat(providers: Set[wire.NodeAnnouncement] = Set.empty): StorageFormat = throw new RuntimeException
  def accountCheckBlock: Option[Int] = throw new RuntimeException
  def asString: String = new String
}

case object NoAccountType extends AccountType

case class MnemonicAccount(mnemonic: Seq[String] = Nil, isRandomSeed: Boolean) extends AccountType {
  override def accountCheckBlock: Option[Int] = if (isRandomSeed) Some(R.string.action_open_restore_disabled_random_phrase) else None
  override def asString: String = WalletApp.app getString { if (isRandomSeed) R.string.action_recovery_phrase_new else R.string.action_recovery_phrase_existing }

  override def toFormat(providers: Set[wire.NodeAnnouncement] = Set.empty): StorageFormat = {
    val seed: ByteVector = ByteVector view WalletApp.getSeedFromMnemonic(mnemonic)
    val keys: LightningNodeKeys = LightningNodeKeys.makeFromSeed(seed.toArray)
    // We store seed if mnemonic was randomly generated by wallet
    val seedOpt = if (isRandomSeed) seed.toSome else None
    MnemonicExtStorageFormat(providers, keys, seedOpt)
  }
}

case class EmailPasswordAccount(email: String, password: String, isRandomPass: Boolean) extends AccountType {
  override def accountCheckBlock: Option[Int] = if (isRandomPass) Some(R.string.action_open_restore_disabled_random_pass) else None
  override def asString: String = email

  override def toFormat(providers: Set[wire.NodeAnnouncement] = Set.empty): StorageFormat = {
    val scryptDerivedSeed: ByteVector = ByteVector view WalletApp.scryptDerive(email, password)
    val keys: LightningNodeKeys = LightningNodeKeys.makeFromSeed(scryptDerivedSeed.toArray)
    // We store user password if it was randomly generated by wallet
    val passOpt = if (isRandomPass) password.toSome else None
    PasswordStorageFormat(providers, keys, email, passOpt)
  }
}

class SetupAccount(host: FirebirdActivity, title: String) extends Step[AccountType](title, false) {
  val preGeneratedMnemonic: SeedWordSeq = WalletApp.getMnemonic(randomBytes(16).toArray)
  var chosenAccountType: AccountType = NoAccountType
  private[this] final val SEPARATOR: String = " "

  class InputWithInfo(parentView: View, viewRes: Int, hintRes: Int, inputType: Int, fillType: String) {
    def setInfo(wrap: PasswordStrength): Unit = runAnd(info setTextColor wrap.getColor)(info setText wrap.getTextRes)
    def getTrimmedText: String = input.getText.toString.toLowerCase.trim

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
      val phraseRadioGroup = mnemonicWrap.findViewById(R.id.phraseRadioGroup).asInstanceOf[RadioGroup]

      val existingPhraseView = mnemonicWrap.findViewById(R.id.existingPhraseView).asInstanceOf[LinearLayout]
      val restoreCode = mnemonicWrap.findViewById(R.id.restoreCode).asInstanceOf[com.hootsuite.nachos.NachoTextView]

      val generatedPhraseView = mnemonicWrap.findViewById(R.id.generatedPhraseView).asInstanceOf[LinearLayout]
      val generatedPhraseContent = mnemonicWrap.findViewById(R.id.generatedPhraseContent).asInstanceOf[TextView]

      restoreCode.addChipTerminator(' ', com.hootsuite.nachos.terminator.ChipTerminatorHandler.BEHAVIOR_CHIPIFY_TO_TERMINATOR)
      restoreCode.addChipTerminator(',', com.hootsuite.nachos.terminator.ChipTerminatorHandler.BEHAVIOR_CHIPIFY_TO_TERMINATOR)
      restoreCode.addChipTerminator('\n', com.hootsuite.nachos.terminator.ChipTerminatorHandler.BEHAVIOR_CHIPIFY_TO_TERMINATOR)
      restoreCode setAdapter new ArrayAdapter(host, android.R.layout.simple_list_item_1, MnemonicCode.INSTANCE.getWordList)
      restoreCode setDropDownBackgroundResource R.color.button_material_dark

      def maybeProceed(alert: AlertDialog): Unit =
        phraseRadioGroup.getCheckedRadioButtonId match {
          case R.id.existingPhrase if restoreCode.getAllChips.size != 12 =>
            WalletApp.app.quickToast(R.string.error_short_phrase)

          case R.id.existingPhrase =>
            val mnemonic = restoreCode.getText.toString.toLowerCase.trim
            val pureMnemonic = mnemonic.replaceAll("[^a-zA-Z0-9']+", SEPARATOR).split(SEPARATOR)
            chosenAccountType = MnemonicAccount(pureMnemonic.toList, isRandomSeed = false)
            markAsCompletedOrUncompleted(true)
            getFormView.goToNextStep(true)
            alert.dismiss

          case R.id.generatedPhrase =>
            val mnemonic = generatedPhraseContent.getText.toString.split(SEPARATOR)
            chosenAccountType = MnemonicAccount(mnemonic, isRandomSeed = true)
            markAsCompletedOrUncompleted(true)
            getFormView.goToNextStep(true)
            alert.dismiss

          case _ =>
        }

      phraseRadioGroup setOnCheckedChangeListener new RadioGroup.OnCheckedChangeListener {
        override def onCheckedChanged(group: RadioGroup, checkedId: Int): Unit =
          if (checkedId == R.id.generatedPhrase) {
            generatedPhraseView.setVisibility(View.VISIBLE)
            existingPhraseView.setVisibility(View.GONE)
          } else {
            generatedPhraseView.setVisibility(View.GONE)
            existingPhraseView.setVisibility(View.VISIBLE)
          }
      }

      chosenAccountType match {
        case MnemonicAccount(mnemonic, false) =>
          // User has provided an mnemonic of their own
          // we show a user provided one, but also pre-fill a randomly generated one
          generatedPhraseContent.setText(preGeneratedMnemonic mkString SEPARATOR)
          restoreCode.setText(mnemonic mkString SEPARATOR)
          phraseRadioGroup.check(R.id.existingPhrase)

        case _: MnemonicAccount =>
          // User has previously chosen a randomly generated mnemonic
          generatedPhraseContent.setText(preGeneratedMnemonic mkString SEPARATOR)
          phraseRadioGroup.check(R.id.generatedPhrase)

        case _ =>
          // This is the first opening: offer existing, but pre-fill a randomly generated one
          generatedPhraseContent.setText(preGeneratedMnemonic mkString SEPARATOR)
          phraseRadioGroup.check(R.id.existingPhrase)
      }

      val bld = host.titleBodyAsViewBuilder(host.str2View(host getString R.string.action_recovery_phrase), mnemonicWrap)
      host.mkCheckForm(maybeProceed, none, bld, R.string.dialog_ok, R.string.dialog_cancel)
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
          val randomPass = List.fill(12)(secureRandom nextInt alphabet.length).map(alphabet).mkString
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
      host.mkCheckForm(maybeProceed, none, bld, R.string.dialog_ok, R.string.dialog_cancel)
    }

    view
  }

  override def getStepData: AccountType = chosenAccountType
  override def getStepDataAsHumanReadableString: String = chosenAccountType.asString
  override def isStepDataValid(stepData: AccountType) = new IsDataValid(chosenAccountType != NoAccountType, new String)

  override def onStepOpened(animated: Boolean): Unit = none
  override def onStepClosed(animated: Boolean): Unit = none
  override def onStepMarkedAsCompleted(animated: Boolean): Unit = none
  override def onStepMarkedAsUncompleted(animated: Boolean): Unit = none
  override def restoreStepData(stepData: AccountType): Unit = none
}
