package com.btcontract.wallet.lnutils

import spray.json._
import fr.acinq.eclair.wire.LightningMessageCodecs.{channelUpdateCodec, errorCodec, lightningMessageCodec, nodeAnnouncementCodec, updateAddHtlcCodec, updateFailHtlcCodec, updateFailMalformedHtlcCodec}
import fr.acinq.eclair.wire.{ChannelUpdate, HostedChannelBranding, LastCrossSignedState, LightningMessage, NodeAnnouncement, UpdateAddHtlc, UpdateFailHtlc, UpdateFailMalformedHtlc}
import com.btcontract.wallet.ln.{CommitmentSpec, FailAndAdd, HostedCommits, Htlc, MalformAndAdd, NodeAnnouncementExt, PaymentAction}
import com.btcontract.wallet.{Bitpay, BitpayItem, BlockchainInfoItem, CoinGecko, CoinGeckoItem, RatesInfo}
import fr.acinq.eclair.wire.HostedMessagesCodecs.{hostedChannelBrandingCodec, lastCrossSignedStateCodec}
import com.btcontract.wallet.FiatRates.{BitpayItemList, CoinGeckoItemMap, Rates}
import fr.acinq.bitcoin.{ByteVector32, Transaction}
import fr.acinq.eclair.{MilliSatoshi, wire}

import com.btcontract.wallet.ln.CommitmentSpec.LNDirectionalMessage
import fr.acinq.eclair.wire.CommonCodecs.bytes32
import scodec.bits.BitVector
import java.math.BigInteger
import scodec.Codec


object ImplicitJsonFormats extends DefaultJsonProtocol { me =>
  def to[T : JsonFormat](raw: String): T = raw.parseJson.convertTo[T]
  val json2String: JsValue => String = (_: JsValue).convertTo[String]
  private val TAG = "tag"

  def taggedJsonFmt[T](base: JsonFormat[T], tag: String) = new JsonFormat[T] {
    def write(unserialized: T) = writeExt(TAG -> JsString(tag), base write unserialized)
    def read(serialized: JsValue) = base read serialized
  }

  def json2BitVec(json: JsValue): Option[BitVector] = BitVector fromHex json2String(json)
  def sCodecJsonFmt[T](codec: Codec[T] /* Json <-> sCodec bridge */) = new JsonFormat[T] {
    def read(serialized: JsValue) = codec.decode(json2BitVec(serialized).get).require.value
    def write(unserialized: T) = codec.encode(unserialized).require.toHex.toJson
  }

  def writeExt[T](ext: (String, JsValue), base: JsValue) =
    JsObject(base.asJsObject.fields + ext)

  implicit object BigIntegerFmt extends JsonFormat[BigInteger] {
    def read(json: JsValue): BigInteger = new BigInteger(me json2String json)
    def write(internal: BigInteger): JsValue = internal.toString.toJson
  }

  implicit object TransactionFmt extends JsonFormat[Transaction] {
    def read(json: JsValue): Transaction = Transaction.read(me json2String json)
    def write(internal: Transaction): JsValue = internal.bin.toHex.toJson
  }

  // Channel

  implicit val errorFmt: JsonFormat[wire.Error] = sCodecJsonFmt(errorCodec)
  implicit val channelUpdateFmt: JsonFormat[ChannelUpdate] = sCodecJsonFmt(channelUpdateCodec)
  implicit val updateAddHtlcFmt: JsonFormat[UpdateAddHtlc] = sCodecJsonFmt(updateAddHtlcCodec)
  implicit val nodeAnnouncementFmt: JsonFormat[NodeAnnouncement] = sCodecJsonFmt(nodeAnnouncementCodec)
  implicit val lightningMessageFmt: JsonFormat[LightningMessage] = sCodecJsonFmt(lightningMessageCodec)
  implicit val lastCrossSignedStateFmt: JsonFormat[LastCrossSignedState] = sCodecJsonFmt(lastCrossSignedStateCodec)
  implicit val hostedChannelBrandingFmt: JsonFormat[HostedChannelBranding] = sCodecJsonFmt(hostedChannelBrandingCodec)
  implicit val updateFailMalformedHtlcFmt: JsonFormat[UpdateFailMalformedHtlc] = sCodecJsonFmt(updateFailMalformedHtlcCodec)
  implicit val updateFailHtlcFmt: JsonFormat[UpdateFailHtlc] = sCodecJsonFmt(updateFailHtlcCodec)
  implicit val bytes32Fmt: JsonFormat[ByteVector32] = sCodecJsonFmt(bytes32)

  implicit val milliSatoshiFmt: JsonFormat[MilliSatoshi] = jsonFormat[Long, MilliSatoshi](MilliSatoshi.apply, "underlying")
  implicit val failAndAddFmt: JsonFormat[FailAndAdd] = jsonFormat[UpdateFailHtlc, UpdateAddHtlc, FailAndAdd](FailAndAdd.apply, "theirFail", "ourAdd")
  implicit val malformAndAddFmt: JsonFormat[MalformAndAdd] = jsonFormat[UpdateFailMalformedHtlc, UpdateAddHtlc, MalformAndAdd](MalformAndAdd.apply, "theirMalform", "ourAdd")
  implicit val htlcFmt: JsonFormat[Htlc] = jsonFormat[Boolean, UpdateAddHtlc, Htlc](Htlc.apply, "incoming", "add")

  implicit val commitmentSpecFmt: JsonFormat[CommitmentSpec] =
    jsonFormat[Long, MilliSatoshi, MilliSatoshi, Set[Htlc], Set[FailAndAdd], Set[MalformAndAdd], Set[ByteVector32],
      CommitmentSpec](CommitmentSpec.apply, "feeratePerKw", "toLocal", "toRemote", "htlcs", "remoteFailed", "remoteMalformed", "localFulfilled")

  implicit val nodeAnnouncementExtFmt: JsonFormat[NodeAnnouncementExt] =
    jsonFormat[NodeAnnouncement, NodeAnnouncementExt](NodeAnnouncementExt.apply, "na")

  implicit val hostedCommitsFmt: JsonFormat[HostedCommits] =
    jsonFormat[NodeAnnouncementExt, LastCrossSignedState, Vector[LNDirectionalMessage],
      CommitmentSpec, Option[ChannelUpdate], Option[HostedChannelBranding], Option[wire.Error], Option[wire.Error], Long,
      HostedCommits](HostedCommits.apply, "announce", "lastCrossSignedState", "futureUpdates", "localSpec", "updateOpt",
      "brandingOpt", "localError", "remoteError", "startedAt")

  // Fiat feerates

  implicit val ratesInfoFmt: JsonFormat[RatesInfo] = jsonFormat[Rates, Long, RatesInfo](RatesInfo.apply, "rates", "stamp")
  implicit val BlockchainInfoItemFmt: JsonFormat[BlockchainInfoItem] = jsonFormat[Double, BlockchainInfoItem](BlockchainInfoItem.apply, "last")
  implicit val bitpayItemFmt: JsonFormat[BitpayItem] = jsonFormat[String, Double, BitpayItem](BitpayItem.apply, "code", "rate")
  implicit val coinGeckoItemFmt: JsonFormat[CoinGeckoItem] = jsonFormat[Double, CoinGeckoItem](CoinGeckoItem.apply, "value")
  implicit val coinGeckoFmt: JsonFormat[CoinGecko] = jsonFormat[CoinGeckoItemMap, CoinGecko](CoinGecko.apply, "rates")
  implicit val bitpayFmt: JsonFormat[Bitpay] = jsonFormat[BitpayItemList, Bitpay](Bitpay.apply, "data")

  // LNURL and payment actions

  implicit object PaymentActionFmt extends JsonFormat[PaymentAction] {
    def read(raw: JsValue): PaymentAction = raw.asJsObject fields TAG match {
      case JsString("message") => raw.convertTo[MessageAction]
      case JsString("aes") => raw.convertTo[AESAction]
      case JsString("url") => raw.convertTo[UrlAction]
      case tag => throw new Exception(s"Unknown action=$tag")
    }

    def write(internal: PaymentAction): JsValue = internal match {
      case paymentAction: MessageAction => paymentAction.toJson
      case paymentAction: UrlAction => paymentAction.toJson
      case paymentAction: AESAction => paymentAction.toJson
      case _ => throw new Exception
    }
  }

  implicit val aesActionFmt: JsonFormat[AESAction] = taggedJsonFmt(jsonFormat[Option[String], String, String, String, AESAction](AESAction.apply, "domain", "description", "ciphertext", "iv"), tag = "aes")
  implicit val messageActionFmt: JsonFormat[MessageAction] = taggedJsonFmt(jsonFormat[Option[String], String, MessageAction](MessageAction.apply, "domain", "message"), tag = "message")
  implicit val urlActionFmt: JsonFormat[UrlAction] = taggedJsonFmt(jsonFormat[Option[String], String, String, UrlAction](UrlAction.apply, "domain", "description", "url"), tag = "url")

  implicit object LNUrlDataFmt extends JsonFormat[LNUrlData] {
    def write(unserialized: LNUrlData): JsValue = throw new RuntimeException
    def read(serialized: JsValue): LNUrlData = serialized.asJsObject fields TAG match {
      case JsString("hostedChannelRequest") => serialized.convertTo[HostedChannelRequest]
      case JsString("withdrawRequest") => serialized.convertTo[WithdrawRequest]
      case JsString("payRequest") => serialized.convertTo[PayRequest]
      case tag => throw new Exception(s"Unknown lnurl=$tag")
    }
  }

  implicit val withdrawRequestFmt: JsonFormat[WithdrawRequest] = taggedJsonFmt(jsonFormat[String, String, Long, String, Option[Long],
    WithdrawRequest](WithdrawRequest.apply, "callback", "k1", "maxWithdrawable", "defaultDescription", "minWithdrawable"), tag = "withdrawRequest")

  implicit val hostedChannelRequestFmt: JsonFormat[HostedChannelRequest] = taggedJsonFmt(jsonFormat[String, Option[String], String,
    HostedChannelRequest](HostedChannelRequest.apply, "uri", "alias", "k1"), tag = "hostedChannelRequest")

  implicit val payRequestFmt: JsonFormat[PayRequest] = taggedJsonFmt(jsonFormat[String, Long, Long, String, Option[Int],
    PayRequest](PayRequest.apply, "callback", "maxSendable", "minSendable", "metadata", "commentAllowed"), tag = "payRequest")

  implicit val payRequestFinalFmt: JsonFormat[PayRequestFinal] = jsonFormat[Option[PaymentAction], Option[Boolean], Vector[String], String,
    PayRequestFinal](PayRequestFinal.apply, "successAction", "disposable", "routes", "pr")
}
