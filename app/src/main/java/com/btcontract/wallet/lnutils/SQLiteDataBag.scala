package com.btcontract.wallet.lnutils

import spray.json._
import com.btcontract.wallet.lnutils.SQLiteDataBag._
import com.btcontract.wallet.ln.utils.ImplicitJsonFormats._
import com.btcontract.wallet.lnutils.ImplicitJsonFormatsExt._
import fr.acinq.eclair.wire.{HostedChannelBranding, SwapInState}
import com.btcontract.wallet.ln.{StorageFormat, SwapInStateExt}
import fr.acinq.bitcoin.Crypto.PublicKey
import scala.util.Try


object SQLiteDataBag {
  final val LABEL_FORMAT = "label-format"
  final val LABEL_USED_ADDONS = "label-used-addons"
  final val LABEL_BRANDING_PREFIX = "label-branding-node-"
  final val LABEL_SWAP_IN_STATE_PREFIX = "label-swap-in-node-"
}

class SQLiteDataBag(db: SQLiteInterface) {
  def put(label: String, content: String): Unit = {
    // Insert and then update because of INSERT IGNORE
    db.change(DataTable.newSql, label, content)
    db.change(DataTable.updSql, content, label)
  }

  def delete(label: String): Unit = db.change(DataTable.killSql, label)
  def tryGet(label: String): Try[String] = db.select(DataTable.selectSql, label).headTry(_ string DataTable.content)

  def tryGetUsedAddons: Try[UsedAddons] = tryGet(LABEL_USED_ADDONS) map to[UsedAddons]
  def tryGetFormat: Try[StorageFormat] = tryGet(LABEL_FORMAT) map to[StorageFormat]

  def putBranding(nodeId: PublicKey, branding: HostedChannelBranding): Unit = put(LABEL_BRANDING_PREFIX + nodeId.toString, branding.toJson.compactPrint)
  def tryGetBranding(nodeId: PublicKey): Try[HostedChannelBranding] = tryGet(LABEL_BRANDING_PREFIX + nodeId.toString) map to[HostedChannelBranding]

  def putSwapInState(nodeId: PublicKey, state: SwapInState): Unit = put(LABEL_SWAP_IN_STATE_PREFIX + nodeId.toString, state.toJson.compactPrint)
  def tryGetSwapInState(nodeId: PublicKey): Try[SwapInStateExt] = tryGet(LABEL_SWAP_IN_STATE_PREFIX + nodeId.toString) map { stateString =>
    SwapInStateExt(state = to[SwapInState](stateString), nodeId)
  }
}
