/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.payment

import java.util.UUID

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.router.Router.{ChannelDesc, ChannelHop, Hop, Ignore}
import fr.acinq.eclair.wire.Node

/**
  * Created by PM on 01/02/2017.
  */

sealed trait PaymentEvent {
  val paymentHash: ByteVector32
  val timestamp: Long
}

/**
  * A payment was successfully sent and fulfilled.
  *
  * @param id              id of the whole payment attempt (if using multi-part, there will be multiple parts, each with
  *                        a different id).
  * @param paymentHash     payment hash.
  * @param paymentPreimage payment preimage (proof of payment).
  * @param recipientAmount amount that has been received by the final recipient.
  * @param recipientNodeId id of the final recipient.
  * @param parts           child payments (actual outgoing HTLCs).
  */
case class PaymentSent(id: UUID, paymentHash: ByteVector32, paymentPreimage: ByteVector32, recipientAmount: MilliSatoshi, recipientNodeId: PublicKey, parts: Seq[PaymentSent.PartialPayment]) extends PaymentEvent {
  require(parts.nonEmpty, "must have at least one payment part")
  val amountWithFees: MilliSatoshi = parts.map(_.amountWithFees).sum
  val feesPaid: MilliSatoshi = amountWithFees - recipientAmount // overall fees for this payment (routing + trampoline)
  val trampolineFees: MilliSatoshi = parts.map(_.amount).sum - recipientAmount
  val nonTrampolineFees: MilliSatoshi = feesPaid - trampolineFees // routing fees to reach the first trampoline node, or the recipient if not using trampoline
  val timestamp: Long = parts.map(_.timestamp).min // we use min here because we receive the proof of payment as soon as the first partial payment is fulfilled
}

object PaymentSent {

  /**
    * A successfully sent partial payment (single outgoing HTLC).
    *
    * @param id          id of the outgoing payment.
    * @param amount      amount received by the target node.
    * @param feesPaid    fees paid to route to the target node.
    * @param toChannelId id of the channel used.
    * @param route       payment route used.
    * @param timestamp   absolute time in milli-seconds since UNIX epoch when the payment was fulfilled.
    */
  case class PartialPayment(id: UUID, amount: MilliSatoshi, feesPaid: MilliSatoshi, toChannelId: ByteVector32, route: Option[Seq[Hop]], timestamp: Long = System.currentTimeMillis) {
    require(route.isEmpty || route.get.nonEmpty, "route must be None or contain at least one hop")
    val amountWithFees: MilliSatoshi = amount + feesPaid
  }

}

case class PaymentFailed(id: UUID, paymentHash: ByteVector32, failures: Seq[PaymentFailure], timestamp: Long = System.currentTimeMillis) extends PaymentEvent

sealed trait PaymentRelayed extends PaymentEvent {
  val amountIn: MilliSatoshi
  val amountOut: MilliSatoshi
  val timestamp: Long
}

case class ChannelPaymentRelayed(amountIn: MilliSatoshi, amountOut: MilliSatoshi, paymentHash: ByteVector32, fromChannelId: ByteVector32, toChannelId: ByteVector32, timestamp: Long = System.currentTimeMillis) extends PaymentRelayed

case class TrampolinePaymentRelayed(paymentHash: ByteVector32, incoming: PaymentRelayed.Incoming, outgoing: PaymentRelayed.Outgoing, timestamp: Long = System.currentTimeMillis) extends PaymentRelayed {
  override val amountIn: MilliSatoshi = incoming.map(_.amount).sum
  override val amountOut: MilliSatoshi = outgoing.map(_.amount).sum
}

object PaymentRelayed {

  case class Part(amount: MilliSatoshi, channelId: ByteVector32)

  type Incoming = Seq[Part]
  type Outgoing = Seq[Part]

}

case class PaymentReceived(paymentHash: ByteVector32, parts: Seq[PaymentReceived.PartialPayment]) extends PaymentEvent {
  require(parts.nonEmpty, "must have at least one payment part")
  val amount: MilliSatoshi = parts.map(_.amount).sum
  val timestamp: Long = parts.map(_.timestamp).max // we use max here because we fulfill the payment only once we received all the parts
}

object PaymentReceived {

  case class PartialPayment(amount: MilliSatoshi, fromChannelId: ByteVector32, timestamp: Long = System.currentTimeMillis)

}

case class PaymentSettlingOnChain(id: UUID, amount: MilliSatoshi, paymentHash: ByteVector32, timestamp: Long = System.currentTimeMillis) extends PaymentEvent

sealed trait PaymentFailure {
  def route: Seq[Hop]
}

/** A failure happened locally, preventing the payment from being sent (e.g. no route found). */
case class LocalFailure(route: Seq[Hop], t: Throwable) extends PaymentFailure

/** A remote node failed the payment and we were able to decrypt the onion failure packet. */
case class RemoteFailure(route: Seq[Hop], e: Sphinx.DecryptedFailurePacket) extends PaymentFailure

/** A remote node failed the payment but we couldn't decrypt the failure (e.g. a malicious node tampered with the message). */
case class UnreadableRemoteFailure(route: Seq[Hop]) extends PaymentFailure

object PaymentFailure {

  import fr.acinq.eclair.channel.AddHtlcFailed
  import fr.acinq.eclair.router.RouteNotFound
  import fr.acinq.eclair.wire.Update

  /**
    * This allows us to detect if a bad node always answers with a new update (e.g. with a slightly different expiry or fee)
    * in order to mess with us.
    */
  def hasAlreadyFailed(nodeId: PublicKey, failures: Seq[PaymentFailure], times: Int): Boolean =
    failures.collect { case RemoteFailure(_, Sphinx.DecryptedFailurePacket(origin, u: Update)) if origin == nodeId => u.update }.size >= times

  /** Update the set of nodes and channels to ignore in retries depending on the failure we received. */
  def updateIgnored(failure: PaymentFailure, ignore: Ignore): Ignore = failure match {
    case RemoteFailure(hops, Sphinx.DecryptedFailurePacket(nodeId, _)) if nodeId == hops.last.nextNodeId =>
      // The failure came from the final recipient: the payment should be aborted without penalizing anyone in the route.
      ignore
    case RemoteFailure(_, Sphinx.DecryptedFailurePacket(nodeId, _: Node)) =>
      ignore + nodeId
    case RemoteFailure(hops, Sphinx.DecryptedFailurePacket(nodeId, _)) =>
      // Let's ignore the channel outgoing from nodeId.
      hops.collectFirst {
        case hop: ChannelHop if hop.nodeId == nodeId => ChannelDesc(hop.lastUpdate.shortChannelId, hop.nodeId, hop.nextNodeId)
      } match {
        case Some(faultyChannel) => ignore + faultyChannel
        case None => ignore
      }
    case UnreadableRemoteFailure(hops) =>
      // We don't know which node is sending garbage, let's blacklist all nodes except the one we are directly connected to and the final recipient.
      val blacklist = hops.map(_.nextNodeId).drop(1).dropRight(1).toSet
      ignore ++ blacklist
    case LocalFailure(hops, _) => hops.headOption match {
      case Some(hop: ChannelHop) =>
        val faultyChannel = ChannelDesc(hop.lastUpdate.shortChannelId, hop.nodeId, hop.nextNodeId)
        ignore + faultyChannel
      case _ => ignore
    }
  }
}
