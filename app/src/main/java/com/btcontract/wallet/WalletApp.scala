package com.btcontract.wallet

import spray.json._
import com.btcontract.wallet.R.string._
import scala.collection.JavaConverters._
import com.btcontract.wallet.ln.crypto.Tools._
import com.btcontract.wallet.lnutils.ImplicitJsonFormats._
import com.btcontract.wallet.helper.{AwaitService, BtcDenomination, Denomination, SatDenomination}
import android.content.{ClipboardManager, Context, Intent, SharedPreferences}
import android.app.{Application, NotificationChannel, NotificationManager}
import scala.util.{Success, Try}

import fr.acinq.eclair.payment.PaymentRequest
import com.btcontract.wallet.FiatRates.Rates
import com.btcontract.wallet.lnutils.LNUrl
import scala.util.matching.UnanchoredRegex
import org.bitcoinj.params.MainNetParams
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.wire.NodeAddress
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
  var value: Any = new String // Keep default empty string
  val params: MainNetParams = org.bitcoinj.params.MainNetParams.get
  val denoms = List(SatDenomination, BtcDenomination)
  val chainLink = new BitcoinJChainLink(params)

  final val FIAT_TYPE = "fiatType"
  final val FIAT_RATES_DATA = "fiatRatesData"
  final val LAST_GOSSIP_SYNC = "lastGossipSync"
  final val ENSURE_TOR = "ensureTor"
  final val DENOM_TYPE = "denomType"

  private[this] val prefixes = PaymentRequest.prefixes.values mkString "|"
  private[this] val lnUrl = s"(?im).*?(lnurl)([0-9]{1,}[a-z0-9]+){1}".r.unanchored
  private[this] val lnPayReq = s"(?im).*?($prefixes)([0-9]{1,}[a-z0-9]+){1}".r.unanchored
  private[this] val shortNodeLink = "([a-fA-F0-9]{66})@([a-zA-Z0-9:\\.\\-_]+)".r.unanchored
  val nodeLink: UnanchoredRegex = "([a-fA-F0-9]{66})@([a-zA-Z0-9:\\.\\-_]+):([0-9]+)".r.unanchored

  case object DoNotEraseValue
  type Checker = PartialFunction[Any, Any]
  def checkAndMaybeErase(check: Checker): Unit = check(value) match {
    // Sometimes we need to forward a value between activity switches
    case DoNotEraseValue => log("WalletApp.value retained")
    case _ => value = null
  }

  def bitcoinUri(bitcoinUriLink: String): BitcoinURI = {
    val bitcoinURI = new BitcoinURI(params, bitcoinUriLink)
    require(null != bitcoinURI.getAddress, "No address detected")
    bitcoinURI
  }

  def recordValue(raw: String): Unit = value = parse(raw)
  def parse(rawInput: String): Any = rawInput take 2880 match {
    case bitcoinUriLink if bitcoinUriLink startsWith "bitcoin" => bitcoinUri(bitcoinUriLink)
    case bitcoinUriLink if bitcoinUriLink startsWith "BITCOIN" => bitcoinUri(bitcoinUriLink.toLowerCase)
    case nodeLink(key, host, port) => mkNodeAnnouncement(PublicKey.fromBin(ByteVector fromValidHex key), NodeAddress.fromParts(host, port.toInt), host)
    case shortNodeLink(key, host) => mkNodeAnnouncement(PublicKey.fromBin(ByteVector fromValidHex key), NodeAddress.fromParts(host, port = 9735), host)
    case lnPayReq(prefix, data) => PaymentRequest.read(s"$prefix$data")
    case lnUrl(prefix, data) => LNUrl.fromBech32(s"$prefix$data")
    case _ => bitcoinUri(s"bitcoin:$rawInput")
  }

  // Fiat conversion

  def msatInFiat(rates: Rates, code: String, msat: MilliSatoshi): Try[Double] =
    Try(rates apply code) map { perBtc => msat.toLong * perBtc / BtcDenomination.factor }

  def msatInFiatHuman(rates: Rates, code: String, msat: MilliSatoshi): String = msatInFiat(rates, code, msat) match {
    case Success(generatedFiatAmount) => s"≈ ${Denomination.formatFiat format generatedFiatAmount} $code"
    case _ => s"≈ ? $code"
  }

  val currentMsatInFiatHuman: MilliSatoshi => String =
    msatInFiatHuman(FiatRates.ratesInfo.rates, fiatCode, _: MilliSatoshi)

  // Mnemonic

  type SeedWordSeq = Seq[String]
  def getRandomMnemonic: SeedWordSeq = getMnemonic(random getBytes 16)
  def getMnemonic(seed: Bytes): SeedWordSeq = MnemonicCode.INSTANCE.toMnemonic(seed).asScala
  def getSeedFromMnemonic(words: SeedWordSeq): Bytes = MnemonicCode.toSeed(words.asJava, new String)

  // Scrypt

  def scryptDerive(email: String, pass: String): ByteVector = {
    // An intentionally expensive key-stretching method
    // N = 2^19, r = 8, p = 2

    val derived: Bytes = new Array[Byte](64)
    val salt: Bytes = Crypto.hash256(email.getBytes).take(16).toArray
    Wally.scrypt(pass.trim.getBytes, salt, Math.pow(2, 19).toLong, 8, 2, derived)
    ByteVector.view(derived)
  }
}

class WalletApp extends Application {
  lazy val foregroundServiceIntent = new Intent(this, AwaitService.classof)
  lazy val prefs: SharedPreferences = getSharedPreferences("prefs", Context.MODE_PRIVATE)

  private[this] lazy val metrics = getResources.getDisplayMetrics
  lazy val scrWidth: Double = metrics.widthPixels.toDouble / metrics.densityDpi
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
    WalletApp.fiatCode = prefs.getString(WalletApp.FIAT_TYPE, "usd")
    WalletApp.denom = WalletApp denoms prefs.getInt(WalletApp.DENOM_TYPE, 0)

    FiatRates.ratesInfo = {
      val raw = prefs.getString(WalletApp.FIAT_RATES_DATA, new String)
      Try(raw) map to[RatesInfo] getOrElse RatesInfo(Map.empty, stamp = 0L)
    }

    FiatRates.observable(FiatRates.ratesInfo.stamp).subscribe(newFiatRates => {
      val newRatesInfo = RatesInfo(rates = newFiatRates, System.currentTimeMillis)
      prefs.edit.putString(WalletApp.FIAT_RATES_DATA, newRatesInfo.toJson.toString).commit
      FiatRates.ratesInfo = newRatesInfo
    }, none)

    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
      val srvChan = new NotificationChannel(AwaitService.CHANNEL_ID, "NC", NotificationManager.IMPORTANCE_DEFAULT)
      this getSystemService classOf[NotificationManager] createNotificationChannel srvChan
    }
  }

  def quickToast(code: Int): Unit = quickToast(this getString code)
  def quickToast(msg: CharSequence): Unit = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show
  def clipboardManager: ClipboardManager = getSystemService(Context.CLIPBOARD_SERVICE).asInstanceOf[ClipboardManager]
  def plur1OrZero(opts: Array[String], num: Long): String = if (num > 0) plur(opts, num).format(num) else opts(0)
  def getBufferUnsafe: String = clipboardManager.getPrimaryClip.getItemAt(0).getText.toString
}
