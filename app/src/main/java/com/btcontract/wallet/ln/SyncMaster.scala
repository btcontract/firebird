package com.btcontract.wallet.ln

import fr.acinq.eclair.wire._
import scala.concurrent.duration._
import com.btcontract.wallet.ln.SyncMaster._
import QueryShortChannelIdsTlv.QueryFlagType._
import com.btcontract.wallet.ln.crypto.Tools._
import fr.acinq.eclair.router.{Announcements, Sync}
import fr.acinq.eclair.{MilliSatoshi, ShortChannelId}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import com.btcontract.wallet.ln.crypto.{CanBeRepliedTo, StateMachine, Tools}
import com.btcontract.wallet.ln.crypto.Noise.KeyPair
import fr.acinq.eclair.router.Router.Data
import fr.acinq.bitcoin.Crypto.PublicKey
import com.btcontract.wallet.ln.utils.Rx
import java.util.concurrent.Executors
import scala.util.Random.shuffle
import scala.collection.mutable
import scodec.bits.ByteVector


object SyncMaster {
  val WAITING = "state-waiting"
  val SHUT_DOWN = "state-shut-down"
  val SHORT_ID_SYNC = "state-short-ids"
  val GOSSIP_SYNC = "state-gossip-sync"
  val PHC_SYNC = "phc-sync"

  val CMDAddSync = "cmd-add-sync"
  val CMDGetGossip = "cmd-get-gossip"
  val CMDShutdown = "cmd-shut-down"

  type ConifrmedBySet = Set[PublicKey]
  type ShortChanIdSet = Set[ShortChannelId]
  type PositionSet = Set[java.lang.Integer]

  val blw: NodeAnnouncement = mkNodeAnnouncement(PublicKey(ByteVector fromValidHex "03144fcc73cea41a002b2865f98190ab90e4ff58a2ce24d3870f5079081e42922d"), NodeAddress.unresolved(9735, host = 5, 9, 83, 143), "BLW Den")
  val lightning: NodeAnnouncement = mkNodeAnnouncement(PublicKey(ByteVector fromValidHex "03baa70886d9200af0ffbd3f9e18d96008331c858456b16e3a9b41e735c6208fef"), NodeAddress.unresolved(9735, host = 45, 20, 67, 1), "LIGHTNING")
  val cheese: NodeAnnouncement = mkNodeAnnouncement(PublicKey(ByteVector fromValidHex "0276e09a267592e7451a939c932cf685f0754de382a3ca85d2fb3a864d4c365ad5"), NodeAddress.unresolved(9735, host = 94, 177, 171, 73), "Cheese")
  val acinq: NodeAnnouncement = mkNodeAnnouncement(PublicKey(ByteVector fromValidHex "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"), NodeAddress.unresolved(9735, host = 34, 239, 230, 56), "ACINQ")
  val hostedChanNodes: Set[NodeAnnouncement] = Set(blw, lightning, acinq) // Trusted nodes which are shown as default ones when user chooses providers
  val hostedSyncNodes: Set[NodeAnnouncement] = Set(blw, lightning, acinq) // Semi-trusted PHC-enabled nodes which can be used as seeds for PHC sync
  val syncNodes: Set[NodeAnnouncement] = Set(lightning, cheese, acinq) // Nodes with extended queries support used as seeds for normal sync
  val phcCapacity = MilliSatoshi(100000000000000L) // 1000 BTC
  val minCapacity = MilliSatoshi(500000000L) // 500k sat
  val minNormalChansForPHC = 10
  val maxPHCPerNode = 2
  val chunksToWait = 3
}

sealed trait SyncWorkerData

case class SyncWorkerShortIdsData(ranges: List[ReplyChannelRange] = Nil, from: Int) extends SyncWorkerData {
  lazy val allShortIds: Seq[ShortChannelId] = ranges.flatMap(_.shortChannelIds.array)

  def isHolistic: Boolean = ranges forall { range =>
    val sameStampToChecksums = range.timestamps.timestamps.size == range.checksums.checksums.size
    val sameDataToTlv = range.shortChannelIds.array.size == range.timestamps.timestamps.size
    sameStampToChecksums && sameDataToTlv
  }
}

case class SyncWorkerGossipData(syncMaster: SyncMaster,
                                queries: Seq[QueryShortChannelIds],
                                updates: Set[ChannelUpdate] = Set.empty,
                                announces: Set[ChannelAnnouncement] = Set.empty,
                                excluded: Set[UpdateCore] = Set.empty) extends SyncWorkerData

case class CMDShortIdsComplete(sync: SyncWorker, data: SyncWorkerShortIdsData)
case class CMDChunkComplete(sync: SyncWorker, data: SyncWorkerGossipData)
case class CMDGossipComplete(sync: SyncWorker)

// This entirely relies on fact that peer sends ChannelAnnouncement messages first, then ChannelUpdate messages

case class SyncWorkerPHCData(phcMaster: PHCSyncMaster,
                             expectedPositions: Map[ShortChannelId, PositionSet] = Map.empty, nodeIdToShortIds: Map[PublicKey, ShortChanIdSet] = Map.empty,
                             updates: Set[ChannelUpdate] = Set.empty, announces: Map[ShortChannelId, ChannelAnnouncement] = Map.empty) extends SyncWorkerData {

  def withNewAnnounce(ann: ChannelAnnouncement): SyncWorkerPHCData = {
    val nodeId1ToShortIds = nodeIdToShortIds.getOrElse(ann.nodeId1, Set.empty) + ann.shortChannelId
    val nodeId2ToShortIds = nodeIdToShortIds.getOrElse(ann.nodeId2, Set.empty) + ann.shortChannelId
    val nodeIdToShortIds1 = nodeIdToShortIds.updated(ann.nodeId1, nodeId1ToShortIds).updated(ann.nodeId2, nodeId2ToShortIds)
    copy(expectedPositions = expectedPositions.updated(ann.shortChannelId, ChannelUpdate.fullSet), announces = announces.updated(ann.shortChannelId, ann), nodeIdToShortIds = nodeIdToShortIds1)
  }

  def withNewUpdate(cu: ChannelUpdate): SyncWorkerPHCData = {
    val oneLessPosition = expectedPositions.getOrElse(cu.shortChannelId, Set.empty) - cu.position
    copy(expectedPositions = expectedPositions.updated(cu.shortChannelId, oneLessPosition), updates = updates + cu)
  }

  def isAcceptable(ann: ChannelAnnouncement): Boolean = {
    val notTooMuchNode1PHCs = nodeIdToShortIds.getOrElse(ann.nodeId1, Set.empty).size < maxPHCPerNode
    val notTooMuchNode2PHCs = nodeIdToShortIds.getOrElse(ann.nodeId2, Set.empty).size < maxPHCPerNode
    val isCorrect = Tools.hostedShortChanId(ann.nodeId1.value, ann.nodeId2.value) == ann.shortChannelId
    ann.isPHC && isCorrect && notTooMuchNode1PHCs && notTooMuchNode2PHCs
  }

  def isUpdateAcceptable(cu: ChannelUpdate): Boolean =
    cu.htlcMaximumMsat.contains(phcCapacity) && // PHC declared capacity must always be exactly 100 btc
      expectedPositions.getOrElse(cu.shortChannelId, Set.empty).contains(cu.position) && // Remote node does not send the same update twice
      announces.get(cu.shortChannelId).map(_ getNodeIdSameSideAs cu).exists(Announcements checkSig cu) // We have received a related announce, signature is valid
}

case class SyncWorker(master: CanBeRepliedTo, keyPair: KeyPair, ann: NodeAnnouncement) extends StateMachine[SyncWorkerData] { me =>
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
    case (data1: SyncWorkerPHCData, null, WAITING) => become(data1, PHC_SYNC)
    case (data1: SyncWorkerShortIdsData, null, WAITING) => become(data1, SHORT_ID_SYNC)
    case (data1: SyncWorkerGossipData, _, WAITING | SHORT_ID_SYNC) => become(data1, GOSSIP_SYNC)

    case (worker: CommsTower.Worker, data: SyncWorkerShortIdsData, SHORT_ID_SYNC) =>
      val tlv = QueryChannelRangeTlv.QueryFlags(QueryChannelRangeTlv.QueryFlags.WANT_ALL)
      val query = QueryChannelRange(LNParams.chainHash, data.from, Int.MaxValue, TlvStream apply tlv)
      worker.handler process query

    case (reply: ReplyChannelRange, data1: SyncWorkerShortIdsData, SHORT_ID_SYNC) =>
      val updatedData: SyncWorkerShortIdsData = data1.copy(ranges = reply +: data1.ranges)
      if (reply.numberOfBlocks < 1000000000L) become(updatedData, SHORT_ID_SYNC)
      else master process CMDShortIdsComplete(me, updatedData)

    // GOSSIP_SYNC

    case (_: CommsTower.Worker, _: SyncWorkerGossipData, GOSSIP_SYNC) =>
      // Remote peer is connected, (re-)start remaining gossip sync
      me process CMDGetGossip

    case (CMDGetGossip, data1: SyncWorkerGossipData, GOSSIP_SYNC) if data1.queries.isEmpty =>
      // We have no more queries left, inform master that we are finished and shut down
      master process CMDGossipComplete(me)
      me process CMDShutdown

    case (CMDGetGossip, data1: SyncWorkerGossipData, GOSSIP_SYNC) =>
      // We still have queries left, send another one to peer and expect incoming gossip
      CommsTower.workers.get(pkap).foreach(_.handler process data1.queries.head)

    case (update: ChannelUpdate, d1: SyncWorkerGossipData, GOSSIP_SYNC) if d1.syncMaster.provenAndTooSmallOrNoInfo(update) => become(d1.copy(excluded = d1.excluded + update.core), GOSSIP_SYNC)
    case (update: ChannelUpdate, d1: SyncWorkerGossipData, GOSSIP_SYNC) if d1.syncMaster.provenAndNotExcluded(update.shortChannelId) => become(d1.copy(updates = d1.updates + update.lite), GOSSIP_SYNC)
    case (ann: ChannelAnnouncement, d1: SyncWorkerGossipData, GOSSIP_SYNC) if d1.syncMaster.provenShortIds.contains(ann.shortChannelId) => become(d1.copy(announces = d1.announces + ann.lite), GOSSIP_SYNC)

    case (_: ReplyShortChannelIdsEnd, data1: SyncWorkerGossipData, GOSSIP_SYNC) =>
      // We have completed current chunk, inform master and either continue or complete
      become(SyncWorkerGossipData(data1.syncMaster, data1.queries.tail), GOSSIP_SYNC)
      master process CMDChunkComplete(me, data1)
      me process CMDGetGossip

    // PHC_SYNC

    case (worker: CommsTower.Worker, _: SyncWorkerPHCData, PHC_SYNC) => worker.handler process QueryPublicHostedChannels(LNParams.chainHash)
    case (update: ChannelUpdate, d1: SyncWorkerPHCData, PHC_SYNC) if d1.isUpdateAcceptable(update) => become(d1 withNewUpdate update.lite, PHC_SYNC)
    case (ann: ChannelAnnouncement, d1: SyncWorkerPHCData, PHC_SYNC) if d1.isAcceptable(ann) && d1.phcMaster.isAcceptable(ann) => become(d1 withNewAnnounce ann.lite, PHC_SYNC)

    case (_: ReplyPublicHostedChannelsEnd, completeSyncData: SyncWorkerPHCData, PHC_SYNC) =>
      // Peer has informed us that there is no more PHC gossip left, inform master and shut down
      master process completeSyncData
      me process CMDShutdown

    case (CMDShutdown, _, _) =>
      become(freshData = null, SHUT_DOWN)
      CommsTower forget pkap

    case _ =>
  }
}

trait SyncMasterData extends {
  lazy val threshold: Int = maxSyncs / 2
  def activeSyncs: Set[SyncWorker]
  def maxSyncs: Int
}

trait GetNewSyncMachine extends CanBeRepliedTo { me =>
  def getNewSync(data1: SyncMasterData, allNodes: Set[NodeAnnouncement] = Set.empty): SyncWorker = {
    val goodAnnounces: Set[NodeAnnouncement] = allNodes -- data1.activeSyncs.map(activeSync => activeSync.ann)
    SyncWorker(me, randomKeyPair, shuffle(goodAnnounces.toList).head)
  }
}

case class PureRoutingData(announces: Set[ChannelAnnouncement], updates: Set[ChannelUpdate], excluded: Set[UpdateCore] = Set.empty)
case class SyncMasterShortIdData(activeSyncs: Set[SyncWorker], collectedRanges: Map[PublicKey, SyncWorkerShortIdsData], maxSyncs: Int) extends SyncMasterData
case class SyncMasterGossipData(activeSyncs: Set[SyncWorker], chunksLeft: Int, maxSyncs: Int) extends SyncMasterData

case class UpdateConifrmState(liteUpdOpt: Option[ChannelUpdate], confirmedBy: ConifrmedBySet) {
  def add(cu: ChannelUpdate, from: PublicKey): UpdateConifrmState = copy(liteUpdOpt = Some(cu), confirmedBy = confirmedBy + from)
}

abstract class SyncMaster(extraNodes: Set[NodeAnnouncement], excluded: Set[Long], routerData: Data) extends StateMachine[SyncMasterData] with GetNewSyncMachine { me =>
  val confirmedChanUpdates: mutable.Map[UpdateCore, UpdateConifrmState] = mutable.Map.empty withDefaultValue UpdateConifrmState(None, Set.empty)
  val confirmedChanAnnounces: mutable.Map[ChannelAnnouncement, ConifrmedBySet] = mutable.Map.empty withDefaultValue Set.empty
  var newExcludedChanUpdates: Set[UpdateCore] = Set.empty
  var provenShortIds: ShortChanIdSet = Set.empty

  def onChunkSyncComplete(pure: PureRoutingData): Unit
  def onTotalSyncComplete: Unit

  def provenAndTooSmallOrNoInfo(update: ChannelUpdate): Boolean = provenShortIds.contains(update.shortChannelId) && update.htlcMaximumMsat.forall(_ < minCapacity)
  def provenAndNotExcluded(shortId: ShortChannelId): Boolean = provenShortIds.contains(shortId) && !excluded.contains(shortId.id)

  implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def process(changeMessage: Any): Unit = scala.concurrent.Future(me doProcess changeMessage)
  become(SyncMasterShortIdData(Set.empty, Map.empty, maxSyncs = 3), SHORT_ID_SYNC)
  for (_ <- 0 until data.maxSyncs) me process CMDAddSync

  def doProcess(change: Any): Unit = (change, data, state) match {
    case (CMDAddSync, data1: SyncMasterShortIdData, SHORT_ID_SYNC) if data1.activeSyncs.size < data1.maxSyncs =>
      // Turns out we don't have enough workers, create one with unused remote nodeId and track its progress
      val newSyncWorker = getNewSync(data1, syncNodes ++ extraNodes)

      // Worker is connecting, tell it get shortIds once connection is there
      become(data1.copy(activeSyncs = data1.activeSyncs + newSyncWorker), SHORT_ID_SYNC)
      newSyncWorker process SyncWorkerShortIdsData(ranges = Nil, from = 550000)

    case (sync: SyncWorker, SyncMasterShortIdData(activeSyncs, ranges, maxSyncs), SHORT_ID_SYNC) =>
      val data1 = SyncMasterShortIdData(activeSyncs - sync, ranges - sync.pkap.them, maxSyncs)
      // Sync has disconnected, stop tracking it and try to connect to another one with delay
      Rx.ioQueue.delay(3.seconds).foreach(_ => me process CMDAddSync)
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
        for (currentSync <- activeSyncs) currentSync process SyncWorkerGossipData(me, queries)
        for (currentSync <- activeSyncs) currentSync process CMDGetGossip
      }

    // GOSSIP_SYNC

    case (workerData: SyncWorkerGossipData, data1: SyncMasterGossipData, GOSSIP_SYNC) if data1.activeSyncs.size < data1.maxSyncs =>
      // Turns out one of the workers has disconnected while getting gossip, create one with unused remote nodeId and track its progress
      val newSyncWorker = getNewSync(data1, syncNodes ++ extraNodes)

      // Worker is connecting, tell it to get the rest of gossip once connection is there
      become(data1.copy(activeSyncs = data1.activeSyncs + newSyncWorker), GOSSIP_SYNC)
      newSyncWorker process SyncWorkerGossipData(me, workerData.queries)

    case (sync: SyncWorker, data1: SyncMasterGossipData, GOSSIP_SYNC) =>
      become(data1.copy(activeSyncs = data1.activeSyncs - sync), GOSSIP_SYNC)
      // Sync has disconnected, stop tracking it and try to connect to another one
      Rx.ioQueue.delay(3.seconds).foreach(_ => me process sync.data)

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
        sendPureData(nextData)
      }

    case (CMDGossipComplete(sync), data1: SyncMasterGossipData, GOSSIP_SYNC) =>
      val nextData = data1.copy(activeSyncs = data1.activeSyncs - sync)

      if (nextData.activeSyncs.nonEmpty) {
        become(nextData, GOSSIP_SYNC)
      } else {
        become(null, SHUT_DOWN)
        sendPureData(nextData)
        onTotalSyncComplete
      }

    case _ =>
  }

  def sendPureData(data1: SyncMasterGossipData): Unit = {
    val goodAnnounces = confirmedChanAnnounces.collect { case announce \ confirmedByNodes if confirmedByNodes.size > data1.threshold => announce }.toSet
    val goodUpdates = confirmedChanUpdates.collect { case _ \ UpdateConifrmState(Some(update), confs) if confs.size > data1.threshold => update }.toSet
    me onChunkSyncComplete PureRoutingData(goodAnnounces, goodUpdates, newExcludedChanUpdates)
    for (announce <- goodAnnounces) confirmedChanAnnounces -= announce
    for (update <- goodUpdates) confirmedChanUpdates -= update.core
    newExcludedChanUpdates = Set.empty
  }

  def reply2Query(reply: ReplyChannelRange): QueryShortChannelIds = {
    val stack = (reply.shortChannelIds.array, reply.timestamps.timestamps, reply.checksums.checksums)

    val shortIdFlagSeq = for {
      (shortId, theirTimestamps, theirChecksums) <- stack.zipped if provenAndNotExcluded(shortId)
      finalFlag = computeFlag(shortId, theirTimestamps, theirChecksums) if finalFlag != 0
    } yield (shortId, finalFlag)

    val shortIds \ flags = shortIdFlagSeq.toList.unzip
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

case class CompleteHostedRoutingData(announces: Set[ChannelAnnouncement], updates: Set[ChannelUpdate] = Set.empty)
case class SyncMasterPHCData(activeSyncs: Set[SyncWorker], attemptsLeft: Int) extends SyncMasterData { final val maxSyncs: Int = 1 }
abstract class PHCSyncMaster(extraNodes: Set[NodeAnnouncement], routerData: Data) extends StateMachine[SyncMasterPHCData] with GetNewSyncMachine { me =>
  implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def process(changeMessage: Any): Unit = scala.concurrent.Future(me doProcess changeMessage)
  become(SyncMasterPHCData(Set.empty, attemptsLeft = 12), PHC_SYNC)
  me process CMDAddSync

  // These checks require router and graph
  def isAcceptable(ann: ChannelAnnouncement): Boolean = {
    val node1HasEnoughIncomingChans = routerData.graph.vertices.getOrElse(ann.nodeId1, Nil).count(_.desc.a != ann.nodeId2) >= minNormalChansForPHC
    val node2HasEnoughIncomingChans = routerData.graph.vertices.getOrElse(ann.nodeId2, Nil).count(_.desc.a != ann.nodeId1) >= minNormalChansForPHC
    !routerData.channels.contains(ann.shortChannelId) && node1HasEnoughIncomingChans && node2HasEnoughIncomingChans
  }

  def onSyncComplete(pure: CompleteHostedRoutingData): Unit

  def doProcess(change: Any): Unit = (change, state) match {
    case CMDAddSync \ PHC_SYNC if data.activeSyncs.size < data.maxSyncs =>
      val newSyncWorker: SyncWorker = getNewSync(data, hostedSyncNodes ++ extraNodes)
      become(data.copy(activeSyncs = data.activeSyncs + newSyncWorker), PHC_SYNC)
      newSyncWorker process SyncWorkerPHCData(me)

    case (sync: SyncWorker, PHC_SYNC) if data.attemptsLeft > 0 =>
      // Sync has disconnected, stop tracking it and try to connect to another one with delay
      become(data.copy(data.activeSyncs - sync, attemptsLeft = data.attemptsLeft - 1), PHC_SYNC)
      Rx.ioQueue.delay(3.seconds).foreach(_ => me process CMDAddSync)

    case (_: SyncWorker, PHC_SYNC) =>
      // No more reconnect attempts left
      become(null, SHUT_DOWN)

    case (d1: SyncWorkerPHCData, PHC_SYNC) =>
      // Worker has informed us that PHC sync is complete, shut down
      val pure = CompleteHostedRoutingData(d1.announces.values.toSet, d1.updates)
      become(null, SHUT_DOWN)
      onSyncComplete(pure)
  }
}