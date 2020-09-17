package com.btcontract.wallet

import com.btcontract.wallet.R.string._
import android.content.{Context, Intent}
import com.btcontract.wallet.ln.crypto.Tools.{none, runAnd}
import android.net.{ConnectivityManager, NetworkCapabilities}
import info.guardianproject.netcipher.proxy.{OrbotHelper, StatusCallback}
import org.ndeftools.util.activity.NfcReaderActivity
import com.btcontract.wallet.WalletApp.app
import org.ndeftools.Message
import android.os.Bundle
import android.view.View
import com.aurelhubert.ahbottomnavigation.{AHBottomNavigation, AHBottomNavigationItem}
import com.btcontract.wallet.helper.Auth
import com.btcontract.wallet.steps.{ChooseProviders, OpenWallet, SetupAccount}
import ernestoyaquello.com.verticalstepperform.VerticalStepperFormView
import ernestoyaquello.com.verticalstepperform.listener.StepperFormListener
import fr.acinq.eclair.MilliSatoshi

import scala.util.Try


class MainActivity extends NfcReaderActivity with WalletActivity with StepperFormListener { me =>

  def INIT(state: Bundle): Unit = {
    setContentView(R.layout.activity_main)
//    val chooseProviders = new ChooseProviders(me, me getString step_title_choose)
//    val setupAccount = new SetupAccount(me, me getString step_title_account)
//    val openWallet = new OpenWallet(me, me getString step_title_open)
//
//    val stepper = findViewById(R.id.stepper).asInstanceOf[VerticalStepperFormView]
//    stepper.setup(this, chooseProviders, setupAccount, openWallet).init
//
//    val content = getLayoutInflater.inflate(R.layout.frag_input_fiat_converter, null, false)
//    val rateManager = new RateManager(content, Some("Add a comment"), FiatRates.ratesInfo.rates, WalletApp.fiatCode)
//    val bld = titleBodyAsViewBuilder(me getString amount_send_title, content)
//    mkCheckForm(_.dismiss, none, bld, dialog_ok, dialog_cancel)
//
//    rateManager.hintDenom.setText(getString(amount_hint_can_send).format(WalletApp.denom.parsedWithSign(MilliSatoshi(10000000000L))))
//    rateManager.hintFiatDenom.setText(getString(amount_hint_can_send).format(WalletApp.currentMsatInFiatHuman(MilliSatoshi(10000000000L))))

    val bottom_navigation = findViewById(R.id.bottom_navigation).asInstanceOf[AHBottomNavigation]
    val shopping = new AHBottomNavigationItem(item_shopping, R.drawable.ic_shopping_black_24dp, R.color.accent)
    val wallet = new AHBottomNavigationItem(item_wallet, R.drawable.ic_wallet_black_24dp, R.color.accent)
    val addon = new AHBottomNavigationItem(item_addons, R.drawable.ic_add_black_24dp, R.color.accent)

    try {
      bottom_navigation.addItem(shopping)
      bottom_navigation.addItem(wallet)
      bottom_navigation.addItem(addon)
    } catch {
      case e: Throwable => e.printStackTrace()
    }

    val auth = new Auth(me) {
      def onNoHardware: Unit = WalletApp.app.quickToast(fp_no_support)
      def onHardwareUnavailable: Unit = WalletApp.app.quickToast(fp_not_available)
      def onCanAuthenticate: Unit = this.callAuthDialog
      def onNoneEnrolled: Unit = WalletApp.app.quickToast(fp_add_auth_method)
      def onAuthSucceeded: Unit = WalletApp.app.quickToast("SUCCESS")
    }

    auth.checkAuth
  }

  def showCookie(view: View): Unit = {
    toast("and now some bitcoiners claim eth peeps shouldn't release half made stuff")
  }

  def onCompletedForm: Unit = {
    println("onCompletedForm")
  }

  def onCancelledForm: Unit = {
    println("onCancelledForm")
  }

  // NFC AND SHARE

  private[this] def readFail(readingError: Throwable): Unit = runAnd(app quickToast err_nothing_useful)(proceedToWallet)
  def readNdefMessage(msg: Message): Unit = runInFutureProcessOnUI(WalletApp recordValue ndefMessageString(msg), readFail)(_ => proceedToWallet)

  override def onNoNfcIntentFound: Unit = {
    val processIntent = (getIntent.getFlags & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
    val dataOpt = Seq(getIntent.getDataString, getIntent getStringExtra Intent.EXTRA_TEXT).find(data => null != data)
    if (processIntent) runInFutureProcessOnUI(dataOpt foreach WalletApp.recordValue, readFail)(_ => proceedToWallet)
    else proceedToWallet
  }

  def onNfcStateEnabled: Unit = none
  def onNfcStateDisabled: Unit = none
  def onNfcFeatureNotFound: Unit = none
  def onNfcStateChange(ok: Boolean): Unit = none
  def readEmptyNdefMessage: Unit = readFail(null)
  def readNonNdefMessage: Unit = readFail(null)

  def proceedToWallet: Unit = {}

  // Tor

  def ensureTorWorking: Unit = {
    val orbotHelper = OrbotHelper get app
    lazy val orbotCallback = new StatusCallback {
      def onEnabled(intent: Intent): Unit = if (isVPNOn) proceedToWallet else onStatusTimeout
      def onStatusTimeout: Unit = showIssue(orbot_err_unclear, orbot_action_open, closeAppExitOrbot).run
      def onNotYetInstalled: Unit = showIssue(orbot_err_not_installed, orbot_action_install, closeAppInstallOrbot).run
      def onStopping: Unit = onStatusTimeout
      def onDisabled: Unit = none
      def onStarting: Unit = none
    }

    def closeAppExitOrbot: Unit = {
      val pack = OrbotHelper.ORBOT_PACKAGE_NAME
      val intent = getPackageManager getLaunchIntentForPackage pack
      Option(intent) foreach startActivity
      finishAffinity
      System exit 0
    }

    def closeAppInstallOrbot: Unit = {
      orbotHelper installOrbot me
      finishAffinity
      System exit 0
    }

    def showIssue(megRes: Int, btnRes: Int, whenTapped: => Unit) = UITask {
      ???
    }

    orbotHelper.addStatusCallback(orbotCallback)
    try timer.schedule(orbotCallback.onStatusTimeout, 20000) catch none
    if (!orbotHelper.init) orbotCallback.onNotYetInstalled
  }

  def isVPNOn: Boolean = Try {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE).asInstanceOf[ConnectivityManager]
    cm.getAllNetworks.exists(cm getNetworkCapabilities _ hasTransport NetworkCapabilities.TRANSPORT_VPN)
  } getOrElse false
}

