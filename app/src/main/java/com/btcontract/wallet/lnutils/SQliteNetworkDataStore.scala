package com.btcontract.wallet.lnutils

import fr.acinq.eclair._
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate}
import com.btcontract.wallet.ln.{LNParams, NetworkDataStore, PureRoutingData}
import com.btcontract.wallet.ln.crypto.Tools.bytes2VecView
import fr.acinq.eclair.router.Router.PublicChannel
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.ByteVector64
import scodec.bits.ByteVector


class SQliteNetworkDataStore(db: SQLiteInterface) extends NetworkDataStore { me =>
  def removeChannelUpdate(shortId: ShortChannelId): Unit = db.change(ChannelUpdateTable.killSql, shortId.toJavaLong)
  def incrementChannelScore(cu: ChannelUpdate): Unit = db.change(ChannelUpdateTable.updScoreSql, cu.shortChannelId.toJavaLong, cu.position)
  def addChannelAnnouncement(ca: ChannelAnnouncement): Unit = db.change(ChannelAnnouncementTable.newSql, Array.emptyByteArray, ca.shortChannelId.toJavaLong, ca.nodeId1.value.toArray, ca.nodeId2.value.toArray)
  def addExcludedChannel(shortId: ShortChannelId, untilStamp: Long): Unit = db.change(ExcludedChannelTable.newSql, shortId.toJavaLong, System.currentTimeMillis + untilStamp: java.lang.Long)
  def listExcludedChannels: Set[Long] = db.select(ExcludedChannelTable.selectSql, System.currentTimeMillis.toString).set(_ long ExcludedChannelTable.shortChannelId)

  def listChannelAnnouncements: Vector[ChannelAnnouncement] =
    db select ChannelAnnouncementTable.selectAllSql vec { rc =>
      val nodeId1 = PublicKey(rc bytes ChannelAnnouncementTable.nodeId1)
      val nodeId2 = PublicKey(rc bytes ChannelAnnouncementTable.nodeId2)
      val shortChannelId = ShortChannelId(rc long ChannelAnnouncementTable.shortChannelId)
      ChannelAnnouncement(nodeSignature1 = ByteVector64.Zeroes, nodeSignature2 = ByteVector64.Zeroes,
        bitcoinSignature1 = ByteVector64.Zeroes, bitcoinSignature2 = ByteVector64.Zeroes, features = Features.empty,
        chainHash = LNParams.chainHash, shortChannelId, nodeId1, nodeId2, bitcoinKey1 = dummyPubKey, bitcoinKey2 = dummyPubKey)
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

    db.change(ChannelUpdateTable.newSql, cu.shortChannelId.toJavaLong, timestamp, messageFlags, channelFlags, cltvExpiryDelta, htlcMinimumMsat, feeBaseMsat, feeProportionalMillionths, htlcMaxMsat, cu.position)
    db.change(ChannelUpdateTable.updSQL, timestamp, messageFlags, channelFlags, cltvExpiryDelta, htlcMinimumMsat, feeBaseMsat, feeProportionalMillionths, htlcMaxMsat)
  }

  def listChannelUpdates: Vector[ChannelUpdate] =
    db select ChannelUpdateTable.selectAllSql vec { rc =>
      val channelFlags = rc int ChannelUpdateTable.channelFlags
      val messageFlags = rc int ChannelUpdateTable.messageFlags
      val feeBaseMsat = MilliSatoshi(rc long ChannelUpdateTable.base)
      val htlcMaximumMsat = MilliSatoshi(rc long ChannelUpdateTable.maxMsat)
      val htlcMinimumMsat = MilliSatoshi(rc long ChannelUpdateTable.minMsat)
      val shortChannelId = ShortChannelId(rc long ChannelUpdateTable.shortChannelId)
      val cltvExpiryDelta = CltvExpiryDelta(rc int ChannelUpdateTable.cltvExpiryDelta)
      val update = ChannelUpdate(signature = ByteVector64.Zeroes, chainHash = LNParams.chainHash, shortChannelId,
        timestamp = rc long ChannelUpdateTable.timestamp, messageFlags.toByte, channelFlags.toByte, cltvExpiryDelta,
        htlcMinimumMsat, feeBaseMsat, feeProportionalMillionths = rc long ChannelUpdateTable.proportional,
        htlcMaximumMsat = Some(htlcMaximumMsat), unknownFields = ByteVector.empty)

      // We can't make score a field so assign it here
      update.score = rc long ChannelUpdateTable.score
      update
    }

  def getRoutingData: Map[ShortChannelId, PublicChannel] = {
    val chanUpdatesByShortId = listChannelUpdates.groupBy(_.shortChannelId)

    val tuples = listChannelAnnouncements flatMap { ann =>
      chanUpdatesByShortId get ann.shortChannelId collectFirst {
        // We disregard channels with one update since that state signals something is wrong with one of peers
        case Vector(u1, u2) if ChannelUpdate.POSITION_NODE_1 == u1.position => ann.shortChannelId -> PublicChannel(Some(u1), Some(u2), ann)
        case Vector(u2, u1) if ChannelUpdate.POSITION_NODE_2 == u2.position => ann.shortChannelId -> PublicChannel(Some(u1), Some(u2), ann)
      }
    }

    tuples.toMap
  }

  def removeGhostChannels(ghostIds: Set[ShortChannelId] = Set.empty): Unit = db txWrap {
    // Once sync is complete we may have shortIds which our peers know nothing about, we may also have channels with one update, remove all of them
    val chansWithOneUpdate = db.select(ChannelUpdateTable.selectHavingOneUpdate).set(_ long ChannelUpdateTable.shortChannelId).map(ShortChannelId.apply)
    println(s"chansWithOneUpdate.size: ${chansWithOneUpdate.size}")
    for (shortId <- chansWithOneUpdate) addExcludedChannel(shortId, 1000L * 3600 * 24 * 14) // Exclude for two weeks, maybe second update will show up by then
    for (shortId <- ghostIds ++ chansWithOneUpdate) removeChannelUpdate(shortId) // Make sure we only have channels with both updates

    db.change(ExcludedChannelTable.killPresentInChans) // Remove from excluded if present in channels (minority says it's bad, majority says it's good)
    db.change(ChannelAnnouncementTable.killNotPresentInChans) // Remove from announces if not present in channels (announce for excluded channel)
    db.change(ExcludedChannelTable.killOldSql, System.currentTimeMillis: java.lang.Long) // Give old excluded channels a second chance
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
