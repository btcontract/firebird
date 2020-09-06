package com.btcontract.wallet.lnutils

import fr.acinq.eclair._
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate}
import com.btcontract.wallet.ln.{LNParams, NetworkDataStore, PureRoutingData}
import com.btcontract.wallet.ln.crypto.Tools.bytes2VecView
import fr.acinq.eclair.wire.ChannelUpdate.PositionalId
import fr.acinq.eclair.router.Router.PublicChannel
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.ByteVector64
import scodec.bits.ByteVector


class SQliteNetworkDataStore(db: LNOpenHelper) extends NetworkDataStore { me =>
  def positionalIdAsString(shortId: ShortChannelId, position: java.lang.Integer): String = shortId.id + "/" + position
  def incrementChannelScore(cu: ChannelUpdate): Unit = db.change(params = positionalIdAsString(cu.shortChannelId, cu.position), sql = ChannelUpdateTable.updScoreSql)
  def removeChannelUpdateByPosition(shortId: ShortChannelId, position: java.lang.Integer): Unit = db.change(params = positionalIdAsString(shortId, position), sql = ChannelUpdateTable.killByPositionalIdSql)
  def addChannelAnnouncement(ca: ChannelAnnouncement): Unit = db.change(ChannelAnnouncementTable.newSql, Array.emptyByteArray, ca.shortChannelId.toJavaLong, ca.nodeId1.value.toArray, ca.nodeId2.value.toArray)

  def listExcludedChannels: Set[PositionalId] =
    db.select(ExcludedChannelTable.selectSql, System.currentTimeMillis.toString) set { rc =>
      (rc long ExcludedChannelTable.shortChannelId, rc int ExcludedChannelTable.position)
    }

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

    val dbPositionalId: String = positionalIdAsString(cu.shortChannelId, cu.position)
    db.change(ChannelUpdateTable.newSql, cu.shortChannelId.toJavaLong, timestamp, messageFlags, channelFlags, cltvExpiryDelta, htlcMinimumMsat, feeBaseMsat, feeProportionalMillionths, htlcMaxMsat, dbPositionalId)
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
        case Vector(update1, update2) if ChannelUpdate.POSITION_NODE_1 == update1.position => ann.shortChannelId -> PublicChannel(Some(update1), Some(update2), ann)
        case Vector(update2, update1) if ChannelUpdate.POSITION_NODE_1 == update1.position => ann.shortChannelId -> PublicChannel(Some(update1), Some(update2), ann)
        case Vector(update1) if ChannelUpdate.POSITION_NODE_1 == update1.position => ann.shortChannelId -> PublicChannel(Some(update1), None, ann)
        case Vector(update2) => ann.shortChannelId -> PublicChannel(None, Some(update2), ann)
      }
    }

    tuples.toMap
  }

  def removeGhostChannels(ghostIds: Set[ShortChannelId] = Set.empty): Unit = {
    // Once sync is complete we may have shortIds which our peers know nothing about
    // this means related channels have been closed and we need to remove them locally
    db txWrap {
      for (shortId <- ghostIds) {
        db.change(ChannelAnnouncementTable.killSql, shortId.toJavaLong)
        removeChannelUpdateByPosition(shortId, ChannelUpdate.POSITION_NODE_1)
        removeChannelUpdateByPosition(shortId, ChannelUpdate.POSITION_NODE_2)
      }

      db.change(ExcludedChannelTable.killPresentInChans) // Remove from excluded if present in channels (minority says it's bad, majority says it's good)
      db.change(ChannelAnnouncementTable.killNotPresentInChans) // Remove from announces if not present in channels (announce for excluded channel)
      db.change(ExcludedChannelTable.killOldSql, System.currentTimeMillis: java.lang.Long) // Give old excluded channels a second chance
    }
  }

  def processPureData(pure: PureRoutingData): Unit = db txWrap {
    for (announce <- pure.announces) addChannelAnnouncement(announce)
    for (update <- pure.updates) addChannelUpdateByPosition(update)

    for (update <- pure.excluded) {
      val dbPositionalId: String = positionalIdAsString(update.shortChannelId, update.position)
      val until = if (update.htlcMaximumMsat.isEmpty) 1000L * 3600 * 24 * 30 else 1000L * 3600 * 24 * 300
      // If htlcMaximumMsat is empty then peer uses an old software and may update it soon, otherwise capacity is unlikely to increase so ban it for a longer time
      db.change(ExcludedChannelTable.newSql, update.shortChannelId.toJavaLong, System.currentTimeMillis + until: java.lang.Long, update.position, dbPositionalId)
    }
  }
}
