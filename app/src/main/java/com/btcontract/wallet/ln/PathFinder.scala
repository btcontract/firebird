package com.btcontract.wallet.ln

import com.btcontract.wallet.ln.PathFinder._
import com.btcontract.wallet.ln.crypto.Tools._
import fr.acinq.eclair.wire.{ChannelUpdate, NodeAnnouncement}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import com.btcontract.wallet.ln.crypto.{CanBeRepliedTo, StateMachine}
import fr.acinq.eclair.router.{Announcements, RouteCalculation, Router}
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Router.{ChannelDesc, Data, PublicChannel, RouteRequest, RouterConf}
import java.util.concurrent.Executors
import fr.acinq.eclair.ShortChannelId


object PathFinder {
  val WAITING = "state-waiting"
  val INIT_SYNC = "state-init-sync"
  val OPERATIONAL = "state-operational"

  val NotifyRejected = "notify-rejected"
  val NotifyInitSync = "notify-init-sync"
  val NotifyOperational = "notify-operational"
  val CMDLoadGraph = "cmd-load-graph"
  val CMDResync = "cmd-resync"
}

abstract class PathFinder(store: NetworkDataStore, val routerConf: RouterConf) extends StateMachine[Data] { me =>
  implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def process(changeMessage: Any): Unit = scala.concurrent.Future(me doProcess changeMessage)
  var listeners: Set[CanBeRepliedTo] = Set.empty

  // We don't load routing data on every startup but when user (or system) actually needs it
  become(freshData = Data(channels = Map.empty, extraEdges = Map.empty, graph = DirectedGraph.apply), WAITING)
  RxUtils.initDelay(RxUtils.ioQueue.map(_ => me process CMDResync), getLastResyncStamp, 1000L * 3600 * 48).subscribe(none)

  def getLastResyncStamp: Long
  def updateLastResyncStamp(stamp: Long): Unit
  def getExtraNodes: Set[NodeAnnouncement]
  def getChainTip: Long

  def doProcess(change: Any): Unit = (change, state) match {
    // In OPERATIONAL state we instruct graph to search through the single pre-selected local channel by inserting desc and making it a source, in ALL OTHER states we send a rejection back to sender
    case (sender: CanBeRepliedTo, request: RouteRequest) \ OPERATIONAL => sender process RouteCalculation.handleRouteRequest(data.graph addEdge request.localEdge, routerConf, getChainTip, request)
    case (sender: CanBeRepliedTo, _: RouteRequest) \ _ => sender process NotifyRejected

    case CMDResync \ OPERATIONAL =>
      if (System.currentTimeMillis - getLastResyncStamp > 1000L * 3600 * 24 * 28) {
        // App has not been opened during 4 weeks, notify that sync will take some time
        listeners.foreach(_ process NotifyInitSync)
        become(data, INIT_SYNC)
      }

      new SyncMaster(getExtraNodes, store.listExcludedChannels, data, from = 0, routerConf) { self =>
        def onChunkSyncComplete(pureRoutingData: PureRoutingData): Unit = me process pureRoutingData
        def onTotalSyncComplete: Unit = me process self
      }

    case CMDResync \ WAITING =>
      // We need a loaded routing data to sync properly
      // load that data before proceeding if it's absent
      me process CMDLoadGraph
      me process CMDResync

    case CMDLoadGraph \ WAITING =>
      val channelMap: Map[ShortChannelId, PublicChannel] = store.getRoutingData
      val searchGraph = DirectedGraph.makeGraph(channelMap).addEdges(data.extraEdges.values)
      become(freshData = Data(channelMap, data.extraEdges, searchGraph), OPERATIONAL)
      listeners.foreach(_ process NotifyOperational)

    case (pure: PureRoutingData, OPERATIONAL | INIT_SYNC) =>
      // Run in PathFinder thread to not overload SyncMaster thread
      store.processPureData(pure)

    case (sync: SyncMaster, OPERATIONAL | INIT_SYNC) =>
      val channelMap: Map[ShortChannelId, PublicChannel] = store.getRoutingData
      val ghostShortIdsPeersKnowNothingAbout = channelMap.keySet.diff(sync.provenShortIds)
      val channelMap1 = channelMap -- ghostShortIdsPeersKnowNothingAbout

      val searchGraph = DirectedGraph.makeGraph(channelMap1).addEdges(data.extraEdges.values)
      become(freshData = Data(channelMap1, data.extraEdges, searchGraph), OPERATIONAL)
      store.removeGhostChannels(ghostShortIdsPeersKnowNothingAbout)
      updateLastResyncStamp(System.currentTimeMillis)
      listeners.foreach(_ process NotifyOperational)

    // We always accept and store disabled channels:
    // - to reduce subsequent sync traffic if channel remains disabled
    // - to account for the case when channel becomes enabled but we don't know
    // If we hit an updated channel while routing we save it to db and update in-memory graph
    // If disabled channel stays disabled for a long time it will be pruned by peers and then us

    case (cu: ChannelUpdate, OPERATIONAL)
      if data.channels.contains(cu.shortChannelId) =>
      val newUpdateIsOlder = data.channels(cu.shortChannelId).getChannelUpdateSameSideAs(cu).exists(_.timestamp >= cu.timestamp)
      val data1 = resolveKnownDesc(Router.getDesc(cu, data.channels(cu.shortChannelId).ann), cu, newUpdateIsOlder, isPublic = true)
      become(data1, OPERATIONAL)

    case (cu: ChannelUpdate, OPERATIONAL)
      if data.extraEdges.contains(cu.shortChannelId) =>
      val chanDesc = data.extraEdges(cu.shortChannelId).desc
      // Fake updates do not provide timestamp so any refresh should be newer
      val data1 = resolveKnownDesc(chanDesc, cu, isOld = false, isPublic = false)
      become(data1, OPERATIONAL)

    case (edge: GraphEdge, WAITING | OPERATIONAL | INIT_SYNC) if !data.channels.contains(edge.desc.shortChannelId) =>
      // We add assisted routes to graph as if they are normal channels, also rememeber them to refill later if graph gets reloaded
      val data1 = data.copy(extraEdges = data.extraEdges + (edge.update.shortChannelId -> edge), graph = data.graph addEdge edge)
      become(data1, state)

    case _ =>
  }

  def resolveKnownDesc(desc: ChannelDesc, cu: ChannelUpdate, isOld: Boolean, isPublic: Boolean): Data = {
    // Resolves channel updates which we obtain from node errors while trying to route payments
    val isEnabled = Announcements.isEnabled(cu.channelFlags)
    val edge = GraphEdge(desc, cu)

    if (cu.htlcMaximumMsat.isEmpty) {
      // Will be queried again on next sync and will likely get excluded
      store.removeChannelUpdateByPosition(cu.shortChannelId, cu.position)
      data.copy(graph = data.graph removeEdge edge.desc)
    } else if (isOld) {
      // We have a newer one or this one is stale
      // retain db record since we have a more recent copy
      data.copy(graph = data.graph removeEdge edge.desc)
    } else if (isPublic && isEnabled) {
      store.addChannelUpdateByPosition(cu)
      data.copy(graph = data.graph addEdge edge)
    } else if (isPublic) {
      // Save in db because it's fresh
      store.addChannelUpdateByPosition(cu)
      // But remove from runtime graph because disabled
      data.copy(graph = data.graph removeEdge edge.desc)
    } else if (isEnabled) {
      // Good new private update, store in runtime map also
      val extraEdges1 = data.extraEdges + (cu.shortChannelId -> edge)
      data.copy(graph = data.graph addEdge edge, extraEdges = extraEdges1)
    } else {
      // Remove from runtime graph because disabled
      data.copy(graph = data.graph removeEdge edge.desc)
    }
  }
}
