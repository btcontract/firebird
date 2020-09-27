package com.btcontract.wallet

import scala.concurrent.duration._
import com.btcontract.wallet.lnutils._
import com.btcontract.wallet.R.string._

import scala.util.{Success, Try}
import android.content.{Context, Intent}
import com.btcontract.wallet.ln.crypto.Tools.{none, runAnd}
import android.net.{ConnectivityManager, NetworkCapabilities}
import fr.acinq.eclair.channel.{CMD_SOCKET_OFFLINE, CMD_SOCKET_ONLINE}
import info.guardianproject.netcipher.proxy.{OrbotHelper, StatusCallback}
import fr.acinq.eclair.wire.{HostedChannelMessage, LightningMessage, NodeAnnouncement}
import com.btcontract.wallet.ln.{ChannelMaster, CommsTower, ConnectionListener, LNParams, PathFinder, RxUtils, StorageFormat}
import org.ndeftools.util.activity.NfcReaderActivity
import com.ornach.nobobutton.NoboButton
import android.widget.TextView
import org.ndeftools.Message
import android.os.Bundle
import android.view.View


object MainActivity {
  def makeOperational(host: FirebirdActivity, format: StorageFormat): Unit = {
    val normalNetworkDataStore = new SQliteNetworkDataStore(WalletApp.db, NormalChannelUpdateTable, NormalChannelAnnouncementTable, NormalExcludedChannelTable)
    val hostedNetworkDataStore = new SQliteNetworkDataStore(WalletApp.db, HostedChannelUpdateTable, HostedChannelAnnouncementTable, HostedExcludedChannelTable)
    val channelBag = new SQliteChannelBag(WalletApp.db)

    val pf: PathFinder =
      new PathFinder(normalNetworkDataStore, hostedNetworkDataStore, LNParams.routerConf) {
        def updateLastResyncStamp(stamp: Long): Unit = WalletApp.app.prefs.edit.putLong(WalletApp.LAST_GOSSIP_SYNC, stamp).commit
        def getExtraNodes: Set[NodeAnnouncement] = LNParams.channelMaster.all.map(_.data.announce.na).toSet
        def getLastResyncStamp: Long = WalletApp.app.prefs.getLong(WalletApp.LAST_GOSSIP_SYNC, 0L)
      }

    val channelMaster: ChannelMaster =
      new ChannelMaster(WalletApp.paymentBag.bag, channelBag, pf, WalletApp.chainLink) {
        val socketToChannelBridge: ConnectionListener = new ConnectionListener {
          // Messages should be differentiated by channelId, but we don't since only one hosted channel per node is allowed
          override def onOperational(worker: CommsTower.Worker): Unit = fromNode(worker.ann.nodeId).foreach(_ process CMD_SOCKET_ONLINE)
          override def onMessage(worker: CommsTower.Worker, msg: LightningMessage): Unit = fromNode(worker.ann.nodeId).foreach(_ process msg)
          override def onHostedMessage(worker: CommsTower.Worker, msg: HostedChannelMessage): Unit = fromNode(worker.ann.nodeId).foreach(_ process msg)

          override def onDisconnect(worker: CommsTower.Worker): Unit = {
            fromNode(worker.ann.nodeId).foreach(_ process CMD_SOCKET_OFFLINE)
            val mustHalt = WalletApp.app.prefs.getBoolean(WalletApp.ENSURE_TOR, false) && !isVPNOn
            if (mustHalt) interruptWallet else RxUtils.ioQueue.delay(5.seconds).foreach(_ => initConnect)
          }
        }
    }

    LNParams.format = format
    LNParams.channelMaster = channelMaster
    require(WalletApp.isOperational, "Not operational")

    channelMaster.initConnect
    host exitTo classOf[HubActivity]
  }

  def isVPNOn: Boolean = Try {
    val cm = WalletApp.app.getSystemService(Context.CONNECTIVITY_SERVICE).asInstanceOf[ConnectivityManager]
    cm.getAllNetworks.exists(cm getNetworkCapabilities _ hasTransport NetworkCapabilities.TRANSPORT_VPN)
  } getOrElse false

  def interruptWallet: Unit = if (WalletApp.isAlive) {
    // This may be called multiple times from different threads
    // execute once if app is alive, otherwise do nothing

    WalletApp.app.freePossiblyUsedResouces
    WalletApp.app.quickToast(orbot_err_disconnect)
    require(!WalletApp.isOperational, "Still operational")

    // Effectively restart an app
    val mainActivityClass = classOf[MainActivity]
    val component = new Intent(WalletApp.app, mainActivityClass).getComponent
    WalletApp.app.startActivity(Intent makeRestartActivityTask component)
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
    case false => runAnd(WalletApp.app.makeAlive)(me proceed null)
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
    private[this] val orbotHelper = OrbotHelper.get(me)
    private[this] val initCallback = new StatusCallback {
      def onStatusTimeout: Unit = showIssue(orbot_err_unclear, getString(orbot_action_open), closeAppExitOrbot).run
      def onNotYetInstalled: Unit = showIssue(orbot_err_not_installed, getString(orbot_action_install), closeAppInstallOrbot).run
      def onEnabled(intent: Intent): Unit = if (MainActivity.isVPNOn) runAnd(orbotHelper removeStatusCallback this)(next.makeAttempt) else onStatusTimeout
      def onStopping: Unit = onStatusTimeout
      def onDisabled: Unit = none
      def onStarting: Unit = none
    }

    def closeAppExitOrbot: Unit = {
      val pack = OrbotHelper.ORBOT_PACKAGE_NAME
      val intent = getPackageManager getLaunchIntentForPackage pack
      Option(intent).foreach(startActivity)
      finishAffinity
      System exit 0
    }

    def closeAppInstallOrbot: Unit = {
      orbotHelper installOrbot me
      finishAffinity
      System exit 0
    }

    def proceedAnyway: Unit = {
      // We must disable Tor check because disconnect later will bring us here again
      WalletApp.app.prefs.edit.putBoolean(WalletApp.ENSURE_TOR, false).commit
      next.makeAttempt
    }

    private def showIssue(msgRes: Int, btnText: String, whenTapped: => Unit) = UITask {
      skipOrbotCheck setOnClickListener onButtonTap(proceedAnyway)
      takeOrbotAction setOnClickListener onButtonTap(whenTapped)
      mainOrbotIssues setVisibility View.VISIBLE
      mainOrbotCheck setVisibility View.GONE
      mainOrbotMessage setText msgRes
      takeOrbotAction setText btnText
      timer.cancel
    }

    def makeAttempt: Unit = {
      orbotHelper.addStatusCallback(initCallback)
      try timer.schedule(initCallback.onStatusTimeout, 20000) catch none
      try timer.schedule(mainOrbotCheck setVisibility View.VISIBLE, 2000) catch none
      if (!orbotHelper.init) initCallback.onNotYetInstalled
    }
  }
}
