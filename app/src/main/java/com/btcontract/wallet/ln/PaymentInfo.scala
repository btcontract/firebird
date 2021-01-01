package com.btcontract.wallet.ln

import com.btcontract.wallet.ln.utils.ImplicitJsonFormats._
import com.btcontract.wallet.ln.crypto.Tools.{Bytes, Fiat2Btc}
import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import com.btcontract.wallet.ln.utils.uri.Uri
import fr.acinq.eclair.payment.PaymentRequest
import com.btcontract.wallet.ln.utils.LNUrl
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.MilliSatoshi
import scodec.bits.ByteVector


object PaymentInfo {
  final val SENDABLE = 0
  final val NOT_SENDABLE_LOW_BALANCE = 1
  final val NOT_SENDABLE_IN_FLIGHT = 2
  final val NOT_SENDABLE_INCOMING = 3
  final val NOT_SENDABLE_SUCCESS = 4
}

object PaymentStatus {
  final val INIT = "state-init"
  final val PENDING = "state-pending"
  final val ABORTED = "state-aborted"
  final val SUCCEEDED = "state-succeeded"
  // Not used in ChannelMaster, only in db
  final val HIDDEN = "state-hidden"
}

case class PaymentInfo(payeeNodeIdString: String, prString: String, preimageString: String, status: String, stamp: Long, descriptionString: String,
                       actionString: String, paymentHashString: String, received: MilliSatoshi, sent: MilliSatoshi, fee: MilliSatoshi,
                       balanceSnapshot: MilliSatoshi, fiatRateSnapshotString: String, incoming: Long) {

  def isIncoming: Boolean = 1 == incoming
  lazy val pr: PaymentRequest = PaymentRequest.read(prString)
  lazy val payeeNodeId: PublicKey = PublicKey(ByteVector fromValidHex payeeNodeIdString)
  lazy val preimage: ByteVector32 = ByteVector32(ByteVector fromValidHex preimageString)
  lazy val paymentHash: ByteVector32 = ByteVector32(ByteVector fromValidHex paymentHashString)

  lazy val fiatRateSnapshot: Fiat2Btc = to[Fiat2Btc](fiatRateSnapshotString)
  lazy val description: PaymentDescription = to[PaymentDescription](descriptionString)
  lazy val action: PaymentAction = to[PaymentAction](actionString)
}

// Bag of stored payments

trait PaymentBag {
  def getPaymentInfo(paymentHash: ByteVector32): Option[PaymentInfo]
}

// Payment actions

sealed trait PaymentAction {
  val domain: Option[String]
  val finalMessage: String
}

case class MessageAction(domain: Option[String], message: String) extends PaymentAction {
  val finalMessage = s"<br>${message take 144}"
}

case class UrlAction(domain: Option[String], description: String, url: String) extends PaymentAction {
  val finalMessage = s"<br>${description take 144}<br><br><font color=#0000FF><tt>$url</tt></font><br>"
  require(domain.forall(url.contains), "Payment action domain mismatch")
  val uri: Uri = LNUrl.checkHost(url)
}

case class AESAction(domain: Option[String], description: String, ciphertext: String, iv: String) extends PaymentAction {
  val ciphertextBytes: Bytes = ByteVector.fromValidBase64(ciphertext).take(1024 * 4).toArray // up to ~2kb of encrypted data
  val ivBytes: Bytes = ByteVector.fromValidBase64(iv).take(24).toArray // 16 bytes
  val finalMessage = s"<br>${description take 144}"
}

// Payment descriptions

sealed trait PaymentDescription { val invoiceText: String }

case class PlainDescription(invoiceText: String) extends PaymentDescription

case class PlainMetaDescription(invoiceText: String, meta: String) extends PaymentDescription

case class SwapInDescription(invoiceText: String, txid: String, internalId: Long, nodeId: PublicKey) extends PaymentDescription

case class SwapOutDescription(invoiceText: String, btcAddress: String, chainFee: Satoshi, nodeId: PublicKey) extends PaymentDescription