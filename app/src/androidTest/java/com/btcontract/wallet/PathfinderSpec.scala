package com.btcontract.wallet

import fr.acinq.eclair._
import com.btcontract.wallet.SyncSpec._
import com.btcontract.wallet.GraphSpec._
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.btcontract.wallet.ln.{LNParams, LightningNodeKeys, PathFinder}
import com.btcontract.wallet.ln.crypto.{CanBeRepliedTo, Tools}
import com.btcontract.wallet.lnutils.SQliteNetworkDataStore
import fr.acinq.eclair.router.Router.RouteFound
import fr.acinq.eclair.{CltvExpiryDelta, ShortChannelId}
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, NodeAnnouncement}
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.junit.Test


@RunWith(classOf[AndroidJUnit4])
class PathfinderSpec {
  LNParams.keys = LightningNodeKeys.makeFromSeed(Tools.random.getBytes(32))
  val store: SQliteNetworkDataStore = getStore

  val channelAB: ChannelAnnouncement = makeChannel(1L, a, b)
  val channelAC: ChannelAnnouncement = makeChannel(2L, a, c)
  val channelBD: ChannelAnnouncement = makeChannel(3L, b, d)
  val channelCD: ChannelAnnouncement = makeChannel(4L, c, d)
  val channelAS: ChannelAnnouncement = makeChannel(5L, a, s) // To be excluded
  val channelASOneSideUpdate: ChannelAnnouncement = makeChannel(6L, a, s) // To be excluded

  val updateABFromA: ChannelUpdate = makeUpdate(ShortChannelId(1L), a, b, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = 500000.msat)
  val updateABFromB: ChannelUpdate = makeUpdate(ShortChannelId(1L), b, a, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = 500000.msat)

  val updateACFromA: ChannelUpdate = makeUpdate(ShortChannelId(2L), a, c, 1.msat, 10, cltvDelta = CltvExpiryDelta(134), maxHtlc = 500000.msat)
  val updateACFromC: ChannelUpdate = makeUpdate(ShortChannelId(2L), c, a, 1.msat, 10, cltvDelta = CltvExpiryDelta(134), maxHtlc = 500000.msat)

  val updateBDFromB: ChannelUpdate = makeUpdate(ShortChannelId(3L), b, d, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = 500000.msat)
  val updateBDFromD: ChannelUpdate = makeUpdate(ShortChannelId(3L), d, b, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = 500000.msat)

  val updateCDFromC: ChannelUpdate = makeUpdate(ShortChannelId(4L), c, d, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = 500000.msat)
  val updateCDFromD: ChannelUpdate = makeUpdate(ShortChannelId(4L), d, c, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = 500000.msat)

  val updateASFromA: ChannelUpdate = makeUpdate(ShortChannelId(5L), a, s, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = 500000.msat)
  val updateASFromS: ChannelUpdate = makeUpdate(ShortChannelId(5L), s, a, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = 500000.msat)

  val updateASFromSOneSide: ChannelUpdate = makeUpdate(ShortChannelId(6L), s, a, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = 500000.msat)

  db txWrap {
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

  @Test
  def restoreChannelMap(): Unit = {
    store.addChannelAnnouncement(channelAS)
    store.addChannelAnnouncement(channelASOneSideUpdate)
    // This will be removed because ghost channel
    store.addChannelUpdateByPosition(updateASFromA)
    store.addChannelUpdateByPosition(updateASFromS)

    // This will be removed because one-sided
    store.addChannelUpdateByPosition(updateASFromSOneSide)

    store.removeGhostChannels(Set(ShortChannelId(5L)))
    val routingMap = store.getRoutingData
    assertTrue(store.listExcludedChannels.contains(6L))
    assertTrue(!store.listChannelAnnouncements.map(_.shortChannelId).contains(ShortChannelId(6L)))
    assertTrue(!store.listChannelUpdates.map(_.shortChannelId).contains(ShortChannelId(6L)))
    assertTrue(!routingMap.keySet.contains(ShortChannelId(5L)))
    assertTrue(routingMap.size == 4)
  }

  @Test
  def rejectSearchWhenNotOperational(): Unit = {
    var response: Any = null

    val pf = new PathFinder(store, routerConf) {
      def getLastResyncStamp: Long = System.currentTimeMillis
      def updateLastResyncStamp(stamp: Long): Unit = println("updateLastResyncStamp")
      def getExtraNodes: Set[NodeAnnouncement] = Set.empty
      def getChainTip: Long = 400000L
    }

    val sender = new CanBeRepliedTo {
      override def process(reply: Any): Unit = response = reply
    }

    val fakeLocalEdge = Tools.mkFakeLocalEdge(from = LNParams.keys.routingPubKey, to = a)
    pf process Tuple2(sender, makeRouteRequest(fromNode = LNParams.keys.routingPubKey, fakeLocalEdge))
    synchronized(wait(2000L))
    assertTrue(response == PathFinder.NotifyRejected)
  }

  @Test
  def findRoute(): Unit = {
    var response1: Any = null
    var response2: Any = null

    val pf = new PathFinder(store, routerConf) {
      def getLastResyncStamp: Long = System.currentTimeMillis
      def updateLastResyncStamp(stamp: Long): Unit = println("updateLastResyncStamp")
      def getExtraNodes: Set[NodeAnnouncement] = Set.empty
      def getChainTip: Long = 400000L
    }

    val listener = new CanBeRepliedTo {
      override def process(reply: Any): Unit = response1 = reply
    }

    val sender = new CanBeRepliedTo {
      override def process(reply: Any): Unit = response2 = reply
    }

    val fakeLocalEdge = Tools.mkFakeLocalEdge(from = LNParams.keys.routingPubKey, to = a)

    pf.listeners += listener
    pf process PathFinder.CMDLoadGraph
    pf process Tuple2(sender, makeRouteRequest(fromNode = LNParams.keys.routingPubKey, fakeLocalEdge))
    synchronized(wait(2000L))
    assertTrue(response1 == PathFinder.NotifyOperational)
    assertTrue(response2.asInstanceOf[RouteFound].route.hops.map(_.desc.a) == Seq(LNParams.keys.routingPubKey, a, c))
  }

  @Test
  def findRouteThroughAssistedChannel(): Unit = {
    val edgeDSFromD = makeEdge(ShortChannelId(6L), d, s, 1.msat, 10, cltvDelta = CltvExpiryDelta(144), maxHtlc = 500000.msat)
    var response2: Any = null

    val pf = new PathFinder(store, routerConf) {
      def getLastResyncStamp: Long = System.currentTimeMillis
      def updateLastResyncStamp(stamp: Long): Unit = println("updateLastResyncStamp")
      def getExtraNodes: Set[NodeAnnouncement] = Set.empty
      def getChainTip: Long = 400000L
    }

    val sender = new CanBeRepliedTo {
      override def process(reply: Any): Unit = response2 = reply
    }

    val fakeLocalEdge = Tools.mkFakeLocalEdge(from = LNParams.keys.routingPubKey, to = a)

    // Assisted channel is now reachable
    pf process edgeDSFromD
    pf process PathFinder.CMDLoadGraph
    pf process Tuple2(sender, makeRouteRequest(fromNode = LNParams.keys.routingPubKey, fakeLocalEdge).copy(target = s))
    synchronized(wait(2000L))
    assertTrue(response2.asInstanceOf[RouteFound].route.hops.map(_.desc.a) == Seq(LNParams.keys.routingPubKey, a, c, d))
    assertTrue(response2.asInstanceOf[RouteFound].route.hops.map(_.desc.b).take(4) == Seq(a, c, d, s))

    // Assisted channel has been updated
    val updateDSFromD = makeEdge(ShortChannelId(6L), d, s, 2.msat, 100, cltvDelta = CltvExpiryDelta(144), maxHtlc = 500000.msat)
    pf process updateDSFromD
    pf process Tuple2(sender, makeRouteRequest(fromNode = LNParams.keys.routingPubKey, fakeLocalEdge).copy(target = s))
    synchronized(wait(2000L))
    assertTrue(response2.asInstanceOf[RouteFound].route.hops.map(_.desc.a) == Seq(LNParams.keys.routingPubKey, a, c, d))
    assertTrue(response2.asInstanceOf[RouteFound].route.hops.last.update.feeBaseMsat == 2.msat)

    // Public channel has been updated
    val updateACFromA1: ChannelUpdate = makeUpdate(ShortChannelId(2L), a, c, 1.msat, 10, cltvDelta = CltvExpiryDelta(154), maxHtlc = 500000.msat) // It got worse because of CLTV
    pf process updateACFromA1
    pf process Tuple2(sender, makeRouteRequest(fromNode = LNParams.keys.routingPubKey, fakeLocalEdge).copy(target = s))
    synchronized(wait(2000L))
    assertTrue(response2.asInstanceOf[RouteFound].route.hops.map(_.desc.a) == Seq(LNParams.keys.routingPubKey, a, b, d))
  }
}
