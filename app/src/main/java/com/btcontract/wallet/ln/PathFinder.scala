package com.btcontract.wallet.ln

import com.softwaremill.quicklens._
import com.btcontract.wallet.ln.PathFinder._
import com.btcontract.wallet.ln.crypto.Tools._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import fr.acinq.eclair.router.Router.{Data, RouteRequest, RouterConf}
import com.btcontract.wallet.ln.crypto.{CanBeRepliedTo, StateMachine}
import fr.acinq.eclair.router.{Announcements, RouteCalculation, Router}
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import com.btcontract.wallet.ln.SyncMaster.{NodeAnnouncements, ShortChanIdSet}
import scala.collection.immutable.SortedMap
import fr.acinq.eclair.wire.ChannelUpdate
import java.util.concurrent.Executors
import fr.acinq.eclair.MilliSatoshi


object PathFinder {
  val OPERATIONAL = "state-operational"
  val INIT_SYNC = "state-init-sync"
  val CMDResync = "cmd-resync"
  val CMDLoad = "cmd-load"
}

abstract class PathFinder(store: NetworkDataStore, val routerConf: RouterConf) extends StateMachine[Data] { me =>
  implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def process(changeMessage: Any): Unit = scala.concurrent.Future(me doProcess changeMessage)

  become(freshData = Data(SortedMap.empty, MilliSatoshi(5000L), DirectedGraph.apply), freshState = OPERATIONAL)
  RxUtils.initDelay(RxUtils.ioQueue.map(_ => me process CMDResync), getLastResyncStamp, 1000L * 3600 * 24).subscribe(none)
  me process CMDLoad

  def getLastResyncStamp: Long
  def updateLastResyncStamp(stamp: Long): Unit
  def getExtraNodes: NodeAnnouncements
  def getChainTip: Long

  def doProcess(change: Any): Unit = (change, state) match {
    case (sender: CanBeRepliedTo, routeRequest: RouteRequest) \ OPERATIONAL =>
      val dataWithAugmentedGraph = data.modify(_.graph).using(_ addEdge routeRequest.localEdge)
      val routesTry = RouteCalculation.handleRouteRequest(dataWithAugmentedGraph, routerConf, getChainTip, routeRequest)
      sender process routesTry

    case CMDResync \ OPERATIONAL =>
      if (data.channels.isEmpty) become(data, INIT_SYNC)
      new SyncMaster(getExtraNodes, store.listExcludedChannels(System.currentTimeMillis), data, routerConf) {
        def onTotalSyncComplete(syncMasterGossip: SyncMasterGossipData): Unit = me process syncMasterGossip
        def onChunkSyncComplete(pureRoutingData: PureRoutingData): Unit = me process pureRoutingData
      }

    case (CMDLoad, OPERATIONAL | INIT_SYNC) =>
      // Initial graph load from local data
      loadGraph

    case (pure: PureRoutingData, OPERATIONAL | INIT_SYNC) =>
      // Run in PathFinder thread to not overload SyncMaster thread
      store.processPureData(pure)

    case (sync: SyncMasterGossipData, OPERATIONAL | INIT_SYNC) =>
      // On sync done we might have shortIds which no peer is aware of
      val probablyClosedShortIds = loadGraph diff sync.provenShortIds
      store.removeMissingChannels(probablyClosedShortIds)
      updateLastResyncStamp(System.currentTimeMillis)

    // We always accept and store disabled channels:
    // - to reduce subsequent sync traffic if channel remains disabled
    // - to account for the case when channel becomes ebabled but we don't know
    // If we hit an updated channel while routing we save it to db and update in-memory graph
    // If disabled channel stays disabled for a long time it will eventually be pruned from db

    case (cu: ChannelUpdate, OPERATIONAL) if cu.htlcMaximumMsat.isDefined =>
      val saveUpdateToDb = data.channels.contains(cu.shortChannelId) && SyncMaster.isFresh(cu, data)
      val chanDesc = Router.getDesc(cu, announcement = data.channels(cu.shortChannelId).ann)
      val isEnabled = Announcements.isEnabled(cu.channelFlags)

      // This update may belong to private channel, in this case we only update runtime graph, but not db
      val g1 = if (isEnabled) data.graph.addEdge(chanDesc, cu) else data.graph.removeEdge(chanDesc)
      if (saveUpdateToDb) store.addChannelUpdate(cu)
      become(data.copy(graph = g1), OPERATIONAL)

    case (edge: GraphEdge, OPERATIONAL) =>
      // We add remote private channels to graph as if they are normal
      become(data.modify(_.graph).using(_ addEdge edge), OPERATIONAL)

    case _ =>
  }

  def loadGraph: ShortChanIdSet = {
    val Tuple3(currentChannelsMap, localShortIds, avgFeeBase) = store.getRoutingData
    val data = Data(currentChannelsMap, avgFeeBase, DirectedGraph makeGraph currentChannelsMap)
    become(data, OPERATIONAL)
    localShortIds
  }
}
