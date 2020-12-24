package com.btcontract.wallet.ln.utils

import spray.json._
import fr.acinq.eclair.wire._
import com.btcontract.wallet.ln._
import fr.acinq.eclair.wire.LightningMessageCodecs._

import scodec.bits.{BitVector, ByteVector}
import fr.acinq.eclair.{MilliSatoshi, wire}
import fr.acinq.bitcoin.DeterministicWallet.{ExtendedPrivateKey, KeyPath}
import fr.acinq.eclair.wire.CommonCodecs.{bytes32, privateKey, varsizebinarydata}
import com.btcontract.wallet.ln.utils.FiatRates.{BitpayItemList, CoinGeckoItemMap, Rates}
import fr.acinq.eclair.wire.HostedMessagesCodecs.{hostedChannelBrandingCodec, lastCrossSignedStateCodec, resizeChannelCodec}
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.ByteVector32
import scodec.Codec


object ImplicitJsonFormats extends DefaultJsonProtocol { me =>
  def to[T : JsonFormat](raw: String): T = raw.parseJson.convertTo[T]
  val json2String: JsValue => String = (_: JsValue).convertTo[String]
  val TAG = "tag"

  def json2BitVec(json: JsValue): Option[BitVector] = BitVector fromHex json2String(json)
  def writeExt[T](ext: (String, JsValue), base: JsValue) = JsObject(base.asJsObject.fields + ext)

  def taggedJsonFmt[T](base: JsonFormat[T], tag: String): JsonFormat[T] = new JsonFormat[T] {
    def write(unserialized: T): JsValue = writeExt(TAG -> JsString(tag), base write unserialized)
    def read(serialized: JsValue): T = base read serialized
  }

  def sCodecJsonFmt[T](codec: Codec[T] /* Json <-> sCodec bridge */): JsonFormat[T] = new JsonFormat[T] {
    def read(serialized: JsValue): T = codec.decode(json2BitVec(serialized).get).require.value
    def write(unserialized: T): JsValue = codec.encode(unserialized).require.toHex.toJson
  }

  // Channel

  implicit val errorFmt: JsonFormat[wire.Error] = sCodecJsonFmt(errorCodec)
  implicit val channelUpdateFmt: JsonFormat[ChannelUpdate] = sCodecJsonFmt(channelUpdateCodec)
  implicit val updateAddHtlcFmt: JsonFormat[UpdateAddHtlc] = sCodecJsonFmt(updateAddHtlcCodec)
  implicit val updateFailHtlcFmt: JsonFormat[UpdateFailHtlc] = sCodecJsonFmt(updateFailHtlcCodec)
  implicit val nodeAnnouncementFmt: JsonFormat[NodeAnnouncement] = sCodecJsonFmt(nodeAnnouncementCodec)
  implicit val lightningMessageFmt: JsonFormat[LightningMessage] = sCodecJsonFmt(lightningMessageCodec)
  implicit val updateFailMalformedHtlcFmt: JsonFormat[UpdateFailMalformedHtlc] = sCodecJsonFmt(updateFailMalformedHtlcCodec)

  implicit val resizeChannelFmt: JsonFormat[ResizeChannel] = sCodecJsonFmt(resizeChannelCodec)
  implicit val lastCrossSignedStateFmt: JsonFormat[LastCrossSignedState] = sCodecJsonFmt(lastCrossSignedStateCodec)
  implicit val hostedChannelBrandingFmt: JsonFormat[HostedChannelBranding] = sCodecJsonFmt(hostedChannelBrandingCodec)

  implicit val bytesFmt: JsonFormat[ByteVector] = sCodecJsonFmt(varsizebinarydata)
  implicit val privateKeyFmt: JsonFormat[PrivateKey] = sCodecJsonFmt(privateKey)
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
    jsonFormat[NodeAnnouncementExt, LastCrossSignedState, Vector[LightningMessage], Vector[LightningMessage],
      CommitmentSpec, Option[ChannelUpdate], Option[wire.Error], Option[wire.Error], Option[ResizeChannel], Long,
      HostedCommits](HostedCommits.apply, "announce", "lastCrossSignedState", "nextLocalUpdates", "nextRemoteUpdates",
      "localSpec", "updateOpt", "localError", "remoteError", "resizeProposal", "startedAt")

  // Fiat feerates

  implicit val ratesInfoFmt: JsonFormat[RatesInfo] = jsonFormat[Rates, Rates, Long, RatesInfo](RatesInfo.apply, "rates", "oldRates", "stamp")
  implicit val BlockchainInfoItemFmt: JsonFormat[BlockchainInfoItem] = jsonFormat[Double, BlockchainInfoItem](BlockchainInfoItem.apply, "last")
  implicit val bitpayItemFmt: JsonFormat[BitpayItem] = jsonFormat[String, Double, BitpayItem](BitpayItem.apply, "code", "rate")
  implicit val coinGeckoItemFmt: JsonFormat[CoinGeckoItem] = jsonFormat[Double, CoinGeckoItem](CoinGeckoItem.apply, "value")
  implicit val coinGeckoFmt: JsonFormat[CoinGecko] = jsonFormat[CoinGeckoItemMap, CoinGecko](CoinGecko.apply, "rates")
  implicit val bitpayFmt: JsonFormat[Bitpay] = jsonFormat[BitpayItemList, Bitpay](Bitpay.apply, "data")

  // Wallet keys

  implicit val keyPathFmt: JsonFormat[KeyPath] = jsonFormat[Seq[Long], KeyPath](KeyPath.apply, "path")
  implicit val extendedPrivateKeyFmt: JsonFormat[ExtendedPrivateKey] = jsonFormat[ByteVector32, ByteVector32, Int, KeyPath, Long, ExtendedPrivateKey](ExtendedPrivateKey.apply, "secretkeybytes", "chaincode", "depth", "path", "parent")
  implicit val lightningNodeKeysFmt: JsonFormat[LightningNodeKeys] = jsonFormat[ExtendedPrivateKey, (String, String), PrivateKey, LightningNodeKeys](LightningNodeKeys.apply, "extendedNodeKey", "xpub", "hashingKey")

  implicit object StorageFormatFmt extends JsonFormat[StorageFormat] {
    def write(internal: StorageFormat): JsValue = internal match {
      case mnemonicFormat: MnemonicStorageFormat => mnemonicFormat.toJson
      case passwordFormat: PasswordStorageFormat => passwordFormat.toJson
      case _ => throw new Exception
    }

    def read(raw: JsValue): StorageFormat = raw.asJsObject fields TAG match {
      case JsString("MnemonicStorageFormat") => raw.convertTo[MnemonicStorageFormat]
      case JsString("PasswordStorageFormat") => raw.convertTo[PasswordStorageFormat]
      case tag => throw new Exception(s"Unknown wallet key format=$tag")
    }
  }

  implicit val mnemonicStorageFormatFmt: JsonFormat[MnemonicStorageFormat] = taggedJsonFmt(jsonFormat[Set[NodeAnnouncement], LightningNodeKeys, Option[ByteVector],
    MnemonicStorageFormat](MnemonicStorageFormat.apply, "outstandingProviders", "keys", "seed"), tag = "MnemonicStorageFormat")

  implicit val passwordStorageFormatFmt: JsonFormat[PasswordStorageFormat] = taggedJsonFmt(jsonFormat[Set[NodeAnnouncement], LightningNodeKeys, String, Option[String],
    PasswordStorageFormat](PasswordStorageFormat.apply, "outstandingProviders", "keys", "user", "password"), tag = "PasswordStorageFormat")

  // Payment description

  implicit object PaymentDescriptionFmt extends JsonFormat[PaymentDescription] {
    def write(internal: PaymentDescription): JsValue = internal match {
      case paymentDescription: PlainMetaDescription => paymentDescription.toJson
      case paymentDescription: PlainDescription => paymentDescription.toJson
      case _ => throw new Exception
    }

    def read(raw: JsValue): PaymentDescription = raw.asJsObject fields TAG match {
      case JsString("PlainMetaDescription") => raw.convertTo[PlainMetaDescription]
      case JsString("PlainDescription") => raw.convertTo[PlainDescription]
      case tag => throw new Exception(s"Unknown action=$tag")
    }
  }

  implicit val plainDescriptionFmt: JsonFormat[PlainDescription] = taggedJsonFmt(jsonFormat[String, PlainDescription](PlainDescription.apply, "invoiceText"), tag = "PlainDescription")
  implicit val plainMetainfoDescriptionFmt: JsonFormat[PlainMetaDescription] = taggedJsonFmt(jsonFormat[String, String, PlainMetaDescription](PlainMetaDescription.apply, "invoiceText", "meta"), tag = "PlainMetaDescription")

  // Payment action

  implicit object PaymentActionFmt extends JsonFormat[PaymentAction] {
    def write(internal: PaymentAction): JsValue = internal match {
      case paymentAction: MessageAction => paymentAction.toJson
      case paymentAction: UrlAction => paymentAction.toJson
      case paymentAction: AESAction => paymentAction.toJson
      case _ => throw new Exception
    }

    def read(raw: JsValue): PaymentAction = raw.asJsObject fields TAG match {
      case JsString("message") => raw.convertTo[MessageAction]
      case JsString("aes") => raw.convertTo[AESAction]
      case JsString("url") => raw.convertTo[UrlAction]
      case tag => throw new Exception(s"Unknown action=$tag")
    }
  }

  implicit val aesActionFmt: JsonFormat[AESAction] = taggedJsonFmt(jsonFormat[Option[String], String, String, String, AESAction](AESAction.apply, "domain", "description", "ciphertext", "iv"), tag = "aes")
  implicit val messageActionFmt: JsonFormat[MessageAction] = taggedJsonFmt(jsonFormat[Option[String], String, MessageAction](MessageAction.apply, "domain", "message"), tag = "message")
  implicit val urlActionFmt: JsonFormat[UrlAction] = taggedJsonFmt(jsonFormat[Option[String], String, String, UrlAction](UrlAction.apply, "domain", "description", "url"), tag = "url")

  // LNURL

  implicit object LNUrlDataFmt extends JsonFormat[LNUrlData] {
    def write(internal: LNUrlData): JsValue = throw new RuntimeException

    def read(raw: JsValue): LNUrlData = raw.asJsObject fields TAG match {
      case JsString("withdrawRequest") => raw.convertTo[WithdrawRequest]
      case JsString("payRequest") => raw.convertTo[PayRequest]
      case tag => throw new Exception(s"Unknown lnurl=$tag")
    }
  }

  // Note: tag on these MUST start with lower case because it is defined that way on protocol level
  implicit val withdrawRequestFmt: JsonFormat[WithdrawRequest] = taggedJsonFmt(jsonFormat[String, String, Long, String, Option[Long],
    WithdrawRequest](WithdrawRequest.apply, "callback", "k1", "maxWithdrawable", "defaultDescription", "minWithdrawable"), tag = "withdrawRequest")

  implicit val payRequestFmt: JsonFormat[PayRequest] = taggedJsonFmt(jsonFormat[String, Long, Long, String, Option[Int],
    PayRequest](PayRequest.apply, "callback", "maxSendable", "minSendable", "metadata", "commentAllowed"), tag = "payRequest")

  implicit val payRequestFinalFmt: JsonFormat[PayRequestFinal] = jsonFormat[Option[PaymentAction], Vector[String], String,
    PayRequestFinal](PayRequestFinal.apply, "successAction", "routes", "pr")
}
