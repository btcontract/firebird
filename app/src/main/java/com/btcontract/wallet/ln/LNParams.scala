package com.btcontract.wallet.ln

import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.Features._
import fr.acinq.bitcoin.DeterministicWallet._
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.{ActivatedFeature, CltvExpiryDelta, FeatureSupport, Features}
import fr.acinq.bitcoin.{Block, ByteVector32, DeterministicWallet, Protocol, Satoshi}
import fr.acinq.eclair.router.Router.{Data, PublicChannel, RouterConf}
import com.btcontract.wallet.ln.CommitmentSpec.LNDirectionalMessage
import com.btcontract.wallet.ln.SyncMaster.ShortChanIdSet
import com.btcontract.wallet.ln.crypto.Noise.KeyPair
import com.btcontract.wallet.ln.crypto.Tools.Bytes
import com.btcontract.wallet.ln.crypto.Tools
import scala.collection.immutable.SortedMap
import java.io.ByteArrayInputStream
import fr.acinq.eclair.crypto.Mac32
import scodec.bits.ByteVector
import java.nio.ByteOrder


object LNParams {
  val blocksPerDay: Int = 144
  val cltvExpiry: Int = blocksPerDay * 2 - 3
  val chainHash: ByteVector32 = Block.LivenetGenesisBlock.hash
  val minHostedOnChainRefund = Satoshi(1000000L)
  val minPayment = MilliSatoshi(10000L)
  val minHostedLiabilityBlockdays = 365
  val maxHostedBlockHeight = 500000L

  lazy val routerConf =
    RouterConf(searchAttempts = 100, nodeFailTolerance = 10, requestNodeAnnouncements = false, encodingType = EncodingType.UNCOMPRESSED,
      channelRangeChunkSize = 200, channelQueryChunkSize = 100, searchMaxFeeBase = 21.sat, searchMaxFeePct = 0.01, searchMaxCltv = CltvExpiryDelta(1008),
      firstPassMaxRouteLength = 6, searchRatioCltv = 0.1, searchRatioChannelAge = 0.4, searchRatioChannelCapacity = 0.2, searchRatioSuccessScore = 0.3,
      mppMinPartAmount = MilliSatoshi(50000000), maxRoutesPerPart = 12)

  private[this] val localFeatures = Set(
    ActivatedFeature(OptionDataLossProtect, FeatureSupport.Optional),
    ActivatedFeature(ChannelRangeQueriesExtended, FeatureSupport.Mandatory),
    ActivatedFeature(BasicMultiPartPayment, FeatureSupport.Mandatory),
    ActivatedFeature(ChannelRangeQueries, FeatureSupport.Mandatory),
    ActivatedFeature(VariableLengthOnion, FeatureSupport.Mandatory),
    ActivatedFeature(PaymentSecret, FeatureSupport.Mandatory)
  )

  var keys: LightningNodeKeys = _

  // Approximates how much a given number of payments and hops can take in off-chain fees
  def maxAcceptableFee(msat: MilliSatoshi, shards: Int, hops: Int, conf: RouterConf): MilliSatoshi =
    conf.searchMaxFeeBase * (hops + shards + 1) + msat * conf.searchMaxFeePct

  def makeLocalInitMessage: Init = {
    val networks = InitTlv.Networks(chainHash :: Nil)
    Init(Features(localFeatures), TlvStream apply networks)
  }
}


class LightningNodeKeys(seed: Bytes) {
  private lazy val master: ExtendedPrivateKey = generate(ByteVector view seed)
  lazy val extendedNodeKey: ExtendedPrivateKey = derivePrivateKey(master, hardened(46L) :: hardened(0L) :: Nil)
  lazy val hashingKey: PrivateKey = derivePrivateKey(master, hardened(138L) :: 0L :: Nil).privateKey
  lazy val ourRoutingSourceNodeId: PublicKey = extendedNodeKey.publicKey

  // Compatible with Electrum/Phoenix/BLW
  def buildAddress(num: Long, chainHash: ByteVector32): String = {
    val derivationPath = DeterministicWallet KeyPath s"m/84'/0'/0'/0/$num"
    val priv = DeterministicWallet.derivePrivateKey(master, derivationPath)
    fr.acinq.bitcoin.computeBIP84Address(priv.publicKey, chainHash)
  }

  def buildXPub: (String, String) = {
    val derivationPath = DeterministicWallet KeyPath "m/84'/0'/0'"
    val pub = DeterministicWallet publicKey DeterministicWallet.derivePrivateKey(master, derivationPath)
    (DeterministicWallet.encode(pub, DeterministicWallet.zpub), derivationPath.toString)
  }

  // User for separate key per domain
  def makeLinkingKey(domain: String): PrivateKey = {
    val domainBytes = ByteVector.view(domain getBytes "UTF-8")
    val pathMaterial = Mac32.hmac256(hashingKey.value, domainBytes)
    val chain = hardened(138) +: makeKeyPath(pathMaterial)
    derivePrivateKey(master, chain).privateKey
  }

  // Our node separate key per peer
  def makeLightningKey(theirNodeId: PublicKey): PrivateKey = {
    val chain = hardened(230) +: makeKeyPath(theirNodeId.value)
    derivePrivateKey(master, chain).privateKey
  }

  // Used for fake NodeId in invoices
  def makeFakeKey(paymentHash: ByteVector): PrivateKey = {
    val chain = hardened(184) +: makeKeyPath(paymentHash)
    derivePrivateKey(master, chain).privateKey
  }

  def makeKeyPath(material: ByteVector): Vector[Long] = {
    require(material.size > 15, "Material size must be at least 16")
    val stream = new ByteArrayInputStream(material.slice(0, 16).toArray)
    def getChunk = Protocol.uint32(stream, ByteOrder.BIG_ENDIAN)
    Vector.fill(4)(getChunk)
  }
}


object ChanErrorCodes {
  final val ERR_HOSTED_WRONG_BLOCKDAY = ByteVector.fromValidHex("0001")
  final val ERR_HOSTED_WRONG_LOCAL_SIG = ByteVector.fromValidHex("0002")
  final val ERR_HOSTED_WRONG_REMOTE_SIG = ByteVector.fromValidHex("0003")
  final val ERR_HOSTED_TOO_MANY_STATE_UPDATES = ByteVector.fromValidHex("0005")
  final val ERR_HOSTED_TIMED_OUT_OUTGOING_HTLC = ByteVector.fromValidHex("0006")
  final val ERR_HOSTED_IN_FLIGHT_HTLC_WHILE_RESTORING = ByteVector.fromValidHex("0007")
  final val ERR_HOSTED_CHANNEL_DENIED = ByteVector.fromValidHex("0008")

  val ERR_LOCAL_AMOUNT_HIGH = 1
  val ERR_REMOTE_AMOUNT_HIGH = 2
  val ERR_REMOTE_AMOUNT_LOW = 3
  val ERR_TOO_MANY_HTLC = 4
  val ERR_NOT_OPEN = 5
}


case class NodeAnnouncementExt(na: NodeAnnouncement) {
  lazy val prettyNodeName: String = na.addresses collectFirst {
    case _: IPv4 | _: IPv6 => na.nodeId.toString take 15 grouped 3 mkString "\u0020"
    case _: Tor2 => s"<strong>Tor</strong>\u0020${na.nodeId.toString take 12 grouped 3 mkString "\u0020"}"
    case _: Tor3 => s"<strong>Tor</strong>\u0020${na.nodeId.toString take 12 grouped 3 mkString "\u0020"}"
  } getOrElse "No IP address"

  lazy val nodeSpecificPrivKey: PrivateKey =
    LNParams.keys.makeLightningKey(na.nodeId)

  lazy val nodeSpecificPubKey: PublicKey =
    nodeSpecificPrivKey.publicKey

  lazy val nodeSpecificHostedChanId: ByteVector32 =
    Tools.hostedChanId(nodeSpecificPubKey.value, na.nodeId.value)

  lazy val nodeSpecificPkap: PublicKeyAndPair = {
    val pair = KeyPair(nodeSpecificPubKey.value, nodeSpecificPrivKey.value)
    PublicKeyAndPair(na.nodeId, pair)
  }
}

case class LightningMessageExt(msg: LightningMessage) {
  def asRemote: LNDirectionalMessage = msg -> false
  def asLocal: LNDirectionalMessage = msg -> true
}


trait NetworkDataStore {
  def removeChannel(sid: ShortChannelId): Unit
  def removeStaleChannels(data: Data, chainTip: Long): Unit
  def addChannelAnnouncement(ca: ChannelAnnouncement): Unit
  def listChannelAnnouncements: Iterable[ChannelAnnouncement]

  def incrementScore(cu: ChannelUpdate): Unit
  def addChannelUpdate(cu: ChannelUpdate): Unit
  def listChannelUpdates: Iterable[ChannelUpdate]
  def getCurrentRoutingMap: SortedMap[ShortChannelId, PublicChannel]

  def addExcludedChannel(sid: ShortChannelId, until: Long): Unit
  def listExcludedChannels(until: Long): ShortChanIdSet
  def processCatchup(data: CatchupSyncData): Unit
}


trait ChainLink {
  var listeners = Set.empty[ChainLinkListener]
  def chainTipCanBeTrusted: Boolean
  def currentChainTip: Int
}

trait ChainLinkListener {
  def onChainTipKnown: Unit
  def onTotalDisconnect: Unit
}


trait ChannelBag {
  def all: Vector[HostedCommits]
  def put(chanId: ByteVector32, data: HostedCommits): HostedCommits
}