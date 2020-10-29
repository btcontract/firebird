package com.btcontract.wallet.ln

import com.btcontract.wallet.ln.PathFinder._
import com.btcontract.wallet.ln.crypto.Tools._
import fr.acinq.eclair.wire.{ChannelUpdate, NodeAnnouncement}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import com.btcontract.wallet.ln.crypto.{CanBeRepliedTo, StateMachine}
import fr.acinq.eclair.router.{Announcements, ChannelUpdateExt, Router, Sync}
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Router.{Data, PublicChannel, RouteRequest, RouterConf}
import fr.acinq.eclair.router.RouteCalculation.handleRouteRequest
import com.btcontract.wallet.ln.utils.Rx
import java.util.concurrent.Executors


object PathFinder {
  val WAITING = "state-waiting"
  val OPERATIONAL = "state-operational"

  val NotifyRejected = "notify-rejected" // Pathfinder can't process a route request right now
  val NotifyOperational = "notify-operational" // Pathfinder has loaded a graph and is operational
  val CMDLoadGraph = "cmd-load-graph"
  val CMDResync = "cmd-resync"

  val RESYNC_PERIOD: Long = 1000L * 3600 * 24 * 2
}

abstract class PathFinder(normalStore: NetworkDataStore, hostedStore: NetworkDataStore, val routerConf: RouterConf) extends StateMachine[Data] { me =>
  implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def process(changeMessage: Any): Unit = scala.concurrent.Future(me doProcess changeMessage)
  var listeners: Set[CanBeRepliedTo] = Set.empty

  // We don't load routing data on every startup but when user (or system) actually needs it
  become(Data(channels = Map.empty, hostedChannels = Map.empty, extraEdges = Map.empty, DirectedGraph.apply), WAITING)
  Rx.initDelay(Rx.ioQueue, getLastTotalResyncStamp, RESYNC_PERIOD).foreach(_ => me process CMDResync)

  def getLastTotalResyncStamp: Long
  def getLastNormalResyncStamp: Long
  def updateLastTotalResyncStamp(stamp: Long): Unit
  def updateLastNormalResyncStamp(stamp: Long): Unit
  def getExtraNodes: Set[NodeAnnouncement]

  def doProcess(change: Any): Unit = (change, state) match {
    // This covers two cases: when graph is still not loaded at all and when we have a loaded empty graph (likey a first launch)
    case (sender: CanBeRepliedTo, _: RouteRequest) \ OPERATIONAL if data.channels.isEmpty => sender process NotifyRejected
    case (sender: CanBeRepliedTo, _: RouteRequest) \ WAITING => sender process NotifyRejected

    case (sender: CanBeRepliedTo, request: RouteRequest) \ OPERATIONAL =>
      // In OPERATIONAL state we instruct graph to search through the single pre-selected local channel
      // it is safe to not check for existance becase base graph never has our private outgoing edges
      val graph1 = data.graph.addEdge(edge = request.localEdge, checkIfContains = false)
      sender process handleRouteRequest(graph1, routerConf, request)

    case CMDResync \ WAITING =>
      // We need a loaded routing data to sync properly
      // load that data before proceeding if it's absent
      me process CMDLoadGraph
      me process CMDResync

    case CMDLoadGraph \ WAITING =>
      val normalShortIdToPubChan = normalStore.getRoutingData
      val hostedShortIdToPubChan = hostedStore.getRoutingData
      val searchGraph = DirectedGraph.makeGraph(normalShortIdToPubChan ++ hostedShortIdToPubChan).addEdges(data.extraEdges.values)
      become(Data(channels = normalShortIdToPubChan, hostedChannels = hostedShortIdToPubChan, data.extraEdges, searchGraph), OPERATIONAL)
      listeners.foreach(_ process NotifyOperational)

    case CMDResync \ OPERATIONAL if System.currentTimeMillis - getLastNormalResyncStamp > RESYNC_PERIOD =>
      // Last normal sync has happened too long ago, start with normal sync, then proceed with PHC sync
      new SyncMaster(getExtraNodes, normalStore.listExcludedChannels, data) { self =>
        def onChunkSyncComplete(pure: PureRoutingData): Unit = me process pure
        def onTotalSyncComplete: Unit = me process self
      }

    case CMDResync \ OPERATIONAL =>
      // Normal resync has happened recently, but PHC resync is outdated (PHC failed last time due to running out of attempts)
      // in this case we skip normal sync and start directly with PHC sync to save time and increase PHC sync success chances
      new PHCSyncMaster(getExtraNodes, data) { def onSyncComplete(pure: CompleteHostedRoutingData): Unit = me process pure }

    case (pure: CompleteHostedRoutingData, OPERATIONAL) =>
      // First, completely replace PHC data with obtained one
      hostedStore.processCompleteHostedData(pure)

      // Then reconstruct graph with new PHC data
      val hostedShortIdToPubChan = hostedStore.getRoutingData
      val searchGraph = DirectedGraph.makeGraph(data.channels ++ hostedShortIdToPubChan).addEdges(data.extraEdges.values)
      become(Data(channels = data.channels, hostedChannels = hostedShortIdToPubChan, data.extraEdges, searchGraph), OPERATIONAL)
      // And finally update a total sync checkpoint, a sync is considered fully done, there will be no new attempts for a while
      updateLastTotalResyncStamp(System.currentTimeMillis)

    case (pure: PureRoutingData, OPERATIONAL) =>
      // Run in this thread to not overload SyncMaster
      normalStore.processPureData(pure)

    case (sync: SyncMaster, OPERATIONAL) =>
      // Get rid of channels which peers know nothing about
      val normalShortIdToPubChan = normalStore.getRoutingData
      val oneSideShortIds = normalStore.listChannelsWithOneUpdate
      val ghostIds = normalShortIdToPubChan.keySet.diff(sync.provenShortIds)
      val normalShortIdToPubChan1 = normalShortIdToPubChan -- ghostIds -- oneSideShortIds
      val searchGraph = DirectedGraph.makeGraph(normalShortIdToPubChan1 ++ data.hostedChannels).addEdges(data.extraEdges.values)
      become(Data(channels = normalShortIdToPubChan1, hostedChannels = data.hostedChannels, data.extraEdges, searchGraph), OPERATIONAL)
      new PHCSyncMaster(getExtraNodes, data) { def onSyncComplete(pure: CompleteHostedRoutingData): Unit = me process pure }
      // Perform database cleaning in a different thread since it's relatively slow and we are operational now
      Rx.ioQueue.foreach(_ => normalStore.removeGhostChannels(ghostIds, oneSideShortIds), log)
      // Update normal checkpoint, if PHC sync fails this time we'll jump to it next time
      updateLastNormalResyncStamp(System.currentTimeMillis)
      listeners.foreach(_ process NotifyOperational)

    // We always accept and store disabled channels:
    // - to reduce subsequent sync traffic if channel remains disabled
    // - to account for the case when channel becomes enabled but we don't know
    // If we hit an updated channel while routing we save it to db and update in-memory graph
    // If disabled channel stays disabled for a long time it will be pruned by peers and then by us

    case (cu: ChannelUpdate, OPERATIONAL) if data.channels.contains(cu.shortChannelId) =>
      val data1 = resolve(data.channels(cu.shortChannelId), cu, normalStore)
      become(data1, OPERATIONAL)

    case (cu: ChannelUpdate, OPERATIONAL) if data.hostedChannels.contains(cu.shortChannelId) =>
      val data1 = resolve(data.hostedChannels(cu.shortChannelId), cu, hostedStore)
      become(data1, OPERATIONAL)

    case (cu: ChannelUpdate, OPERATIONAL) =>
      data.extraEdges get cu.shortChannelId foreach { edge =>
        val edge1 = edge.copy(updExt = edge.updExt withNewUpdate cu)
        val data1 = resolveKnownDesc(edge1, None, isOld = false)
        become(data1, OPERATIONAL)
      }

    case (edge: GraphEdge, WAITING | OPERATIONAL) if !data.channels.contains(edge.desc.shortChannelId) =>
      // We add assisted routes to graph as if they are normal channels, also rememeber them to refill later if graph gets reloaded
      // these edges will be private most of the time, but they may be public and we may have them already so checkIfContains == true
      val data1 = data.copy(extraEdges = data.extraEdges + (edge.updExt.update.shortChannelId -> edge), graph = data.graph addEdge edge)
      become(data1, state)

    case _ =>
  }

  // Common resover for normal/hosted public channel updates
  def resolve(pubChan: PublicChannel, newUpdate: ChannelUpdate, store: NetworkDataStore): Data = {
    val currentUpdateExtOpt: Option[ChannelUpdateExt] = pubChan.getChannelUpdateSameSideAs(newUpdate)
    val newUpdateIsOlder: Boolean = currentUpdateExtOpt.exists(_.update.timestamp >= newUpdate.timestamp)
    val newUpdateExt = currentUpdateExtOpt.map(_ withNewUpdate newUpdate) getOrElse ChannelUpdateExt(newUpdate, Sync.getChecksum(newUpdate), score = 1L, useHeuristics = false)
    resolveKnownDesc(edge = GraphEdge(desc = Router.getDesc(newUpdate, pubChan.ann), newUpdateExt), Some(store), newUpdateIsOlder)
  }

  // Resolves channel updates which we obtain from node errors while trying to route payments
  // store is optional to make sure private normal/hosted channel updates never make it to our database
  def resolveKnownDesc(edge: GraphEdge, storeOpt: Option[NetworkDataStore], isOld: Boolean): Data = {
    val isEnabled = Announcements.isEnabled(edge.updExt.update.channelFlags)

    storeOpt match {
      case Some(store) if edge.updExt.update.htlcMaximumMsat.isEmpty =>
        // Will be queried on next sync and will most likely be excluded
        store.removeChannelUpdate(edge.updExt.update.shortChannelId)
        data.copy(graph = data.graph removeEdge edge.desc)

      case _ if isOld =>
        // We have a newer one or this one is stale
        // retain db record since we have a more recent copy
        data.copy(graph = data.graph removeEdge edge.desc)

      case Some(store) if isEnabled =>
        // This is a legitimate public update, refresh everywhere
        store.addChannelUpdateByPosition(edge.updExt.update)
        data.copy(graph = data.graph addEdge edge)

      case Some(store) =>
        // Save in db because update is fresh
        store.addChannelUpdateByPosition(edge.updExt.update)
        // But remove from runtime graph because it's disabled
        data.copy(graph = data.graph removeEdge edge.desc)

      case None if isEnabled =>
        // This is a legitimate private update, don't save in DB but update graph
        val extraEdges1 = data.extraEdges + (edge.updExt.update.shortChannelId -> edge)
        data.copy(graph = data.graph addEdge edge, extraEdges = extraEdges1)

      case None =>
        // Disabled private update, remove from graph
        data.copy(graph = data.graph removeEdge edge.desc)
    }
  }
}
