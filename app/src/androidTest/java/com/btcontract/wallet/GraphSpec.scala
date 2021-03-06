package com.btcontract.wallet

import org.junit.Assert._
import com.btcontract.wallet.GraphSpec._
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.btcontract.wallet.ln.LNParams
import com.btcontract.wallet.ln.crypto.Tools
import com.btcontract.wallet.lnutils.SQLiteNetworkDataStore
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Block, ByteVector32, ByteVector64, Satoshi}
import fr.acinq.eclair._
import fr.acinq.eclair.router.{Announcements, ChannelUpdateExt, RouteCalculation, Sync}
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Router.{ChannelDesc, NoRouteAvailable, RouteFound, RouteParams, RouteRequest}
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate}
import org.junit.runner.RunWith
import org.junit.Test
import scodec.bits.ByteVector

object GraphSpec {
  val DEFAULT_CAPACITY: Satoshi = Satoshi(100000)
  val PlaceHolderSig = ByteVector64(ByteVector.fill(64)(0xaa))
  def randomPubKey = PublicKey(Tools.randomKeyPair.pub)

  def makeUpdate(shortChannelId: ShortChannelId,
                 nodeId1: PublicKey,
                 nodeId2: PublicKey,
                 feeBase: MilliSatoshi,
                 feeProportionalMillionth: Int,
                 minHtlc: MilliSatoshi = 10000.msat,
                 maxHtlc: MilliSatoshi,
                 cltvDelta: CltvExpiryDelta = CltvExpiryDelta(0)): ChannelUpdate = {
    ChannelUpdate(
      signature = PlaceHolderSig,
      chainHash = Block.RegtestGenesisBlock.hash,
      shortChannelId = shortChannelId,
      timestamp = System.currentTimeMillis,
      messageFlags = 1,
      channelFlags = if (Announcements.isNode1(nodeId1, nodeId2)) 0 else 1,
      cltvExpiryDelta = cltvDelta,
      htlcMinimumMsat = minHtlc,
      feeBaseMsat = feeBase,
      feeProportionalMillionths = feeProportionalMillionth,
      htlcMaximumMsat = Some(maxHtlc)
    )
  }

  def makeEdge(shortChannelId: ShortChannelId,
               nodeId1: PublicKey,
               nodeId2: PublicKey,
               feeBase: MilliSatoshi,
               feeProportionalMillionth: Int,
               minHtlc: MilliSatoshi = 10000.msat,
               maxHtlc: MilliSatoshi,
               cltvDelta: CltvExpiryDelta = CltvExpiryDelta(0),
               score: Int = 1): GraphEdge = {
    val update = makeUpdate(shortChannelId, nodeId1, nodeId2, feeBase, feeProportionalMillionth, minHtlc, maxHtlc, cltvDelta)
    GraphEdge(ChannelDesc(shortChannelId, nodeId1, nodeId2), ChannelUpdateExt(update, Sync.getChecksum(update), score, useHeuristics = true))
  }

  def makeChannel(shortChannelId: Long, nodeIdA: PublicKey, nodeIdB: PublicKey): ChannelAnnouncement = {
    val (nodeId1, nodeId2) = if (Announcements.isNode1(nodeIdA, nodeIdB)) (nodeIdA, nodeIdB) else (nodeIdB, nodeIdA)
    ChannelAnnouncement(PlaceHolderSig, PlaceHolderSig, PlaceHolderSig, PlaceHolderSig, Features.empty, Block.RegtestGenesisBlock.hash,
      ShortChannelId(shortChannelId), nodeId1, nodeId2, randomKey.publicKey, randomKey.publicKey)
  }

  val (a, b, c, d, s, e) = (randomPubKey, randomPubKey, randomPubKey, randomPubKey, randomPubKey, randomPubKey)

  def getParams = RouteParams(maxFeeBase = LNParams.routerConf.searchMaxFeeBase, maxFeePct = LNParams.routerConf.searchMaxFeePct,
    routeMaxLength = LNParams.routerConf.firstPassMaxRouteLength, routeMaxCltv = LNParams.routerConf.firstPassMaxCltv)

  def makeRouteRequest(fromNode: PublicKey, fromLocalEdge: GraphEdge): RouteRequest = {
    RouteRequest(paymentHash = ByteVector32(randomBytes(32)),
      partId = ByteVector.empty,
      source = fromNode,
      target = d,
      amount = 100000.msat,
      localEdge = fromLocalEdge,
      routeParams = getParams)
  }

  def fillBasicGraph(store: SQLiteNetworkDataStore): Unit = {
    val channelAB: ChannelAnnouncement = makeChannel(1L, a, b)
    val channelAC: ChannelAnnouncement = makeChannel(2L, a, c)
    val channelBD: ChannelAnnouncement = makeChannel(3L, b, d)
    val channelCD: ChannelAnnouncement = makeChannel(4L, c, d)

    val updateABFromA: ChannelUpdate = makeUpdate(ShortChannelId(1L), a, b, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = 500000.msat)
    val updateABFromB: ChannelUpdate = makeUpdate(ShortChannelId(1L), b, a, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = 500000.msat)

    val updateACFromA: ChannelUpdate = makeUpdate(ShortChannelId(2L), a, c, 1.msat, 10, cltvDelta = CltvExpiryDelta(134), maxHtlc = 500000.msat)
    val updateACFromC: ChannelUpdate = makeUpdate(ShortChannelId(2L), c, a, 1.msat, 10, cltvDelta = CltvExpiryDelta(134), maxHtlc = 500000.msat)

    val updateBDFromB: ChannelUpdate = makeUpdate(ShortChannelId(3L), b, d, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = 500000.msat)
    val updateBDFromD: ChannelUpdate = makeUpdate(ShortChannelId(3L), d, b, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = 500000.msat)

    val updateCDFromC: ChannelUpdate = makeUpdate(ShortChannelId(4L), c, d, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = 500000.msat)
    val updateCDFromD: ChannelUpdate = makeUpdate(ShortChannelId(4L), d, c, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = 500000.msat)

    store.db txWrap {
      store.addChannelAnnouncement(channelAB)
      store.addChannelAnnouncement(channelAC)
      store.addChannelAnnouncement(channelBD)
      store.addChannelAnnouncement(channelCD)

      store.addChannelUpdateByPosition(updateABFromA)
      store.addChannelUpdateByPosition(updateABFromB)

      store.addChannelUpdateByPosition(updateACFromA)
      store.addChannelUpdateByPosition(updateACFromC)

      store.addChannelUpdateByPosition(updateBDFromB)
      store.addChannelUpdateByPosition(updateBDFromD)

      store.addChannelUpdateByPosition(updateCDFromC)
      store.addChannelUpdateByPosition(updateCDFromD)
    }
  }
}

@RunWith(classOf[AndroidJUnit4])
class GraphSpec {
  LNParams.routerConf = LNParams.routerConf.copy(mppMinPartAmount = MilliSatoshi(30000L), firstPassMaxCltv = CltvExpiryDelta(1008 + 504))
  val r: RouteRequest = makeRouteRequest(fromNode = a, fromLocalEdge = null)

  @Test
  def preferDirectPath(): Unit = {
    val g = DirectedGraph(List(
      makeEdge(ShortChannelId(1L), a, b, 1.msat, 10, cltvDelta = CltvExpiryDelta(1), maxHtlc = 500000.msat),
      makeEdge(ShortChannelId(2L), a, c, 1.msat, 10, cltvDelta = CltvExpiryDelta(1), maxHtlc = 500000.msat),
      makeEdge(ShortChannelId(3L), c, b, 2.msat, 10, cltvDelta = CltvExpiryDelta(1), maxHtlc = 500000.msat)
    ))

    val RouteFound(_, _, route) = RouteCalculation.handleRouteRequest(g, LNParams.routerConf, r.copy(target = b))
    assert(route.hops.size == 1 && route.hops.head.desc.from == a && route.hops.head.desc.to == b)
  }

  @Test
  def calculateRoute(): Unit = {
    val g = DirectedGraph(List(
      makeEdge(ShortChannelId(1L), a, b, 1.msat, 10, cltvDelta = CltvExpiryDelta(1), maxHtlc = 500000.msat),
      makeEdge(ShortChannelId(2L), a, c, 1.msat, 10, cltvDelta = CltvExpiryDelta(1), maxHtlc = 500000.msat),
      makeEdge(ShortChannelId(3L), b, d, 2.msat, 10, cltvDelta = CltvExpiryDelta(1), maxHtlc = 500000.msat),
      makeEdge(ShortChannelId(4L), c, d, 1.msat, 10, cltvDelta = CltvExpiryDelta(1), maxHtlc = 600000.msat)
    ))

    val RouteFound(_, _, route) = RouteCalculation.handleRouteRequest(g, LNParams.routerConf, r)

    assertTrue(route.hops.map(_.desc.from) == Seq(a, c))

    assertTrue(route.hops.map(_.desc.to) == Seq(c, d))

    assertTrue(route.weight.costs == List(100002.msat, 100000.msat))

    val RouteFound(_, _, route1) = RouteCalculation.handleRouteRequest(g, LNParams.routerConf, r.copy(ignoreChannels = Set(ChannelDesc(ShortChannelId(2L), a, c))))

    assertTrue(route1.hops.map(_.desc.from) == Seq(a, b))

    val RouteFound(_, _, route2) = RouteCalculation.handleRouteRequest(g, LNParams.routerConf, r.copy(ignoreNodes = Set(c)))

    assertTrue(route2.hops.map(_.desc.from) == Seq(a, b))

    val NoRouteAvailable(_, _) = RouteCalculation.handleRouteRequest(g, LNParams.routerConf, r.copy(amount = 500000.msat)) // Can't handle fees
  }

  @Test
  def cltvAffectsResult(): Unit = {
    val g = DirectedGraph(List(
      makeEdge(ShortChannelId(1L), s, a, 1000.msat, 100, cltvDelta = CltvExpiryDelta(576), maxHtlc = 5000000000L.msat),
      makeEdge(ShortChannelId(2L), a, b, 1000.msat, 100, cltvDelta = CltvExpiryDelta(576), maxHtlc = 5000000000L.msat),
      makeEdge(ShortChannelId(3L), a, c, 1000.msat, 150, cltvDelta = CltvExpiryDelta(9), maxHtlc = 5000000000L.msat), // Used despite higher fee because of much lower cltv
      makeEdge(ShortChannelId(4L), b, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(576), maxHtlc = 5000000000L.msat),
      makeEdge(ShortChannelId(5L), c, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(576), maxHtlc = 6000000000L.msat)
    ))

    val RouteFound(_, _, route) = RouteCalculation.handleRouteRequest(g, LNParams.routerConf, r.copy(source = s, amount = 500000000L.msat))

    assertTrue(route.hops.map(_.desc.from) == Seq(s, a, c))
  }

  @Test
  def capacityAffectsResult(): Unit = {
    val g = DirectedGraph(List(
      makeEdge(ShortChannelId(1L), s, a, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), maxHtlc = 5000000000L.msat),
      makeEdge(ShortChannelId(2L), a, b, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), maxHtlc = 5000000000L.msat),
      makeEdge(ShortChannelId(3L), a, c, 1000.msat, 150, cltvDelta = CltvExpiryDelta(144), maxHtlc = 800000000000L.msat), // Used despite higher fee because of much larger channel size
      makeEdge(ShortChannelId(4L), b, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), maxHtlc = 5000000000L.msat),
      makeEdge(ShortChannelId(5L), c, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), maxHtlc = 6000000000L.msat)
    ))

    val RouteFound(_, _, route) = RouteCalculation.handleRouteRequest(g, LNParams.routerConf, r.copy(source = s, amount = 500000000L.msat))

    assertTrue(route.hops.map(_.desc.from) == Seq(s, a, c))
  }

  @Test
  def scoreAffectsResult(): Unit = {
    val g = DirectedGraph(List(
      makeEdge(ShortChannelId(1L), s, a, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), maxHtlc = 5000000000L.msat),
      makeEdge(ShortChannelId(2L), a, b, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), maxHtlc = 5000000000L.msat),
      makeEdge(ShortChannelId(3L), a, c, 1000.msat, 150, cltvDelta = CltvExpiryDelta(144), maxHtlc = 5000000000L.msat, score = 260), // Used despite higher fee because of much better score
      makeEdge(ShortChannelId(4L), b, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), maxHtlc = 5000000000L.msat),
      makeEdge(ShortChannelId(5L), c, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), maxHtlc = 6000000000L.msat)
    ))

    val RouteFound(_, _, route) = RouteCalculation.handleRouteRequest(g, LNParams.routerConf, r.copy(source = s, amount = 500000000L.msat))

    assertTrue(route.hops.map(_.desc.from) == Seq(s, a, c))
  }

  @Test
  def channelAgeAffectsResult(): Unit = {
    val g = DirectedGraph(List(
      makeEdge(ShortChannelId("600000x1x1"), s, a, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), maxHtlc = 5000000000L.msat),
      makeEdge(ShortChannelId("600000x1x1"), a, b, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), maxHtlc = 5000000000L.msat),
      makeEdge(ShortChannelId("500000x1x1"), a, c, 1000.msat, 150, cltvDelta = CltvExpiryDelta(144), maxHtlc = 5000000000L.msat), // Used despite higher fee because it's very old
      makeEdge(ShortChannelId("600000x1x1"), b, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), maxHtlc = 5000000000L.msat),
      makeEdge(ShortChannelId("600000x1x1"), c, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(144), maxHtlc = 6000000000L.msat)
    ))

    val RouteFound(_, _, route) = RouteCalculation.handleRouteRequest(g, LNParams.routerConf, r.copy(source = s, amount = 500000000L.msat))
    assertTrue(route.hops.map(_.desc.from) == Seq(s, a, c))
  }

  @Test
  def hostedChannelAffectsResult(): Unit = {
    import com.softwaremill.quicklens._
    val g = DirectedGraph(List(
      makeEdge(ShortChannelId(1L), s, a, 1000.msat, 100, cltvDelta = CltvExpiryDelta(576), maxHtlc = 5000000000L.msat),
      makeEdge(ShortChannelId(2L), a, b, 1000.msat, 100, cltvDelta = CltvExpiryDelta(576), maxHtlc = 5000000000L.msat),
      makeEdge(ShortChannelId(3L), a, c, 100.msat, 10, cltvDelta = CltvExpiryDelta(9), maxHtlc = 500000000000L.msat).modify(_.updExt.useHeuristics).setTo(false), // Not used despite better parameters
      makeEdge(ShortChannelId(4L), b, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(576), maxHtlc = 5000000000L.msat),
      makeEdge(ShortChannelId(5L), c, d, 1000.msat, 100, cltvDelta = CltvExpiryDelta(576), maxHtlc = 6000000000L.msat)
    ))

    val RouteFound(_, _, route) = RouteCalculation.handleRouteRequest(g, LNParams.routerConf, r.copy(source = s, amount = 500000000L.msat))

    assertTrue(route.hops.map(_.desc.from) == Seq(s, a, b))
  }
}