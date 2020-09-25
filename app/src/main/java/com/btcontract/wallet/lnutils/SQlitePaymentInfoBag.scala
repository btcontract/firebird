package com.btcontract.wallet.lnutils

import spray.json._
import com.btcontract.wallet.ln.PaymentInfo._
import com.btcontract.wallet.ln.crypto.Tools._
import com.btcontract.wallet.lnutils.ImplicitJsonFormats._
import com.btcontract.wallet.ln.{PaymentAction, PaymentInfo, PaymentInfoBag, PaymentMaster, PaymentRequestExt}
import fr.acinq.eclair.wire.{UpdateAddHtlc, UpdateFulfillHtlc}
import com.btcontract.wallet.helper.RichCursor
import com.btcontract.wallet.FiatRates.Rates
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.MilliSatoshi
import android.content.Context
import scala.util.Try


case class SentToNodeSummary(fees: MilliSatoshi, sent: MilliSatoshi, count: Long)
case class TotalStatSummary(fees: MilliSatoshi, received: MilliSatoshi, sent: MilliSatoshi, count: Long)

class SQlitePaymentInfoBag(db: SQLiteInterface) extends PaymentInfoBag {
  def uiNotify(ctxt: Context): Unit = ctxt.getContentResolver.notifyChange(db sqlPath PaymentTable.table, null)
  def getPaymentInfo(paymentHash: ByteVector32): Option[PaymentInfo] = db.select(PaymentTable.selectOneSql, paymentHash.toHex).headTry(toPaymentInfo).toOption
  def addSearchablePayment(search: String, paymentHash: ByteVector32): Unit = db.change(PaymentTable.newVirtualSql, search, paymentHash.toHex)
  def searchPayments(rawSearchQuery: String): RichCursor = db.search(PaymentTable.searchSql, rawSearchQuery)
  def listLastPayments(limit: Int): RichCursor = db.select(PaymentTable selectLastSql limit)

  def updOkOutgoing(upd: UpdateFulfillHtlc, sent: MilliSatoshi, fee: MilliSatoshi): Unit =
    db.change(PaymentTable.updOkOutgoingSql, upd.paymentPreimage.toHex, sent.toLong: java.lang.Long,
      fee.toLong: java.lang.Long, upd.paymentHash.toHex)

  def updOkIncoming(add: UpdateAddHtlc): Unit =
    db.change(PaymentTable.updOkIncomingSql, add.amountMsat.toLong: java.lang.Long,
      System.currentTimeMillis: java.lang.Long, add.paymentHash.toHex)

  def updStatus(status: String, paymentHash: ByteVector32): Unit =
    db.change(PaymentTable.updStatusSql, status, paymentHash.toHex)

  def addOutgoingPayment(nodeId: PublicKey, prex: PaymentRequestExt, description: String, action: Option[PaymentAction], finalAmount: MilliSatoshi, balanceSnap: MilliSatoshi, fiatRateSnap: Rates): Unit =
    db.change(PaymentTable.newSql, nodeId.toString, prex.raw, NOIMAGE.toHex, PaymentMaster.PENDING, System.currentTimeMillis: java.lang.Long, description, action.map(_.toJson.toString).getOrElse(new String),
      prex.pr.paymentHash.toHex, 0L: java.lang.Long /* RECEIVED = 0, OUTGOING */, finalAmount.toLong: java.lang.Long, 0L: java.lang.Long /* FEE IS UNCERTAIN YET */, balanceSnap.toLong: java.lang.Long,
      fiatRateSnap.toJson.toString, 0: java.lang.Integer /* INCOMING = 0 */, new String /* EMPTY EXT FOR NOW */)

  def addIncomingPayment(prex: PaymentRequestExt, preimage: ByteVector32, description: String, finalAmount: MilliSatoshi, balanceSnap: MilliSatoshi, fiatRateSnap: Rates): Unit =
    db.change(PaymentTable.newSql, NONODEID.toString, prex.raw, preimage.toHex, PaymentMaster.PENDING, System.currentTimeMillis: java.lang.Long, description, new String /* NO ACTION */,
      prex.pr.paymentHash.toHex, finalAmount.toLong: java.lang.Long, 0L: java.lang.Long /* SENT = 0, INCOMING */, 0L: java.lang.Long /* NO FEE FOR INCOMING */, balanceSnap.toLong: java.lang.Long,
      fiatRateSnap.toJson.toString, 1: java.lang.Integer /* INCOMING = 1 */, new String /* EMPTY EXT FOR NOW */)

  def toNodeSummary(nodeId: PublicKey): Try[SentToNodeSummary] = db.select(PaymentTable.selectToNodeSummarySql, nodeId.toString) headTry { rc =>
    SentToNodeSummary(fees = MilliSatoshi(rc.c getLong 0), sent = MilliSatoshi(rc.c getLong 1), count = rc.c getLong 2)
  }

  def betweenSummary(from: Long, to: Long): Try[TotalStatSummary] = db.select(PaymentTable.selectBetweenSummarySql, from.toString, to.toString) headTry { rc =>
    TotalStatSummary(fees = MilliSatoshi(rc.c getLong 0), received = MilliSatoshi(rc.c getLong 1), sent = MilliSatoshi(rc.c getLong 2), count = rc.c getLong 3)
  }

  def toPaymentInfo(rc: RichCursor): PaymentInfo =
    PaymentInfo(payeeNodeId = PublicKey(rc bytes PaymentTable.nodeId), rawPr = rc string PaymentTable.pr, preimage = ByteVector32(rc bytes PaymentTable.preimage),
      status = rc string PaymentTable.status, stamp = rc long PaymentTable.stamp, description = rc string PaymentTable.description, rawAction = rc string PaymentTable.action,
      hashString = rc string PaymentTable.hash, received = MilliSatoshi(rc long PaymentTable.receivedMsat), sent = MilliSatoshi(rc long PaymentTable.sentMsat),
      fee = MilliSatoshi(rc long PaymentTable.feeMsat), balanceSnapshot = MilliSatoshi(rc long PaymentTable.balanceSnapMsat),
      fiatRateSnapshot = rc string PaymentTable.fiatRateSnap, incoming = rc long PaymentTable.incoming)
}
