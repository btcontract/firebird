package com.btcontract.wallet.ln

import fr.acinq.eclair._
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.MilliSatoshi
import scodec.bits.ByteVector


object PaymentInfo {
  final val SENDABLE = 0
  final val NOT_SENDABLE_LOW_BALANCE = 1
  final val NOT_SENDABLE_IN_FLIGHT = 2
  final val NOT_SENDABLE_INCOMING = 3
  final val NOT_SENDABLE_SUCCESS = 4
}

case class PaymentInfo(payeeNodeIdString: String, rawPr: String, preimageString: String, status: String, stamp: Long,
                       description: String, rawAction: String, hashString: String, received: MilliSatoshi, sent: MilliSatoshi,
                       fee: MilliSatoshi, balanceSnapshot: MilliSatoshi, fiatRateSnapshot: String, incoming: Long) {

  def isIncoming: Boolean = 1 == incoming
  lazy val pr: PaymentRequest = PaymentRequest.read(rawPr)
  lazy val amountOrZero: MilliSatoshi = pr.amount.getOrElse(0L.msat)
  lazy val payeeNodeId: PublicKey = PublicKey(ByteVector fromValidHex payeeNodeIdString)
  lazy val preimage: ByteVector32 = ByteVector32(ByteVector fromValidHex payeeNodeIdString)
  lazy val paymentHash: ByteVector32 = ByteVector32(ByteVector fromValidHex hashString)
}

// Bag of stored payments

trait PaymentBag {
  def getPaymentInfo(paymentHash: ByteVector32): Option[PaymentInfo]
}

// Payment action template

trait PaymentAction {
  val domain: Option[String]
  val finalMessage: String
}