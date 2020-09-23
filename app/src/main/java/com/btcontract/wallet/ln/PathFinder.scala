package com.btcontract.wallet.ln

import com.btcontract.wallet.ln.PathFinder._
import com.btcontract.wallet.ln.crypto.Tools._
import fr.acinq.eclair.router.{Announcements, Router}
import fr.acinq.eclair.wire.{ChannelUpdate, NodeAnnouncement}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import com.btcontract.wallet.ln.crypto.{CanBeRepliedTo, StateMachine}
import fr.acinq.eclair.router.Router.{Data, RouteRequest, RouterConf}
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.RouteCalculation.handleRouteRequest
import java.util.concurrent.Executors


object PathFinder {
  val WAITING = "state-waiting"
  val INIT_SYNC = "state-init-sync"
  val OPERATIONAL = "state-operational"

  val NotifyRejected = "notify-rejected"
  val NotifyOperational = "notify-operational"
  val CMDLoadGraph = "cmd-load-graph"
  val CMDResync = "cmd-resync"
}

abstract class PathFinder(normalStore: NetworkDataStore, hostedStore: NetworkDataStore, val routerConf: RouterConf) extends StateMachine[Data] { me =>
  implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def process(changeMessage: Any): Unit = scala.concurrent.Future(me doProcess changeMessage)
  var listeners: Set[CanBeRepliedTo] = Set.empty

  // We don't load routing data on every startup but when user (or system) actually needs it
  become(Data(channels = Map.empty, hostedChannels = Map.empty, extraEdges = Map.empty, DirectedGraph.apply), WAITING)
  RxUtils.initDelay(RxUtils.ioQueue.map(_ => me process CMDResync), getLastResyncStamp, 1000L * 3600 * 24 * 2).subscribe(none)

  def getLastResyncStamp: Long
  def updateLastResyncStamp(stamp: Long): Unit
  def getExtraNodes: Set[NodeAnnouncement]

  def doProcess(change: Any): Unit = (change, state) match {
    case (sender: CanBeRepliedTo, request: RouteRequest) \ OPERATIONAL =>
      // In OPERATIONAL state we instruct graph to search through the single pre-selected local channel
      // it is safe to not check for existance becase base graph never has our private outgoing edges
      val graph1 = data.graph.addEdge(edge = request.localEdge, checkIfContains = false)
      sender process handleRouteRequest(graph1, routerConf, request)

    case (sender: CanBeRepliedTo, _: RouteRequest) \ _ =>
      // in ALL OTHER states we send a rejection back to sender
      sender process NotifyRejected

    case CMDResync \ OPERATIONAL =>
      // App has not been opened during ~month, notify that sync will take some time
      if (System.currentTimeMillis - getLastResyncStamp > 1000L * 3600 * 24 * 30) become(data, INIT_SYNC)
      new SyncMaster(getExtraNodes, normalStore.listExcludedChannels, data, from = 550000, routerConf) { self =>
        def onChunkSyncComplete(pureRoutingData: PureRoutingData): Unit = me process pureRoutingData
        def onTotalSyncComplete: Unit = me process self
      }

    case CMDResync \ WAITING =>
      // We need a loaded routing data to sync properly
      // load that data before proceeding if it's absent
      me process CMDLoadGraph
      me process CMDResync

    case CMDLoadGraph \ WAITING =>
      val shortId2ChannelMap = normalStore.getRoutingData
      val searchGraph = DirectedGraph.makeGraph(shortId2ChannelMap).addEdges(data.extraEdges.values)
      become(Data(shortId2ChannelMap, data.hostedChannels, data.extraEdges, searchGraph), OPERATIONAL)
      listeners.foreach(_ process NotifyOperational)

    case (pure: PureRoutingData, OPERATIONAL | INIT_SYNC) =>
      // Run in PathFinder thread to not overload SyncMaster thread
      normalStore.processPureData(pure)

    case (sync: SyncMaster, OPERATIONAL | INIT_SYNC) =>
      val shortId2ChannelMap = normalStore.getRoutingData
      val ghostShortIdsPeersKnowNothingAbout = shortId2ChannelMap.keySet.diff(sync.provenShortIds)
      val channelMap1 = shortId2ChannelMap -- ghostShortIdsPeersKnowNothingAbout

      val searchGraph = DirectedGraph.makeGraph(channelMap1).addEdges(data.extraEdges.values)
      become(Data(channelMap1, data.hostedChannels, data.extraEdges, searchGraph), OPERATIONAL)
      normalStore.removeGhostChannels(ghostShortIdsPeersKnowNothingAbout)
      updateLastResyncStamp(System.currentTimeMillis)
      listeners.foreach(_ process NotifyOperational)

    // We always accept and store disabled channels:
    // - to reduce subsequent sync traffic if channel remains disabled
    // - to account for the case when channel becomes enabled but we don't know
    // If we hit an updated channel while routing we save it to db and update in-memory graph
    // If disabled channel stays disabled for a long time it will be pruned by peers and then by us

    case (cu: ChannelUpdate, OPERATIONAL) if data.channels.contains(cu.shortChannelId) =>
      val currentUpdateOpt = data.channels(cu.shortChannelId).getChannelUpdateSameSideAs(cu)
      val edge = GraphEdge(Router.getDesc(cu, data.channels(cu.shortChannelId).ann), cu)
      val newUpdateIsOlder = currentUpdateOpt.exists(_.timestamp >= cu.timestamp)
      val data1 = resolveKnownDesc(edge, Some(normalStore), newUpdateIsOlder)
      // Make new update score the same as existing update score
      currentUpdateOpt.foreach(cu0 => cu.score = cu0.score)
      become(data1, OPERATIONAL)

    case (cu: ChannelUpdate, OPERATIONAL) if data.hostedChannels.contains(cu.shortChannelId) =>
      val currentUpdateOpt = data.hostedChannels(cu.shortChannelId).getChannelUpdateSameSideAs(cu)
      val edge = GraphEdge(Router.getDesc(cu, data.hostedChannels(cu.shortChannelId).ann), cu)
      val newUpdateIsOlder = currentUpdateOpt.exists(_.timestamp >= cu.timestamp)
      val data1 = resolveKnownDesc(edge, Some(hostedStore), newUpdateIsOlder)
      become(data1, OPERATIONAL)

    case (cu: ChannelUpdate, OPERATIONAL) if data.extraEdges.contains(cu.shortChannelId) =>
      // Fake updates do not provide timestamp so any real refresh we are getting here should be newer
      val data1 = resolveKnownDesc(GraphEdge(data.extraEdges(cu.shortChannelId).desc, cu), None, isOld = false)
      become(data1, OPERATIONAL)

    case (edge: GraphEdge, WAITING | OPERATIONAL | INIT_SYNC) if !data.channels.contains(edge.desc.shortChannelId) =>
      // We add assisted routes to graph as if they are normal channels, also rememeber them to refill later if graph gets reloaded
      // these edges will be private most of the time, but they may be public and we may have them already so checkIfContains == true
      val data1 = data.copy(extraEdges = data.extraEdges + (edge.update.shortChannelId -> edge), graph = data.graph addEdge edge)
      become(data1, state)

    case _ =>
  }

  // Resolves channel updates which we obtain from node errors while trying to route payments
  // store is optional to make sure private normal/hosted channel updates never make it to our database
  def resolveKnownDesc(edge: GraphEdge, storeOpt: Option[NetworkDataStore], isOld: Boolean): Data = {
    val isEnabled = Announcements.isEnabled(edge.update.channelFlags)

    storeOpt match {
      case Some(store) if edge.update.htlcMaximumMsat.isEmpty =>
        // Will be queried on next sync and most likely excluded
        store.removeChannelUpdate(edge.update.shortChannelId)
        data.copy(graph = data.graph removeEdge edge.desc)

      case _ if isOld =>
        // We have a newer one or this one is stale
        // retain db record since we have a more recent copy
        data.copy(graph = data.graph removeEdge edge.desc)

      case Some(store) if isEnabled =>
        // This is a legitimate public update
        store.addChannelUpdateByPosition(edge.update)
        data.copy(graph = data.graph addEdge edge)

      case Some(store) =>
        // Save in db because update is fresh
        store.addChannelUpdateByPosition(edge.update)
        // But remove from runtime graph because it's disabled
        data.copy(graph = data.graph removeEdge edge.desc)

      case None if isEnabled =>
        // This is a legitimate private update
        val extraEdges1 = data.extraEdges + (edge.update.shortChannelId -> edge)
        data.copy(graph = data.graph addEdge edge, extraEdges = extraEdges1)

      case None =>
        // Disabled private update, remove from graph
        data.copy(graph = data.graph removeEdge edge.desc)
    }
  }
}
