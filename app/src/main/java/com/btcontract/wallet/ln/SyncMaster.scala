package com.btcontract.wallet.ln

import fr.acinq.eclair.wire._
import scala.concurrent.duration._
import com.btcontract.wallet.ln.SyncMaster._
import QueryShortChannelIdsTlv.QueryFlagType._
import com.btcontract.wallet.ln.crypto.Tools._

import scodec.bits.ByteVector
import scala.collection.mutable
import scala.util.Random.shuffle
import fr.acinq.eclair.router.Sync
import java.util.concurrent.Executors
import fr.acinq.bitcoin.Crypto.PublicKey
import com.btcontract.wallet.ln.crypto.StateMachine
import com.btcontract.wallet.ln.crypto.Noise.KeyPair

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
  val lightning: NodeAnnouncement = mkNodeAnnouncement(PublicKey(ByteVector fromValidHex "03baa70886d9200af0ffbd3f9e18d96008331c858456b16e3a9b41e735c6208fef"), NodeAddress.unresolved(9735, host = 45, 20, 67, 1), "LIGHTNING")
  val conductor: NodeAnnouncement = mkNodeAnnouncement(PublicKey(ByteVector fromValidHex "03c436af41160a355fc1ed230a64f6a64bcbd2ae50f12171d1318f9782602be601"), NodeAddress.unresolved(9735, host = 18, 191, 89, 219), "Conductor")
  val cheese: NodeAnnouncement = mkNodeAnnouncement(PublicKey(ByteVector fromValidHex "0276e09a267592e7451a939c932cf685f0754de382a3ca85d2fb3a864d4c365ad5"), NodeAddress.unresolved(9735, host = 94, 177, 171, 73), "Cheese")
  val acinq: NodeAnnouncement = mkNodeAnnouncement(PublicKey(ByteVector fromValidHex "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"), NodeAddress.unresolved(9735, host = 34, 239, 230, 56), "ACINQ")
  val syncNodes: Set[NodeAnnouncement] = Set(lightning, cheese, acinq)
  val hostedChanNodes: Set[NodeAnnouncement] = Set(conductor, acinq)
  val minCapacity = MilliSatoshi(500000000L)
  val chunksToWait = 3
}

sealed trait SyncWorkerData

case class SyncWorkerShortIdsData(ranges: List[ReplyChannelRange] = Nil) extends SyncWorkerData {
  lazy val allShortIds: Seq[ShortChannelId] = ranges.flatMap(_.shortChannelIds.array)

  def isHolistic: Boolean = ranges forall { range =>
    val sameStampToChecksums = range.timestamps.timestamps.size == range.checksums.checksums.size
    val sameDataToTlv = range.shortChannelIds.array.size == range.timestamps.timestamps.size
    sameStampToChecksums && sameDataToTlv
  }
}

case class SyncWorkerGossipData(queries: Seq[QueryShortChannelIds],
                                updates: Set[ChannelUpdate] = Set.empty,
                                announces: Set[ChannelAnnouncement] = Set.empty,
                                excluded: Set[UpdateCore] = Set.empty) extends SyncWorkerData

case class CMDShortIdsComplete(sync: SyncWorker, data: SyncWorkerShortIdsData)
case class CMDChunkComplete(sync: SyncWorker, data: SyncWorkerGossipData)
case class CMDGossipComplete(sync: SyncWorker)

case class SyncWorker(master: SyncMaster, keyPair: KeyPair, ann: NodeAnnouncement) extends StateMachine[SyncWorkerData] { me =>
  implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def process(changeMessage: Any): Unit = scala.concurrent.Future(me doProcess changeMessage)
  val pkap = PublicKeyAndPair(ann.nodeId, keyPair)

  val listener: ConnectionListener = new ConnectionListener {
    override def onOperational(worker: CommsTower.Worker): Unit = me process worker
    override def onMessage(worker: CommsTower.Worker, msg: LightningMessage): Unit = me process msg

    override def onDisconnect(worker: CommsTower.Worker): Unit = {
      // Remove this listener and remove an object itself from master
      // This disconnect is unexpected, normal shoutdown removes listener
      CommsTower.listeners(worker.pkap) -= listener
      master process me
    }
  }

  become(null, WAITING)
  // Connect and start listening immediately
  CommsTower.listen(Set(listener), pkap, ann)

  def doProcess(change: Any): Unit = (change, data, state) match {
    case (data1: SyncWorkerShortIdsData, null, WAITING) => become(data1, SHORT_ID_SYNC)
    case (data1: SyncWorkerGossipData, _, WAITING | SHORT_ID_SYNC) => become(data1, GOSSIP_SYNC)

    case (worker: CommsTower.Worker, _: SyncWorkerShortIdsData, SHORT_ID_SYNC) =>
      val tlv = QueryChannelRangeTlv.QueryFlags(QueryChannelRangeTlv.QueryFlags.WANT_ALL)
      val query = QueryChannelRange(LNParams.chainHash, master.from, Int.MaxValue, TlvStream apply tlv)
      worker.handler process query

    case (reply: ReplyChannelRange, data1: SyncWorkerShortIdsData, SHORT_ID_SYNC) =>
      val updatedData: SyncWorkerShortIdsData = data1.copy(ranges = reply +: data1.ranges)
      if (reply.numberOfBlocks < 1000000000L) become(updatedData, SHORT_ID_SYNC)
      else master process CMDShortIdsComplete(me, updatedData)

    // GOSSIP_SYNC

    case (_: CommsTower.Worker, _: SyncWorkerGossipData, GOSSIP_SYNC) => me process CMDGetGossip
    case (CMDGetGossip, data1: SyncWorkerGossipData, GOSSIP_SYNC) if data1.queries.isEmpty => me process CMDShutdown
    case (CMDGetGossip, data1: SyncWorkerGossipData, GOSSIP_SYNC) => CommsTower.workers.get(pkap).foreach(_.handler process data1.queries.head)

    case (upd: ChannelUpdate, data1: SyncWorkerGossipData, GOSSIP_SYNC) if master.provenAndTooSmallOrNoInfo(upd) => become(data1.copy(excluded = data1.excluded + upd.core), GOSSIP_SYNC)
    case (upd: ChannelUpdate, data1: SyncWorkerGossipData, GOSSIP_SYNC) if master.provenAndNotExcluded(upd.shortChannelId) => become(data1.copy(updates = data1.updates + upd.lite), GOSSIP_SYNC)
    case (ann: ChannelAnnouncement, data1: SyncWorkerGossipData, GOSSIP_SYNC) if master.provenShortIds.contains(ann.shortChannelId) => become(data1.copy(announces = data1.announces + ann.lite), GOSSIP_SYNC)

    case (_: ReplyShortChannelIdsEnd, data1: SyncWorkerGossipData, GOSSIP_SYNC) =>
      // We have completed current chunk, inform master and either continue or complete
      become(SyncWorkerGossipData(data1.queries.tail), GOSSIP_SYNC)
      master process CMDChunkComplete(me, data1)
      me process CMDGetGossip

    case (CMDShutdown, data1, _) =>
      CommsTower.listeners(pkap) -= listener
      CommsTower.workers.get(pkap).foreach(_.disconnect)
      // Stop reacting to disconnect or processing commands
      master process CMDGossipComplete(me)
      become(data1, SHUT_DOWN)

    case _ =>
  }
}

trait SyncMasterData {
  lazy val threshold: Int = maxSyncs / 2
  def activeSyncs: Set[SyncWorker]
  def maxSyncs: Int
}

case class SyncMasterShortIdData(activeSyncs: Set[SyncWorker], collectedRanges: Map[PublicKey, SyncWorkerShortIdsData], maxSyncs: Int) extends SyncMasterData
case class SyncMasterGossipData(activeSyncs: Set[SyncWorker], chunksLeft: Int, maxSyncs: Int) extends SyncMasterData

case class UpdateConifrmState(liteUpdOpt: Option[ChannelUpdate], confirmedBy: ConifrmedBySet) {
  def add(cu: ChannelUpdate, from: PublicKey): UpdateConifrmState = copy(liteUpdOpt = Some(cu), confirmedBy = confirmedBy + from)
}

case class PureRoutingData(announces: Set[ChannelAnnouncement], updates: Set[ChannelUpdate], excluded: Set[UpdateCore] = Set.empty)
abstract class SyncMaster(extraNodes: Set[NodeAnnouncement], excluded: Set[Long], routerData: Data, val from: Int, routerConf: RouterConf) extends StateMachine[SyncMasterData] { me =>
  val confirmedChanUpdates: mutable.Map[UpdateCore, UpdateConifrmState] = mutable.Map.empty withDefaultValue UpdateConifrmState(liteUpdOpt = None, confirmedBy = Set.empty)
  val confirmedChanAnnounces: mutable.Map[ChannelAnnouncement, ConifrmedBySet] = mutable.Map.empty withDefaultValue Set.empty
  var newExcludedChanUpdates: Set[UpdateCore] = Set.empty
  var provenShortIds: Set[ShortChannelId] = Set.empty

  def provenAndNotExcluded(shortId: ShortChannelId): Boolean = provenShortIds.contains(shortId) && !excluded.contains(shortId.id)
  def provenAndTooSmallOrNoInfo(update: ChannelUpdate): Boolean = provenShortIds.contains(update.shortChannelId) && update.htlcMaximumMsat.forall(_ < minCapacity)
  def onChunkSyncComplete(pure: PureRoutingData): Unit
  def onTotalSyncComplete: Unit

  implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def process(changeMessage: Any): Unit = scala.concurrent.Future(me doProcess changeMessage)
  become(SyncMasterShortIdData(Set.empty, Map.empty, maxSyncs = 3), SHORT_ID_SYNC)
  for (_ <- 0 until data.maxSyncs) me process CMDAddSync

  def doProcess(change: Any): Unit = (change, data, state) match {
    case (CMDAddSync, data1: SyncMasterShortIdData, SHORT_ID_SYNC) if data1.activeSyncs.size < data1.maxSyncs =>
      // Turns out we don't have enough workers, create one with unused remote nodeId and track its progress
      val newSyncWorker = getNewSync(data1)

      // Worker is connecting, tell it get shortIds once connection is there
      become(data1.copy(activeSyncs = data1.activeSyncs + newSyncWorker), SHORT_ID_SYNC)
      newSyncWorker process SyncWorkerShortIdsData(ranges = Nil)

    case (sync: SyncWorker, SyncMasterShortIdData(activeSyncs, ranges, maxSyncs), SHORT_ID_SYNC) =>
      val data1 = SyncMasterShortIdData(activeSyncs - sync, ranges - sync.pkap.them, maxSyncs)
      // Sync has disconnected, stop tracking it and try to connect to another one with delay
      RxUtils.ioQueue.delay(3.seconds).foreach(_ => me process CMDAddSync)
      become(data1, SHORT_ID_SYNC)

    case (CMDShortIdsComplete(sync, ranges1), SyncMasterShortIdData(activeSyncs, ranges, maxSyncs), SHORT_ID_SYNC) =>
      val data1 = SyncMasterShortIdData(activeSyncs, ranges + (sync.pkap.them -> ranges1), maxSyncs)
      become(data1, SHORT_ID_SYNC)

      if (data1.collectedRanges.size == maxSyncs) {
        // We have collected enough channel ranges for gossip
        val goodRanges = data1.collectedRanges.values.filter(_.isHolistic)
        val accum = mutable.Map.empty[ShortChannelId, Int] withDefaultValue 0
        goodRanges.flatMap(_.allShortIds).foreach(shortId => accum(shortId) += 1)
        provenShortIds = accum.collect { case shortId \ confs if confs > data.threshold => shortId }.toSet
        val queries = goodRanges.maxBy(_.allShortIds.size).ranges.par.map(reply2Query).toList

        // Transfer every worker into gossip syncing state
        become(SyncMasterGossipData(activeSyncs, chunksToWait, maxSyncs), GOSSIP_SYNC)
        for (currentSync <- activeSyncs) currentSync process SyncWorkerGossipData(queries)
        for (currentSync <- activeSyncs) currentSync process CMDGetGossip
      }

    // GOSSIP_SYNC

    case (workerData: SyncWorkerGossipData, data1: SyncMasterGossipData, GOSSIP_SYNC) if data1.activeSyncs.size < data1.maxSyncs =>
      // Turns out one of the workers has disconnected while getting gossip, create one with unused remote nodeId and track its progress
      val newSyncWorker = getNewSync(data1)

      // Worker is connecting, tell it to get the rest of gossip once connection is there
      become(data1.copy(activeSyncs = data1.activeSyncs + newSyncWorker), GOSSIP_SYNC)
      newSyncWorker process SyncWorkerGossipData(workerData.queries)

    case (sync: SyncWorker, data1: SyncMasterGossipData, GOSSIP_SYNC) =>
      become(data1.copy(activeSyncs = data1.activeSyncs - sync), GOSSIP_SYNC)
      // Sync has disconnected, stop tracking it and try to connect to another one
      RxUtils.ioQueue.delay(3.seconds).foreach(_ => me process sync.data)

    case (CMDChunkComplete(sync, workerData), data1: SyncMasterGossipData, GOSSIP_SYNC) =>
      for (liteAnnounce <- workerData.announces) confirmedChanAnnounces(liteAnnounce) = confirmedChanAnnounces(liteAnnounce) + sync.pkap.them
      for (liteUpdate <- workerData.updates) confirmedChanUpdates(liteUpdate.core) = confirmedChanUpdates(liteUpdate.core).add(liteUpdate, sync.pkap.them)
      newExcludedChanUpdates ++= workerData.excluded

      if (data1.chunksLeft > 0) {
        // We batch multiple chunks to have less upstream db calls
        val nextData = data1.copy(chunksLeft = data1.chunksLeft - 1)
        become(nextData, GOSSIP_SYNC)
      } else {
        // Batch is ready, send out and start a new one
        val nextData = data1.copy(chunksLeft = chunksToWait)
        become(nextData, GOSSIP_SYNC)
        sendOutData(nextData)
      }

    case (CMDGossipComplete(sync), data1: SyncMasterGossipData, GOSSIP_SYNC) =>
      val nextData = data1.copy(activeSyncs = data1.activeSyncs - sync)

      if (nextData.activeSyncs.nonEmpty) {
        become(nextData, GOSSIP_SYNC)
      } else {
        become(null, SHUT_DOWN)
        sendOutData(nextData)
        onTotalSyncComplete
      }

    case _ =>
  }

  def sendOutData(data1: SyncMasterGossipData): Unit = {
    val goodAnnounces = confirmedChanAnnounces.collect { case announce \ confirmedByNodes if confirmedByNodes.size > data1.threshold => announce }.toSet
    val goodUpdates = confirmedChanUpdates.collect { case _ \ UpdateConifrmState(Some(update), confs) if confs.size > data1.threshold => update }.toSet
    me onChunkSyncComplete PureRoutingData(goodAnnounces, goodUpdates, newExcludedChanUpdates)
    for (announce <- goodAnnounces) confirmedChanAnnounces -= announce
    for (update <- goodUpdates) confirmedChanUpdates -= update.core
    newExcludedChanUpdates = Set.empty
  }

  def getNewSync(data1: SyncMasterData): SyncWorker = {
    // Note: will throw if we have less than maxSync nodes
    val usedAnnounces = for (sync <- data1.activeSyncs) yield sync.ann
    val goodAnnounces = syncNodes ++ extraNodes -- usedAnnounces
    val randomAnnounce = shuffle(goodAnnounces.toList).head
    SyncWorker(me, randomKeyPair, randomAnnounce)
  }

  def reply2Query(reply: ReplyChannelRange): QueryShortChannelIds = {
    val stack = (reply.shortChannelIds.array, reply.timestamps.timestamps, reply.checksums.checksums)

    val shortIdFlagSeq = for {
      (shortId, theirTimestamps, theirChecksums) <- stack.zipped.toList if provenAndNotExcluded(shortId)
      finalFlag = computeFlag(shortId, theirTimestamps, theirChecksums) if finalFlag != 0
    } yield (shortId, finalFlag)

    val shortIds \ flags = shortIdFlagSeq.unzip
    val shortChannelIds = EncodedShortChannelIds(reply.shortChannelIds.encoding, shortIds)
    val tlv = QueryShortChannelIdsTlv.EncodedQueryFlags(reply.shortChannelIds.encoding, flags)
    QueryShortChannelIds(LNParams.chainHash, shortChannelIds, TlvStream apply tlv)
  }

  private def computeFlag(shortlId: ShortChannelId,
                          theirTimestamps: ReplyChannelRangeTlv.Timestamps,
                          theirChecksums: ReplyChannelRangeTlv.Checksums) = {

    if (routerData.channels contains shortlId) {
      val ReplyChannelRangeTlv.Timestamps(stamp1, stamp2) \ ReplyChannelRangeTlv.Checksums(checksum1, checksum2) = Sync.getChannelDigestInfo(routerData.channels)(shortlId)
      val shouldRequestUpdate1 = Sync.shouldRequestUpdate(stamp1, checksum1, theirTimestamps.timestamp1, theirChecksums.checksum1)
      val shouldRequestUpdate2 = Sync.shouldRequestUpdate(stamp2, checksum2, theirTimestamps.timestamp2, theirChecksums.checksum2)

      val flagUpdate1 = if (shouldRequestUpdate1) INCLUDE_CHANNEL_UPDATE_1 else 0
      val flagUpdate2 = if (shouldRequestUpdate2) INCLUDE_CHANNEL_UPDATE_2 else 0
      0 | flagUpdate1 | flagUpdate2
    } else {
      INCLUDE_CHANNEL_ANNOUNCEMENT | INCLUDE_CHANNEL_UPDATE_1 | INCLUDE_CHANNEL_UPDATE_2
    }
  }
}
