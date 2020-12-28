package com.btcontract.wallet.lnutils

import spray.json._
import com.btcontract.wallet.ln.utils.ImplicitJsonFormats._
import com.btcontract.wallet.ln.{ChannelBag, ChannelTable, HostedCommits}
import fr.acinq.bitcoin.ByteVector32


class SQLiteChannelBag(db: SQLiteInterface) extends ChannelBag {
  def delete(chanId: ByteVector32): Unit = db.change(ChannelTable.killSql, chanId.toHex)

  def all: List[HostedCommits] = db.select(ChannelTable.selectAllSql).list(_ string ChannelTable.data) map to[HostedCommits]

  def put(chanId: ByteVector32, data: HostedCommits): HostedCommits = {
    // Insert and then update because of INSERT IGNORE sqlite effects
    val dataJson = data.toJson.compactPrint
    val chanIdJson = chanId.toHex

    db.change(ChannelTable.newSql, chanIdJson, dataJson)
    db.change(ChannelTable.updSql, dataJson, chanIdJson)
    data
  }
}