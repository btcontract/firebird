package com.btcontract.wallet

import com.btcontract.wallet.SyncSpec._
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.btcontract.wallet.ln._
import com.btcontract.wallet.lnutils._
import fr.acinq.eclair._

import concurrent.ExecutionContext.Implicits.global
import fr.acinq.eclair.router.Graph.GraphStructure.DirectedGraph
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.router.Router.Data
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert._

import scala.concurrent.Future
import scala.util.Random


object SyncSpec {
  def getRandomStore: (SQLiteNetworkDataStore, SQLiteNetworkDataStore) = {
    def alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    def randomDbName: String = List.fill(12)(secureRandom nextInt alphabet.length).map(alphabet).mkString
    def db = new SQLiteInterface(WalletApp.app, randomDbName)
    val normal = new SQLiteNetworkDataStore(db, NormalChannelUpdateTable, NormalChannelAnnouncementTable, NormalExcludedChannelTable)
    val hosted = new SQLiteNetworkDataStore(db, HostedChannelUpdateTable, HostedChannelAnnouncementTable, HostedExcludedChannelTable)
    (normal, hosted)
  }
}

@RunWith(classOf[AndroidJUnit4])
class SyncSpec {
  val (store, _) = getRandomStore
  def run: Unit = {
    val channelMap0 = store.getRoutingData
    val data1 = Data(channelMap0, hostedChannels = Map.empty, extraEdges = Map.empty, graph = DirectedGraph.makeGraph(channelMap0))
    new SyncMaster(extraNodes = Set.empty, store.listExcludedChannels, data1) {
      def onChunkSyncComplete(pure: PureRoutingData): Unit = {
        println(s"Chunk complete, announces=${pure.announces.size}, updates=${pure.updates.size}, excluded=${pure.excluded.size}")
        val a = System.currentTimeMillis
        store.processPureData(pure)
        println(s"DB chunk processing took ${System.currentTimeMillis - a} msec")
      }

      def onTotalSyncComplete: Unit = {
        val map = store.getRoutingData
        println(s"Total sync complete, we have ${map.keys.size} channels")
        val a1 = System.currentTimeMillis
        val oneSidedShortIds = store.listChannelsWithOneUpdate
        store.removeGhostChannels(map.keySet.diff(provenShortIds), oneSidedShortIds)
        println(s"removeGhostChannels took ${System.currentTimeMillis - a1} msec")
        val a2 = System.currentTimeMillis
        val map1: Map[ShortChannelId, Router.PublicChannel] = store.getRoutingData
        println(s"Loading data took ${System.currentTimeMillis - a2} msec")
        println(s"Total sync complete, we have ${map1.keys.size} purified channels")
        val a3 = System.currentTimeMillis
        val graph = DirectedGraph.makeGraph(map1)
        assert(graph.vertices.forall { case (nodeId, incomingEdges) => incomingEdges.forall(_.desc.to == nodeId) })
        println(s"making graph took ${System.currentTimeMillis - a3} msec")
        assert(map1.nonEmpty)
        run
      }
    }
  }

  @Test
  def liveSync(): Unit = {
    run
    synchronized(wait(2000000L))
  }

  def synchronizedRemoval(): Unit = {
    LNParams.format = MnemonicStorageFormat(SyncMaster.syncNodes ++ SyncMaster.hostedChanNodes, keys = null)
    for (ann <- Random.shuffle(LNParams.format.outstandingProviders.toList)) Future {
      Thread.sleep(secureRandom.nextInt(2))
      WalletApp syncRmOutstanding ann
    }
    synchronized(wait(200L))
    assertTrue(LNParams.format.outstandingProviders.isEmpty)
  }

  @Test
  def manySynchronizedRemovals(): Unit = {
    WalletApp.db = store.db
    WalletApp.dataBag = new SQLiteDataBag(WalletApp.db)
    for (_ <- 0 to 50) synchronizedRemoval()
  }
}
