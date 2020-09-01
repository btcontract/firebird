package com.btcontract.wallet.ln

import fr.acinq.eclair.wire._
import scala.concurrent.duration._
import com.btcontract.wallet.ln.SyncMaster._
import com.btcontract.wallet.ln.crypto.Tools._

import scodec.bits.ByteVector
import scala.collection.mutable
import java.util.concurrent.Executors
import fr.acinq.bitcoin.Crypto.PublicKey
import com.btcontract.wallet.ln.crypto.StateMachine
import com.btcontract.wallet.ln.crypto.Noise.KeyPair

import fr.acinq.eclair.router.{StaleChannels, Sync}
import fr.acinq.eclair.{MilliSatoshi, ShortChannelId}
import fr.acinq.eclair.router.Router.{Data, RouterConf}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}


object SyncMaster {
  val WAITING = "state-waiting"
  val SHORT_ID_SYNC = "state-short-ids"
  val GOSSIP_SYNC = "state-gossip-sync"
  val SHUT_DOWN = "state-shut-down"

  val CMDAddSync = "cmd-add-sync"
  val CMDGetGossip = "cmd-get-gossip"
  val CMDShutdown = "cmd-shut-down"

  type ConifrmedBySet = Set[PublicKey]
  type ShortChanIdSet = Set[ShortChannelId]
  type NodeAnnouncements = List[NodeAnnouncement]

  val blw: NodeAnnouncement = mkNodeAnnouncement(PublicKey(ByteVector fromValidHex "03144fcc73cea41a002b2865f98190ab90e4ff58a2ce24d3870f5079081e42922d"), NodeAddress.unresolved(9735, host = 5, 9, 83, 143), "BLW")
  val cheese: NodeAnnouncement = mkNodeAnnouncement(PublicKey(ByteVector fromValidHex "0276e09a267592e7451a939c932cf685f0754de382a3ca85d2fb3a864d4c365ad5"), NodeAddress.unresolved(9735, host = 94, 177, 171, 73), "Cheese")
  val bitrefill: NodeAnnouncement = mkNodeAnnouncement(PublicKey(ByteVector fromValidHex "0254ff808f53b2f8c45e74b70430f336c6c76ba2f4af289f48d6086ae6e60462d3"), NodeAddress.unresolved(9735, host = 52, 30, 63, 2), "Bitrefill")
  val acinq: NodeAnnouncement = mkNodeAnnouncement(PublicKey(ByteVector fromValidHex "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"), NodeAddress.unresolved(9735, host = 34, 239, 230, 56), "ACINQ")
  val syncNodeVec: NodeAnnouncements = List(blw, cheese, bitrefill, acinq)
  val minCapacity = MilliSatoshi(1000000000L)

  def isFresh(cu: ChannelUpdate, routerData: Data): Boolean = {
    val oldCopyOpt = routerData.channels(cu.shortChannelId).getChannelUpdateSameSideAs(cu)
    oldCopyOpt.forall(_.timestamp < cu.timestamp) || !StaleChannels.isStale(cu)
  }
}


sealed trait SyncWorkerData
case class SyncWorkerShortIdsData(ranges: List[ReplyChannelRange] = Nil) extends SyncWorkerData
case class SyncWorkerGossipData(routerData: Data, queries: Seq[QueryShortChannelIds], provenShortIds: ShortChanIdSet, currentExcluded: ShortChanIdSet,
                                updates: Set[ChannelUpdate] = Set.empty[ChannelUpdate], chanAnnounces: Set[ChannelAnnouncement] = Set.empty[ChannelAnnouncement],
                                freshExcluded: ShortChanIdSet = Set.empty) extends SyncWorkerData {

  def restarted: SyncWorkerGossipData = copy(queries = queries.tail, updates = Set.empty, chanAnnounces = Set.empty, freshExcluded = Set.empty)
  def notExcludedAndProven(shortId: ShortChannelId): Boolean = !currentExcluded.contains(shortId) && provenShortIds.contains(shortId)
}

case class CMDGossipComplete(sync: SyncWorker)
case class CMDChunkComplete(sync: SyncWorker, data: SyncWorkerGossipData)
case class CMDShortIdComplete(sync: SyncWorker, data: SyncWorkerShortIdsData)

case class SyncWorker(master: SyncMaster, keyPair: KeyPair, ann: NodeAnnouncement) extends StateMachine[SyncWorkerData] { me =>
  implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def process(changeMessage: Any): Unit = scala.concurrent.Future(me doProcess changeMessage)

  val pkap = PublicKeyAndPair(ann.nodeId, keyPair)
  val listener: ConnectionListener = new ConnectionListener {
    override def onOperational(worker: CommsTower.Worker): Unit = process(worker)
    override def onMessage(worker: CommsTower.Worker, msg: LightningMessage): Unit = process(msg)

    override def onDisconnect(worker: CommsTower.Worker): Unit = {
      // Remove this listener and remove an object itself from master
      CommsTower.listeners(worker.pkap) -= listener
      master process me
    }
  }

  become(null, WAITING)
  // Connect and start listening immediately
  CommsTower.listen(Set(listener), pkap, ann)

  def doProcess(change: Any): Unit = (change, data, state) match {
    case (data1: SyncWorkerShortIdsData, null, WAITING) => become(data1, SHORT_ID_SYNC)
    case (data1: SyncWorkerGossipData, null, WAITING | SHORT_ID_SYNC) => become(data1, GOSSIP_SYNC)

    case (worker: CommsTower.Worker, _: SyncWorkerShortIdsData, SHORT_ID_SYNC) =>
      val tlv: QueryChannelRangeTlv = QueryChannelRangeTlv.QueryFlags(QueryChannelRangeTlv.QueryFlags.WANT_TIMESTAMPS)
      val query = QueryChannelRange(LNParams.chainHash, firstBlockNum = 0L, numberOfBlocks = Long.MaxValue, TlvStream apply tlv)
      worker.handler process query

    case (reply: ReplyChannelRange, data1: SyncWorkerShortIdsData, SHORT_ID_SYNC) =>
      val updatedData: SyncWorkerShortIdsData = data1.copy(ranges = reply +: data1.ranges)
      if (reply.numberOfBlocks < Long.MaxValue) become(updatedData, SHORT_ID_SYNC)
      else master process CMDShortIdComplete(me, updatedData)

    // GOSSIP_SYNC

    case (_: CommsTower.Worker, _: SyncWorkerGossipData, GOSSIP_SYNC) => me process CMDGetGossip
    case (CMDGetGossip, data1: SyncWorkerGossipData, GOSSIP_SYNC) if data1.queries.isEmpty => master process CMDGossipComplete(me)
    case (CMDGetGossip, data1: SyncWorkerGossipData, GOSSIP_SYNC) => CommsTower.workers.get(pkap).foreach(_.handler process data1.queries.head)
    case (msg: ChannelAnnouncement, data1: SyncWorkerGossipData, GOSSIP_SYNC) if data1.notExcludedAndProven(msg.shortChannelId) => become(data1.copy(chanAnnounces = data1.chanAnnounces + msg), GOSSIP_SYNC)
    case (msg: ChannelUpdate, data1: SyncWorkerGossipData, GOSSIP_SYNC) if msg.htlcMaximumMsat.forall(_ < minCapacity) => become(data1.copy(freshExcluded = data1.freshExcluded + msg.shortChannelId), GOSSIP_SYNC)
    case (msg: ChannelUpdate, data1: SyncWorkerGossipData, GOSSIP_SYNC) if data1.notExcludedAndProven(msg.shortChannelId) && isFresh(msg, data1.routerData) => become(data1.copy(updates = data1.updates + msg), GOSSIP_SYNC)

    case (_: ReplyShortChannelIdsEnd, data1: SyncWorkerGossipData, GOSSIP_SYNC) =>
      // We have completed current chunk, inform master and either continue or complete
      master process CMDChunkComplete(me, data1)
      become(data1.restarted, GOSSIP_SYNC)
      me process CMDGetGossip

    case (CMDShutdown, data1, _) =>
      CommsTower.listeners(pkap) -= listener
      CommsTower.workers.get(pkap).foreach(_.disconnect)
      // Stop reacting to disconnect, processing commands
      become(data1, SHUT_DOWN)

    case _ =>
  }
}


trait SyncMasterData { val activeSyncs: List[SyncWorker] = Nil }
case class SyncMasterShortIdData(override val activeSyncs: List[SyncWorker], collectedRanges: Map[PublicKey, SyncWorkerShortIdsData], maxSyncs: Int = 3) extends SyncMasterData
case class SyncMasterGossipData(override val activeSyncs: List[SyncWorker], provenShortIds: ShortChanIdSet, queries: Seq[QueryShortChannelIds], maxSyncs: Int) extends SyncMasterData {
  lazy val threshold: Int = maxSyncs / 2
}

case class PureRoutingData(announces: Set[ChannelAnnouncement], updates: Set[ChannelUpdate], excluded: ShortChanIdSet)
abstract class SyncMaster(extraNodes: NodeAnnouncements, excludedShortIds: ShortChanIdSet, routerData: Data, routerConf: RouterConf) extends StateMachine[SyncMasterData] { me =>
  private[this] val confirmedChanAnnounces: mutable.Map[ChannelAnnouncement, ConifrmedBySet] = mutable.Map.empty withDefaultValue Set.empty[PublicKey]
  private[this] val confirmedChanUpdates: mutable.Map[ChannelUpdate, ConifrmedBySet] = mutable.Map.empty withDefaultValue Set.empty[PublicKey]
  private[this] var freshExcludedShortIds: Set[ShortChannelId] = Set.empty[ShortChannelId]

  def onTotalSyncComplete(sync: SyncMasterGossipData): Unit
  def onChunkSyncComplete(pure: PureRoutingData): Unit

  implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def process(changeMessage: Any): Unit = scala.concurrent.Future(me doProcess changeMessage)
  become(SyncMasterShortIdData(Nil, Map.empty), SHORT_ID_SYNC)
  me process CMDAddSync

  def doProcess(change: Any): Unit = (change, data, state) match {
    case (CMDAddSync, data1: SyncMasterShortIdData, SHORT_ID_SYNC) if data1.activeSyncs.size < data1.maxSyncs =>
      // Turns out we don't have enough workers, create one with unused remote nodeId and track its progress
      val newSyncWorker = getNewSync(data1)

      // Worker is connecting now, tell it what to do once connection is established
      become(data1.copy(activeSyncs = newSyncWorker :: data1.activeSyncs), SHORT_ID_SYNC)
      newSyncWorker process SyncWorkerShortIdsData(ranges = Nil)
      me process CMDAddSync

    case (sync: SyncWorker, SyncMasterShortIdData(activeSyncs, collectedRanges, maxSyncs), SHORT_ID_SYNC) =>
      val data1 = SyncMasterShortIdData(activeSyncs diff sync :: Nil, collectedRanges - sync.pkap.pk, maxSyncs)
      // Sync has disconnected, stop tracking it and try to connect a new sync with delay
      become(data1, SHORT_ID_SYNC)
      delayedAddSync

    case (CMDShortIdComplete(sync, ranges), SyncMasterShortIdData(activeSyncs, collectedRanges, maxSyncs), SHORT_ID_SYNC) =>
      val data1 = SyncMasterShortIdData(activeSyncs, collectedRanges + Tuple2(sync.pkap.pk, ranges), maxSyncs)
      become(data1, SHORT_ID_SYNC)

      if (data1.collectedRanges.size == maxSyncs) {
        // We have collected enough channel ranges for gossip
        val bestRange = data1.collectedRanges.values.maxBy(_.ranges.size)
        val shortIdsPerSync = data1.collectedRanges.values.map(allShortIds).toList
        val provenShortIds = getMajorityConfirmedShortIds(shortIdsPerSync:_*)
        val queries = bestRange.ranges flatMap reply2Query(provenShortIds)

        // Transfer every worker into gossip syncing state
        val initialGossipData = SyncWorkerGossipData(routerData, queries, provenShortIds, excludedShortIds)
        become(SyncMasterGossipData(activeSyncs, provenShortIds, queries, maxSyncs), GOSSIP_SYNC)
        for (currentSync <- activeSyncs) currentSync process initialGossipData
        for (currentSync <- activeSyncs) currentSync process CMDGetGossip
      }

    // GOSSIP_SYNC

    case (CMDAddSync, data1: SyncMasterGossipData, GOSSIP_SYNC) if data1.activeSyncs.size < data1.maxSyncs =>
      // On creating new peer we nullify all progress on queries and make new peer sync gossip from start again
      val gossipData = SyncWorkerGossipData(routerData, data1.queries, data1.provenShortIds, excludedShortIds)
      // Turns out we don't have enough workers, create one with unused remote nodeId and track it
      val newSyncWorker = getNewSync(data1)

      // Worker is connecting now, tell it what to do once connection is established
      become(data1.copy(activeSyncs = newSyncWorker :: data1.activeSyncs), GOSSIP_SYNC)
      newSyncWorker process gossipData
      me process CMDAddSync

    case (sync: SyncWorker, SyncMasterGossipData(activeSyncs, provenShortIds, queries, maxSyncs), GOSSIP_SYNC) =>
      val data1 = SyncMasterGossipData(activeSyncs diff sync :: Nil, provenShortIds, queries, maxSyncs)
      for (announce <- confirmedChanAnnounces.keys) confirmedChanAnnounces(announce) -= sync.pkap.pk
      for (update <- confirmedChanUpdates.keys) confirmedChanUpdates(update) -= sync.pkap.pk
      // Sync has disconnected, stop tracking it and try to connect a new sync with delay
      become(data1, GOSSIP_SYNC)
      delayedAddSync

    case (CMDChunkComplete(sync, data1), gossip: SyncMasterGossipData, GOSSIP_SYNC) =>
      // One of syncs has completed a gossip chunk, update and see if threshold is reached
      for (announce <- data1.chanAnnounces) confirmedChanAnnounces(announce) += sync.pkap.pk
      for (update <- data1.updates) confirmedChanUpdates(update) += sync.pkap.pk
      freshExcludedShortIds ++= data1.freshExcluded

      val goodAnnounces = confirmedChanAnnounces.filter { case _ \ confirmedByNodes => confirmedByNodes.size > gossip.threshold }.keys.toSet
      val goodUpdates = confirmedChanUpdates.filter { case _ \ confirmedByNodes => confirmedByNodes.size > gossip.threshold }.keys.toSet
      // It can happen that one peer says an update has low balance so we exclude it, but other two peers provide a good balance data
      // so we need to make sure such a good update won't get into excluded db or else we won't be able to get updates for it
      freshExcludedShortIds --= goodAnnounces.map(_.shortChannelId) ++ goodUpdates.map(_.shortChannelId)

      if (goodAnnounces.nonEmpty || goodUpdates.nonEmpty) {
        // Notify whoever is listening and free resources by removing useless data
        val pure = PureRoutingData(goodAnnounces, goodUpdates, freshExcludedShortIds)
        confirmedChanAnnounces --= goodAnnounces
        confirmedChanUpdates --= goodUpdates
        freshExcludedShortIds = Set.empty
        onChunkSyncComplete(pure)
      }

    case (CMDGossipComplete(sync), data1: SyncMasterGossipData, GOSSIP_SYNC) =>
      val updatedData = data1.copy(activeSyncs = data1.activeSyncs diff sync :: Nil)
      if (updatedData.activeSyncs.isEmpty) shutDown(updatedData)
      else become(updatedData, GOSSIP_SYNC)
      sync process CMDShutdown

    case _ =>
  }

  def shutDown(data1: SyncMasterGossipData): Unit = {
    // Free up the rest of resources and stop reacting
    onTotalSyncComplete(data1)
    become(null, SHUT_DOWN)
  }

  def delayedAddSync: Unit = RxUtils.ioQueue.delay(2.seconds).map(_ => me process CMDAddSync).foreach(identity)
  def getRandomNode(augmentedNodes: NodeAnnouncements): NodeAnnouncement = scala.util.Random.shuffle(augmentedNodes).head
  def allShortIds(sync: SyncWorkerShortIdsData): List[ShortChannelId] = sync.ranges.flatMap(_.shortChannelIds.array)

  def getNewSync(data1: SyncMasterData): SyncWorker = {
    val usedAnns = for (sync <- data1.activeSyncs) yield sync.ann
    val newAnn = getRandomNode(extraNodes ::: syncNodeVec diff usedAnns)
    SyncWorker(me, randomKeyPair, newAnn)
  }

  def getMajorityConfirmedShortIds(lists: List[ShortChannelId] *): ShortChanIdSet = {
    val acc \ threshold = (mutable.Map.empty[ShortChannelId, Int] withDefaultValue 0, lists.size / 2)
    for (shortChannelIdToIncrement <- lists.flatten) acc(shortChannelIdToIncrement) += 1
    acc.collect { case shortId \ num if num > threshold => shortId }.toSet
  }

  def reply2Query(provenShortIds: ShortChanIdSet)(reply: ReplyChannelRange): Seq[QueryShortChannelIds] = {
    val shortIdsWithTimestams = (reply.shortChannelIds.array, reply.timestamps.timestamps).zipped.toList

    val shortChannelIdAndFlag = for {
      shortChannelId \ stamps <- shortIdsWithTimestams
      if provenShortIds.contains(shortChannelId) && !excludedShortIds.contains(shortChannelId)
      result <- Sync.computeShortIdAndFlag(routerData.channels, shortChannelId, stamps)
    } yield result

    val queries = for {
      chunk <- shortChannelIdAndFlag grouped routerConf.channelQueryChunkSize
      finalEncoding = if (chunk.isEmpty) EncodingType.UNCOMPRESSED else reply.shortChannelIds.encoding
      shortChannelIds = EncodedShortChannelIds(finalEncoding, for (item <- chunk) yield item.shortChannelId)
      tlv = QueryShortChannelIdsTlv.EncodedQueryFlags(finalEncoding, for (item <- chunk) yield item.flag)
    } yield QueryShortChannelIds(reply.chainHash, shortChannelIds, TlvStream apply tlv)

    queries.toList
  }
}
