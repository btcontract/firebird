package com.btcontract.wallet

import fr.acinq.eclair._
import com.btcontract.wallet.SyncSpec._
import com.btcontract.wallet.GraphSpec._
import fr.acinq.eclair.{CltvExpiryDelta, ShortChannelId}
import com.btcontract.wallet.ln.{LNParams, LightningNodeKeys, MnemonicExtStorageFormat, PathFinder}
import com.btcontract.wallet.ln.crypto.{CanBeRepliedTo, Tools}
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, NodeAnnouncement}
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.router.Router.{NoRouteAvailable, RouteFound}
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.junit.Test


@RunWith(classOf[AndroidJUnit4])
class PathfinderSpec {
  LNParams.routerConf = LNParams.routerConf.copy(mppMinPartAmount = MilliSatoshi(30000L), firstPassMaxCltv = CltvExpiryDelta(1008 + 504))
  LNParams.format = MnemonicExtStorageFormat(outstandingProviders = Set.empty, LightningNodeKeys.makeFromSeed(randomBytes(32).toArray))
  val (normal, hosted) = getRandomStore

  fillBasicGraph(normal)

  val channelAS: ChannelAnnouncement = makeChannel(5L, a, s) // To be excluded
  val channelASOneSideUpdate: ChannelAnnouncement = makeChannel(6L, a, s) // To be excluded

  val updateASFromA: ChannelUpdate = makeUpdate(ShortChannelId(5L), a, s, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = 500000.msat)
  val updateASFromS: ChannelUpdate = makeUpdate(ShortChannelId(5L), s, a, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = 500000.msat)

  val updateASFromSOneSide: ChannelUpdate = makeUpdate(ShortChannelId(6L), s, a, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = 500000.msat)

  @Test
  def restoreChannelMap(): Unit = {
    normal.addChannelAnnouncement(channelAS)
    normal.addChannelAnnouncement(channelASOneSideUpdate)
    // This will be removed because ghost channel
    normal.addChannelUpdateByPosition(updateASFromA)
    normal.addChannelUpdateByPosition(updateASFromS)

    // This will be removed because one-sided
    normal.addChannelUpdateByPosition(updateASFromSOneSide)

    val oneSideShortIds = normal.listChannelsWithOneUpdate
    normal.removeGhostChannels(Set(ShortChannelId(5L)), oneSideShortIds)
    val routingMap = normal.getRoutingData
    assertTrue(normal.listExcludedChannels.contains(6L))
    assertTrue(!normal.listChannelAnnouncements.map(_.shortChannelId).toList.contains(ShortChannelId(6L)))
    assertTrue(!normal.listChannelUpdates.map(_.update.shortChannelId).toList.contains(ShortChannelId(6L)))
    assertTrue(!routingMap.keySet.contains(ShortChannelId(5L)))
    assertTrue(routingMap.size == 4)
  }

  @Test
  def rejectSearchWhenNotOperational(): Unit = {
    var response: Any = null

    val pf = new PathFinder(normal, hosted, LNParams.routerConf) {
      def getLastTotalResyncStamp: Long = System.currentTimeMillis
      def getLastNormalResyncStamp: Long = System.currentTimeMillis
      def updateLastTotalResyncStamp(stamp: Long): Unit = println("updateLastTotalResyncStamp")
      def updateLastNormalResyncStamp(stamp: Long): Unit = println("updateLastNormalResyncStamp")
      def getExtraNodes: Set[NodeAnnouncement] = Set.empty
    }

    val sender = new CanBeRepliedTo {
      override def process(reply: Any): Unit = response = reply
    }

    val fakeLocalEdge = Tools.mkFakeLocalEdge(from = LNParams.format.keys.ourNodePubKey, toPeer = a)
    pf process Tuple2(sender, makeRouteRequest(fromNode = LNParams.format.keys.ourNodePubKey, fakeLocalEdge))
    synchronized(wait(2000L))
    assertTrue(response == PathFinder.NotifyRejected)
  }

  @Test
  def findRoute(): Unit = {
    var response1: Any = null
    var response2: Any = null

    val pf = new PathFinder(normal, hosted, LNParams.routerConf) {
      def getLastTotalResyncStamp: Long = System.currentTimeMillis
      def getLastNormalResyncStamp: Long = System.currentTimeMillis
      def updateLastTotalResyncStamp(stamp: Long): Unit = println("updateLastTotalResyncStamp")
      def updateLastNormalResyncStamp(stamp: Long): Unit = println("updateLastNormalResyncStamp")
      def getExtraNodes: Set[NodeAnnouncement] = Set.empty
    }

    val listener = new CanBeRepliedTo {
      override def process(reply: Any): Unit = response1 = reply
    }

    val sender = new CanBeRepliedTo {
      override def process(reply: Any): Unit = response2 = reply
    }

    val fakeLocalEdge = Tools.mkFakeLocalEdge(from = LNParams.format.keys.ourNodePubKey, toPeer = a)

    pf.listeners += listener
    pf process PathFinder.CMDLoadGraph
    pf process Tuple2(sender, makeRouteRequest(fromNode = LNParams.format.keys.ourNodePubKey, fakeLocalEdge))
    synchronized(wait(2000L))
    assertTrue(response1 == PathFinder.NotifyOperational)
    assertTrue(response2.asInstanceOf[RouteFound].route.hops.map(_.desc.from) == Seq(LNParams.format.keys.ourNodePubKey, a, c))
    assertTrue(response2.asInstanceOf[RouteFound].route.hops.head.desc.to == a)
  }

  @Test
  def findRouteThroughAssistedChannel(): Unit = {
    val edgeDSFromD = makeEdge(ShortChannelId(6L), d, s, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = 500000.msat)
    var response2: Any = null

    val pf = new PathFinder(normal, hosted, LNParams.routerConf) {
      def getLastTotalResyncStamp: Long = System.currentTimeMillis
      def getLastNormalResyncStamp: Long = System.currentTimeMillis
      def updateLastTotalResyncStamp(stamp: Long): Unit = println("updateLastTotalResyncStamp")
      def updateLastNormalResyncStamp(stamp: Long): Unit = println("updateLastNormalResyncStamp")
      def getExtraNodes: Set[NodeAnnouncement] = Set.empty
    }

    val sender = new CanBeRepliedTo {
      override def process(reply: Any): Unit = response2 = reply
    }

    val fakeLocalEdge = Tools.mkFakeLocalEdge(from = LNParams.format.keys.ourNodePubKey, toPeer = a)

    // Assisted channel is now reachable
    pf process edgeDSFromD
    pf process PathFinder.CMDLoadGraph
    pf process Tuple2(sender, makeRouteRequest(fromNode = LNParams.format.keys.ourNodePubKey, fakeLocalEdge).copy(target = s))
    synchronized(wait(1000L))
    assertTrue(response2.asInstanceOf[RouteFound].route.hops.map(_.desc.from) == Seq(LNParams.format.keys.ourNodePubKey, a, c, d))
    assertTrue(response2.asInstanceOf[RouteFound].route.hops.map(_.desc.to).take(4) == Seq(a, c, d, s))

    // Assisted channel has been updated
    val updateDSFromD = makeEdge(ShortChannelId(6L), d, s, 2.msat, 100, cltvDelta = CltvExpiryDelta(144), maxHtlc = 500000.msat)
    pf process updateDSFromD
    pf process Tuple2(sender, makeRouteRequest(fromNode = LNParams.format.keys.ourNodePubKey, fakeLocalEdge).copy(target = s))
    synchronized(wait(1000L))
    assertTrue(response2.asInstanceOf[RouteFound].route.hops.map(_.desc.from) == Seq(LNParams.format.keys.ourNodePubKey, a, c, d))
    assertTrue(response2.asInstanceOf[RouteFound].route.hops.last.updExt.update.feeBaseMsat == 2.msat)

    // Public channel has been updated
    val updateACFromA1: ChannelUpdate = makeUpdate(ShortChannelId(2L), a, c, 1.msat, 10, cltvDelta = CltvExpiryDelta(154), maxHtlc = 500000.msat) // It got worse because of CLTV
    pf process updateACFromA1
    pf process Tuple2(sender, makeRouteRequest(fromNode = LNParams.format.keys.ourNodePubKey, fakeLocalEdge).copy(target = s))
    synchronized(wait(1000L))
    assertTrue(response2.asInstanceOf[RouteFound].route.hops.map(_.desc.from) == Seq(LNParams.format.keys.ourNodePubKey, a, b, d))

    // Another public channel has been updated
    val disabled = Announcements.makeChannelFlags(isNode1 = Announcements.isNode1(a, b), enable = false)
    val updateABFromA1 = makeUpdate(ShortChannelId(1L), a, b, 1.msat, 10, cltvDelta = CltvExpiryDelta(14), maxHtlc = 500000.msat).copy(channelFlags = disabled) // Better one is now disabled
    pf process updateABFromA1
    pf process Tuple2(sender, makeRouteRequest(fromNode = LNParams.format.keys.ourNodePubKey, fakeLocalEdge).copy(target = s))
    synchronized(wait(1000L))
    assertTrue(response2.asInstanceOf[RouteFound].route.hops.map(_.desc.from) == Seq(LNParams.format.keys.ourNodePubKey, a, c, d))

    // The only assisted channel got disabled, payee is now unreachable
    val disabled1 = Announcements.makeChannelFlags(isNode1 = Announcements.isNode1(d, s), enable = false)
    val updateDSFromD1 = makeUpdate(ShortChannelId(6L), d, s, 2.msat, 100, cltvDelta = CltvExpiryDelta(144), maxHtlc = 500000.msat).copy(channelFlags = disabled1) // Assisted one is now disabled
    pf process updateDSFromD1
    pf process Tuple2(sender, makeRouteRequest(fromNode = LNParams.format.keys.ourNodePubKey, fakeLocalEdge).copy(target = s))
    synchronized(wait(1000L))
    assertTrue(response2.isInstanceOf[NoRouteAvailable])
  }
}
