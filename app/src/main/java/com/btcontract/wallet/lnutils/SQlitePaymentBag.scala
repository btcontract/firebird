package com.btcontract.wallet.lnutils

import spray.json._
import com.btcontract.wallet.ln._
import com.btcontract.wallet.ln.utils.ImplicitJsonFormats._
import fr.acinq.eclair.wire.{UpdateAddHtlc, UpdateFulfillHtlc}
import fr.acinq.eclair.{MilliSatoshi, invalidPubKey}

import com.btcontract.wallet.ln.crypto.Tools.Fiat2Btc
import com.btcontract.wallet.helper.RichCursor
import com.btcontract.wallet.ln.PaymentStatus
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.ByteVector32
import android.content.Context
import scala.util.Try


case class SentToNodeSummary(fees: MilliSatoshi, sent: MilliSatoshi, count: Long)

case class TotalStatSummary(fees: MilliSatoshi, received: MilliSatoshi, sent: MilliSatoshi, count: Long)

case class TotalStatSummaryExt(summary: Option[TotalStatSummary], from: Long, to: Long)

class SQlitePaymentBag(db: SQLiteInterface) extends PaymentBag with PaymentDBUpdater {
  def uiNotify(ctxt: Context): Unit = ctxt.getContentResolver.notifyChange(db sqlPath PaymentTable.table, null)

  def getPaymentInfo(paymentHash: ByteVector32): Option[PaymentInfo] = db.select(PaymentTable.selectOneSql, paymentHash.toHex).headTry(toPaymentInfo).toOption

  def addSearchablePayment(search: String, paymentHash: ByteVector32): Unit = db.change(PaymentTable.newVirtualSql, search, paymentHash.toHex)

  def searchPayments(rawSearchQuery: String): RichCursor = db.search(PaymentTable.searchSql, rawSearchQuery)

  def listRecentPayments: RichCursor = db.select(PaymentTable.selectRecentSql)

  def updOkOutgoing(upd: UpdateFulfillHtlc, fee: MilliSatoshi): Unit = db.change(PaymentTable.updOkOutgoingSql, upd.paymentPreimage.toHex, fee.toLong: java.lang.Long, upd.paymentHash.toHex)

  def updStatusIncoming(add: UpdateAddHtlc, status: String): Unit = db.change(PaymentTable.updParamsIncomingSql, status, add.amountMsat.toLong: java.lang.Long, System.currentTimeMillis: java.lang.Long, add.paymentHash.toHex)

  def abortPayment(paymentHash: ByteVector32): Unit = db.change(PaymentTable.updStatusSql, PaymentStatus.ABORTED, paymentHash.toHex)

  def replaceOutgoingPayment(nodeId: PublicKey, prex: PaymentRequestExt, description: PaymentDescription, action: Option[PaymentAction],
                             finalAmount: MilliSatoshi, balanceSnap: MilliSatoshi, fiatRateSnap: Fiat2Btc): Unit = db txWrap {

    db.change(PaymentTable.deleteSql, prex.paymentHashStr)
    db.change(PaymentTable.newSql, nodeId.toString, prex.raw, ByteVector32.Zeroes.toHex, PaymentStatus.PENDING, System.currentTimeMillis: java.lang.Long, description.toJson.compactPrint,
      action.map(_.toJson.compactPrint).getOrElse(new String), prex.paymentHashStr, 0L: java.lang.Long /* RECEIVED = 0 MSAT, OUTGOING */, finalAmount.toLong: java.lang.Long /* SENT IS KNOWN */,
      0L: java.lang.Long /* FEE IS UNCERTAIN YET */, balanceSnap.toLong: java.lang.Long, fiatRateSnap.toJson.compactPrint, 0: java.lang.Integer /* INCOMING = 0 */, new String /* EMPTY EXT */)
  }

  def replaceIncomingPayment(prex: PaymentRequestExt, preimage: ByteVector32, description: PaymentDescription,
                             balanceSnap: MilliSatoshi, fiatRateSnap: Fiat2Btc): Unit = db txWrap {

    val finalReceived = prex.pr.amount.map(_.toLong: java.lang.Long).getOrElse(0L: java.lang.Long)
    val finalStatus = if (prex.pr.amount.isDefined) PaymentStatus.PENDING else PaymentStatus.HIDDEN

    db.change(PaymentTable.deleteSql, prex.paymentHashStr)
    db.change(PaymentTable.newSql, invalidPubKey.toString, prex.raw, preimage.toHex, finalStatus, System.currentTimeMillis: java.lang.Long, description.toJson.compactPrint,
      new String /* NO ACTION */, prex.paymentHashStr, finalReceived /* MUST COME FROM PR! IF RECEIVED = 0 MSAT THEN NO AMOUNT */, 0L: java.lang.Long /* SENT = 0 MSAT, INCOMING */,
      0L: java.lang.Long /* NO FEE FOR INCOMING */, balanceSnap.toLong: java.lang.Long, fiatRateSnap.toJson.compactPrint, 1: java.lang.Integer /* INCOMING = 1 */, new String /* EMPTY EXT */)
  }

  def toNodeSummary(nodeId: PublicKey): Try[SentToNodeSummary] = db.select(PaymentTable.selectToNodeSummarySql, nodeId.toString) headTry { rc =>
    SentToNodeSummary(fees = MilliSatoshi(rc.c getLong 0), sent = MilliSatoshi(rc.c getLong 1), count = rc.c getLong 2)
  }

  def betweenSummary(start: Long, end: Long): Try[TotalStatSummary] = db.select(PaymentTable.selectBetweenSummarySql, start.toString, end.toString) headTry { rc =>
    TotalStatSummary(fees = MilliSatoshi(rc.c getLong 0), received = MilliSatoshi(rc.c getLong 1), sent = MilliSatoshi(rc.c getLong 2), count = rc.c getLong 3)
  }

  def toPaymentInfo(rc: RichCursor): PaymentInfo =
    PaymentInfo(payeeNodeIdString = rc string PaymentTable.nodeId, prString = rc string PaymentTable.pr, preimageString = rc string PaymentTable.preimage,
      status = rc string PaymentTable.status, stamp = rc long PaymentTable.stamp, descriptionString = rc string PaymentTable.description, actionString = rc string PaymentTable.action,
      paymentHashString = rc string PaymentTable.hash, received = MilliSatoshi(rc long PaymentTable.receivedMsat), sent = MilliSatoshi(rc long PaymentTable.sentMsat),
      fee = MilliSatoshi(rc long PaymentTable.feeMsat), balanceSnapshot = MilliSatoshi(rc long PaymentTable.balanceSnapMsat),
      fiatRateSnapshotString = rc string PaymentTable.fiatRateSnap, incoming = rc long PaymentTable.incoming)
}