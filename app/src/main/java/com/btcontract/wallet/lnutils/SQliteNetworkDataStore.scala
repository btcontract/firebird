package com.btcontract.wallet.lnutils

import fr.acinq.eclair._
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate}
import com.btcontract.wallet.ln.{LNParams, NetworkDataStore, PureRoutingData}
import com.btcontract.wallet.ln.crypto.Tools.bytes2VecView
import com.btcontract.wallet.ln.SyncMaster.ShortChanIdSet
import fr.acinq.eclair.router.Router.PublicChannel
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.ByteVector64
import scodec.bits.ByteVector


class SQliteNetworkDataStore(db: LNOpenHelper) extends NetworkDataStore {
  def addExcludedChannel(shortId: ShortChannelId): Unit = db.change(ExcludedChannelTable.newSql, shortId.toJavaLong)
  def listExcludedChannels: ShortChanIdSet = db.select(ExcludedChannelTable.selectSql).set(_ long ExcludedChannelTable.shortChannelId).map(ShortChannelId.apply)
  def incrementChannelScore(cu: ChannelUpdate): Unit = db.change(ChannelUpdateTable.updScoreSql, cu.shortChannelId.toJavaLong, cu.directionality)
  def removeChannelUpdate(cu: ChannelUpdate): Unit = db.change(ChannelUpdateTable.killSql, cu.shortChannelId.toJavaLong)

  def addChannelAnnouncement(ca: ChannelAnnouncement): Unit =
    db.change(ChannelAnnouncementTable.newSql, params = Array.emptyByteArray,
      ca.shortChannelId.toJavaLong, ca.nodeId1.value.toArray, ca.nodeId2.value.toArray)

  def listChannelAnnouncements: Vector[ChannelAnnouncement] =
    db select ChannelAnnouncementTable.selectAllSql vec { rc =>
      val nodeId1 = PublicKey(rc bytes ChannelAnnouncementTable.nodeId1)
      val nodeId2 = PublicKey(rc bytes ChannelAnnouncementTable.nodeId2)
      val shortChannelId = ShortChannelId(rc long ChannelAnnouncementTable.shortChannelId)
      ChannelAnnouncement(nodeSignature1 = ByteVector64.Zeroes, nodeSignature2 = ByteVector64.Zeroes,
        bitcoinSignature1 = ByteVector64.Zeroes, bitcoinSignature2 = ByteVector64.Zeroes, features = Features.empty,
        chainHash = LNParams.chainHash, shortChannelId, nodeId1, nodeId2, bitcoinKey1 = dummyPubKey, bitcoinKey2 = dummyPubKey)
    }

  def addChannelUpdate(cu: ChannelUpdate): Unit = {
    val feeProportionalMillionths: java.lang.Long = cu.feeProportionalMillionths
    val cltvExpiryDelta: java.lang.Integer = cu.cltvExpiryDelta.toInt
    val htlcMinimumMsat: java.lang.Long = cu.htlcMinimumMsat.toLong
    val htlcMaxMsat: java.lang.Long = cu.htlcMaximumMsat.get.toLong
    val messageFlags: java.lang.Integer = cu.messageFlags.toInt
    val channelFlags: java.lang.Integer = cu.channelFlags.toInt
    val feeBaseMsat: java.lang.Long = cu.feeBaseMsat.toLong
    val timestamp: java.lang.Long = cu.timestamp

    db.change(ChannelUpdateTable.newSql, cu.shortChannelId.toJavaLong, timestamp, messageFlags, channelFlags,
      cltvExpiryDelta, htlcMinimumMsat, feeBaseMsat, feeProportionalMillionths, htlcMaxMsat, cu.directionality)

    db.change(ChannelUpdateTable.updSQL, timestamp, messageFlags, channelFlags, cltvExpiryDelta,
      htlcMinimumMsat, feeBaseMsat, feeProportionalMillionths, htlcMaxMsat)
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

  def getRoutingData: (Map[ShortChannelId, PublicChannel], ShortChanIdSet) = {
    val chanUpdatesByShortId = listChannelUpdates.groupBy(_.shortChannelId)
    val shortIdSet = chanUpdatesByShortId.keys.toSet

    val tuples = listChannelAnnouncements flatMap { ann =>
      chanUpdatesByShortId get ann.shortChannelId collectFirst {
        case Vector(update1, update2) if update1.isNode1 => ann.shortChannelId -> PublicChannel(Some(update1), Some(update2), ann)
        case Vector(update2, update1) if update1.isNode1 => ann.shortChannelId -> PublicChannel(Some(update1), Some(update2), ann)
        case Vector(update1) if update1.isNode1 => ann.shortChannelId -> PublicChannel(Some(update1), None, ann)
        case Vector(update2) => ann.shortChannelId -> PublicChannel(None, Some(update2), ann)
      }
    }

    (tuples.toMap, shortIdSet)
  }

  def removeGhostChannels(shortIds: ShortChanIdSet): Unit =
    // Transactional inserts for MUCH faster performance
    db txWrap {
      for (shortId <- shortIds) {
        // Remove local channels which peers do not have now
        db.change(ChannelUpdateTable.killSql, shortId.toJavaLong)
        db.change(ExcludedChannelTable.killSql, shortId.toJavaLong)
        db.change(ChannelAnnouncementTable.killSql, shortId.toJavaLong)
      }

      db.change(ExcludedChannelTable.killPresentInChans) // Remove from excluded if present in channels (one peer says it's not good, other two peers say it's good)
      db.change(ChannelAnnouncementTable.killNotPresentInChans) // Remove from announces if not present in channels (announce for excluded channel)
    }

  def processPureData(pure: PureRoutingData): Unit =
    db txWrap {
      for (announcement <- pure.announces) addChannelAnnouncement(announcement)
      for (channelUpdate <- pure.updates) addChannelUpdate(channelUpdate)
      for (shortId <- pure.excluded) addExcludedChannel(shortId)
    }
}
