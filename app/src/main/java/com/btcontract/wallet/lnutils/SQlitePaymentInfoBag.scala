package com.btcontract.wallet.lnutils

import com.btcontract.wallet.ln.crypto.Tools._
import com.btcontract.wallet.ln.{PaymentInfo, PaymentInfoBag}
import com.btcontract.wallet.helper.RichCursor
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.MilliSatoshi


class SQlitePaymentInfoBag(db: SQLiteInterface) extends PaymentInfoBag {
  def getPaymentInfo(paymentHash: ByteVector32): Option[PaymentInfo] = db.select(PaymentTable.selectOneSql, paymentHash.toHex).headTry(toPaymentInfo).toOption

  def toPaymentInfo(rc: RichCursor): PaymentInfo =
    PaymentInfo(payeeNodeId = PublicKey(rc bytes PaymentTable.nodeId), rawPr = rc string PaymentTable.pr, preimage = ByteVector32(rc bytes PaymentTable.preimage),
      status = rc string PaymentTable.status, stamp = rc long PaymentTable.stamp, description = rc string PaymentTable.description, rawAction = rc string PaymentTable.action,
      hashString = rc string PaymentTable.hash, received = MilliSatoshi(rc long PaymentTable.receivedMsat), sent = MilliSatoshi(rc long PaymentTable.sentMsat),
      fee = MilliSatoshi(rc long PaymentTable.feeMsat), balanceSnapshot = MilliSatoshi(rc long PaymentTable.balanceSnapMsat),
      fiatRateSnapshot = rc string PaymentTable.fiatRateSnap, incoming = rc long PaymentTable.incoming)
}
