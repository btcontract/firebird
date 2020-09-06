package com.btcontract.wallet.ln

import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.Features._
import fr.acinq.bitcoin.DeterministicWallet._
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.{ActivatedFeature, CltvExpiryDelta, FeatureSupport, Features}
import fr.acinq.bitcoin.{Block, ByteVector32, DeterministicWallet, Protocol, Satoshi}
import com.btcontract.wallet.ln.CommitmentSpec.LNDirectionalMessage
import com.btcontract.wallet.ln.SyncMaster.ShortIdToPublicChanMap
import fr.acinq.eclair.wire.ChannelUpdate.PositionalId
import com.btcontract.wallet.ln.crypto.Noise.KeyPair
import com.btcontract.wallet.ln.crypto.Tools.Bytes
import fr.acinq.eclair.router.Router.RouterConf
import com.btcontract.wallet.ln.crypto.Tools
import java.io.ByteArrayInputStream
import fr.acinq.eclair.crypto.Mac32
import scodec.bits.ByteVector
import java.nio.ByteOrder


object LNParams {
  val blocksPerDay: Int = 144
  val cltvExpiry: Int = blocksPerDay * 2 - 3
  val chainHash: ByteVector32 = Block.LivenetGenesisBlock.hash
  val minHostedOnChainRefund = Satoshi(1000000L)
  val minHostedLiabilityBlockdays = 365
  val minPayment = MilliSatoshi(5000L)

  lazy val routerConf =
    RouterConf(channelQueryChunkSize = 100, searchMaxFeeBase = MilliSatoshi(25000L), searchMaxFeePct = 0.01,
      firstPassMaxCltv = CltvExpiryDelta(1008), firstPassMaxRouteLength = 6, mppMinPartAmount = MilliSatoshi(30000000L),
      maxLocalAttempts = 6, maxRemoteAttempts = 12, maxChannelFailures = 12, maxStrangeNodeFailures = 12)

  private[this] val localFeatures = Set(
    ActivatedFeature(ChannelRangeQueriesExtended, FeatureSupport.Optional),
    ActivatedFeature(ChannelRangeQueries, FeatureSupport.Optional),
    ActivatedFeature(VariableLengthOnion, FeatureSupport.Optional)
  )

  var keys: LightningNodeKeys = _

  def makeLocalInitMessage: Init = {
    val networks = InitTlv.Networks(chainHash :: Nil)
    Init(Features(localFeatures), TlvStream apply networks)
  }
}

class LightningNodeKeys(seed: Bytes) {
  private lazy val master: ExtendedPrivateKey = generate(ByteVector view seed)
  lazy val extendedNodeKey: ExtendedPrivateKey = derivePrivateKey(master, hardened(46L) :: hardened(0L) :: Nil)
  lazy val hashingKey: PrivateKey = derivePrivateKey(master, hardened(138L) :: 0L :: Nil).privateKey
  lazy val routingPubKey: PublicKey = extendedNodeKey.publicKey

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

  // Used for separate key per domain
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

  val ERR_NOT_ENOUGH_BALANCE = 1
  val ERR_TOO_MUCH_IN_FLIGHT = 2
  val ERR_AMOUNT_TOO_SMALL = 3
  val ERR_TOO_MANY_HTLC = 4
  val ERR_NOT_OPEN = 5
}

case class NodeAnnouncementExt(na: NodeAnnouncement) {
  lazy val prettyNodeName: String = na.addresses collectFirst {
    case _: IPv4 | _: IPv6 => na.nodeId.toString take 15 grouped 3 mkString "\u0020"
    case _: Tor2 => s"<strong>Tor</strong>\u0020${na.nodeId.toString take 12 grouped 3 mkString "\u0020"}"
    case _: Tor3 => s"<strong>Tor</strong>\u0020${na.nodeId.toString take 12 grouped 3 mkString "\u0020"}"
  } getOrElse "No IP address"

  lazy val nodeSpecificPubKey: PublicKey = nodeSpecificPrivKey.publicKey
  lazy val nodeSpecificPrivKey: PrivateKey = LNParams.keys.makeLightningKey(na.nodeId)
  lazy val nodeSpecificHostedChanId: ByteVector32 = Tools.hostedChanId(nodeSpecificPubKey.value, na.nodeId.value)
  lazy val nodeSpecificPkap: PublicKeyAndPair = PublicKeyAndPair(keyPair = KeyPair(nodeSpecificPubKey.value, nodeSpecificPrivKey.value), them = na.nodeId)
}

case class LightningMessageExt(msg: LightningMessage) {
  def asRemote: LNDirectionalMessage = msg -> false
  def asLocal: LNDirectionalMessage = msg -> true
}

trait NetworkDataStore {
  def addChannelAnnouncement(ca: ChannelAnnouncement): Unit
  def listChannelAnnouncements: Vector[ChannelAnnouncement]

  def addChannelUpdateByPosition(cu: ChannelUpdate): Unit
  def listChannelUpdates: Vector[ChannelUpdate]
  def listExcludedChannels: Set[PositionalId]

  def incrementChannelScore(cu: ChannelUpdate): Unit
  def removeChannelUpdateByPosition(shortId: ShortChannelId, position: java.lang.Integer): Unit
  def removeGhostChannels(ghostIds: Set[ShortChannelId] = Set.empty): Unit
  def processPureData(data: PureRoutingData): Unit
  def getRoutingData: ShortIdToPublicChanMap
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
  def delete(chanId: ByteVector32): Unit
  def put(chanId: ByteVector32, data: HostedCommits): HostedCommits
}