package com.btcontract.wallet

import scala.util.{Success, Try}
import android.content.{Context, Intent}
import com.btcontract.wallet.ln.crypto.Tools.{none, runAnd}
import android.net.{ConnectivityManager, NetworkCapabilities}
import info.guardianproject.netcipher.proxy.{OrbotHelper, StatusCallback}
import com.btcontract.wallet.ln.{ChannelMaster, LNParams, PathFinder, StorageFormat}
import com.btcontract.wallet.lnutils.{SQliteChannelBag, SQliteNetworkDataStore, SQlitePaymentInfoBag}
import com.btcontract.wallet.R.string._

import org.ndeftools.util.activity.NfcReaderActivity
import fr.acinq.eclair.wire.NodeAnnouncement
import com.ornach.nobobutton.NoboButton
import android.widget.TextView
import org.ndeftools.Message
import android.os.Bundle
import android.view.View


object MainActivity {
  def makeOperational(host: FirebirdActivity, format: StorageFormat): Unit= {
    val networkDataStore = new SQliteNetworkDataStore(WalletApp.db)
    val paymentInfoBag = new SQlitePaymentInfoBag(WalletApp.db)
    val channelBag = new SQliteChannelBag(WalletApp.db)

    val channelMaster = new ChannelMaster(paymentInfoBag, channelBag, new PathFinder(networkDataStore, LNParams.routerConf) {
      def updateLastResyncStamp(stamp: Long): Unit = WalletApp.app.prefs.edit.putLong(WalletApp.LAST_GOSSIP_SYNC, stamp).commit
      def getExtraNodes: Set[NodeAnnouncement] = LNParams.channelMaster.all.map(_.data.announce.na).toSet
      def getLastResyncStamp: Long = WalletApp.app.prefs.getLong(WalletApp.LAST_GOSSIP_SYNC, 0L)
    }, WalletApp.chainLink)

    LNParams.format = format
    LNParams.channelMaster = channelMaster
    require(WalletApp.isOperational, "Not operational")
    host exitTo classOf[HubActivity]
    channelMaster.initConnect
  }
}

class MainActivity extends NfcReaderActivity with FirebirdActivity { me =>
  lazy val skipOrbotCheck: NoboButton = findViewById(R.id.skipOrbotCheck).asInstanceOf[NoboButton]
  lazy val takeOrbotAction: NoboButton = findViewById(R.id.takeOrbotAction).asInstanceOf[NoboButton]
  lazy val mainOrbotMessage: TextView = findViewById(R.id.mainOrbotMessage).asInstanceOf[TextView]
  lazy val mainOrbotIssues: View = findViewById(R.id.mainOrbotIssues).asInstanceOf[View]
  lazy val mainOrbotCheck: View = findViewById(R.id.mainOrbotCheck).asInstanceOf[View]

  def INIT(state: Bundle): Unit = {
    setContentView(R.layout.activity_main)
    me initNfc state
  }

  // NFC AND SHARE

  // This method is always run when `onResume` event is fired, should be a starting point for all subsequent checks
  def readNdefMessage(msg: Message): Unit = runInFutureProcessOnUI(WalletApp recordValue ndefMessageString(msg), proceed)(proceed)

  override def onNoNfcIntentFound: Unit = {
    val processIntent = (getIntent.getFlags & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
    val dataOpt = Seq(getIntent.getDataString, getIntent getStringExtra Intent.EXTRA_TEXT).find(data => null != data)
    if (processIntent) runInFutureProcessOnUI(dataOpt foreach WalletApp.recordValue, proceed)(proceed) else proceed(null)
  }

  def onNfcStateEnabled: Unit = none
  def onNfcStateDisabled: Unit = none
  def onNfcFeatureNotFound: Unit = none
  def onNfcStateChange(ok: Boolean): Unit = none
  def readEmptyNdefMessage: Unit = proceed(null)
  def readNonNdefMessage: Unit = proceed(null)

  def proceed(disregard: Any): Unit = WalletApp.isAlive match {
    case false => runAnd(WalletApp.app.initAppVars)(me proceed null)
    case true if WalletApp.isOperational => me exitTo classOf[HubActivity]
    case true =>
      val checkTor = WalletApp.app.prefs.getBoolean(WalletApp.ENSURE_TOR, false)
      val checkAuth = WalletApp.app.prefs.getBoolean(WalletApp.USE_AUTH, false)

      val step3 = new Step {
        def makeAttempt: Unit = WalletApp.dataBag.tryGetFormat match {
          case Success(format) => MainActivity.makeOperational(me, format)
          case _ => me exitTo classOf[SetupActivity]
        }
      }

      val step2 = if (checkTor) new EnsureTor(step3) else step3
      val step1 = if (checkAuth) new EnsureAuth(step2) else step2
      step1.makeAttempt
  }

  // Tor and auth

  trait Step { def makeAttempt: Unit }

  class EnsureAuth(next: Step) extends Step {
    def makeAttempt: Unit = new helper.Auth(me) {
      def onNoHardware: Unit = WalletApp.app.quickToast(fp_no_support)
      def onHardwareUnavailable: Unit = WalletApp.app.quickToast(fp_not_available)
      def onCanAuthenticate: Unit = callAuthDialog
      def onAuthSucceeded: Unit = next.makeAttempt
      def onNoneEnrolled: Unit = next.makeAttempt
    }.checkAuth
  }

  class EnsureTor(next: Step) extends Step {
    val orbotHelper: OrbotHelper = OrbotHelper.get(me)
    val orbotCallback: StatusCallback = new StatusCallback {
      def onStatusTimeout: Unit = showIssue(orbot_err_unclear, getString(orbot_action_open), closeAppExitOrbot).run
      def onNotYetInstalled: Unit = showIssue(orbot_err_not_installed, getString(orbot_action_install), closeAppInstallOrbot).run
      def onEnabled(intent: Intent): Unit = if (isVPNOn) next.makeAttempt else onStatusTimeout
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

    private def showIssue(msgRes: Int, btnText: String, whenTapped: => Unit) = UITask {
      skipOrbotCheck setOnClickListener onButtonTap(next.makeAttempt)
      takeOrbotAction setOnClickListener onButtonTap(whenTapped)
      mainOrbotIssues setVisibility View.VISIBLE
      mainOrbotCheck setVisibility View.GONE
      mainOrbotMessage setText msgRes
      takeOrbotAction setText btnText
      timer.cancel
    }

    def isVPNOn: Boolean = Try {
      val cm = getSystemService(Context.CONNECTIVITY_SERVICE).asInstanceOf[ConnectivityManager]
      cm.getAllNetworks.exists(cm getNetworkCapabilities _ hasTransport NetworkCapabilities.TRANSPORT_VPN)
    } getOrElse false

    def makeAttempt: Unit = {
      orbotHelper.addStatusCallback(orbotCallback)
      try timer.schedule(orbotCallback.onStatusTimeout, 20000) catch none
      try timer.schedule(mainOrbotCheck setVisibility View.VISIBLE, 2000) catch none
      if (!orbotHelper.init) orbotCallback.onNotYetInstalled
    }
  }
}
