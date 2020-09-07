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
import scala.util.Try


class MainActivity extends NfcReaderActivity with WalletActivity { me =>
  def INIT(state: Bundle): Unit = setContentView(R.layout.activity_main)

  def checkExternalData: Unit = println(s"WalletApp.value: ${WalletApp.value}")

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

