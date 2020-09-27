package com.btcontract.wallet.lnutils

import spray.json._
import com.btcontract.wallet.ln.{CommitmentSpec, FailAndAdd, HostedCommits, Htlc, LightningNodeKeys, MalformAndAdd, MnemonicStorageFormat, NodeAnnouncementExt, PasswordStorageFormat, PaymentAction, StorageFormat}
import fr.acinq.eclair.wire.LightningMessageCodecs.{channelUpdateCodec, errorCodec, lightningMessageCodec, nodeAnnouncementCodec, updateAddHtlcCodec, updateFailHtlcCodec, updateFailMalformedHtlcCodec}
import fr.acinq.eclair.wire.{ChannelUpdate, HostedChannelBranding, LastCrossSignedState, LightningMessage, NodeAnnouncement, UpdateAddHtlc, UpdateFailHtlc, UpdateFailMalformedHtlc}
import com.btcontract.wallet.{Bitpay, BitpayItem, BlockchainInfoItem, CoinGecko, CoinGeckoItem, RatesInfo}
import fr.acinq.eclair.wire.HostedMessagesCodecs.{hostedChannelBrandingCodec, lastCrossSignedStateCodec}
import com.btcontract.wallet.FiatRates.{BitpayItemList, CoinGeckoItemMap, Rates}
import fr.acinq.bitcoin.DeterministicWallet.{ExtendedPrivateKey, KeyPath}
import fr.acinq.eclair.wire.CommonCodecs.{bytes32, privateKey}
import fr.acinq.eclair.{MilliSatoshi, wire}

import com.btcontract.wallet.ln.CommitmentSpec.LNDirectionalMessage
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.ByteVector32
import scodec.bits.BitVector
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
    jsonFormat[NodeAnnouncementExt, LastCrossSignedState, Vector[LNDirectionalMessage],
      CommitmentSpec, Option[ChannelUpdate], Option[HostedChannelBranding], Option[wire.Error], Option[wire.Error], Long,
      HostedCommits](HostedCommits.apply, "announce", "lastCrossSignedState", "futureUpdates", "localSpec", "updateOpt",
      "brandingOpt", "localError", "remoteError", "startedAt")

  // Fiat feerates

  implicit val ratesInfoFmt: JsonFormat[RatesInfo] = jsonFormat[Rates, Rates, Long, RatesInfo](RatesInfo.apply, "rates", "oldRates", "stamp")
  implicit val BlockchainInfoItemFmt: JsonFormat[BlockchainInfoItem] = jsonFormat[Double, BlockchainInfoItem](BlockchainInfoItem.apply, "last")
  implicit val bitpayItemFmt: JsonFormat[BitpayItem] = jsonFormat[String, Double, BitpayItem](BitpayItem.apply, "code", "rate")
  implicit val coinGeckoItemFmt: JsonFormat[CoinGeckoItem] = jsonFormat[Double, CoinGeckoItem](CoinGeckoItem.apply, "value")
  implicit val coinGeckoFmt: JsonFormat[CoinGecko] = jsonFormat[CoinGeckoItemMap, CoinGecko](CoinGecko.apply, "rates")
  implicit val bitpayFmt: JsonFormat[Bitpay] = jsonFormat[BitpayItemList, Bitpay](Bitpay.apply, "data")

  // LNURL and payment actions

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

  implicit val payRequestFinalFmt: JsonFormat[PayRequestFinal] = jsonFormat[Option[PaymentAction], Option[Boolean], Vector[String], String,
    PayRequestFinal](PayRequestFinal.apply, "successAction", "disposable", "routes", "pr")

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

  implicit val mnemonicStorageFormatFmt: JsonFormat[MnemonicStorageFormat] = taggedJsonFmt(jsonFormat[Set[NodeAnnouncement], LightningNodeKeys,
    MnemonicStorageFormat](MnemonicStorageFormat.apply, "outstandingProviders", "keys"), tag = "MnemonicStorageFormat")

  implicit val passwordStorageFormatFmt: JsonFormat[PasswordStorageFormat] = taggedJsonFmt(jsonFormat[Set[NodeAnnouncement], LightningNodeKeys, String, Option[String],
    PasswordStorageFormat](PasswordStorageFormat.apply, "outstandingProviders", "keys", "user", "password"), tag = "PasswordStorageFormat")

  // Payment summary cache

  implicit val totalStatSummaryFmt: JsonFormat[TotalStatSummary] = jsonFormat[MilliSatoshi, MilliSatoshi, MilliSatoshi, Long, TotalStatSummary](TotalStatSummary.apply, "fees", "received", "sent", "count")
  implicit val totalStatSummaryExtFmt: JsonFormat[TotalStatSummaryExt] = jsonFormat[Option[TotalStatSummary], Long, Long, TotalStatSummaryExt](TotalStatSummaryExt.apply, "summary", "from", "to")

  // Addons

  implicit object AddonFmt extends JsonFormat[Addon] {
    def write(internal: Addon): JsValue = internal match {
      case exampleAddon: ExampleAddon => exampleAddon.toJson
      case _ => throw new Exception
    }

    def read(raw: JsValue): Addon = raw.asJsObject fields TAG match {
      case JsString("ExampleAddon") => raw.convertTo[ExampleAddon]
      case tag => throw new Exception(s"Unknown addon=$tag")
    }
  }

  implicit val exampleAddonFmt: JsonFormat[ExampleAddon] = taggedJsonFmt(jsonFormat[Option[String], String, String, ExampleAddon](ExampleAddon.apply, "authToken", "domain", "keyName"), tag = "ExampleAddon")
  implicit val usedAddonsFmt: JsonFormat[UsedAddons] = jsonFormat[List[Addon], UsedAddons](UsedAddons.apply, "addons")
}
