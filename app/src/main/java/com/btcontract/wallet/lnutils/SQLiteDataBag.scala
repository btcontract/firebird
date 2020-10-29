package com.btcontract.wallet.lnutils

import com.btcontract.wallet.ln.utils.ImplicitJsonFormats._
import com.btcontract.wallet.lnutils.ImplicitJsonFormatsExt._
import com.btcontract.wallet.ln.{DataTable, StorageFormat}
import scala.util.Try


object SQLiteDataBag {
  final val LABEL_FORMAT = "label-format"
  final val LABEL_USED_ADDONS = "label-used-addons"
}

class SQLiteDataBag(db: SQLiteInterface) {
  def put(label: String, content: String): Unit = {
    // Insert and then update because of INSERT IGNORE
    db.change(DataTable.newSql, label, content)
    db.change(DataTable.updSql, content, label)
  }

  def delete(label: String): Unit = db.change(DataTable.killSql, label)
  def tryGet(label: String): Try[String] = db.select(DataTable.selectSql, label).headTry(_ string DataTable.content)
  def tryGetUsedAddons: Try[UsedAddons] = tryGet(SQLiteDataBag.LABEL_USED_ADDONS) map to[UsedAddons]
  def tryGetFormat: Try[StorageFormat] = tryGet(SQLiteDataBag.LABEL_FORMAT) map to[StorageFormat]
}
