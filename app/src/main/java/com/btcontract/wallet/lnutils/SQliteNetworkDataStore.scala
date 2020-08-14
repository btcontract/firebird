package com.btcontract.wallet.lnutils

import fr.acinq.eclair._
import fr.acinq.bitcoin.{ByteVector32, ByteVector64}
import fr.acinq.eclair.router.Router.{Data, PublicChannel}
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate}
import com.btcontract.wallet.ln.crypto.Tools.{bytes2VecView, random}
import com.btcontract.wallet.ln.{CatchupSyncData, NetworkDataStore}
import com.btcontract.wallet.ln.SyncMaster.ShortChanIdSet
import scala.collection.immutable.SortedMap
import fr.acinq.eclair.router.StaleChannels
import fr.acinq.bitcoin.Crypto.PublicKey
import scodec.bits.ByteVector


class SQliteNetworkDataStore(db: LNOpenHelper) extends NetworkDataStore {
  val dummyPubKey: PublicKey = PublicKey(random getBytes 33)

  def removeChannel(sid: ShortChannelId): Unit = {
    db.change(ChannelAnnouncementTable.killSql, sid.toString)
    db.change(ChannelUpdateTable.killSql, sid.toString)
  }

  def addChannelAnnouncement(ca: ChannelAnnouncement): Unit =
    db.change(ChannelAnnouncementTable.newSql, params = Array.emptyByteArray,
      ca.shortChannelId.toLong: java.lang.Long, ca.nodeId1.value.toArray,
      ca.nodeId2.value.toArray)

  def listChannelAnnouncements: Iterable[ChannelAnnouncement] =
    db select ChannelAnnouncementTable.selectAllSql map { rc =>
      val nodeId1 = PublicKey(rc bytes ChannelAnnouncementTable.nodeId1)
      val nodeId2 = PublicKey(rc bytes ChannelAnnouncementTable.nodeId2)
      val shortChannelId = ShortChannelId(rc long ChannelAnnouncementTable.shortChannelId)
      ChannelAnnouncement(nodeSignature1 = ByteVector64.Zeroes, nodeSignature2 = ByteVector64.Zeroes,
        bitcoinSignature1 = ByteVector64.Zeroes, bitcoinSignature2 = ByteVector64.Zeroes, features = Features.empty,
        chainHash = ByteVector32.Zeroes, shortChannelId, nodeId1, nodeId2, bitcoinKey1 = dummyPubKey, bitcoinKey2 = dummyPubKey)
    }

  def addChannelUpdate(cu: ChannelUpdate): Unit = {
    val feeProportionalMillionths = cu.feeProportionalMillionths: java.lang.Long
    val cltvExpiryDelta = cu.cltvExpiryDelta.toInt: java.lang.Integer
    val htlcMinimumMsat = cu.htlcMinimumMsat.toLong: java.lang.Long
    val htlcMaxMsat = cu.htlcMaximumMsat.get.toLong: java.lang.Long
    val position = { if (cu.isNode1) 1 else 2 }: java.lang.Integer
    val messageFlags = cu.messageFlags.toInt: java.lang.Integer
    val channelFlags = cu.channelFlags.toInt: java.lang.Integer
    val feeBaseMsat = cu.feeBaseMsat.toLong: java.lang.Long
    val timestamp = cu.timestamp: java.lang.Long

    db.change(ChannelUpdateTable.newSql, cu.shortChannelId.toLong: java.lang.Long, timestamp, messageFlags,
      channelFlags, cltvExpiryDelta, htlcMinimumMsat, feeBaseMsat, feeProportionalMillionths, htlcMaxMsat, position)

    db.change(ChannelUpdateTable.updSQL, timestamp, messageFlags, channelFlags, cltvExpiryDelta,
      htlcMinimumMsat, feeBaseMsat, feeProportionalMillionths, htlcMaxMsat)
  }

  def incrementScore(cu: ChannelUpdate): Unit = {
    val shortId = cu.shortChannelId.toLong: java.lang.Long
    val position = { if (cu.isNode1) 1 else 2 }: java.lang.Integer
    db.change(ChannelUpdateTable.updScoreSql, shortId, position)
  }

  def listChannelUpdates: Iterable[ChannelUpdate] =
    db select ChannelUpdateTable.selectAllSql map { rc =>
      val channelFlags = rc int ChannelUpdateTable.channelFlags
      val messageFlags = rc int ChannelUpdateTable.messageFlags
      val feeBaseMsat = MilliSatoshi(rc long ChannelUpdateTable.base)
      val htlcMaximumMsat = MilliSatoshi(rc long ChannelUpdateTable.maxMsat)
      val htlcMinimumMsat = MilliSatoshi(rc long ChannelUpdateTable.minMsat)
      val shortChannelId = ShortChannelId(rc long ChannelUpdateTable.shortChannelId)
      val cltvExpiryDelta = CltvExpiryDelta(rc int ChannelUpdateTable.cltvExpiryDelta)
      val update = ChannelUpdate(signature = ByteVector64.Zeroes, chainHash = ByteVector32.Zeroes, shortChannelId,
        timestamp = rc long ChannelUpdateTable.timestamp, messageFlags.toByte, channelFlags.toByte, cltvExpiryDelta,
        htlcMinimumMsat, feeBaseMsat, feeProportionalMillionths = rc long ChannelUpdateTable.proportional,
        htlcMaximumMsat = Some(htlcMaximumMsat), unknownFields = ByteVector.empty)

      // We can't make score a field so assign it here
      update.score = rc long ChannelUpdateTable.score
      update
    }

  def getCurrentRoutingMap: SortedMap[ShortChannelId, PublicChannel] = {
    val updates = listChannelUpdates.toList.groupBy(_.shortChannelId)
    val announcements = listChannelAnnouncements.toList

    val res = announcements flatMap { ann =>
      updates get ann.shortChannelId collect {
        case update1 :: update2 :: Nil if update1.isNode1 => ann.shortChannelId -> PublicChannel(ann, Some(update1), Some(update2))
        case update2 :: update1 :: Nil if update1.isNode1 => ann.shortChannelId -> PublicChannel(ann, Some(update2), Some(update1))
        case update1 :: Nil if update1.isNode1 => ann.shortChannelId -> PublicChannel(ann, Some(update1), None)
        case update2 :: Nil => ann.shortChannelId -> PublicChannel(ann, None, Some(update2))
      }
    }

    SortedMap(res:_*)
  }

  def addExcludedChannel(sid: ShortChannelId, until: Long): Unit = {
    val bannedUntil = System.currentTimeMillis + until: java.lang.Long
    val shortChannelId = sid.toLong: java.lang.Long

    db.change(ExcludedChannelTable.newSql, shortChannelId, bannedUntil)
    db.change(ExcludedChannelTable.updSql, bannedUntil, shortChannelId)
    removeChannel(sid)
  }

  def listExcludedChannels(until: Long): ShortChanIdSet =
    db.select(ExcludedChannelTable.selectAllSql, until.toString)
      .set(_ long ExcludedChannelTable.shortChannelId)
      .map(ShortChannelId.apply)

  def processCatchup(data: CatchupSyncData): Unit = db txWrap {
    val timestamp = System.currentTimeMillis + 60 * 24 * 3600 * 1000L
    for (channelUpdate <- data.updates) addChannelUpdate(channelUpdate)
    for (announcement <- data.announcements) addChannelAnnouncement(announcement)
    for (shortChannelId <- data.excluded) addExcludedChannel(shortChannelId, timestamp)
  }

  def removeStaleChannels(data: Data, chainTip: Long): Unit = db txWrap {
    val staleChannels = StaleChannels.getStaleChannels(data.channels.values, chainTip)
    for (channel <- staleChannels) removeChannel(channel.ann.shortChannelId)
  }
}
