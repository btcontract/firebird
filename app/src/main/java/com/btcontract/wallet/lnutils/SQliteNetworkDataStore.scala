package com.btcontract.wallet.lnutils

import fr.acinq.eclair._
import fr.acinq.eclair.router.{ChannelUpdateExt, Sync}
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate}
import com.btcontract.wallet.ln.{LNParams, NetworkDataStore, PureRoutingData}
import com.btcontract.wallet.ln.crypto.Tools.bytes2VecView
import com.btcontract.wallet.ln.SyncMaster.ShortChanIdSet
import fr.acinq.eclair.router.Router.PublicChannel
import fr.acinq.bitcoin.Crypto.PublicKey
import scodec.bits.ByteVector


class SQliteNetworkDataStore(val db: SQLiteInterface, updateTable: ChannelUpdateTable, announceTable: ChannelAnnouncementTable, excludedTable: ExcludedChannelTable) extends NetworkDataStore {
  def addChannelAnnouncement(ca: ChannelAnnouncement): Unit = db.change(announceTable.newSql, Array.emptyByteArray, ca.shortChannelId.toJavaLong, ca.nodeId1.value.toArray, ca.nodeId2.value.toArray)
  def addExcludedChannel(shortId: ShortChannelId, untilStamp: Long): Unit = db.change(excludedTable.newSql, shortId.toJavaLong, System.currentTimeMillis + untilStamp: java.lang.Long)
  def listExcludedChannels: Set[Long] = db.select(excludedTable.selectSql, System.currentTimeMillis.toString).set(_ long excludedTable.shortChannelId)
  def incrementChannelScore(cu: ChannelUpdate): Unit = db.change(updateTable.updScoreSql, cu.shortChannelId.toJavaLong, cu.position)
  def removeChannelUpdate(shortId: ShortChannelId): Unit = db.change(updateTable.killSql, shortId.toJavaLong)

  def listChannelAnnouncements: Vector[ChannelAnnouncement] = db select announceTable.selectAllSql vec { rc =>
    ChannelAnnouncement(nodeSignature1 = announceTable.sigFiller, nodeSignature2 = announceTable.sigFiller, bitcoinSignature1 = announceTable.sigFiller, bitcoinSignature2 = announceTable.sigFiller,
      features = Features.empty, chainHash = LNParams.chainHash, shortChannelId = ShortChannelId(rc long announceTable.shortChannelId), nodeId1 = PublicKey(rc bytes announceTable.nodeId1),
      nodeId2 = PublicKey(rc bytes announceTable.nodeId2), bitcoinKey1 = invalidPubKey, bitcoinKey2 = invalidPubKey)
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

  def listChannelUpdates: Vector[ChannelUpdateExt] =
    db select updateTable.selectAllSql vec { rc =>
      val messageFlags = rc int updateTable.msgFlags
      val channelFlags = rc int updateTable.chanFlags
      val feeBaseMsat = MilliSatoshi(rc long updateTable.base)
      val shortChannelId = ShortChannelId(rc long updateTable.sid)
      val htlcMaximumMsat = MilliSatoshi(rc long updateTable.maxMsat)
      val htlcMinimumMsat = MilliSatoshi(rc long updateTable.minMsat)
      val cltvExpiryDelta = CltvExpiryDelta(rc int updateTable.cltvExpiryDelta)

      ChannelUpdateExt(ChannelUpdate(signature = byteVector64One, chainHash = LNParams.chainHash, shortChannelId,
        timestamp = rc long updateTable.timestamp, messageFlags.toByte, channelFlags.toByte, cltvExpiryDelta,
        htlcMinimumMsat, feeBaseMsat, feeProportionalMillionths = rc long updateTable.proportional,
        htlcMaximumMsat = Some(htlcMaximumMsat), unknownFields = ByteVector.empty),
        score = rc long updateTable.score, crc32 = rc long updateTable.crc32)
    }

  def getRoutingData: Map[ShortChannelId, PublicChannel] = {
    val shortId2Updates = listChannelUpdates.groupBy(_.update.shortChannelId)

    val tuples = listChannelAnnouncements flatMap { ann =>
      shortId2Updates get ann.shortChannelId collectFirst {
        case Vector(u1, u2) if ChannelUpdate.POSITION1NODE == u1.update.position => ann.shortChannelId -> PublicChannel(Some(u1), Some(u2), ann)
        case Vector(u2, u1) if ChannelUpdate.POSITION2NODE == u2.update.position => ann.shortChannelId -> PublicChannel(Some(u1), Some(u2), ann)
        case Vector(u1) if ChannelUpdate.POSITION1NODE == u1.update.position => ann.shortChannelId -> PublicChannel(Some(u1), None, ann)
        case Vector(u2) if ChannelUpdate.POSITION2NODE == u2.update.position => ann.shortChannelId -> PublicChannel(None, Some(u2), ann)
      }
    }

    tuples.toMap
  }

  def removeGhostChannels(ghostIds: ShortChanIdSet): Unit = db txWrap {
    // We might have shortIds which our peers know nothing about, as well as channels with one update, remove all of them
    val chansWithOneUpdate = db.select(updateTable.selectHavingOneUpdate).set(_ long updateTable.sid).map(ShortChannelId.apply)
    for (shortId <- chansWithOneUpdate) addExcludedChannel(shortId, 1000L * 3600 * 24 * 14) // Exclude for two weeks, maybe second update will show up by then
    for (shortId <- ghostIds ++ chansWithOneUpdate) removeChannelUpdate(shortId) // Make sure we only have channels with both updates

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
}
