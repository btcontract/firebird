package com.btcontract.wallet.lnutils

import com.btcontract.wallet.ln.utils.{LNUrl, PayLinkInfo, PayRequest}
import com.btcontract.wallet.helper.RichCursor
import com.btcontract.wallet.ln.PayMarketTable
import fr.acinq.eclair.MilliSatoshi
import android.content.Context


class SQLitePayMarketBag(db: SQLiteInterface) {
  def rm(lnUrl: LNUrl): Unit = db.change(PayMarketTable.killSql, lnUrl.request)
  def saveLink(lnUrl: LNUrl, payReq: PayRequest, msat: MilliSatoshi, hash: String): Unit = db txWrap {
    val thumbnailImageString64 = payReq.metaDataImageBase64s.headOption.getOrElse(default = new String)
    val stamp = System.currentTimeMillis: java.lang.Long
    val lastPaymentMsat = msat.toLong: java.lang.Long

    db.change(PayMarketTable.updInfoSql, payReq.metaDataTextPlain, lastPaymentMsat, stamp, hash, thumbnailImageString64, lnUrl.request)
    db.change(PayMarketTable.newSql, lnUrl.request, payReq.metaDataTextPlain, lastPaymentMsat, stamp, hash, thumbnailImageString64)
    db.change(PayMarketTable.newVirtualSql, s"${lnUrl.uri.getHost} ${payReq.metaDataTextPlain}", lnUrl.request)
  }

  def uiNotify(ctxt: Context): Unit = ctxt.getContentResolver.notifyChange(db sqlPath PayMarketTable.table, null)
  def byQuery(rawSearchQuery: String): RichCursor = db.search(PayMarketTable.searchSql, rawSearchQuery)
  def byRecent: RichCursor = db select PayMarketTable.selectRecentSql

  def toLinkInfo(rc: RichCursor) =
    PayLinkInfo(image64 = rc string PayMarketTable.image, lnurl = LNUrl(rc string PayMarketTable.lnurl),
      text = rc string PayMarketTable.text, lastMsat = MilliSatoshi(rc long PayMarketTable.lastMsat),
      hash = rc string PayMarketTable.hash, lastDate = rc long PayMarketTable.lastDate)
}