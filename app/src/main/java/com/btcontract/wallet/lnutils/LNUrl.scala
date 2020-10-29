package com.btcontract.wallet.lnutils

import spray.json._
import fr.acinq.eclair._
import com.btcontract.wallet.ln.crypto.Tools._
import com.github.kevinsawicki.http.HttpRequest._
import com.btcontract.wallet.lnutils.ImplicitJsonFormats._
import com.btcontract.wallet.ln.{LNParams, PaymentAction, RxUtils}
import android.graphics.{Bitmap, BitmapFactory}
import fr.acinq.bitcoin.{Bech32, Crypto}

import com.btcontract.wallet.lnutils.PayRequest.PayMetaData
import com.btcontract.wallet.lnutils.LNUrl.LNUrlAndData
import com.github.kevinsawicki.http.HttpRequest
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.wire.NodeAddress
import rx.lang.scala.Observable
import scodec.bits.ByteVector
import android.net.Uri
import scala.util.Try


object LNUrl {
  type LNUrlAndData = (LNUrl, LNUrlData)
  type LNUrlAndWithdraw = (LNUrl, WithdrawRequest)

  def fromBech32(bech32url: String): LNUrl = {
    val _ \ dataBody = Bech32.decode(bech32url)
    val request = new String(Bech32.five2eight(dataBody), "UTF-8")
    LNUrl(request)
  }

  def guardResponse(raw: String): String = {
    val validJson = Try(raw.parseJson.asJsObject.fields)
    val hasError = validJson.map(_ apply "reason").map(json2String)
    if (validJson.isFailure) throw new Exception(s"Invalid json from remote provider: $raw")
    if (hasError.isSuccess) throw new Exception(s"Error message from remote provider: ${hasError.get}")
    raw
  }

  def checkHost(host: String): Uri = {
    val uri = android.net.Uri.parse(host)
    val isOnion = host.startsWith("http://") && uri.getHost.endsWith(NodeAddress.onionSuffix)
    val isSSLPlain = host.startsWith("https://") && !uri.getHost.endsWith(NodeAddress.onionSuffix)
    require(isSSLPlain || isOnion, "URI is neither Plain-HTTPS nor Onion-HTTP request")
    uri
  }
}

case class LNUrl(request: String) {
  val uri: Uri = LNUrl.checkHost(request)
  lazy val k1 = Try(uri getQueryParameter "k1")
  lazy val isAuth: Boolean = Try(uri getQueryParameter "tag" equals "login").getOrElse(false)

  lazy val withdrawAttempt = Try {
    require(uri getQueryParameter "tag" equals "withdrawRequest")
    val minWithdrawableOpt = Some(uri.getQueryParameter("minWithdrawable").toLong)

    WithdrawRequest(minWithdrawable = minWithdrawableOpt,
      maxWithdrawable = uri.getQueryParameter("maxWithdrawable").toLong,
      defaultDescription = uri getQueryParameter "defaultDescription",
      callback = uri getQueryParameter "callback",
      k1 = uri getQueryParameter "k1")
  }

  def lnUrlAndDataObs: Observable[LNUrlAndData] = RxUtils.ioQueue map { _ =>
    val level1DataResponse = get(uri.toString, false).header("Connection", "close")
    val lnUrlData = to[LNUrlData](LNUrl guardResponse level1DataResponse.connectTimeout(15000).body)
    require(lnUrlData.checkAgainstParent(this), "1st/2nd level callback domains mismatch")
    this -> lnUrlData
  }
}

trait LNUrlData {
  def checkAgainstParent(lnUrl: LNUrl): Boolean = true
  def level2DataResponse(req: Uri.Builder): HttpRequest = {
    val finalReq = req.appendQueryParameter(randomBytes(4).toHex, new String)
    get(finalReq.build.toString, false).header("Connection", "close")
  }
}

case class WithdrawRequest(callback: String, k1: String, maxWithdrawable: Long, defaultDescription: String, minWithdrawable: Option[Long] = None) extends LNUrlData { me =>
  def requestWithdraw(lnUrl: LNUrl, pr: PaymentRequest): HttpRequest = me level2DataResponse callbackUri.buildUpon.appendQueryParameter("pr", PaymentRequest write pr).appendQueryParameter("k1", k1)
  override def checkAgainstParent(lnUrl: LNUrl): Boolean = lnUrl.uri.getHost == callbackUri.getHost

  val callbackUri: Uri = LNUrl.checkHost(callback)
  val minCanReceive: MilliSatoshi = minWithdrawable.map(MilliSatoshi.apply).getOrElse(LNParams.minPayment).max(LNParams.minPayment)
  require(minCanReceive <= MilliSatoshi(maxWithdrawable), s"$maxWithdrawable is less than min $minCanReceive")
}

object PayRequest {
  type TagAndContent = Vector[String]
  type PayMetaData = Vector[TagAndContent]
}

case class PayLinkInfo(image64: String, lnurl: LNUrl, text: String, lastMsat: MilliSatoshi, hash: String, lastDate: Long) {
  lazy val bitmap: Try[Bitmap] = for (bytes <- imageBytesTry) yield BitmapFactory.decodeByteArray(bytes, 0, bytes.length)
  def imageBytesTry: Try[Bytes] = Try(org.bouncycastle.util.encoders.Base64 decode image64)
  lazy val paymentHash: ByteVector = ByteVector.fromValidHex(hash)
}

case class PayRequest(callback: String, maxSendable: Long, minSendable: Long, metadata: String, commentAllowed: Option[Int] = None) extends LNUrlData { me =>
  def requestFinal(amount: MilliSatoshi): HttpRequest = me level2DataResponse callbackUri.buildUpon.appendQueryParameter("amount", amount.toLong.toString)
  override def checkAgainstParent(lnUrl: LNUrl): Boolean = lnUrl.uri.getHost == callbackUri.getHost
  def metaDataHash: ByteVector = Crypto.sha256(ByteVector view metadata.getBytes)
  private val decodedMetadata = to[PayMetaData](metadata)

  val metaDataImageBase64s: Seq[String] = for {
    Vector("image/png;base64" | "image/jpeg;base64", content) <- decodedMetadata
    _ = require(content.length <= 136536, s"Image is too heavy, base64 length=${content.length}")
  } yield content

  val callbackUri: Uri = LNUrl.checkHost(callback)
  val minCanSend: MilliSatoshi = MilliSatoshi(minSendable) max LNParams.minPayment
  val metaDataTexts: Vector[String] = decodedMetadata.collect { case Vector("text/plain", content) => content }
  require(minCanSend <= MilliSatoshi(maxSendable), s"max=$maxSendable while min=$minCanSend")
  require(metaDataTexts.size == 1, "There must be exactly one text/plain entry in metadata")
  val metaDataTextPlain: String = metaDataTexts.head
}

case class PayRequestFinal(successAction: Option[PaymentAction], disposable: Option[Boolean],
                           routes: Vector[String], pr: String) extends LNUrlData {

  val paymentRequest: PaymentRequest = PaymentRequest.read(pr)
  val isThrowAway: Boolean = disposable getOrElse true
}

case class MessageAction(domain: Option[String], message: String) extends PaymentAction {
  val finalMessage = s"<br>${message take 144}"
}

case class UrlAction(domain: Option[String], description: String, url: String) extends PaymentAction {
  val finalMessage = s"<br>${description take 144}<br><br><font color=#0000FF><tt>$url</tt></font><br>"
  require(domain.forall(url.contains), "Payment action domain mismatch")
  val uri: Uri = LNUrl.checkHost(url)
}

case class AESAction(domain: Option[String], description: String, ciphertext: String, iv: String) extends PaymentAction {
  val ciphertextBytes: Bytes = ByteVector.fromValidBase64(ciphertext).take(1024 * 4).toArray // up to ~2kb of encrypted data
  val ivBytes: Bytes = ByteVector.fromValidBase64(iv).take(24).toArray // 16 bytes
  val finalMessage = s"<br>${description take 144}"
}