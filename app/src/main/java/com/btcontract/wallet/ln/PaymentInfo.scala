package com.btcontract.wallet.ln

import fr.acinq.eclair._
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.MilliSatoshi
import scodec.bits.ByteVector


object PaymentInfo {
  final val SENDABLE = 0
  final val NOT_SENDABLE_LOW_BALANCE = 1
  final val NOT_SENDABLE_IN_FLIGHT = 2
  final val NOT_SENDABLE_SUCCESS = 3
}

case class PaymentInfo(rawPr: String, paymentHash: ByteVector32, preimage: ByteVector32, isIncoming: Boolean,
                       status: String, stamp: Long, description: String, rawAction: String, received: MilliSatoshi,
                       sent: MilliSatoshi, fee: MilliSatoshi, balanceSnapshot: MilliSatoshi, fiatRateSnapshot: String) {

  lazy val amountOrZero: MilliSatoshi = pr.amount.getOrElse(0L.msat)
  lazy val pr: PaymentRequest = PaymentRequest.read(rawPr)
}

// Bag of stored payments

trait PaymentInfoBag {
  def getPaymentInfo(paymentHash: ByteVector32): Option[PaymentInfo]
}

// Payment action template

trait PaymentAction {
  val domain: Option[String]
  val finalMessage: String
}