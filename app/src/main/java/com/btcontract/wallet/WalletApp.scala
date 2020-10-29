package com.btcontract.wallet

import spray.json._
import com.softwaremill.quicklens._
import com.btcontract.wallet.lnutils._
import com.btcontract.wallet.R.string._
import scala.collection.JavaConverters._
import com.btcontract.wallet.ln.crypto.Tools._
import com.btcontract.wallet.ln.utils.ImplicitJsonFormats._
import com.btcontract.wallet.lnutils.ImplicitJsonFormatsExt._
import com.btcontract.wallet.ln.utils.{BtcDenomination, Denomination, FiatRates, RatesInfo, SatDenomination}
import fr.acinq.eclair.wire.{NodeAddress, NodeAnnouncement, UpdateAddHtlc, UpdateFulfillHtlc}
import com.btcontract.wallet.ln.{ChainLink, CommsTower, LNParams, PaymentRequestExt, utils}
import android.content.{ClipboardManager, Context, Intent, SharedPreferences}
import android.app.{Application, NotificationChannel, NotificationManager}
import scala.util.{Success, Try}

import com.btcontract.wallet.ln.utils.FiatRates.Rates
import com.btcontract.wallet.helper.AwaitService
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
  var paymentBag: CachedSQlitePaymentBag = _
  var usedAddons: UsedAddons = _
  var chainLink: ChainLink = _
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
  final val PAYMENT_SUMMARY_CACHE = "paymentSummaryCache"

  private[this] val prefixes = PaymentRequest.prefixes.values mkString "|"
  private[this] val lnUrl = s"(?im).*?(lnurl)([0-9]{1,}[a-z0-9]+){1}".r.unanchored
  private[this] val lnPayReq = s"(?im).*?($prefixes)([0-9]{1,}[a-z0-9]+){1}".r.unanchored
  private[this] val shortNodeLink = "([a-fA-F0-9]{66})@([a-zA-Z0-9:\\.\\-_]+)".r.unanchored
  val nodeLink: UnanchoredRegex = "([a-fA-F0-9]{66})@([a-zA-Z0-9:\\.\\-_]+):([0-9]+)".r.unanchored

  case object DoNotEraseValue
  type Checker = PartialFunction[Any, Any]
  def checkAndMaybeErase(checkerMethod: Checker): Unit = checkerMethod(value) match { case DoNotEraseValue => case _ => value = null }
  def isAlive: Boolean = null != app && null != fiatCode && null != denom && null != db && null != dataBag && null != paymentBag && null != usedAddons && null != chainLink
  def isOperational: Boolean = isAlive && null != LNParams.format && null != LNParams.channelMaster

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
    case lnPayReq(prefix, data) => new PaymentRequestExt(pr = PaymentRequest.read(s"$prefix$data"), raw = s"$prefix$data")
    case lnUrl(prefix, data) => LNUrl.fromBech32(s"$prefix$data")
    case _ => bitcoinUri(s"bitcoin:$rawInput")
  }

  // Fiat conversion

  def currentRate(rates: Rates, code: String): Try[Double] = Try(rates apply code)
  def msatInFiat(rates: Rates, code: String)(msat: MilliSatoshi): Try[Double] = currentRate(rates, code) map { ratePerOneBtc => msat.toLong * ratePerOneBtc / BtcDenomination.factor }
  def msatInFiatHuman(rates: Rates, code: String, msat: MilliSatoshi): String = msatInFiat(rates, code)(msat) match { case Success(amt) => s"≈ ${Denomination.formatFiat format amt} $code" case _ => s"≈ ? $code" }
  val currentMsatInFiatHuman: MilliSatoshi => String = msat => msatInFiatHuman(FiatRates.ratesInfo.rates, fiatCode, msat)

  // Mnemonic

  type SeedWordSeq = Seq[String]
  def getMnemonic(seed: Bytes): SeedWordSeq = MnemonicCode.INSTANCE.toMnemonic(seed).asScala
  def getSeedFromMnemonic(words: SeedWordSeq): Bytes = MnemonicCode.toSeed(words.asJava, new String)

  // Scrypt

  def scryptDerive(email: String, pass: String): Bytes = {
    // An intentionally expensive key-stretching method
    // N = 2^19, r = 8, p = 2

    val derived: Bytes = new Array[Byte](64)
    val salt: Bytes = Crypto.hash256(email.getBytes).take(16).toArray
    Wally.scrypt(pass.trim.getBytes, salt, Math.pow(2, 19).toLong, 8, 2, derived)
    derived
  }

  // Safe removal of outstanding peers

  def syncRmOutstanding(ann: NodeAnnouncement): Unit = synchronized {
    LNParams.format = LNParams.format.modify(_.outstandingProviders).using(_ - ann)
    dataBag.put(SQLiteDataBag.LABEL_FORMAT, LNParams.format.toJson.compactPrint)
  }
}

class WalletApp extends Application {
  lazy val foregroundServiceIntent = new Intent(this, AwaitService.classof)
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

  override protected def attachBaseContext(base: Context): Unit = {
    super.attachBaseContext(base)
    MultiDex.install(this)
    WalletApp.app = this

    // Currently night theme is the only option, should be set by default
    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
    makeAlive

    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
      val srvChan = new NotificationChannel(AwaitService.CHANNEL_ID, "NC", NotificationManager.IMPORTANCE_DEFAULT)
      this getSystemService classOf[NotificationManager] createNotificationChannel srvChan
    }
  }

  def freePossiblyUsedResouces: Unit = {
    // Safely disconnect from possibly remaining sockets
    WebSocketBus.workers.keys.foreach(WebSocketBus.forget)
    CommsTower.workers.values.map(_.pkap).foreach(CommsTower.forget)
    if (null != FiatRates.subscription) FiatRates.subscription.unsubscribe
    if (null != LNParams.channelMaster) LNParams.channelMaster.listeners = Set.empty
    if (null != LNParams.channelMaster) LNParams.channelMaster.all = Vector.empty
    if (null != WalletApp.chainLink) WalletApp.chainLink.listeners = Set.empty
    if (null != WalletApp.chainLink) WalletApp.chainLink.stop
    if (null != WalletApp.db) WalletApp.db.close
    // Make sure application is not alive
    LNParams.channelMaster = null
    WalletApp.usedAddons = null
    WalletApp.chainLink = null
    WalletApp.db = null
  }

  def makeAlive: Unit = {
    freePossiblyUsedResouces
    WalletApp.db = new SQLiteInterface(this, "firebird.db")
    WalletApp.fiatCode = prefs.getString(WalletApp.FIAT_TYPE, "usd")
    WalletApp.denom = WalletApp denoms prefs.getInt(WalletApp.DENOM_TYPE, 0)
    WalletApp.chainLink = new BitcoinJChainLink(WalletApp.params)
    // Start looking for chain height asap
    WalletApp.chainLink.start

    // Set up cached payment bag to get payment summary faster
    val bag: SQlitePaymentBag = new SQlitePaymentBag(WalletApp.db)
    WalletApp.paymentBag = new CachedSQlitePaymentBag(bag)

    WalletApp.dataBag = new SQLiteDataBag(WalletApp.db)
    // Set up used addons, but do nothing more here, initialize connection-enabled later
    WalletApp.usedAddons = WalletApp.dataBag.tryGetUsedAddons getOrElse UsedAddons(Nil)

    FiatRates.ratesInfo = Try {
      prefs.getString(WalletApp.FIAT_RATES_DATA, new String)
    } map to[RatesInfo] getOrElse utils.RatesInfo(Map.empty, Map.empty, stamp = 0L)

    FiatRates.subscription = FiatRates.makeObservable(FiatRates.ratesInfo.stamp).subscribe(newFiatRates => {
      val newRatesInfo = RatesInfo(newFiatRates, FiatRates.ratesInfo.rates, System.currentTimeMillis)
      prefs.edit.putString(WalletApp.FIAT_RATES_DATA, newRatesInfo.toJson.compactPrint).commit
      FiatRates.ratesInfo = newRatesInfo
    }, none)
  }

  def quickToast(code: Int): Unit = quickToast(this getString code)
  def quickToast(msg: CharSequence): Unit = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show
  def plur1OrZero(opts: Array[String], num: Long): String = if (num > 0) plur(opts, num).format(num) else opts(0)
  def clipboardManager: ClipboardManager = getSystemService(Context.CLIPBOARD_SERVICE).asInstanceOf[ClipboardManager]
  def getBufferUnsafe: String = clipboardManager.getPrimaryClip.getItemAt(0).getText.toString
}

class CachedSQlitePaymentBag(val bag: SQlitePaymentBag) extends PaymentUpdaterToSuccess {
  private def get: String = WalletApp.app.prefs.getString(WalletApp.PAYMENT_SUMMARY_CACHE, new String)
  private def put(raw: String): Unit = WalletApp.app.prefs.edit.putString(WalletApp.PAYMENT_SUMMARY_CACHE, raw).commit
  def getCurrent: TotalStatSummaryExt = current getOrElse restoreAndUpdateCurrent
  private var current: Option[TotalStatSummaryExt] = None

  private def restoreAndUpdateCurrent: TotalStatSummaryExt = {
    val (restoredExt, updateCache) = Try(get) map to[TotalStatSummaryExt] match {
      case Success(summaryWithCache) if summaryWithCache.summary.isDefined => summaryWithCache -> false
      case Success(noCache) => TotalStatSummaryExt(bag.betweenSummary(noCache.from, noCache.to).toOption, noCache.from, noCache.to) -> true
      case _ => TotalStatSummaryExt(bag.betweenSummary(from = 0L, to = Long.MaxValue).toOption, from = 0L, to = Long.MaxValue) -> true
    }

    if (updateCache) put(restoredExt.toJson.compactPrint)
    // Called after invalidation or on app restart
    current = Some(restoredExt)
    restoredExt
  }

  def invlidateContent: Unit = {
    // Remove stale content snapshot, but retain from/to boundaries
    current.map(_.copy(summary = None).toJson.compactPrint).foreach(put)
    // Next call to `getCurrent` will trigger restoring from db
    current = None
  }

  def invlidateBoundaries(from: Long, until: Long): Unit = {
    put(TotalStatSummaryExt(None, from, until).toJson.compactPrint)
    // Next call to `getCurrent` will trigger restoring from db
    current = None
  }

  def updOkOutgoing(upd: UpdateFulfillHtlc, sent: MilliSatoshi, fee: MilliSatoshi): Unit = {
    // Summary counts SUCCEEDED records, need to invalidate
    bag.updOkOutgoing(upd, sent, fee)
    invlidateContent
  }

  def updOkIncoming(add: UpdateAddHtlc): Unit = {
    // Summary counts SUCCEEDED records, need to invalidate
    bag.updOkIncoming(add)
    invlidateContent
  }
}