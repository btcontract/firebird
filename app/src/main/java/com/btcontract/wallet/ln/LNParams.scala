package com.btcontract.wallet.ln

import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.Features._
import fr.acinq.bitcoin.DeterministicWallet._
import com.btcontract.wallet.ln.crypto.Tools._
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.router.Router.{PublicChannel, RouterConf}
import fr.acinq.eclair.{ActivatedFeature, CltvExpiryDelta, FeatureSupport, Features}
import fr.acinq.bitcoin.{Block, ByteVector32, Protocol, Satoshi, Script}
import com.btcontract.wallet.ln.SyncMaster.ShortChanIdSet
import com.btcontract.wallet.ln.crypto.Noise.KeyPair
import fr.acinq.eclair.router.ChannelUpdateExt
import fr.acinq.eclair.payment.PaymentRequest
import java.io.ByteArrayInputStream
import fr.acinq.eclair.crypto.Mac32
import scodec.bits.ByteVector
import java.nio.ByteOrder


object LNParams {
  val blocksPerDay: Int = 144 // On average we can expect this many blocks per day
  val cltvRejectThreshold: Int = 144 // Reject incoming payment if CLTV expiry is closer than this to current chain tip when HTLC arrives
  val incomingPaymentCltvExpiry: Int = 144 + 72 // Ask payer to set final CLTV expiry to payer's current chain tip + this many blocks
  val chainHash: ByteVector32 = Block.LivenetGenesisBlock.hash
  val minHostedOnChainRefund = Satoshi(1000000L)
  val minHostedLiabilityBlockdays = 365
  val minPayment = MilliSatoshi(5000L)

  var routerConf =
    RouterConf(searchMaxFeeBase = MilliSatoshi(25000L),
      searchMaxFeePct = 0.01, firstPassMaxCltv = CltvExpiryDelta(1008),
      firstPassMaxRouteLength = 6, mppMinPartAmount = MilliSatoshi(30000000L),
      maxRemoteAttempts = 12, maxChannelFailures = 12, maxStrangeNodeFailures = 12)

  var format: StorageFormat = _
  var channelMaster: ChannelMaster = _

  lazy val (syncInit, phcSyncInit, hcInit) = {
    val networks: InitTlv = InitTlv.Networks(chainHash :: Nil)
    val tlvStream: TlvStream[InitTlv] = TlvStream(networks)

    // Mimic phoenix
    val syncFeatures: Set[ActivatedFeature] = Set (
      ActivatedFeature(OptionDataLossProtect, FeatureSupport.Mandatory),
      ActivatedFeature(BasicMultiPartPayment, FeatureSupport.Optional),
      ActivatedFeature(VariableLengthOnion, FeatureSupport.Optional),
      ActivatedFeature(PaymentSecret, FeatureSupport.Optional),
      ActivatedFeature(Wumbo, FeatureSupport.Optional)
    )

    val phcSyncFeatures: Set[ActivatedFeature] = Set (
      ActivatedFeature(HostedChannels, FeatureSupport.Mandatory)
    )

    val hcFeatures: Set[ActivatedFeature] = Set (
      ActivatedFeature(ChannelRangeQueriesExtended, FeatureSupport.Mandatory),
      ActivatedFeature(BasicMultiPartPayment, FeatureSupport.Mandatory),
      ActivatedFeature(ChannelRangeQueries, FeatureSupport.Mandatory),
      ActivatedFeature(VariableLengthOnion, FeatureSupport.Mandatory),
      ActivatedFeature(HostedChannels, FeatureSupport.Mandatory),
      ActivatedFeature(ChainSwap, FeatureSupport.Optional)
    )

    val sync = Init(Features(syncFeatures), tlvStream)
    val phcSync = Init(Features(phcSyncFeatures), tlvStream)
    val hc = Init(Features(hcFeatures), tlvStream)
    (sync, phcSync, hc)
  }
}

object LightningNodeKeys {
  def makeFromSeed(seed: Bytes): LightningNodeKeys = {
    val master: ExtendedPrivateKey = generate(ByteVector view seed)
    val extendedNodeKey: ExtendedPrivateKey = derivePrivateKey(master, hardened(46L) :: hardened(0L) :: Nil)
    val hashingKey: PrivateKey = derivePrivateKey(master, hardened(138L) :: 0L :: Nil).privateKey
    LightningNodeKeys(extendedNodeKey, xPub(master), hashingKey)
  }

  // Compatible with Electrum/Phoenix/BLW
  def xPub(parent: ExtendedPrivateKey): String = {
    val derivationPath: KeyPath = KeyPath("m/84'/0'/0'")
    val privateKey = derivePrivateKey(parent, derivationPath)
    encode(publicKey(privateKey), zpub)
  }
}

case class LightningNodeKeys(extendedNodeKey: ExtendedPrivateKey, xpub: String, hashingKey: PrivateKey) {
  lazy val ourNodePrivateKey: PrivateKey = extendedNodeKey.privateKey
  lazy val ourNodePubKey: PublicKey = extendedNodeKey.publicKey

  // Used for separate key per domain
  def makeLinkingKey(domain: String): PrivateKey = {
    val domainBytes = ByteVector.view(domain getBytes "UTF-8")
    val pathMaterial = Mac32.hmac256(hashingKey.value, domainBytes)
    val chain = hardened(138) :: makeKeyPath(pathMaterial)
    derivePrivateKey(extendedNodeKey, chain).privateKey
  }

  def fakeInvoiceKey(paymentHash: ByteVector): PrivateKey = {
    val chain = hardened(184) :: makeKeyPath(paymentHash)
    derivePrivateKey(extendedNodeKey, chain).privateKey
  }

  def ourFakeNodeIdKey(theirNodeId: PublicKey): PrivateKey = {
    val chain = hardened(230) :: makeKeyPath(theirNodeId.value)
    derivePrivateKey(extendedNodeKey, chain).privateKey
  }

  def refundPubKey(theirNodeId: PublicKey): ByteVector = {
    val derivationChain = hardened(276) :: makeKeyPath(theirNodeId.value)
    val p2wpkh = Script.pay2wpkh(derivePrivateKey(extendedNodeKey, derivationChain).publicKey)
    Script.write(p2wpkh)
  }

  def makeKeyPath(material: ByteVector): List[Long] = {
    require(material.size > 15, "Material size must be at least 16")
    val stream = new ByteArrayInputStream(material.slice(0, 16).toArray)
    def getChunk = Protocol.uint32(stream, ByteOrder.BIG_ENDIAN)
    List.fill(4)(getChunk)
  }
}

sealed trait StorageFormat {
  def attachedChannelSecret(theirNodeId: PublicKey): ByteVector32
  def outstandingProviders: Set[NodeAnnouncement]
  def keys: LightningNodeKeys
}

case class MnemonicStorageFormat(outstandingProviders: Set[NodeAnnouncement], keys: LightningNodeKeys, seed: ByteVector) extends StorageFormat {
  override def attachedChannelSecret(theirNodeId: PublicKey): ByteVector32 = Mac32.hmac256(keys.ourNodePubKey.value, theirNodeId.value)
}

case class MnemonicExtStorageFormat(outstandingProviders: Set[NodeAnnouncement], keys: LightningNodeKeys, seed: Option[ByteVector] = None) extends StorageFormat {
  override def attachedChannelSecret(theirNodeId: PublicKey): ByteVector32 = Mac32.hmac256(keys.ourFakeNodeIdKey(theirNodeId).value, theirNodeId.value)
}

case class PasswordStorageFormat(outstandingProviders: Set[NodeAnnouncement], keys: LightningNodeKeys, user: String, password: Option[String] = None) extends StorageFormat {
  override def attachedChannelSecret(theirNodeId: PublicKey): ByteVector32 = Mac32.hmac256(user.getBytes("UTF-8"), theirNodeId.value)
}

object ChanErrorCodes {
  final val ERR_HOSTED_WRONG_BLOCKDAY = "0001"
  final val ERR_HOSTED_WRONG_LOCAL_SIG = "0002"
  final val ERR_HOSTED_WRONG_REMOTE_SIG = "0003"
  final val ERR_HOSTED_CLOSED_BY_REMOTE_PEER = "0004"
  final val ERR_HOSTED_TIMED_OUT_OUTGOING_HTLC = "0005"
  final val ERR_HOSTED_HTLC_EXTERNAL_FULFILL = "0006"
  final val ERR_HOSTED_CHANNEL_DENIED = "0007"
  final val ERR_HOSTED_MANUAL_SUSPEND = "0008"
  final val ERR_HOSTED_INVALID_RESIZE = "0009"
  final val ERR_MISSING_CHANNEL = "0010"

  val ERR_NOT_ENOUGH_BALANCE = 1
  val ERR_TOO_MUCH_IN_FLIGHT = 2
  val ERR_AMOUNT_TOO_SMALL = 3
  val ERR_TOO_MANY_HTLC = 4
  val ERR_NOT_OPEN = 5
}

// Extension wrappers

case class NodeAnnouncementExt(na: NodeAnnouncement) {
  lazy val prettyNodeName: String = na.addresses collectFirst {
    case _: IPv4 | _: IPv6 => na.nodeId.toString take 15 grouped 3 mkString "\u0020"
    case _: Tor2 => s"<strong>Tor</strong>\u0020${na.nodeId.toString take 12 grouped 3 mkString "\u0020"}"
    case _: Tor3 => s"<strong>Tor</strong>\u0020${na.nodeId.toString take 12 grouped 3 mkString "\u0020"}"
  } getOrElse "No IP address"

  // Important: this relies on format being defined at runtime
  // we do not provide format as class field here to avoid storing of duplicated data
  lazy val nodeSpecificPrivKey: PrivateKey = LNParams.format.keys.ourFakeNodeIdKey(na.nodeId)
  lazy val nodeSpecificPubKey: PublicKey = nodeSpecificPrivKey.publicKey

  lazy val nodeSpecificPkap: PublicKeyAndPair = PublicKeyAndPair(keyPair = KeyPair(nodeSpecificPubKey.value, nodeSpecificPrivKey.value), them = na.nodeId)
  lazy val nodeSpecificHostedChanId: ByteVector32 = hostedChanId(pubkey1 = nodeSpecificPubKey.value, pubkey2 = na.nodeId.value)
}

case class PaymentRequestExt(pr: PaymentRequest, raw: String) {
  def paymentHashStr: String = pr.paymentHash.toHex
}

case class SwapInStateExt(state: SwapInState, nodeId: PublicKey)

// Interfaces

trait NetworkDataStore {
  def addChannelAnnouncement(ca: ChannelAnnouncement): Unit
  def listChannelAnnouncements: Iterable[ChannelAnnouncement]

  def addChannelUpdateByPosition(cu: ChannelUpdate): Unit
  def listChannelUpdates: Iterable[ChannelUpdateExt]

  // We disregard position and always exclude channel as a whole
  def addExcludedChannel(shortId: ShortChannelId, untilStamp: Long): Unit
  def listChannelsWithOneUpdate: ShortChanIdSet
  def listExcludedChannels: Set[Long]

  def incrementChannelScore(cu: ChannelUpdate): Unit
  def removeChannelUpdate(shortId: ShortChannelId): Unit
  def removeGhostChannels(ghostIds: ShortChanIdSet, oneSideIds: ShortChanIdSet): Unit
  def getRoutingData: Map[ShortChannelId, PublicChannel]

  def processCompleteHostedData(pure: CompleteHostedRoutingData): Unit
  def processPureData(data: PureRoutingData): Unit
}

trait PaymentDBUpdater {
  def replaceOutgoingPayment(nodeId: PublicKey, prex: PaymentRequestExt, description: PaymentDescription, action: Option[PaymentAction], finalAmount: MilliSatoshi, balanceSnap: MilliSatoshi, fiatRateSnap: Fiat2Btc): Unit
  def replaceIncomingPayment(prex: PaymentRequestExt, preimage: ByteVector32, description: PaymentDescription, balanceSnap: MilliSatoshi, fiatRateSnap: Fiat2Btc): Unit
  // These MUST be the only two methods capable of updating payment state to SUCCEEDED
  def updOkOutgoing(upd: UpdateFulfillHtlc, fee: MilliSatoshi): Unit
  def updStatusIncoming(add: UpdateAddHtlc, status: String): Unit
}

trait ChainLink {
  var listeners: Set[ChainLinkListener] = Set.empty

  def addAndMaybeInform(listener: ChainLinkListener): Unit = {
    if (chainTipCanBeTrusted) listener.onTrustedChainTipKnown
    listeners += listener
  }

  def chainTipCanBeTrusted: Boolean
  def currentChainTip: Int
  def start: Unit
  def stop: Unit
}

trait ChainLinkListener {
  def onTrustedChainTipKnown: Unit = none
  def onCompleteChainDisconnect: Unit = none
  val isTransferrable: Boolean = false
}

trait ChannelBag {
  def all: List[HostedCommits]
  def delete(chanId: ByteVector32): Unit
  def put(chanId: ByteVector32, data: HostedCommits): HostedCommits
}