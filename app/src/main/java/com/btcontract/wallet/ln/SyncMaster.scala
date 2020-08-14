package com.btcontract.wallet.ln

import fr.acinq.eclair.wire._

import scala.concurrent.duration._
import com.btcontract.wallet.ln.SyncMaster._
import com.btcontract.wallet.ln.crypto.Tools.{\, mkNodeAnnouncement, randomKeyPair}

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import fr.acinq.eclair.router.Router.{Data, RouterConf}
import fr.acinq.eclair.{MilliSatoshi, ShortChannelId}
import fr.acinq.eclair.router.{Announcements, StaleChannels, Sync}
import com.btcontract.wallet.ln.crypto.Noise.KeyPair
import com.btcontract.wallet.ln.crypto.StateMachine
import fr.acinq.bitcoin.Crypto.PublicKey
import java.util.concurrent.Executors

import scala.collection.mutable
import scodec.bits.ByteVector


sealed trait SyncManagerData
case class InitSyncData(activeSyncs: List[InitSync], maxSyncs: Int) extends SyncManagerData
case class CatchupSyncData(queries: Seq[QueryShortChannelIds], announcements: Set[ChannelAnnouncement], updates: Set[ChannelUpdate],
                           excluded: Set[ShortChannelId], pkap: PublicKeyAndPair) extends SyncManagerData

case class InitSync(master: SyncMaster, keyPair: KeyPair, ann: NodeAnnouncement) {
  var replyChannelRangeMessages: List[ReplyChannelRange] = List.empty[ReplyChannelRange]
  var shortIdsObtained: Boolean = false

  val listener: ConnectionListener = new ConnectionListener {
    override def onMessage(worker: CommsTower.Worker, msg: LightningMessage): Unit = msg match {
      case _: ReplyChannelRange if replyChannelRangeMessages.size > 500 => worker.disconnect
      case reply: ReplyChannelRange => handleReply(reply)
      case _ =>
    }

    override def onOperational(worker: CommsTower.Worker): Unit = {
      val tlv: QueryChannelRangeTlv = QueryChannelRangeTlv.QueryFlags(QueryChannelRangeTlv.QueryFlags.WANT_TIMESTAMPS)
      val query = QueryChannelRange(LNParams.chainHash, firstBlockNum = 0L, numberOfBlocks = Long.MaxValue, TlvStream apply tlv)
      worker.handler process query
    }

    override def onDisconnect(worker: CommsTower.Worker): Unit = {
      // Remove this listener and remove an object itself from master
      CommsTower.listeners(worker.pkap) -= listener
      master process this
    }
  }

  def handleReply(replyMessage: ReplyChannelRange): Unit = {
    replyChannelRangeMessages = replyMessage :: replyChannelRangeMessages
    shortIdsObtained = replyMessage.numberOfBlocks == Long.MaxValue
    if (shortIdsObtained) master process CMDShortIdsDone
  }

  // Connect and start collecting messages immediately
  val pkap = PublicKeyAndPair(ann.nodeId, keyPair)
  CommsTower.listen(Set(listener), pkap, ann)
}

object SyncMaster {
  val INIT = "state-init"
  val SYNCRONIZING = "state-syncronizing"
  val CMDShortIdsDone = "cmd-short-ids-done"
  val CMDGetGossip = "cmd-get-gossip"
  val CMDAddSync = "cmd-add-sync"
  val CMDRestart = "cmd-restart"

  type ShortChanIdSet = Set[ShortChannelId]
  type NodeAnnouncements = List[NodeAnnouncement]

  val zap: NodeAnnouncement = mkNodeAnnouncement(PublicKey(ByteVector fromValidHex "027cd974e47086291bb8a5b0160a889c738f2712a703b8ea939985fd16f3aae67e"), NodeAddress.fromParts("335.237.192.216", 9735), "Zap")
  val lnMarkets: NodeAnnouncement = mkNodeAnnouncement(PublicKey(ByteVector fromValidHex "03271338633d2d37b285dae4df40b413d8c6c791fbee7797bc5dc70812196d7d5c"), NodeAddress.fromParts("3.95.117.200", 9735), "LNMarkets")
  val bitstamp: NodeAnnouncement = mkNodeAnnouncement(PublicKey(ByteVector fromValidHex "02a04446caa81636d60d63b066f2814cbd3a6b5c258e3172cbdded7a16e2cfff4c"), NodeAddress.fromParts("3.122.40.122", 9735), "Bitstamp")
  val openNode: NodeAnnouncement = mkNodeAnnouncement(PublicKey(ByteVector fromValidHex "03abf6f44c355dec0d5aa155bdbdd6e0c8fefe318eff402de65c6eb2e1be55dc3e"), NodeAddress.fromParts("18.221.23.28", 9735), "OpenNode")
  val bitrefill: NodeAnnouncement = mkNodeAnnouncement(PublicKey(ByteVector fromValidHex "0254ff808f53b2f8c45e74b70430f336c6c76ba2f4af289f48d6086ae6e60462d3"), NodeAddress.fromParts("52.30.63.2", 9735), "Bitrefill")
  val bitrefillTor: NodeAnnouncement = mkNodeAnnouncement(PublicKey(ByteVector fromValidHex "030c3f19d742ca294a55c00376b3b355c3c90d61c6b6b39554dbc7ac19b141c14f"), NodeAddress.fromParts("52.50.244.44", 9735), "Tor")
  val coinGate: NodeAnnouncement = mkNodeAnnouncement(PublicKey(ByteVector fromValidHex "0242a4ae0c5bef18048fbecf995094b74bfb0f7391418d71ed394784373f41e4f3"), NodeAddress.fromParts("3.124.63.44", 9735), "CoinGate")
  val liteGo: NodeAnnouncement = mkNodeAnnouncement(PublicKey(ByteVector fromValidHex "029aee02904d4e419770b93c1b07aae2814a79032e23cafb4024cbea6fb71be106"), NodeAddress.fromParts("195.154.169.49", 9735), "LiteGo")
  val acinq: NodeAnnouncement = mkNodeAnnouncement(PublicKey(ByteVector fromValidHex "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"), NodeAddress.fromParts("34.239.230.56", 9735), "ACINQ")
  val fold: NodeAnnouncement = mkNodeAnnouncement(PublicKey(ByteVector fromValidHex "02816caed43171d3c9854e3b0ab2cf0c42be086ff1bd4005acc2a5f7db70d83774"), NodeAddress.fromParts("35.238.153.25", 9735), "Fold")
  val syncNodeVec: NodeAnnouncements = List(zap, lnMarkets, bitstamp, openNode, bitrefill, bitrefillTor, coinGate, liteGo, acinq, fold)
  val minCapacity = MilliSatoshi(1000000000L) // We are not interested in channels with capacity less than this

  def isBadChannelUpdate(cu: ChannelUpdate, routerData: Data): Boolean = {
    val oldCopyOpt = routerData.channels(cu.shortChannelId).getChannelUpdateSameSideAs(cu)
    oldCopyOpt.exists(_.timestamp >= cu.timestamp) || StaleChannels.isStale(cu)
  }
}

abstract class SyncMaster(extraNodes: NodeAnnouncements, excludedShortIds: ShortChanIdSet, routerData: Data, routerConf: RouterConf) extends StateMachine[SyncManagerData] { me =>
  implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def process(changeMessage: Any): Unit = scala.concurrent.Future(this doProcess changeMessage)
  def onTotalSyncComplete(data: CatchupSyncData): Unit
  def onChunkSyncComplete(data: CatchupSyncData): Unit

  become(InitSyncData(Nil, maxSyncs = 3), INIT)
  val listener: ConnectionListener = new ConnectionListener {
    override def onDisconnect(worker: CommsTower.Worker): Unit = me process CMDRestart
    override def onMessage(worker: CommsTower.Worker, msg: LightningMessage): Unit = (msg, data) match {
      case (ca: ChannelAnnouncement, csd: CatchupSyncData) if !isExcluded(ca.shortChannelId) => become(csd.copy(announcements = csd.announcements + ca), state)
      case (cu: ChannelUpdate, csd: CatchupSyncData) if cu.htlcMaximumMsat.forall(_ < minCapacity) => become(csd.copy(excluded = csd.excluded + cu.shortChannelId), state)
      case (cu: ChannelUpdate, csd: CatchupSyncData) if !isBadChannelUpdate(cu, routerData) => become(csd.copy(updates = csd.updates + cu), state)
      case (_: ReplyShortChannelIdsEnd, _) => me process CMDGetGossip
      case _ =>
    }
  }

  def doProcess(change: Any): Unit = (change, data, state) match {
    case (CMDAddSync, InitSyncData(activeSyncs, maxSyncs), INIT) if activeSyncs.size < maxSyncs =>
      val usedNodeAnnouncements = for (currentActiveSync <- activeSyncs) yield currentActiveSync.ann
      val newAnn = getRandomNode(extraNodes ::: syncNodeVec diff usedNodeAnnouncements)
      val newActiveSyncs = InitSync(me, randomKeyPair, newAnn) :: activeSyncs
      become(InitSyncData(newActiveSyncs, maxSyncs), INIT)
      me process CMDAddSync

    case (disconnectedSync: InitSync, InitSyncData(activeSyncs, maxSyncs), INIT) =>
      val newInitSyncData = InitSyncData(activeSyncs.diff(disconnectedSync :: Nil), maxSyncs)
      become(newInitSyncData, INIT)
      delayedAddSync

    case (CMDShortIdsDone, InitSyncData(activeSyncs, maxSyncs), INIT)
      // GUARD: only proceed once we have a required number of syns with all data
      if activeSyncs.size == maxSyncs && activeSyncs.forall(_.shortIdsObtained) =>

      val shortIdsPerSync = activeSyncs.map(allShortChannelIds)
      val provenShortIds = getMajorityConfirmedShortIds(shortIdsPerSync:_*)
      val bestSync \ _ = activeSyncs.zip(shortIdsPerSync) maxBy { case _ \ shortIds => shortIds.toSet.intersect(provenShortIds).size }
      val catchupSyncData = CatchupSyncData(bestSync.replyChannelRangeMessages flatMap reply2Query(provenShortIds), Set.empty, Set.empty, Set.empty, bestSync.pkap)

      // First remove all init listeners, then disconnect useless syncs
      activeSyncs.foreach(sync => CommsTower.listeners(sync.pkap) -= sync.listener)
      activeSyncs.diff(bestSync :: Nil).foreach(sync => CommsTower.workers(sync.pkap).disconnect)
      CommsTower.listen(Set(listener), catchupSyncData.pkap, bestSync.ann)
      become(catchupSyncData, SYNCRONIZING)
      me process CMDGetGossip

    case (CMDGetGossip, data: CatchupSyncData, SYNCRONIZING) if data.queries.nonEmpty =>
      val newCatchupSyncData = CatchupSyncData(data.queries.tail, Set.empty, Set.empty, Set.empty, data.pkap)
      CommsTower.workers(data.pkap).handler process data.queries.head
      become(newCatchupSyncData, SYNCRONIZING)
      onChunkSyncComplete(data)

    case (CMDGetGossip, data: CatchupSyncData, SYNCRONIZING) =>
      // No queries left, disconnect from sync peer
      CommsTower.listeners(data.pkap) -= listener
      CommsTower.workers(data.pkap).disconnect
      onChunkSyncComplete(data)
      onTotalSyncComplete(data)

    case (CMDRestart, data: CatchupSyncData, SYNCRONIZING) =>
      // We are already disconnected from peer at this point
      become(InitSyncData(Nil, maxSyncs = 3), INIT)
      CommsTower.listeners(data.pkap) -= listener
      delayedAddSync

    case _ =>
  }

  def delayedAddSync: Unit = RxUtils.ioQueue.delay(2.seconds).map(_ => me process CMDAddSync).foreach(identity)
  def getRandomNode(augmentedNodes: NodeAnnouncements): NodeAnnouncement = scala.util.Random.shuffle(augmentedNodes).head
  def allShortChannelIds(sync: InitSync): List[ShortChannelId] = sync.replyChannelRangeMessages.flatMap(_.shortChannelIds.array)
  def isExcluded(shortId: ShortChannelId): Boolean = excludedShortIds.contains(shortId)

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
      result <- Sync.computeShortIdAndFlag(routerData.channels, routerConf.requestNodeAnnouncements, shortChannelId, stamps)
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