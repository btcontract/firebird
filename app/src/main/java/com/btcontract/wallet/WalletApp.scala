package com.btcontract.wallet

import spray.json._
import com.softwaremill.quicklens._
import com.btcontract.wallet.lnutils._
import com.btcontract.wallet.R.string._
import scala.collection.JavaConverters._
import com.btcontract.wallet.ln.crypto.Tools._
import com.btcontract.wallet.ln.utils.ImplicitJsonFormats._
import com.btcontract.wallet.ln.utils.{BtcDenomination, Denomination, LNUrl, SatDenomination}
import android.content.{ClipboardManager, Context, Intent, SharedPreferences}
import android.app.{Application, NotificationChannel, NotificationManager}
import com.btcontract.wallet.ln.{CommsTower, LNParams, PaymentRequestExt}
import com.btcontract.wallet.helper.{AwaitService, WebSocketBus}
import fr.acinq.eclair.wire.{NodeAddress, NodeAnnouncement}
import scala.util.{Success, Try}

import androidx.appcompat.app.AppCompatDelegate
import fr.acinq.eclair.payment.PaymentRequest
import scala.util.matching.UnanchoredRegex
import org.bitcoinj.params.MainNetParams
import fr.acinq.bitcoin.Crypto.PublicKey
import org.bitcoinj.crypto.MnemonicCode
import com.blockstream.libwally.Wally
import fr.acinq.eclair.MilliSatoshi
import org.bitcoinj.uri.BitcoinURI
import androidx.multidex.MultiDex
import fr.acinq.bitcoin.Crypto
import scodec.bits.ByteVector
import android.widget.Toast
import android.os.Build


object WalletApp {
  var app: WalletApp = _
  var fiatCode: String = _
  var denom: Denomination = _
  var db: SQLiteInterface = _
  var dataBag: SQLiteDataBag = _
  var paymentBag: SQlitePaymentBag = _
  var usedAddons: UsedAddons = _
  var fiatRates: FiatRates = _
  var value: Any = new String

  val params: MainNetParams = org.bitcoinj.params.MainNetParams.get
  val denoms = List(SatDenomination, BtcDenomination)

  final val USE_AUTH = "useAuth"
  final val FIAT_TYPE = "fiatType"
  final val DENOM_TYPE = "denomType"
  final val ENSURE_TOR = "ensureTor"
  final val FIAT_RATES_DATA = "fiatRatesData"
  final val LAST_NORMAL_GOSSIP_SYNC = "lastNormalGossipSync"
  final val LAST_TOTAL_GOSSIP_SYNC = "lastHostedGossipSync"

  private[this] val prefixes = PaymentRequest.prefixes.values mkString "|"
  private[this] val lnUrl = s"(?im).*?(lnurl)([0-9]{1,}[a-z0-9]+){1}".r.unanchored
  private[this] val lnPayReq = s"(?im).*?($prefixes)([0-9]{1,}[a-z0-9]+){1}".r.unanchored
  private[this] val shortNodeLink = "([a-fA-F0-9]{66})@([a-zA-Z0-9:\\.\\-_]+)".r.unanchored
  val nodeLink: UnanchoredRegex = "([a-fA-F0-9]{66})@([a-zA-Z0-9:\\.\\-_]+):([0-9]+)".r.unanchored

  case object DoNotEraseValue
  type Checker = PartialFunction[Any, Any]
  def checkAndMaybeErase(checkerMethod: Checker): Unit = checkerMethod(value) match { case DoNotEraseValue => case _ => value = null }
  def isAlive: Boolean = null != app && null != fiatCode && null != denom && null != db && null != dataBag && null != paymentBag && null != usedAddons
  def isOperational: Boolean = isAlive && null != fiatRates && null != LNParams.format && null != LNParams.channelMaster && null != LNParams.channelMaster.cl

  def bitcoinUri(bitcoinUriLink: String): BitcoinURI = {
    val bitcoinURI = new BitcoinURI(params, bitcoinUriLink)
    require(null != bitcoinURI.getAddress, "No address detected")
    bitcoinURI
  }

  def recordValue(raw: String): Unit = value = parse(raw)
  def parse(rawInput: String): Any = rawInput take 2880 match {
    case bitcoinUriLink if bitcoinUriLink startsWith "bitcoin" => bitcoinUri(bitcoinUriLink)
    case bitcoinUriLink if bitcoinUriLink startsWith "BITCOIN" => bitcoinUri(bitcoinUriLink.toLowerCase)
    case nodeLink(key, host, port) => mkNodeAnnouncement(PublicKey.fromBin(ByteVector fromValidHex key), NodeAddress.fromParts(host, port.toInt), key take 16 grouped 4 mkString " ")
    case shortNodeLink(key, host) => mkNodeAnnouncement(PublicKey.fromBin(ByteVector fromValidHex key), NodeAddress.fromParts(host, port = 9735), key take 16 grouped 4 mkString " ")
    case lnPayReq(prefix, data) => PaymentRequestExt(pr = PaymentRequest.read(s"$prefix$data"), raw = s"$prefix$data")
    case lnUrl(prefix, data) => LNUrl.fromBech32(s"$prefix$data")
    case _ => bitcoinUri(s"bitcoin:$rawInput")
  }

  // Fiat conversion

  def currentRate(rates: Fiat2Btc, code: String): Try[Double] = Try(rates apply code)
  def msatInFiat(rates: Fiat2Btc, code: String)(msat: MilliSatoshi): Try[Double] = currentRate(rates, code) map { ratePerOneBtc => msat.toLong * ratePerOneBtc / BtcDenomination.factor }
  def msatInFiatHuman(rates: Fiat2Btc, code: String, msat: MilliSatoshi): String = msatInFiat(rates, code)(msat) match { case Success(amt) => s"≈ ${Denomination.formatFiat format amt} $code" case _ => s"≈ ? $code" }
  val currentMsatInFiatHuman: MilliSatoshi => String = msat => msatInFiatHuman(fiatRates.ratesInfo.rates, fiatCode, msat)

  // Mnemonic

  type SeedWordSeq = Seq[String]
  def getMnemonic(seed: Bytes): SeedWordSeq = MnemonicCode.INSTANCE.toMnemonic(seed).asScala
  def getSeedFromMnemonic(words: SeedWordSeq): Bytes = MnemonicCode.toSeed(words.asJava, new String)

  // Scrypt

  def scryptDerive(email: String, pass: String): Bytes = {
    // An intentionally expensive key-stretching method
    // N = 2^19, r = 8, p = 2

    val derived = new Array[Byte](64)
    val emailBytes = ByteVector.view(email.getBytes)
    val salt = Crypto.hash256(emailBytes).take(16).toArray
    Wally.scrypt(pass.trim.getBytes, salt, Math.pow(2, 19).toLong, 8, 2, derived)
    derived
  }

  // Safe removal of outstanding peers

  def syncRmOutstanding(ann: NodeAnnouncement): Unit = synchronized {
    LNParams.format = LNParams.format.modify(_.outstandingProviders).using(_ - ann)
    dataBag.put(SQLiteDataBag.LABEL_FORMAT, LNParams.format.toJson.compactPrint)
  }

  object Vibrator {
    private var lastVibrated = 0L
    private val vibrator = app.getSystemService(Context.VIBRATOR_SERVICE).asInstanceOf[android.os.Vibrator]
    def canVibrate: Boolean = null != vibrator && vibrator.hasVibrator && lastVibrated < System.currentTimeMillis - 3000L

    def vibrate: Unit = if (canVibrate) {
      lastVibrated = System.currentTimeMillis
      vibrator.vibrate(Array(0L, 85, 200), -1)
    }
  }
}

class WalletApp extends Application {
  lazy val foregroundServiceIntent = new Intent(this, AwaitService.awaitServiceClass)
  lazy val prefs: SharedPreferences = getSharedPreferences("prefs", Context.MODE_PRIVATE)

  private[this] lazy val metrics = getResources.getDisplayMetrics
  lazy val scrWidth: Double = metrics.widthPixels.toDouble / metrics.densityDpi
  lazy val maxDialog: Double = metrics.densityDpi * 2.2
  lazy val isTablet: Boolean = scrWidth > 3.5

  lazy val plur: (Array[String], Long) => String = getString(lang) match {
    case "eng" | "esp" => (opts: Array[String], num: Long) => if (num == 1) opts(1) else opts(2)
    case "chn" | "jpn" => (phraseOptions: Array[String], _: Long) => phraseOptions(1)
    case "rus" | "ukr" => (phraseOptions: Array[String], num: Long) =>

      val reminder100 = num % 100
      val reminder10 = reminder100 % 10
      if (reminder100 > 10 & reminder100 < 20) phraseOptions(3)
      else if (reminder10 > 1 & reminder10 < 5) phraseOptions(2)
      else if (reminder10 == 1) phraseOptions(1)
      else phraseOptions(3)
  }

  override def onCreate: Unit = runAnd(super.onCreate) {
    // Currently night theme is the only option, should be set by default
    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
      val manager = this getSystemService classOf[NotificationManager]
      val chan = new NotificationChannel(AwaitService.CHANNEL_ID, "NC", NotificationManager.IMPORTANCE_DEFAULT)
      manager.createNotificationChannel(chan)
    }
  }

  override protected def attachBaseContext(base: Context): Unit = {
    super.attachBaseContext(base)
    MultiDex.install(this)
    WalletApp.app = this
  }

  def showStickyPaymentNotification(titleRes: Int, amount: MilliSatoshi): Unit = {
    val bodyText = getString(incoming_notify_body).format(WalletApp.denom parsedWithSign amount)
    foregroundServiceIntent.putExtra(AwaitService.TITLE_TO_DISPLAY, this getString titleRes).putExtra(AwaitService.BODY_TO_DISPLAY, bodyText).setAction(AwaitService.ACTION_SHOW)
    androidx.core.content.ContextCompat.startForegroundService(this, foregroundServiceIntent)
  }

  def freePossiblyUsedResouces: Unit = {
    Option(LNParams.channelMaster) foreach { cm =>
      if (null != cm.cl) cm.cl.listeners = Set.empty
      if (null != cm.cl) cm.cl.stop
      cm.listeners = Set.empty
      cm.all = List.empty
    }

    // Safely disconnect from remaining sockets
    WebSocketBus.workers.keys.foreach(WebSocketBus.forget)
    CommsTower.workers.values.map(_.pkap).foreach(CommsTower.forget)
    if (null != WalletApp.fiatRates) WalletApp.fiatRates.subscription.unsubscribe
    if (null != WalletApp.db) WalletApp.db.close
    // Make sure application is not alive
    LNParams.channelMaster = null
    WalletApp.usedAddons = null
    WalletApp.fiatRates = null
    WalletApp.db = null
  }

  def makeAlive: Unit = {
    freePossiblyUsedResouces
    WalletApp.db = new SQLiteInterface(this, "firebird.db")
    WalletApp.fiatCode = prefs.getString(WalletApp.FIAT_TYPE, "usd")
    WalletApp.denom = WalletApp denoms prefs.getInt(WalletApp.DENOM_TYPE, 0)

    WalletApp.dataBag = new SQLiteDataBag(WalletApp.db)
    WalletApp.paymentBag = new SQlitePaymentBag(WalletApp.db)
    // Set up used addons, but do nothing more here, initialize connection-enabled later
    WalletApp.usedAddons = WalletApp.dataBag.tryGetUsedAddons getOrElse UsedAddons(Nil)
  }

  def quickToast(code: Int): Unit = quickToast(this getString code)
  def quickToast(msg: CharSequence): Unit = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show
  def plur1OrZero(opts: Array[String], num: Long): String = if (num > 0) plur(opts, num).format(num) else opts(0)
  def clipboardManager: ClipboardManager = getSystemService(Context.CLIPBOARD_SERVICE).asInstanceOf[ClipboardManager]
  def getBufferUnsafe: String = clipboardManager.getPrimaryClip.getItemAt(0).getText.toString
}