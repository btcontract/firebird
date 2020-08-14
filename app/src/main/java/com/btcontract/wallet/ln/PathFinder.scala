package com.btcontract.wallet.ln

import com.softwaremill.quicklens._
import com.btcontract.wallet.ln.PathFinder._
import com.btcontract.wallet.ln.crypto.Tools._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import com.btcontract.wallet.ln.crypto.{CanBeRepliedTo, StateMachine}
import fr.acinq.eclair.router.{Announcements, RouteCalculation, Router}
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Router.{Data, RouteRequest, RouteResponse, RouterConf}
import com.btcontract.wallet.ln.SyncMaster.NodeAnnouncements
import scala.collection.immutable.SortedMap
import fr.acinq.eclair.wire.ChannelUpdate
import java.util.concurrent.Executors


object PathFinder {
  val OPERATIONAL = "state-operational"
  val INIT_SYNC = "state-init-sync"
  val CMDResync = "cmd-resync"
  val CMDReload = "cmd-reload"
}

case class CMDLoad(pruneOld: Boolean)
abstract class PathFinder(store: NetworkDataStore, val routerConf: RouterConf) extends StateMachine[Data] { me =>
  implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def process(changeMessage: Any): Unit = scala.concurrent.Future(me doProcess changeMessage)

  become(freshData = Data(channels = SortedMap.empty, graph = DirectedGraph.apply), freshState = OPERATIONAL)
  RxUtils.initDelay(RxUtils.ioQueue.map(_ => me process CMDResync), getLastResyncStamp, 1000L * 3600 * 24).subscribe(none)
  me process CMDLoad(pruneOld = false)

  def getLastResyncStamp: Long
  def updateLastResyncStamp(stamp: Long): Unit
  def getLocalGraphEdges: Iterable[GraphEdge]
  def getExtraNodes: NodeAnnouncements
  def getChainTip: Long

  def doProcess(change: Any): Unit = (change, state) match {
    case (sender: CanBeRepliedTo, request: RouteRequest) \ OPERATIONAL =>
      val dataWithAugmentedGraph = data.modify(_.graph).using(_ addEdges getLocalGraphEdges)
      val routesTry = RouteCalculation.handleRouteRequest(dataWithAugmentedGraph, routerConf, getChainTip, request)
      sender process routesTry.map(RouteResponse)

    case CMDResync \ OPERATIONAL =>
      if (data.channels.isEmpty) become(data, INIT_SYNC)
      val excluded = store.listExcludedChannels(System.currentTimeMillis)
      new SyncMaster(getExtraNodes, excluded, routerData = data, routerConf) {
        def onChunkSyncComplete(catchUp: CatchupSyncData): Unit = me process catchUp
        def onTotalSyncComplete(catchUp: CatchupSyncData): Unit = me process CMDReload
      }

    case (CMDReload, OPERATIONAL | INIT_SYNC) =>
      updateLastResyncStamp(System.currentTimeMillis)
      me process CMDLoad(pruneOld = true)

    case (CMDLoad(pruneOld), OPERATIONAL | INIT_SYNC) =>
      val currentChannelsMap = store.getCurrentRoutingMap
      val graph = DirectedGraph.makeGraph(currentChannelsMap)
      become(Data(channels = currentChannelsMap, graph), OPERATIONAL)
      if (pruneOld) store.removeStaleChannels(data, getChainTip)

    case (catchUp: CatchupSyncData, INIT_SYNC) =>
      // Run in PathFinder thread to not interfere
      // SyncMaster thread may be getting new gossip
      store.processCatchup(catchUp)

      // We accept and store disabled channels at sync phase:
      // - to reduce subsequent sync traffic if channel remains disabled
      // - to account for the case when channel becomes ebabled but we don't know
      // If we hit an updated channel while routing we save it to db and update in-memory graph
      // If disabled channel stays disabled for a long time it will eventually be pruned from db

    case (cu: ChannelUpdate, OPERATIONAL)
      if !SyncMaster.isBadChannelUpdate(cu, data)
        && cu.htlcMaximumMsat.exists(_ >= SyncMaster.minCapacity)
        && data.channels.contains(cu.shortChannelId) =>

      val isChannelEnabled = Announcements.isEnabled(cu.channelFlags)
      val desc = Router.getDesc(cu, data.channels(cu.shortChannelId).ann)
      val g1 = if (isChannelEnabled) data.graph.addEdge(desc, cu, None) else data.graph.removeEdge(desc)
      if (isChannelEnabled) store.addChannelUpdate(cu) // But do not remove disabled channels from db
      become(data.copy(graph = g1), OPERATIONAL)

    case _ =>
  }
}
