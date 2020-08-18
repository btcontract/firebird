package com.btcontract.wallet.lnutils

import android.database.sqlite.{SQLiteDatabase, SQLiteOpenHelper}
import com.btcontract.wallet.ln.PaymentInfo.SUCCESS
import com.btcontract.wallet.ln.crypto.Tools.runAnd
import com.btcontract.wallet.helper.RichCursor
import android.content.Context
import android.net.Uri


object ChannelTable extends Table {
  val Tuple3(table, identifier, data) = ("channel", "identifier", "data")
  val newSql = s"INSERT OR IGNORE INTO $table ($identifier, $data) VALUES (?, ?)"
  val updSql = s"UPDATE $table SET $data = ? WHERE $identifier = ?"
  val selectAllSql = s"SELECT * FROM $table ORDER BY $id DESC"
  val killSql = s"DELETE FROM $table WHERE $identifier = ?"

  val createSql = s"""
    CREATE TABLE IF NOT EXISTS $table (
      $id INTEGER PRIMARY KEY AUTOINCREMENT,
      $identifier TEXT NOT NULL UNIQUE,
      $data STRING NOT NULL
    );"""
}

object ChannelAnnouncementTable extends Table {
  val Tuple5(table, features, shortChannelId, nodeId1, nodeId2) = ("chan_announcements", "features", "short_channel_id", "node_id_1", "node_id_2")
  val newSql = s"INSERT OR IGNORE INTO $table ($features, $shortChannelId, $nodeId1, $nodeId2) VALUES (?, ?, ?, ?)"
  val killSql = s"DELETE FROM $table WHERE $shortChannelId = ?"
  val selectAllSql = s"SELECT * FROM $table"

  val createSql = s"""
    CREATE TABLE IF NOT EXISTS $table (
      $id INTEGER PRIMARY KEY AUTOINCREMENT, $features BLOB NOT NULL,
      $shortChannelId INTEGER NOT NULL UNIQUE, $nodeId1 BLOB NOT NULL,
      $nodeId2 BLOB NOT NULL
    );"""
}

object ChannelUpdateTable extends Table {
  val Tuple12(table, shortChannelId, timestamp, messageFlags, channelFlags, cltvExpiryDelta, minMsat, base, proportional, maxMsat, position, score) =
    ("chan_updates", "short_channel_id", "timestamp", "message_flags", "channel_flags", "cltv_expiry_delta", "htlc_minimum_msat", "fee_base_msat",
      "fee_proportional_millionths", "htlc_maximum_sat", "position", "score")

  val newSql = s"INSERT OR IGNORE INTO $table ($shortChannelId, $timestamp, $messageFlags, $channelFlags, $cltvExpiryDelta, $minMsat, $base, $proportional, $maxMsat, $position, $score) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)"
  val updSQL = s"UPDATE $table SET $timestamp = ?, $messageFlags = ?, $channelFlags = ?, $cltvExpiryDelta = ?, $minMsat = ?, $base = ?, $proportional = ?, $maxMsat = ? WHERE $shortChannelId = ? AND $position = ?"
  val updScoreSql = s"UPDATE $table SET $score = $score + 1 WHERE $shortChannelId = ? AND $position = ?"
  val killSql = s"DELETE FROM $table WHERE $shortChannelId = ?"
  val selectAllSql = s"SELECT * FROM $table"

  val createSql = s"""
    CREATE TABLE IF NOT EXISTS $table (
      $id INTEGER PRIMARY KEY AUTOINCREMENT, $shortChannelId INTEGER NOT NULL, $timestamp INTEGER NOT NULL,
      $messageFlags INTEGER NOT NULL, $channelFlags INTEGER NOT NULL, $cltvExpiryDelta INTEGER NOT NULL, $minMsat INTEGER NOT NULL,
      $base INTEGER NOT NULL, $proportional INTEGER NOT NULL, $maxMsat INTEGER NOT NULL, $position INTEGER NOT NULL, $score INTEGER NOT NULL
    );
    /* we are interested in compund UNIQUE index because there are two updates per channel */
    CREATE UNIQUE INDEX IF NOT EXISTS idx1$table ON $table ($shortChannelId, $position);
    COMMIT"""
}

object ExcludedChannelTable extends Table {
  val Tuple3(table, shortChannelId, until) = ("chan_excluded", "short_channel_id", "until")
  val newSql = s"INSERT OR IGNORE INTO $table ($shortChannelId, $until) VALUES (?, ?)"
  val updSql = s"UPDATE $table SET $until = ? WHERE $shortChannelId = ?"
  val killSql = s"DELETE FROM $table WHERE $shortChannelId = ?"
  val selectAllSql = s"SELECT * FROM $table WHERE $until < ?"

  val createSql = s"""
    CREATE TABLE IF NOT EXISTS $table (
      $id INTEGER PRIMARY KEY AUTOINCREMENT,
      $shortChannelId INTEGER NOT NULL UNIQUE,
      $until INTEGER NOT NULL
    );
    /* shortChannelId index is created automatically because UNIQUE */
    CREATE INDEX IF NOT EXISTS idx1$table ON $table ($until);
    COMMIT"""
}

object PaymentTable extends Table {
  private[this] val paymentTableFieldStrings = ("search", "payment", "pr", "preimage", "status", "stamp", "description", "action", "hash", "receivedMsat", "sentMsat", "feeMsat", "balanceSnap", "fiatRateSnap")
  val (search, table, pr, preimage, status, stamp, description, action, hash, receivedMsat, sentMsat, feeMsat, balanceSnap, fiatRateSnap) = paymentTableFieldStrings
  val insert12 = s"$pr, $preimage, $status, $stamp, $description, $action, $hash, $receivedMsat, $sentMsat, $feeMsat, $balanceSnap, $fiatRateSnap"
  val newSql = s"INSERT OR IGNORE INTO $table ($insert12) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, -1, ?, ?)"
  val newVirtualSql = s"INSERT INTO $fts$table ($search, $hash) VALUES (?, ?)"

  // Selecting
  val selectOneSql = s"SELECT * FROM $table WHERE $hash = ?"
  val selectBetweenSql = s"SELECT * FROM $table WHERE $stamp > ? AND $stamp < ? AND $status = ? LIMIT ?"
  val selectBetweenSummarySql = s"SELECT sum($feeMsat), sum($receivedMsat), sum($sentMsat), count($id) FROM $table WHERE $stamp > ? AND $stamp < ? AND $status = ?"
  val searchSql = s"SELECT * FROM $table WHERE $hash IN (SELECT $hash FROM $fts$table WHERE $search MATCH ? LIMIT 50)"

  // Updating
  val updOkOutgoingSql = s"UPDATE $table SET $status = $SUCCESS, $preimage = ?, $sentMsat = ?, $feeMsat = ?, $balanceSnap = ?, $fiatRateSnap = ? WHERE $hash = ?"
  val updOkIncomingSql = s"UPDATE $table SET $status = ?, $receivedMsat = ?, $stamp = ?, $balanceSnap = ?, $fiatRateSnap = ? WHERE $hash = ?"
  val updStatusSql = s"UPDATE $table SET $status = ? WHERE $hash = ? AND $status <> $SUCCESS"

  // Once incoming or outgoing payment is settled we can search it by various metadata
  val createVSql = s"CREATE VIRTUAL TABLE IF NOT EXISTS $fts$table USING $fts($search, $hash)"

  val createSql = s"""
    CREATE TABLE IF NOT EXISTS $table (
      $id INTEGER PRIMARY KEY AUTOINCREMENT, $pr STRING NOT NULL, $preimage STRING NOT NULL, $status INTEGER NOT NULL,
      $stamp INTEGER NOT NULL, $description STRING NOT NULL, $action STRING NOT NULL, $hash STRING NOT NULL UNIQUE,
      $receivedMsat INTEGER NOT NULL, $sentMsat INTEGER NOT NULL, $feeMsat INTEGER NOT NULL,
      $balanceSnap INTEGER NOT NULL, $fiatRateSnap STRING NOT NULL
    );
    /* hash index is created automatically because this field is UNIQUE */
    CREATE INDEX IF NOT EXISTS idx1$table ON $table ($stamp, $status);
    CREATE INDEX IF NOT EXISTS idx2$table ON $table ($hash, $status);
    COMMIT"""
}

trait Table { val (id, fts) = "_id" -> "fts4" }
class LNOpenHelper(context: Context, name: String) extends SQLiteOpenHelper(context, name, null, 1) {
  def sqlPath(targetTable: String): Uri = Uri parse s"sqlite://com.btcontract.wallet/table/$targetTable"
  def change(sql: String, params: Object*): Unit = base.execSQL(sql, params.toArray)
  val base: SQLiteDatabase = getWritableDatabase

  def select(sql: String, params: String*): RichCursor = {
    val cursor = base.rawQuery(sql, params.toArray)
    RichCursor(cursor)
  }

  def txWrap(run: => Unit): Unit = try {
    runAnd(base.beginTransaction)(run)
    base.setTransactionSuccessful
  } finally base.endTransaction

  def onCreate(dbs: SQLiteDatabase): Unit = {
    dbs execSQL ChannelAnnouncementTable.createSql
    dbs execSQL ExcludedChannelTable.createSql
    dbs execSQL ChannelUpdateTable.createSql
    dbs execSQL PaymentTable.createVSql
    dbs execSQL PaymentTable.createSql
    dbs execSQL ChannelTable.createSql
  }

  def onUpgrade(dbs: SQLiteDatabase, v0: Int, v1: Int): Unit = {
    // Do nothing for now
  }
}