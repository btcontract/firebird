package com.btcontract.wallet.lnutils

import com.btcontract.wallet.lnutils.ImplicitJsonFormats._
import com.btcontract.wallet.ln.StorageFormat
import scala.util.Try


object SQliteDataBag {
  final val LABEL_KEYS = "label-format"
}

class SQliteDataBag(db: SQLiteInterface) {
  def put(label: String, content: String): Unit = {
    // Insert and then update because of INSERT IGNORE
    db.change(DataTable.newSql, label, content)
    db.change(DataTable.updSql, content, label)
  }

  def delete(label: String): Unit = db.change(DataTable.killSql, label)

  def tryGet(label: String): Try[String] = db.select(DataTable.selectSql, label).headTry(_ string DataTable.content)

  def tryGetFormat: Try[StorageFormat] = tryGet(SQliteDataBag.LABEL_KEYS) map to[StorageFormat]
}
