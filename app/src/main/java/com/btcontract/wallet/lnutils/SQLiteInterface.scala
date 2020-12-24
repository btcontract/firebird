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

    DataTable.createStatements.foreach(dbs.execSQL)
    ChannelTable.createStatements.foreach(dbs.execSQL)
    PaymentTable.createStatements.foreach(dbs.execSQL)
  }

  def onUpgrade(dbs: SQLiteDatabase, v0: Int, v1: Int): Unit = {
    // Do nothing for now
  }
}