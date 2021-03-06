package com.btcontract.wallet.lnutils

import fr.acinq.eclair._
import com.btcontract.wallet.ln._
import fr.acinq.eclair.router.{ChannelUpdateExt, Sync}
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate}
import com.btcontract.wallet.ln.SyncMaster.ShortChanIdSet
import fr.acinq.eclair.router.Router.PublicChannel
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.ByteVector64
import scodec.bits.ByteVector


class SQLiteNetworkDataStore(val db: SQLiteInterface, updateTable: ChannelUpdateTable, announceTable: ChannelAnnouncementTable, excludedTable: ExcludedChannelTable) extends NetworkDataStore {
  def addChannelAnnouncement(ca: ChannelAnnouncement): Unit = db.change(announceTable.newSql, Array.emptyByteArray, ca.shortChannelId.toJavaLong, ca.nodeId1.value.toArray, ca.nodeId2.value.toArray)
  def addExcludedChannel(shortId: ShortChannelId, untilStamp: Long): Unit = db.change(excludedTable.newSql, shortId.toJavaLong, System.currentTimeMillis + untilStamp: java.lang.Long)
  def listExcludedChannels: Set[Long] = db.select(excludedTable.selectSql, System.currentTimeMillis.toString).set(_ long excludedTable.shortChannelId)
  def listChannelsWithOneUpdate: ShortChanIdSet = db.select(updateTable.selectHavingOneUpdate).set(_ long updateTable.sid).map(ShortChannelId.apply)
  def incrementChannelScore(cu: ChannelUpdate): Unit = db.change(updateTable.updScoreSql, cu.shortChannelId.toJavaLong, cu.position)
  def removeChannelUpdate(shortId: ShortChannelId): Unit = db.change(updateTable.killSql, shortId.toJavaLong)

  def listChannelAnnouncements: Iterable[ChannelAnnouncement] = db select announceTable.selectAllSql iterable { rc =>
    ChannelAnnouncement(nodeSignature1 = ByteVector64.Zeroes, nodeSignature2 = ByteVector64.Zeroes, bitcoinSignature1 = ByteVector64.Zeroes, bitcoinSignature2 = ByteVector64.Zeroes,
      features = Features.empty, chainHash = LNParams.chainHash, shortChannelId = ShortChannelId(rc long announceTable.shortChannelId), nodeId1 = PublicKey(rc byteVec announceTable.nodeId1),
      nodeId2 = PublicKey(rc byteVec announceTable.nodeId2), bitcoinKey1 = invalidPubKey, bitcoinKey2 = invalidPubKey)
  }

  def addChannelUpdateByPosition(cu: ChannelUpdate): Unit = {
    val feeProportionalMillionths: java.lang.Long = cu.feeProportionalMillionths
    val cltvExpiryDelta: java.lang.Integer = cu.cltvExpiryDelta.toInt
    val htlcMinimumMsat: java.lang.Long = cu.htlcMinimumMsat.toLong
    val htlcMaxMsat: java.lang.Long = cu.htlcMaximumMsat.get.toLong
    val messageFlags: java.lang.Integer = cu.messageFlags.toInt
    val channelFlags: java.lang.Integer = cu.channelFlags.toInt
    val feeBaseMsat: java.lang.Long = cu.feeBaseMsat.toLong
    val timestamp: java.lang.Long = cu.timestamp

    val crc32: java.lang.Long = Sync.getChecksum(cu)

    db.change(updateTable.newSql, cu.shortChannelId.toJavaLong, timestamp, messageFlags, channelFlags,
      cltvExpiryDelta, htlcMinimumMsat, feeBaseMsat, feeProportionalMillionths, htlcMaxMsat, cu.position,
      1L: java.lang.Long, crc32)

    db.change(updateTable.updSQL, timestamp, messageFlags, channelFlags, cltvExpiryDelta, htlcMinimumMsat,
      feeBaseMsat, feeProportionalMillionths, htlcMaxMsat, crc32, cu.shortChannelId.toJavaLong, cu.position)
  }

  def listChannelUpdates: Iterable[ChannelUpdateExt] =
    db select updateTable.selectAllSql iterable { rc =>
      val cltvExpiryDelta = CltvExpiryDelta(rc int updateTable.cltvExpiryDelta)
      val htlcMinimumMsat = MilliSatoshi(rc long updateTable.minMsat)
      val htlcMaximumMsat = MilliSatoshi(rc long updateTable.maxMsat)
      val shortChannelId = ShortChannelId(rc long updateTable.sid)
      val feeBaseMsat = MilliSatoshi(rc long updateTable.base)
      val channelFlags = rc int updateTable.chanFlags
      val messageFlags = rc int updateTable.msgFlags

      val update = ChannelUpdate(signature = ByteVector64.Zeroes, chainHash = LNParams.chainHash, shortChannelId,
        timestamp = rc long updateTable.timestamp, messageFlags.toByte, channelFlags.toByte, cltvExpiryDelta,
        htlcMinimumMsat, feeBaseMsat, feeProportionalMillionths = rc long updateTable.proportional,
        htlcMaximumMsat = Some(htlcMaximumMsat), unknownFields = ByteVector.empty)

      ChannelUpdateExt(update, rc long updateTable.crc32,
        rc long updateTable.score, updateTable.useHeuristics)
    }

  def getRoutingData: Map[ShortChannelId, PublicChannel] = {
    val shortId2Updates = listChannelUpdates.groupBy(_.update.shortChannelId)

    val tuples = listChannelAnnouncements flatMap { ann =>
      shortId2Updates get ann.shortChannelId collectFirst {
        case List(u1, u2) if ChannelUpdate.POSITION1NODE == u1.update.position => ann.shortChannelId -> PublicChannel(Some(u1), Some(u2), ann)
        case List(u2, u1) if ChannelUpdate.POSITION2NODE == u2.update.position => ann.shortChannelId -> PublicChannel(Some(u1), Some(u2), ann)
        case List(u1) if ChannelUpdate.POSITION1NODE == u1.update.position => ann.shortChannelId -> PublicChannel(Some(u1), None, ann)
        case List(u2) if ChannelUpdate.POSITION2NODE == u2.update.position => ann.shortChannelId -> PublicChannel(None, Some(u2), ann)
      }
    }

    tuples.toMap
  }

  def removeGhostChannels(ghostIds: ShortChanIdSet, oneSideIds: ShortChanIdSet): Unit = db txWrap {
    for (shortId <- oneSideIds) addExcludedChannel(shortId, 1000L * 3600 * 24 * 14) // Exclude for two weeks, maybe second update will show up by then
    for (shortId <- ghostIds ++ oneSideIds) removeChannelUpdate(shortId) // Make sure we only have known channels with both updates

    db.change(excludedTable.killPresentInChans) // Remove from excluded if present in channels (minority says it's bad, majority says it's good)
    db.change(announceTable.killNotPresentInChans) // Remove from announces if not present in channels (announce for excluded channel)
    db.change(excludedTable.killOldSql, System.currentTimeMillis: java.lang.Long) // Give old excluded channels a second chance
  }

  def processPureData(pure: PureRoutingData): Unit = db txWrap {
    for (announce <- pure.announces) addChannelAnnouncement(announce)
    for (update <- pure.updates) addChannelUpdateByPosition(update)

    for (core <- pure.excluded) {
      // If max is empty then peer uses an old software and may update it soon, otherwise capacity is unlikely to increase
      val untilStamp = if (core.htlcMaximumMsat.isEmpty) 1000L * 3600 * 24 * 30 else 1000L * 3600 * 24 * 300
      addExcludedChannel(core.shortChannelId, untilStamp)
    }
  }

  def processCompleteHostedData(pure: CompleteHostedRoutingData): Unit = db txWrap {
    // Unlike normal channels here we allow one-sided update channels to be used for now
    // First, clear out everything in hosted channel databases
    db.change(announceTable.killAllSql)
    db.change(updateTable.killAllSql)

    // Then insert new data
    for (announce <- pure.announces) addChannelAnnouncement(announce)
    for (update <- pure.updates) addChannelUpdateByPosition(update)
    // And finally remove announces without any updates
    db.change(announceTable.killNotPresentInChans)
  }
}
