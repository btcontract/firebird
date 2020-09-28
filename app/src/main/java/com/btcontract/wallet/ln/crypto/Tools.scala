package com.btcontract.wallet.ln.crypto

import fr.acinq.eclair._
import fr.acinq.bitcoin._
import scala.util.{Success, Try}
import java.nio.{ByteBuffer, ByteOrder}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.{CltvExpiryDelta, Features, MilliSatoshi, ShortChannelId}
import fr.acinq.eclair.wire.{Color, LightningMessage, NodeAddress, NodeAnnouncement}
import fr.acinq.eclair.router.Graph.GraphStructure.GraphEdge
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import com.btcontract.wallet.ln.crypto.Noise.KeyPair
import com.btcontract.wallet.ln.LightningMessageExt
import fr.acinq.eclair.router.Router.ChannelDesc
import fr.acinq.eclair.router.RouteCalculation
import fr.acinq.eclair.channel.CMD_ADD_HTLC
import java.io.ByteArrayInputStream
import language.implicitConversions
import scala.collection.mutable
import scodec.bits.ByteVector


object Tools {
  type Bytes = Array[Byte]
  def log(info: Any): Unit = println(s"LN LOG: $info")
  def wrap(run: => Unit)(go: => Unit): Unit = try go catch none finally run
  def bin2readable(bin: Bytes) = new String(bin, "UTF-8")
  def none: PartialFunction[Any, Unit] = { case _ => }
  def runAnd[T](result: T)(action: Any): T = result

  implicit class Any2Some[T](underlying: T) {
    def toSome: Option[T] = Some(underlying)
  }

  implicit def bytes2VecView(underlyingBytes: Bytes): ByteVector = ByteVector.view(underlyingBytes)
  implicit def lightningMessage2Ext(msg: LightningMessage): LightningMessageExt = LightningMessageExt(msg)

  def toMapBy[K, V](items: Iterable[V], mapper: V => K): Map[K, V] = items.map(item => mapper(item) -> item).toMap
  def mapKeys[K, V, K1](m: mutable.Map[K, V], fun: K => K1, defVal: V): mutable.Map[K1, V] = m map { case key \ value => fun(key) -> value } withDefaultValue defVal
  def maxByOption[T, B](items: Iterable[T], mapper: T => B)( implicit cmp: Ordering[B] ): Option[T] = if (items.isEmpty) None else Some(items maxBy mapper)
  def memoize[In, Out](fun: In => Out): collection.mutable.HashMap[In, Out] = new collection.mutable.HashMap[In, Out] { self =>
    override def apply(key: In): Out = getOrElseUpdate(key, fun apply key)
  }

  def hostedChanId(pubkey1: ByteVector, pubkey2: ByteVector): ByteVector32 = {
    val pubkey1First: Boolean = LexicographicalOrdering.isLessThan(pubkey1, pubkey2)
    if (pubkey1First) Crypto.sha256(pubkey1 ++ pubkey2) else Crypto.sha256(pubkey2 ++ pubkey1)
  }

  def shortHostedChanId(chanId: ByteVector32): ShortChannelId = {
    val stream: ByteArrayInputStream = new ByteArrayInputStream(chanId.toArray)
    def getChunk: Long = Protocol.uint32(stream, ByteOrder.BIG_ENDIAN)
    val id = Vector.fill(8)(getChunk).foldLeft(Long.MaxValue)(_ * _)
    ShortChannelId(id)
  }

  def mkNodeAnnouncement(id: PublicKey, na: NodeAddress, alias: String) =
    NodeAnnouncement(signature = ByteVector64.Zeroes, features = Features.empty, timestamp = 0L,
      nodeId = id, rgbColor = Color(-128, -128, -128), alias, addresses = na :: Nil)

  def mkFakeLocalEdge(from: PublicKey, toPeer: PublicKey): GraphEdge = {
    // Augments a graph with local edge corresponding to our hosted channel
    // Parameters do not matter except that it must point from us to peer

    val zeroCltvDelta = CltvExpiryDelta(0)
    val randomShortChannelId = ShortChannelId(secureRandom.nextLong)
    val fakeDesc = ChannelDesc(randomShortChannelId, a = from, b = toPeer)
    val fakeHop = ExtraHop(from, randomShortChannelId, MilliSatoshi(0L), 0L, zeroCltvDelta)
    GraphEdge(updExt = RouteCalculation.toFakeUpdate(fakeHop), desc = fakeDesc)
  }

  def randomKeyPair: KeyPair = {
    val pk: PrivateKey = randomKey
    KeyPair(pk.publicKey.value, pk.value)
  }

  def writeUInt64Array(input: Long, order: ByteOrder): Bytes = {
    val bin = new Array[Byte](8)
    val buffer = ByteBuffer.wrap(bin).order(order)
    buffer.putLong(input)
    bin
  }

  def isValidFinalScriptPubkey(raw: ByteVector): Boolean = Try(Script parse raw) match {
    case Success(OP_DUP :: OP_HASH160 :: OP_PUSHDATA(pkh, _) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) => pkh.size == 20
    case Success(OP_HASH160 :: OP_PUSHDATA(scriptHash, _) :: OP_EQUAL :: Nil) => scriptHash.size == 20
    case Success(OP_0 :: OP_PUSHDATA(pubkeyHash, _) :: Nil) if pubkeyHash.length == 20 => true
    case Success(OP_0 :: OP_PUSHDATA(scriptHash, _) :: Nil) if scriptHash.length == 32 => true
    case _ => false
  }

  object \ {
    // Matching Tuple2 via arrows with much less noise
    def unapply[A, B](t2: (A, B) /* Got a tuple */) = Some(t2)
  }
}

case class CMDAddImpossible(cmd: CMD_ADD_HTLC, code: Int) extends LightningException
class LightningException(reason: String = "Lightning related failure") extends RuntimeException(reason)
trait CanBeRepliedTo { def process(reply: Any): Unit }

abstract class StateMachine[T] {
  def become(freshData: T, freshState: String): StateMachine[T] = {
    // Update state, data and return itself for easy chaining operations
    state = freshState
    data = freshData
    this
  }

  def doProcess(change: Any): Unit
  var state: String = _
  var data: T = _
}