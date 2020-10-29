package com.btcontract.wallet.lnutils

import spray.json._
import com.btcontract.wallet.ln._
import com.btcontract.wallet.lnutils.ImplicitJsonFormats._
import fr.acinq.eclair.wire.{UpdateAddHtlc, UpdateFulfillHtlc}
import fr.acinq.eclair.{MilliSatoshi, invalidPubKey}
import com.btcontract.wallet.helper.RichCursor
import com.btcontract.wallet.FiatRates.Rates
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.ByteVector32
import android.content.Context
import scala.util.Try


trait PaymentUpdaterToSuccess {
  // These MUST be the only two methods capabe of updating payment state to SUCCEEDED
  def updOkOutgoing(upd: UpdateFulfillHtlc, sent: MilliSatoshi, fee: MilliSatoshi): Unit
  def updOkIncoming(add: UpdateAddHtlc): Unit
}

case class SentToNodeSummary(fees: MilliSatoshi, sent: MilliSatoshi, count: Long)
case class TotalStatSummary(fees: MilliSatoshi, received: MilliSatoshi, sent: MilliSatoshi, count: Long)
case class TotalStatSummaryExt(summary: Option[TotalStatSummary], from: Long, to: Long)

class SQlitePaymentBag(db: SQLiteInterface) extends PaymentBag with PaymentUpdaterToSuccess {
  def uiNotify(ctxt: Context): Unit = ctxt.getContentResolver.notifyChange(db sqlPath PaymentTable.table, null)
  def getPaymentInfo(paymentHash: ByteVector32): Option[PaymentInfo] = db.select(PaymentTable.selectOneSql, paymentHash.toHex).headTry(toPaymentInfo).toOption
  def addSearchablePayment(search: String, paymentHash: ByteVector32): Unit = db.change(PaymentTable.newVirtualSql, search, paymentHash.toHex)
  def searchPayments(rawSearchQuery: String): RichCursor = db.search(PaymentTable.searchSql, rawSearchQuery)
  def listRecentPayments(limit: Int): RichCursor = db.select(PaymentTable selectRecentSql limit)

  def updOkOutgoing(upd: UpdateFulfillHtlc, sent: MilliSatoshi, fee: MilliSatoshi): Unit =
    db.change(PaymentTable.updOkOutgoingSql, PaymentMaster.SUCCEEDED, upd.paymentPreimage.toHex,
      sent.toLong: java.lang.Long, fee.toLong: java.lang.Long, upd.paymentHash.toHex)

  def updOkIncoming(add: UpdateAddHtlc): Unit = db.change(PaymentTable.updOkIncomingSql, PaymentMaster.SUCCEEDED, add.amountMsat.toLong: java.lang.Long, System.currentTimeMillis: java.lang.Long, add.paymentHash.toHex)

  def abortPayment(paymentHash: ByteVector32): Unit = db.change(PaymentTable.updStatusSql, PaymentMaster.ABORTED, paymentHash.toHex, PaymentMaster.SUCCEEDED)

  def addOutgoingPayment(nodeId: PublicKey, prex: PaymentRequestExt, description: String, action: Option[PaymentAction], finalAmount: MilliSatoshi, balanceSnap: MilliSatoshi, fiatRateSnap: Rates): Unit =
    db.change(PaymentTable.newSql, nodeId.toString, prex.raw, ByteVector32.Zeroes.toHex, PaymentMaster.PENDING, System.currentTimeMillis: java.lang.Long, description, action.map(_.toJson.compactPrint).getOrElse(new String),
      prex.paymentHashStr, 0L: java.lang.Long /* RECEIVED = 0 MSAT, OUTGOING */, finalAmount.toLong: java.lang.Long, 0L: java.lang.Long /* FEE IS UNCERTAIN YET */, balanceSnap.toLong: java.lang.Long,
      fiatRateSnap.toJson.compactPrint, 0: java.lang.Integer /* INCOMING = 0 */, new String /* EMPTY EXT FOR NOW */)

  def addIncomingPayment(prex: PaymentRequestExt, preimage: ByteVector32, description: String, finalAmount: MilliSatoshi, balanceSnap: MilliSatoshi, fiatRateSnap: Rates): Unit =
    db.change(PaymentTable.newSql, invalidPubKey.toString, prex.raw, preimage.toHex, PaymentMaster.PENDING, System.currentTimeMillis: java.lang.Long, description, new String /* NO ACTION */,
      prex.paymentHashStr, finalAmount.toLong: java.lang.Long, 0L: java.lang.Long /* SENT = 0 MSAT, INCOMING */, 0L: java.lang.Long /* NO FEE FOR INCOMING */, balanceSnap.toLong: java.lang.Long,
      fiatRateSnap.toJson.compactPrint, 1: java.lang.Integer /* INCOMING = 1 */, new String /* EMPTY EXT FOR NOW */)

  def toNodeSummary(nodeId: PublicKey): Try[SentToNodeSummary] = db.select(PaymentTable.selectToNodeSummarySql, nodeId.toString, PaymentMaster.SUCCEEDED) headTry { rc =>
    SentToNodeSummary(fees = MilliSatoshi(rc.c getLong 0), sent = MilliSatoshi(rc.c getLong 1), count = rc.c getLong 2)
  }

  def betweenSummary(from: Long, to: Long): Try[TotalStatSummary] = db.select(PaymentTable.selectBetweenSummarySql, from.toString, to.toString, PaymentMaster.SUCCEEDED) headTry { rc =>
    TotalStatSummary(fees = MilliSatoshi(rc.c getLong 0), received = MilliSatoshi(rc.c getLong 1), sent = MilliSatoshi(rc.c getLong 2), count = rc.c getLong 3)
  }

  def toPaymentInfo(rc: RichCursor): PaymentInfo =
    PaymentInfo(payeeNodeIdString = rc string PaymentTable.nodeId, rawPr = rc string PaymentTable.pr, preimageString = rc string PaymentTable.preimage,
      status = rc string PaymentTable.status, stamp = rc long PaymentTable.stamp, description = rc string PaymentTable.description, rawAction = rc string PaymentTable.action,
      hashString = rc string PaymentTable.hash, received = MilliSatoshi(rc long PaymentTable.receivedMsat), sent = MilliSatoshi(rc long PaymentTable.sentMsat),
      fee = MilliSatoshi(rc long PaymentTable.feeMsat), balanceSnapshot = MilliSatoshi(rc long PaymentTable.balanceSnapMsat),
      fiatRateSnapshot = rc string PaymentTable.fiatRateSnap, incoming = rc long PaymentTable.incoming)
}