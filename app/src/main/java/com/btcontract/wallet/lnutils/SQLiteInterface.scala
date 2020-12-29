package com.btcontract.wallet.lnutils

import com.btcontract.wallet.ln._
import android.database.sqlite.{SQLiteDatabase, SQLiteOpenHelper}
import com.btcontract.wallet.ln.crypto.Tools.runAnd
import com.btcontract.wallet.helper.RichCursor
import android.content.Context
import android.net.Uri


object DataTable extends Table {
  val (table, label, content) = ("data", "label", "content")
  val newSql = s"INSERT OR IGNORE INTO $table ($label, $content) VALUES (?, ?)"
  val updSql = s"UPDATE $table SET $content = ? WHERE $label = ?"
  val selectSql = s"SELECT * FROM $table WHERE $label = ?"
  val killSql = s"DELETE FROM $table WHERE $label = ?"

  def createStatements: Seq[String] =
    s"""CREATE TABLE IF NOT EXISTS $table(
      $id INTEGER PRIMARY KEY AUTOINCREMENT,
      $label TEXT NOT NULL UNIQUE,
      $content TEXT NOT NULL
    )""" :: Nil
}

object PayMarketTable extends Table {
  val (table, search, lnurl, text, lastMsat, lastDate, hash, image) = ("paymarket", "search", "lnurl", "text", "lastmsat", "lastdate", "hash", "image")
  val newSql = s"INSERT OR IGNORE INTO $table ($lnurl, $text, $lastMsat, $lastDate, $hash, $image) VALUES (?, ?, ?, ?, ?, ?)"
  val newVirtualSql = s"INSERT INTO $fts$table ($search, $lnurl) VALUES (?, ?)"

  val selectRecentSql = s"SELECT * FROM $table ORDER BY $lastDate DESC LIMIT 48"
  val searchSql = s"SELECT * FROM $table WHERE $lnurl IN (SELECT $lnurl FROM $fts$table WHERE $search MATCH ?) LIMIT 96"
  val updInfoSql = s"UPDATE $table SET $text = ?, $lastMsat = ?, $lastDate = ?, $hash = ?, $image = ? WHERE $lnurl = ?"
  val killSql = s"DELETE FROM $table WHERE $lnurl = ?"

  def createStatements: Seq[String] = {
    val createTable = s"""CREATE TABLE IF NOT EXISTS $table(
      $id INTEGER PRIMARY KEY AUTOINCREMENT, $lnurl STRING NOT NULL UNIQUE,
      $text STRING NOT NULL, $lastMsat INTEGER NOT NULL, $lastDate INTEGER NOT NULL,
      $hash STRING NOT NULL, $image STRING NOT NULL
    )"""

    // Payment links are searchable by their text descriptions (text metadata + domain name)
    val addIndex1 = s"CREATE VIRTUAL TABLE IF NOT EXISTS $fts$table USING $fts($search, $lnurl)"
    val addIndex2 = s"CREATE INDEX IF NOT EXISTS idx1$table ON $table ($lastDate)"
    createTable :: addIndex1 :: addIndex2 :: Nil
  }
}

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
    NormalChannelAnnouncementTable.createStatements.foreach(dbs.execSQL)
    HostedChannelAnnouncementTable.createStatements.foreach(dbs.execSQL)

    NormalExcludedChannelTable.createStatements.foreach(dbs.execSQL)
    HostedExcludedChannelTable.createStatements.foreach(dbs.execSQL)

    NormalChannelUpdateTable.createStatements.foreach(dbs.execSQL)
    HostedChannelUpdateTable.createStatements.foreach(dbs.execSQL)

    ChannelTable.createStatements.foreach(dbs.execSQL)
    PaymentTable.createStatements.foreach(dbs.execSQL)

    DataTable.createStatements.foreach(dbs.execSQL)
    PayMarketTable.createStatements.foreach(dbs.execSQL)
  }

  def onUpgrade(dbs: SQLiteDatabase, v0: Int, v1: Int): Unit = {
    // Do nothing for now
  }
}