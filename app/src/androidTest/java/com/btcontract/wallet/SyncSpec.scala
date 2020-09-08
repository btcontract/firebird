package com.btcontract.wallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.btcontract.wallet.ln.crypto.Tools
import com.btcontract.wallet.ln.{LNParams, PureRoutingData, SyncMaster}
import com.btcontract.wallet.lnutils.{SQLiteInterface, SQliteNetworkDataStore}
import fr.acinq.eclair.router.Graph.GraphStructure.DirectedGraph
import fr.acinq.eclair.router.Router.Data
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(classOf[AndroidJUnit4])
class SyncSpec {
  val db = new SQLiteInterface(WalletApp.app, new String(Tools.random.getBytes(8)))
  val store = new SQliteNetworkDataStore(db)

  def run: Unit = {
    val channelMap0 = store.getRoutingData
    val data1 = Data(channelMap0, extraEdges = Map.empty, graph = DirectedGraph.makeGraph(channelMap0))
    new SyncMaster(extraNodes = Set.empty, store.listExcludedChannels, data1, from = 640000, LNParams.routerConf) {
      def onChunkSyncComplete(pure: PureRoutingData): Unit = {
        println(s"Chunk complete, announces=${pure.announces.size}, updates=${pure.updates.size}, excluded=${pure.excluded.size}")
        store.processPureData(pure)
      }

      def onTotalSyncComplete: Unit = {
        val map = store.getRoutingData
        store.removeGhostChannels(map.keySet.diff(provenShortIds))
        val map1 = store.getRoutingData
        println(s"Total sync complete")
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
