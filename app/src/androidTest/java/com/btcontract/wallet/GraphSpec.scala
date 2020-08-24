package com.btcontract.wallet

import org.junit.Assert._
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.btcontract.wallet.ln.crypto.Tools
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Block, ByteVector32, ByteVector64, Satoshi}
import fr.acinq.eclair._
import fr.acinq.eclair.router.{Announcements, RouteCalculation, Router}
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Router.{ChannelDesc, NoRouteAvailable, RouteFound, RouteParams, RouteRequest, RouterConf}
import fr.acinq.eclair.wire.ChannelUpdate
import org.junit.runner.RunWith
import org.junit.Test
import scodec.bits.ByteVector

@RunWith(classOf[AndroidJUnit4])
class GraphSpec {
  val DEFAULT_CAPACITY: Satoshi = 100000.sat
  val PlaceHolderSig = ByteVector64(ByteVector.fill(64)(0xaa))
  def randomPubKey = PublicKey(Tools.randomKeyPair.pub)

  def makeEdge(shortChannelId: Long,
               nodeId1: PublicKey,
               nodeId2: PublicKey,
               feeBase: MilliSatoshi,
               feeProportionalMillionth: Int,
               minHtlc: MilliSatoshi = 10000.msat,
               maxHtlc: MilliSatoshi,
               cltvDelta: CltvExpiryDelta = CltvExpiryDelta(0),
               capacity: Satoshi = DEFAULT_CAPACITY,
               balance_opt: Option[MilliSatoshi] = None): GraphEdge = {
    val update = ChannelUpdate(
      signature = PlaceHolderSig,
      chainHash = Block.RegtestGenesisBlock.hash,
      shortChannelId = ShortChannelId(shortChannelId),
      timestamp = System.currentTimeMillis,
      messageFlags = 1,
      channelFlags = if (Announcements.isNode1(nodeId1, nodeId2)) 0 else 1,
      cltvExpiryDelta = cltvDelta,
      htlcMinimumMsat = minHtlc,
      feeBaseMsat = feeBase,
      feeProportionalMillionths = feeProportionalMillionth,
      htlcMaximumMsat = Some(maxHtlc)
    )
    GraphEdge(ChannelDesc(ShortChannelId(shortChannelId), nodeId1, nodeId2), update)
  }

  @Test
  def calculateRoute(): Unit = {
    val (a, b, c, d) = (randomPubKey, randomPubKey, randomPubKey, randomPubKey)

    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 2.msat, 10, cltvDelta = CltvExpiryDelta(1), maxHtlc = 500000.msat),
      makeEdge(2L, a, c, 1.msat, 10, cltvDelta = CltvExpiryDelta(1), maxHtlc = 500000.msat),
      makeEdge(3L, b, d, 1.msat, 10, cltvDelta = CltvExpiryDelta(1), maxHtlc = 500000.msat),
      makeEdge(4L, c, d, 1.msat, 10, cltvDelta = CltvExpiryDelta(1), maxHtlc = 600000.msat)
    ))

    val routerConf =
      RouterConf(channelQueryChunkSize = 100, searchMaxFeeBase = MilliSatoshi(60000L), searchMaxFeePct = 0.01,
        firstPassMaxCltv = CltvExpiryDelta(1008), firstPassMaxRouteLength = 6, searchRatioCltv = 0.1, searchRatioChannelAge = 0.4,
        searchRatioChannelCapacity = 0.2, searchRatioSuccessScore = 0.3, mppMinPartAmount = MilliSatoshi(40000000L),
        maxAttemptsPerPart = 12, maxChannelFailures = 12, maxNodeFailures = 12)

    val params = RouteCalculation.getDefaultRouteParams(routerConf)
    val r = RouteRequest(paymentHash = ByteVector32(ByteVector(Tools.random.getBytes(32))),
      partId = ByteVector.empty,
      source = a,
      target = d,
      amount = 100000.msat,
      maxFee = params.getMaxFee(300.msat),
      localEdge = null,
      ignoreNodes = Set.empty,
      ignoreChannels = Set.empty,
      params)
    val RouteFound(_, _, route) = RouteCalculation.handleRouteRequest(g, routerConf, currentBlockHeight = 40000, r)

    assertTrue(route.hops.map(_.nodeId) == Seq(a, c))

    assertTrue(route.hops.map(_.nextNodeId) == Seq(c, d))

    assertTrue(route.amounts == Vector(100002.msat, 100000.msat))

    val RouteFound(_, _, route1) = RouteCalculation.handleRouteRequest(g, routerConf, currentBlockHeight = 40000, r.copy(ignoreChannels = Set(ChannelDesc(ShortChannelId(2L), a, c))))

    assertTrue(route1.hops.map(_.nodeId) == Seq(a, b))

    val RouteFound(_, _, route2) = RouteCalculation.handleRouteRequest(g, routerConf, currentBlockHeight = 40000, r.copy(ignoreNodes = Set(c)))

    assertTrue(route2.hops.map(_.nodeId) == Seq(a, b))

    val NoRouteAvailable(_, _) = RouteCalculation.handleRouteRequest(g, routerConf, currentBlockHeight = 40000, r.copy(amount = 500000.msat)) // Can't handle fees
  }
}