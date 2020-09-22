package com.btcontract.wallet

import com.btcontract.wallet.SyncSpec._
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.btcontract.wallet.ln.{LNParams, PureRoutingData, SyncMaster}
import com.btcontract.wallet.lnutils._
import fr.acinq.eclair._
import fr.acinq.eclair.router.Graph.GraphStructure.DirectedGraph
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.router.Router.Data
import org.junit.Test
import org.junit.runner.RunWith


object SyncSpec {
  def getRandomStore: (SQliteNetworkDataStore, SQliteNetworkDataStore) = {
    def alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    def randomDbName: String = List.fill(12)(secureRandom nextInt alphabet.length).map(alphabet).mkString
    def db = new SQLiteInterface(WalletApp.app, randomDbName)
    val normal = new SQliteNetworkDataStore(db, NormalChannelUpdateTable, NormalChannelAnnouncementTable, NormalExcludedChannelTable)
    val hosted = new SQliteNetworkDataStore(db, HostedChannelUpdateTable, HostedChannelAnnouncementTable, HostedExcludedChannelTable)
    (normal, hosted)
  }
}

@RunWith(classOf[AndroidJUnit4])
class SyncSpec {
  val (store, _) = getRandomStore
  def run: Unit = {
    val channelMap0 = store.getRoutingData
    val data1 = Data(channelMap0, extraEdges = Map.empty, graph = DirectedGraph.makeGraph(channelMap0))
    new SyncMaster(extraNodes = Set.empty, store.listExcludedChannels, data1, from = 0, LNParams.routerConf) {
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
        store.removeGhostChannels(map.keySet.diff(provenShortIds))
        println(s"removeGhostChannels took ${System.currentTimeMillis - a1} msec")
        val a2 = System.currentTimeMillis
        val map1: Map[ShortChannelId, Router.PublicChannel] = store.getRoutingData
        println(s"removeGhostChannels took ${System.currentTimeMillis - a2} msec")
        println(s"Total sync complete, we have ${map1.keys.size} purified channels")
        val a3 = System.currentTimeMillis
        DirectedGraph.makeGraph(map1)
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
}
