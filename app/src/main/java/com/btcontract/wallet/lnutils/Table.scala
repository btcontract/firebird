package com.btcontract.wallet.lnutils

import android.database.sqlite.{SQLiteDatabase, SQLiteOpenHelper}
import com.btcontract.wallet.ln.PaymentMaster.SUCCEEDED
import com.btcontract.wallet.ln.crypto.Tools.runAnd
import com.btcontract.wallet.helper.RichCursor
import fr.acinq.eclair.byteVector64One
import fr.acinq.bitcoin.ByteVector64
import android.content.Context
import android.net.Uri


object DataTable extends Table {
  val (table, label, content) = ("data", "label", "content")
  val newSql = s"INSERT OR IGNORE INTO $table ($label, $content) VALUES (?, ?)"
  val updSql = s"UPDATE $table SET $content = ? WHERE $label = ?"
  val selectSql = s"SELECT * FROM $table WHERE $label = ?"
  val killSql = s"DELETE FROM $table WHERE $label = ?"

  def create(db: SQLiteDatabase): Unit = {
    db execSQL s"""CREATE TABLE IF NOT EXISTS $table (
      $id INTEGER PRIMARY KEY AUTOINCREMENT,
      $label TEXT NOT NULL UNIQUE,
      $content TEXT NOT NULL
    )"""
  }
}

object ChannelTable extends Table {
  val (table, identifier, data) = ("channel", "identifier", "data")
  val newSql = s"INSERT OR IGNORE INTO $table ($identifier, $data) VALUES (?, ?)"
  val updSql = s"UPDATE $table SET $data = ? WHERE $identifier = ?"
  val selectAllSql = s"SELECT * FROM $table ORDER BY $id DESC"
  val killSql = s"DELETE FROM $table WHERE $identifier = ?"

  def create(db: SQLiteDatabase): Unit = {
    db execSQL s"""CREATE TABLE IF NOT EXISTS $table (
      $id INTEGER PRIMARY KEY AUTOINCREMENT,
      $identifier TEXT NOT NULL UNIQUE,
      $data TEXT NOT NULL
    )"""
  }
}

abstract class ChannelAnnouncementTable(val table: String) extends Table {
  val (features, shortChannelId, nodeId1, nodeId2) = ("features", "shortchannelid", "nodeid1", "nodeid2")
  val newSql = s"INSERT OR IGNORE INTO $table ($features, $shortChannelId, $nodeId1, $nodeId2) VALUES (?, ?, ?, ?)"
  val selectAllSql = s"SELECT * FROM $table"
  val killNotPresentInChans: String
  val sigFiller: ByteVector64

  def create(db: SQLiteDatabase): Unit = {
    db execSQL s"""CREATE TABLE IF NOT EXISTS $table (
      $id INTEGER PRIMARY KEY AUTOINCREMENT, $features BLOB NOT NULL,
      $shortChannelId INTEGER NOT NULL UNIQUE, $nodeId1 BLOB NOT NULL,
      $nodeId2 BLOB NOT NULL
    )"""
  }
}

object NormalChannelAnnouncementTable extends ChannelAnnouncementTable("normal_announcements") {
  private val select = s"SELECT ${NormalChannelUpdateTable.shortChannelId} FROM ${NormalChannelUpdateTable.table}"
  val killNotPresentInChans = s"DELETE FROM $table WHERE $shortChannelId NOT IN ($select LIMIT 1000000)"
  val sigFiller: ByteVector64 = byteVector64One
}

object HostedChannelAnnouncementTable extends ChannelAnnouncementTable("hosted_announcements") {
  private val select = s"SELECT ${HostedChannelUpdateTable.shortChannelId} FROM ${HostedChannelUpdateTable.table}"
  val killNotPresentInChans = s"DELETE FROM $table WHERE $shortChannelId NOT IN ($select LIMIT 1000000)"
  val sigFiller: ByteVector64 = ByteVector64.Zeroes
}

abstract class ChannelUpdateTable(val table: String) extends Table {
  private val names = ("shortchannelid", "timestamp", "messageflags", "channelflags", "cltvdelta", "htlcminimum", "feebase", "feeproportional", "htlcmaximum", "position", "score")
  val (shortChannelId, timestamp, messageFlags, channelFlags, cltvExpiryDelta, minMsat, base, proportional, maxMsat, position, score) = names

  val updScoreSql = s"UPDATE $table SET $score = $score + 1 WHERE $shortChannelId = ? AND $position = ?"
  val updSQL = s"UPDATE $table SET $timestamp = ?, $messageFlags = ?, $channelFlags = ?, $cltvExpiryDelta = ?, $minMsat = ?, $base = ?, $proportional = ?, $maxMsat = ? WHERE $shortChannelId = ? AND $position = ?"
  val newSql = s"INSERT OR IGNORE INTO $table ($shortChannelId, $timestamp, $messageFlags, $channelFlags, $cltvExpiryDelta, $minMsat, $base, $proportional, $maxMsat, $position, $score) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)"
  val selectHavingOneUpdate = s"SELECT $shortChannelId FROM $table GROUP BY $shortChannelId HAVING COUNT($shortChannelId) < 2"
  val killSql = s"DELETE FROM $table WHERE $shortChannelId = ?"
  val selectAllSql = s"SELECT * FROM $table"

  def create(db: SQLiteDatabase): Unit = {
    db execSQL s"""CREATE TABLE IF NOT EXISTS $table (
      $id INTEGER PRIMARY KEY AUTOINCREMENT, $shortChannelId INTEGER NOT NULL, $timestamp INTEGER NOT NULL,
      $messageFlags INTEGER NOT NULL, $channelFlags INTEGER NOT NULL, $cltvExpiryDelta INTEGER NOT NULL, $minMsat INTEGER NOT NULL,
      $base INTEGER NOT NULL, $proportional INTEGER NOT NULL, $maxMsat INTEGER NOT NULL, $position INTEGER NOT NULL, $score INTEGER NOT NULL
    )"""

    db execSQL s"CREATE UNIQUE INDEX IF NOT EXISTS idx1$table ON $table ($shortChannelId, $position)"
  }
}

object NormalChannelUpdateTable extends ChannelUpdateTable("normal_updates")
object HostedChannelUpdateTable extends ChannelUpdateTable("hosted_updates")

abstract class ExcludedChannelTable(val table: String) extends Table {
  val Tuple2(shortChannelId, until) = ("shortchannelid", "excludeduntilstamp")
  val newSql = s"INSERT OR IGNORE INTO $table ($shortChannelId, $until) VALUES (?, ?)"
  val selectSql = s"SELECT * FROM $table WHERE $until > ? LIMIT 1000000"
  val killOldSql = s"DELETE FROM $table WHERE $until < ?"
  val killPresentInChans: String

  def create(db: SQLiteDatabase): Unit = {
    db execSQL s"""CREATE TABLE IF NOT EXISTS $table (
      $id INTEGER PRIMARY KEY AUTOINCREMENT,
      $shortChannelId INTEGER NOT NULL UNIQUE,
      $until INTEGER NOT NULL
    )"""

    // Excluded channels expire to give them a scond chance
    db execSQL s"CREATE INDEX IF NOT EXISTS idx1$table ON $table ($until)"
  }
}

object NormalExcludedChannelTable extends ExcludedChannelTable("normal_excluded_updates") {
  private val select = s"SELECT ${NormalChannelUpdateTable.shortChannelId} FROM ${NormalChannelUpdateTable.table}"
  val killPresentInChans = s"DELETE FROM $table WHERE $shortChannelId IN ($select LIMIT 1000000)"
}

object HostedExcludedChannelTable extends ExcludedChannelTable("hosted_excluded_updates") {
  private val select = s"SELECT ${HostedChannelUpdateTable.shortChannelId} FROM ${HostedChannelUpdateTable.table}"
  val killPresentInChans = s"DELETE FROM $table WHERE $shortChannelId IN ($select LIMIT 1000000)"
}

object PaymentTable extends Table {
  private val paymentTableFields = ("search", "payment", "nodeid", "pr", "preimage", "status", "stamp", "description", "action", "hash", "receivedmsat", "sentmsat", "feemsat", "balancesnap", "fiatratesnap", "incoming", "ext")
  val (search, table, nodeId, pr, preimage, status, stamp, description, action, hash, receivedMsat, sentMsat, feeMsat, balanceSnapMsat, fiatRateSnap, incoming, ext) = paymentTableFields
  val inserts = s"$nodeId, $pr, $preimage, $status, $stamp, $description, $action, $hash, $receivedMsat, $sentMsat, $feeMsat, $balanceSnapMsat, $fiatRateSnap, $incoming, $ext"
  val newSql = s"INSERT OR IGNORE INTO $table ($inserts) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
  val newVirtualSql = s"INSERT INTO $fts$table ($search, $hash) VALUES (?, ?)"

  // Selecting
  val selectOneSql = s"SELECT * FROM $table WHERE $hash = ?"
  def selectRecentSql(limit: Int) = s"SELECT * FROM $table ORDER BY $id DESC LIMIT $limit"
  val selectToNodeSummarySql = s"SELECT SUM($feeMsat), SUM($sentMsat), COUNT($id) FROM $table WHERE $nodeId = ? AND $status = ?"
  val selectBetweenSummarySql = s"SELECT SUM($feeMsat), SUM($receivedMsat), SUM($sentMsat), COUNT($id) FROM $table WHERE $stamp > ? AND $stamp < ? AND $status = ?"
  val searchSql = s"SELECT * FROM $table WHERE $hash IN (SELECT $hash FROM $fts$table WHERE $search MATCH ? LIMIT 25)"

  // Updating
  val updOkOutgoingSql = s"UPDATE $table SET $status = ?, $preimage = ?, $sentMsat = ?, $feeMsat = ? WHERE $hash = ?"
  val updOkIncomingSql = s"UPDATE $table SET $status = ?, $receivedMsat = ?, $stamp = ? WHERE $hash = ?"
  val updStatusSql = s"UPDATE $table SET $status = ? WHERE $hash = ? AND $status <> $SUCCEEDED"

  def create(db: SQLiteDatabase): Unit = {
    db execSQL s"""CREATE TABLE IF NOT EXISTS $table (
      $id INTEGER PRIMARY KEY AUTOINCREMENT, $nodeId TEXT NOT NULL, $pr TEXT NOT NULL, $preimage TEXT NOT NULL,
      $status TEXT NOT NULL, $stamp INTEGER NOT NULL, $description TEXT NOT NULL, $action TEXT NOT NULL, $hash TEXT NOT NULL UNIQUE,
      $receivedMsat INTEGER NOT NULL, $sentMsat INTEGER NOT NULL, $feeMsat INTEGER NOT NULL, $balanceSnapMsat INTEGER NOT NULL,
      $fiatRateSnap TEXT NOT NULL, $incoming INTEGER NOT NULL, $ext TEXT NOT NULL
    )"""

    // Once incoming or outgoing payment is settled we can search it by various metadata
    db execSQL s"CREATE VIRTUAL TABLE IF NOT EXISTS $fts$table USING $fts($search, $hash)"
    db execSQL s"CREATE INDEX IF NOT EXISTS idx1$table ON $table ($nodeId, $status)"
    db execSQL s"CREATE INDEX IF NOT EXISTS idx2$table ON $table ($stamp, $status)"
  }
}

object PayMarketTable extends Table {
  val (table, search, lnurl, text, lastMsat, lastDate, hash, image) = ("paymarket", "search", "lnurl", "text", "lastmsat", "lastdate", "hash", "image")
  val newSql = s"INSERT OR IGNORE INTO $table ($lnurl, $text, $lastMsat, $lastDate, $hash, $image) VALUES (?, ?, ?, ?, ?, ?)"
  val newVirtualSql = s"INSERT INTO $fts$table ($search, $lnurl) VALUES (?, ?)"

  val selectRecentSql = s"SELECT * FROM $table ORDER BY $lastDate DESC LIMIT 48"
  val searchSql = s"SELECT * FROM $table WHERE $lnurl IN (SELECT $lnurl FROM $fts$table WHERE $search MATCH ?) LIMIT 96"
  val updInfoSql = s"UPDATE $table SET $text = ?, $lastMsat = ?, $lastDate = ?, $hash = ?, $image = ? WHERE $lnurl = ?"
  val killSql = s"DELETE FROM $table WHERE $lnurl = ?"

  def create(db: SQLiteDatabase): Unit = {
    db execSQL s"""CREATE TABLE IF NOT EXISTS $table (
      $id INTEGER PRIMARY KEY AUTOINCREMENT, $lnurl STRING NOT NULL UNIQUE,
      $text STRING NOT NULL, $lastMsat INTEGER NOT NULL, $lastDate INTEGER NOT NULL,
      $hash STRING NOT NULL, $image STRING NOT NULL
    )"""

    // Payment links are searchable by their text descriptions (text metadata + domain name)
    db execSQL s"CREATE VIRTUAL TABLE IF NOT EXISTS $fts$table USING $fts($search, $lnurl)"
    db execSQL s"CREATE INDEX IF NOT EXISTS idx1$table ON $table ($lastDate)"
  }
}

trait Table { val (id, fts) = "_id" -> "fts4" }
class SQLiteInterface(context: Context, name: String) extends SQLiteOpenHelper(context, name, null, 1) {
  def sqlPath(targetTable: String): Uri = Uri parse s"sqlite://com.btcontract.wallet/table/$targetTable"
  def change(sql: String, params: Object*): Unit = base.execSQL(sql, params.toArray)
  val base: SQLiteDatabase = getWritableDatabase

  def select(sql: String, params: String*): RichCursor = {
    val cursor = base.rawQuery(sql, params.toArray)
    RichCursor(cursor)
  }

  def search(sqlSelectQuery: String, rawQuery: String): RichCursor = {
    val purified = rawQuery.replaceAll("[^ a-zA-Z0-9]", "")
    select(sqlSelectQuery, s"$purified*")
  }

  def txWrap(run: => Unit): Unit = try {
    runAnd(base.beginTransaction)(run)
    base.setTransactionSuccessful
  } finally base.endTransaction

  def onCreate(dbs: SQLiteDatabase): Unit = {
    NormalChannelAnnouncementTable.create(dbs)
    HostedChannelAnnouncementTable.create(dbs)

    NormalExcludedChannelTable.create(dbs)
    HostedExcludedChannelTable.create(dbs)

    NormalChannelUpdateTable.create(dbs)
    HostedChannelUpdateTable.create(dbs)

    DataTable.create(dbs)
    ChannelTable.create(dbs)
    PaymentTable.create(dbs)
    PayMarketTable.create(dbs)
  }

  def onUpgrade(dbs: SQLiteDatabase, v0: Int, v1: Int): Unit = {
    // Do nothing for now
  }
}