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

package fr.acinq.eclair.wire

import scala.concurrent.duration._
import java.net.{Inet4Address, Inet6Address, InetAddress, InetSocketAddress}
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

import com.btcontract.wallet.ln.LNParams
import com.btcontract.wallet.ln.wire.UpdateAddTlv
import com.google.common.base.Charsets
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto, LexicographicalOrdering, Protocol, Satoshi}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, Features, MilliSatoshi, ShortChannelId, UInt64}
import fr.acinq.eclair.{byteVector64One, invalidPubKey}
import scodec.bits.{BitVector, ByteVector}

/**
 * Created by PM on 15/11/2016.
 */

// @formatter:off
sealed trait LightningMessage
sealed trait SetupMessage extends LightningMessage
sealed trait ChannelMessage extends LightningMessage
sealed trait HtlcMessage extends LightningMessage
sealed trait RoutingMessage extends LightningMessage
sealed trait AnnouncementMessage extends RoutingMessage // <- not in the spec
sealed trait HasTimestamp extends LightningMessage { def timestamp: Long }
sealed trait HasTemporaryChannelId extends LightningMessage { def temporaryChannelId: ByteVector32 } // <- not in the spec
sealed trait HasChannelId extends LightningMessage { def channelId: ByteVector32 } // <- not in the spec
sealed trait HasChainHash extends LightningMessage { def chainHash: ByteVector32 } // <- not in the spec
sealed trait UpdateMessage extends HtlcMessage // <- not in the spec
// @formatter:on

case class Init(features: Features, tlvs: TlvStream[InitTlv] = TlvStream.empty) extends SetupMessage {
  val networks: Seq[ByteVector32] = tlvs.get[InitTlv.Networks].map(_.chainHashes).getOrElse(Nil)
}

case class Error(channelId: ByteVector32, data: ByteVector) extends SetupMessage with HasChannelId {
  def toAscii: String = if (fr.acinq.eclair.isAsciiPrintable(data)) new String(data.toArray, StandardCharsets.US_ASCII) else "n/a"
}

object Error {
  def apply(channelId: ByteVector32, msg: String): Error = Error(channelId, ByteVector.view(msg.getBytes(Charsets.US_ASCII)))
}

case class Ping(pongLength: Int, data: ByteVector) extends SetupMessage

case class Pong(data: ByteVector) extends SetupMessage

case class ChannelReestablish(channelId: ByteVector32,
                              nextLocalCommitmentNumber: Long,
                              nextRemoteRevocationNumber: Long,
                              yourLastPerCommitmentSecret: PrivateKey,
                              myCurrentPerCommitmentPoint: PublicKey) extends ChannelMessage with HasChannelId

case class OpenChannel(chainHash: ByteVector32,
                       temporaryChannelId: ByteVector32,
                       fundingSatoshis: Satoshi,
                       pushMsat: MilliSatoshi,
                       dustLimitSatoshis: Satoshi,
                       maxHtlcValueInFlightMsat: UInt64, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
                       channelReserveSatoshis: Satoshi,
                       htlcMinimumMsat: MilliSatoshi,
                       feeratePerKw: Long,
                       toSelfDelay: CltvExpiryDelta,
                       maxAcceptedHtlcs: Int,
                       fundingPubkey: PublicKey,
                       revocationBasepoint: PublicKey,
                       paymentBasepoint: PublicKey,
                       delayedPaymentBasepoint: PublicKey,
                       htlcBasepoint: PublicKey,
                       firstPerCommitmentPoint: PublicKey,
                       channelFlags: Byte,
                       tlvStream: TlvStream[OpenChannelTlv] = TlvStream.empty) extends ChannelMessage with HasTemporaryChannelId with HasChainHash

case class AcceptChannel(temporaryChannelId: ByteVector32,
                         dustLimitSatoshis: Satoshi,
                         maxHtlcValueInFlightMsat: UInt64, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
                         channelReserveSatoshis: Satoshi,
                         htlcMinimumMsat: MilliSatoshi,
                         minimumDepth: Long,
                         toSelfDelay: CltvExpiryDelta,
                         maxAcceptedHtlcs: Int,
                         fundingPubkey: PublicKey,
                         revocationBasepoint: PublicKey,
                         paymentBasepoint: PublicKey,
                         delayedPaymentBasepoint: PublicKey,
                         htlcBasepoint: PublicKey,
                         firstPerCommitmentPoint: PublicKey,
                         tlvStream: TlvStream[AcceptChannelTlv] = TlvStream.empty) extends ChannelMessage with HasTemporaryChannelId

case class FundingCreated(temporaryChannelId: ByteVector32,
                          fundingTxid: ByteVector32,
                          fundingOutputIndex: Int,
                          signature: ByteVector64) extends ChannelMessage with HasTemporaryChannelId

case class FundingSigned(channelId: ByteVector32,
                         signature: ByteVector64) extends ChannelMessage with HasChannelId

case class FundingLocked(channelId: ByteVector32,
                         nextPerCommitmentPoint: PublicKey) extends ChannelMessage with HasChannelId

case class Shutdown(channelId: ByteVector32,
                    scriptPubKey: ByteVector) extends ChannelMessage with HasChannelId

case class ClosingSigned(channelId: ByteVector32,
                         feeSatoshis: Satoshi,
                         signature: ByteVector64) extends ChannelMessage with HasChannelId

case class UpdateAddHtlc(channelId: ByteVector32,
                         id: Long,
                         amountMsat: MilliSatoshi,
                         paymentHash: ByteVector32,
                         cltvExpiry: CltvExpiry,
                         onionRoutingPacket: OnionRoutingPacket,
                         tlvStream: TlvStream[Tlv] = TlvStream.empty) extends HtlcMessage with UpdateMessage with HasChannelId {
  lazy val internalId: Option[UpdateAddTlv.InternalId] = tlvStream.get[UpdateAddTlv.InternalId]
}

case class UpdateFulfillHtlc(channelId: ByteVector32,
                             id: Long,
                             paymentPreimage: ByteVector32) extends HtlcMessage with UpdateMessage with HasChannelId {
  lazy val paymentHash: ByteVector32 = Crypto.sha256(paymentPreimage)
}

case class UpdateFailHtlc(channelId: ByteVector32,
                          id: Long,
                          reason: ByteVector) extends HtlcMessage with UpdateMessage with HasChannelId

case class UpdateFailMalformedHtlc(channelId: ByteVector32,
                                   id: Long,
                                   onionHash: ByteVector32,
                                   failureCode: Int) extends HtlcMessage with UpdateMessage with HasChannelId

case class CommitSig(channelId: ByteVector32,
                     signature: ByteVector64,
                     htlcSignatures: List[ByteVector64]) extends HtlcMessage with HasChannelId

case class RevokeAndAck(channelId: ByteVector32,
                        perCommitmentSecret: PrivateKey,
                        nextPerCommitmentPoint: PublicKey) extends HtlcMessage with HasChannelId

case class UpdateFee(channelId: ByteVector32,
                     feeratePerKw: Long) extends ChannelMessage with UpdateMessage with HasChannelId

case class AnnouncementSignatures(channelId: ByteVector32,
                                  shortChannelId: ShortChannelId,
                                  nodeSignature: ByteVector64,
                                  bitcoinSignature: ByteVector64) extends RoutingMessage with HasChannelId {
  def isPHC: Boolean = bitcoinSignature == ByteVector64.Zeroes
}

case class ChannelAnnouncement(nodeSignature1: ByteVector64,
                               nodeSignature2: ByteVector64,
                               bitcoinSignature1: ByteVector64,
                               bitcoinSignature2: ByteVector64,
                               features: Features,
                               chainHash: ByteVector32,
                               shortChannelId: ShortChannelId,
                               nodeId1: PublicKey,
                               nodeId2: PublicKey,
                               bitcoinKey1: PublicKey,
                               bitcoinKey2: PublicKey,
                               unknownFields: ByteVector = ByteVector.empty) extends RoutingMessage with AnnouncementMessage with HasChainHash {

  def getNodeIdSameSideAs(cu: ChannelUpdate): PublicKey = if (cu.position == ChannelUpdate.POSITION1NODE) nodeId1 else nodeId2

  def isPHC: Boolean =
    bitcoinKey1 == invalidPubKey && bitcoinKey2 == invalidPubKey &&
      bitcoinSignature1 == ByteVector64.Zeroes && bitcoinSignature2 == ByteVector64.Zeroes

  // Point useless fields to same object, db-restored should be the same

  def litePHC: ChannelAnnouncement =
    copy(nodeSignature1 = byteVector64One, nodeSignature2 = byteVector64One, bitcoinSignature1 = ByteVector64.Zeroes, bitcoinSignature2 = ByteVector64.Zeroes,
      features = Features.empty, chainHash = LNParams.chainHash, bitcoinKey1 = invalidPubKey, bitcoinKey2 = invalidPubKey)

  def lite: ChannelAnnouncement =
    copy(nodeSignature1 = byteVector64One, nodeSignature2 = byteVector64One, bitcoinSignature1 = byteVector64One, bitcoinSignature2 = byteVector64One,
      features = Features.empty, chainHash = LNParams.chainHash, bitcoinKey1 = invalidPubKey, bitcoinKey2 = invalidPubKey)
}

case class Color(r: Byte, g: Byte, b: Byte) {
  override def toString: String = f"#$r%02x$g%02x$b%02x" // to hexa s"#  ${r}%02x ${r & 0xFF}${g & 0xFF}${b & 0xFF}"
}

// @formatter:off
sealed trait NodeAddress { def socketAddress: InetSocketAddress }
sealed trait OnionAddress extends NodeAddress
object NodeAddress {
  val onionSuffix = ".onion"
  val V2Len = 16
  val V3Len = 56

  def isTor(na: NodeAddress): Boolean = na match {
    case _: Tor2 | _: Tor3 => true
    case _ => false
  }

  def fromParts(host: String, port: Int, orElse: (String, Int) => NodeAddress = resolveIp): NodeAddress =
    if (host.endsWith(onionSuffix) && host.length == V2Len + onionSuffix.length) Tor2(host.dropRight(onionSuffix.length), port)
    else if (host.endsWith(onionSuffix) && host.length == V3Len + onionSuffix.length) Tor3(host.dropRight(onionSuffix.length), port)
    else orElse(host, port)

  def resolveIp(host: String, port: Int): NodeAddress = {
    // Tries to resolve an IP from domain if given, should not be used on main thread
    // the rationale is that user may get a node address with domain instead of IP
    InetAddress getByName host match {
      case inetV4Address: Inet4Address => IPv4(inetV4Address, port)
      case inetV6Address: Inet6Address => IPv6(inetV6Address, port)
    }
  }

  def unresolved(port: Int, host: Int *): NodeAddress =
    InetAddress getByAddress host.toArray.map(_.toByte) match {
      case inetV4Address: Inet4Address => IPv4(inetV4Address, port)
      case inetV6Address: Inet6Address => IPv6(inetV6Address, port)
    }
}

case class IPv4(ipv4: Inet4Address, port: Int) extends NodeAddress {
  override def socketAddress = new InetSocketAddress(ipv4, port)
  override def toString: String = s"${ipv4.toString.tail}:$port"
}

case class IPv6(ipv6: Inet6Address, port: Int) extends NodeAddress {
  override def socketAddress = new InetSocketAddress(ipv6, port)
  override def toString: String = s"${ipv6.toString.tail}:$port"
}

case class Tor2(tor2: String, port: Int) extends OnionAddress {
  override def socketAddress: InetSocketAddress = InetSocketAddress.createUnresolved(tor2 + NodeAddress.onionSuffix, port)
  override def toString: String = s"$tor2${NodeAddress.onionSuffix}:$port"
}

case class Tor3(tor3: String, port: Int) extends OnionAddress {
  override def socketAddress: InetSocketAddress = InetSocketAddress.createUnresolved(tor3 + NodeAddress.onionSuffix, port)
  override def toString: String = s"$tor3${NodeAddress.onionSuffix}:$port"
}

case class Domain(domain: String, port: Int) extends NodeAddress {
  override def socketAddress = new InetSocketAddress(domain, port)
  override def toString: String = s"$domain:$port"
}
// @formatter:on


case class NodeAnnouncement(signature: ByteVector64,
                            features: Features,
                            timestamp: Long,
                            nodeId: PublicKey,
                            rgbColor: Color,
                            alias: String,
                            addresses: List[NodeAddress],
                            unknownFields: ByteVector = ByteVector.empty) extends RoutingMessage with AnnouncementMessage with HasTimestamp

object ChannelUpdate {
  final val POSITION1NODE: java.lang.Integer = 1
  final val POSITION2NODE: java.lang.Integer = 2
  final val fullSet = Set(POSITION1NODE, POSITION2NODE)
}

case class UpdateCore(position: java.lang.Integer,
                      shortChannelId: ShortChannelId,
                      feeBase: MilliSatoshi,
                      feeProportionalMillionths: Long,
                      cltvExpiryDelta: CltvExpiryDelta,
                      htlcMaximumMsat: Option[MilliSatoshi])

case class ChannelUpdate(signature: ByteVector64,
                         chainHash: ByteVector32,
                         shortChannelId: ShortChannelId,
                         timestamp: Long,
                         messageFlags: Byte,
                         channelFlags: Byte,
                         cltvExpiryDelta: CltvExpiryDelta,
                         htlcMinimumMsat: MilliSatoshi,
                         feeBaseMsat: MilliSatoshi,
                         feeProportionalMillionths: Long,
                         htlcMaximumMsat: Option[MilliSatoshi],
                         unknownFields: ByteVector = ByteVector.empty) extends RoutingMessage with AnnouncementMessage with HasTimestamp with HasChainHash {

  lazy val position: java.lang.Integer = {
    val isNode1: Boolean = Announcements.isNode1(channelFlags)
    if (isNode1) ChannelUpdate.POSITION1NODE else ChannelUpdate.POSITION2NODE
  }

  lazy val core: UpdateCore = UpdateCore(position, shortChannelId, feeBaseMsat, feeProportionalMillionths, cltvExpiryDelta, htlcMaximumMsat)

  // Reference useless fields to same objects to reduce memory footprint and set timestamp to current moment, make sure it does not erase channelUpdateChecksumCodec fields
  def lite: ChannelUpdate = copy(signature = byteVector64One, chainHash = LNParams.chainHash, unknownFields = ByteVector.empty)
}

// @formatter:off
sealed trait EncodingType
object EncodingType {
  case object UNCOMPRESSED extends EncodingType
  case object COMPRESSED_ZLIB extends EncodingType
}
// @formatter:on

case class EncodedShortChannelIds(encoding: EncodingType,
                                  array: List[ShortChannelId])

case class QueryShortChannelIds(chainHash: ByteVector32,
                                shortChannelIds: EncodedShortChannelIds,
                                tlvStream: TlvStream[QueryShortChannelIdsTlv] = TlvStream.empty) extends RoutingMessage with HasChainHash {
  val queryFlags_opt: Option[QueryShortChannelIdsTlv.EncodedQueryFlags] = tlvStream.get[QueryShortChannelIdsTlv.EncodedQueryFlags]
}

case class ReplyShortChannelIdsEnd(chainHash: ByteVector32,
                                   complete: Byte) extends RoutingMessage with HasChainHash

case class QueryChannelRange(chainHash: ByteVector32,
                             firstBlockNum: Long,
                             numberOfBlocks: Long,
                             tlvStream: TlvStream[QueryChannelRangeTlv] = TlvStream.empty) extends RoutingMessage {
  val queryFlags_opt: Option[QueryChannelRangeTlv.QueryFlags] = tlvStream.get[QueryChannelRangeTlv.QueryFlags]
}

case class ReplyChannelRange(chainHash: ByteVector32,
                             firstBlockNum: Long,
                             numberOfBlocks: Long,
                             complete: Byte,
                             shortChannelIds: EncodedShortChannelIds,
                             tlvStream: TlvStream[ReplyChannelRangeTlv] = TlvStream.empty) extends RoutingMessage {
  val timestamps: ReplyChannelRangeTlv.EncodedTimestamps = tlvStream.get[ReplyChannelRangeTlv.EncodedTimestamps].get
  val checksums: ReplyChannelRangeTlv.EncodedChecksums = tlvStream.get[ReplyChannelRangeTlv.EncodedChecksums].get
}

object ReplyChannelRange {
  def apply(chainHash: ByteVector32,
            firstBlockNum: Long,
            numberOfBlocks: Long,
            complete: Byte,
            shortChannelIds: EncodedShortChannelIds,
            timestamps: Option[ReplyChannelRangeTlv.EncodedTimestamps],
            checksums: Option[ReplyChannelRangeTlv.EncodedChecksums]): ReplyChannelRange = {
    timestamps.foreach(ts => require(ts.timestamps.length == shortChannelIds.array.length))
    checksums.foreach(cs => require(cs.checksums.length == shortChannelIds.array.length))
    new ReplyChannelRange(chainHash, firstBlockNum, numberOfBlocks, complete, shortChannelIds, TlvStream(timestamps.toList ::: checksums.toList))
  }
}


case class GossipTimestampFilter(chainHash: ByteVector32,
                                 firstTimestamp: Long,
                                 timestampRange: Long) extends RoutingMessage with HasChainHash

// HOSTED CHANNELS

trait HostedChannelMessage extends ChannelMessage

case class InvokeHostedChannel(chainHash: ByteVector32,
                               refundScriptPubKey: ByteVector,
                               secret: ByteVector = ByteVector.empty,
                               features: BitVector = BitVector.empty) extends HostedChannelMessage {
  require(secret.size <= 64, s"Hosted channel secret size=${secret.size}, max allowed=64")
  val finalSecret: ByteVector = secret.take(64)
}

case class InitHostedChannel(maxHtlcValueInFlightMsat: UInt64,
                             htlcMinimumMsat: MilliSatoshi,
                             maxAcceptedHtlcs: Int,
                             channelCapacityMsat: MilliSatoshi,
                             liabilityDeadlineBlockdays: Int,
                             minimalOnchainRefundAmountSatoshis: Satoshi,
                             initialClientBalanceMsat: MilliSatoshi,
                             features: BitVector = BitVector.empty) extends HostedChannelMessage

case class HostedChannelBranding(rgbColor: Color, pngIcon: ByteVector) extends HostedChannelMessage

case class LastCrossSignedState(refundScriptPubKey: ByteVector,
                                initHostedChannel: InitHostedChannel,
                                blockDay: Long,
                                localBalanceMsat: MilliSatoshi,
                                remoteBalanceMsat: MilliSatoshi,
                                localUpdates: Long,
                                remoteUpdates: Long,
                                incomingHtlcs: List[UpdateAddHtlc],
                                outgoingHtlcs: List[UpdateAddHtlc],
                                remoteSigOfLocal: ByteVector64,
                                localSigOfRemote: ByteVector64) extends HostedChannelMessage {

  lazy val reverse: LastCrossSignedState =
    copy(localUpdates = remoteUpdates, remoteUpdates = localUpdates,
      localBalanceMsat = remoteBalanceMsat, remoteBalanceMsat = localBalanceMsat,
      remoteSigOfLocal = localSigOfRemote, localSigOfRemote = remoteSigOfLocal,
      incomingHtlcs = outgoingHtlcs, outgoingHtlcs = incomingHtlcs)

  lazy val hostedSigHash: ByteVector32 = {
    val inPayments = incomingHtlcs.map(LightningMessageCodecs.updateAddHtlcCodec.encode(_).require.toByteVector).sortWith(LexicographicalOrdering.isLessThan)
    val outPayments = outgoingHtlcs.map(LightningMessageCodecs.updateAddHtlcCodec.encode(_).require.toByteVector).sortWith(LexicographicalOrdering.isLessThan)

    val preimage =
      refundScriptPubKey ++
        Protocol.writeUInt16(initHostedChannel.liabilityDeadlineBlockdays, ByteOrder.LITTLE_ENDIAN) ++
        Protocol.writeUInt64(initHostedChannel.minimalOnchainRefundAmountSatoshis.toLong, ByteOrder.LITTLE_ENDIAN) ++
        Protocol.writeUInt64(initHostedChannel.channelCapacityMsat.toLong, ByteOrder.LITTLE_ENDIAN) ++
        Protocol.writeUInt64(initHostedChannel.initialClientBalanceMsat.toLong, ByteOrder.LITTLE_ENDIAN) ++
        Protocol.writeUInt32(blockDay, ByteOrder.LITTLE_ENDIAN) ++
        Protocol.writeUInt64(localBalanceMsat.toLong, ByteOrder.LITTLE_ENDIAN) ++
        Protocol.writeUInt64(remoteBalanceMsat.toLong, ByteOrder.LITTLE_ENDIAN) ++
        Protocol.writeUInt32(localUpdates, ByteOrder.LITTLE_ENDIAN) ++
        Protocol.writeUInt32(remoteUpdates, ByteOrder.LITTLE_ENDIAN) ++
        inPayments.foldLeft(ByteVector.empty) { case (acc, htlc) => acc ++ htlc } ++
        outPayments.foldLeft(ByteVector.empty) { case (acc, htlc) => acc ++ htlc }

    Crypto.sha256(preimage)
  }

  def verifyRemoteSig(pubKey: PublicKey): Boolean = Crypto.verifySignature(hostedSigHash, remoteSigOfLocal, pubKey)

  def withLocalSigOfRemote(priv: PrivateKey): LastCrossSignedState = copy(localSigOfRemote = Crypto.sign(reverse.hostedSigHash, priv))

  def isAhead(remoteLCSS: LastCrossSignedState): Boolean = remoteUpdates > remoteLCSS.localUpdates || localUpdates > remoteLCSS.remoteUpdates

  def isEven(remoteLCSS: LastCrossSignedState): Boolean = remoteUpdates == remoteLCSS.localUpdates && localUpdates == remoteLCSS.remoteUpdates

  def stateUpdate(isTerminal: Boolean): StateUpdate = StateUpdate(blockDay, localUpdates, remoteUpdates, localSigOfRemote, isTerminal)
}

case class StateUpdate(blockDay: Long, localUpdates: Long, remoteUpdates: Long, localSigOfRemoteLCSS: ByteVector64, isTerminal: Boolean) extends HostedChannelMessage

case class StateOverride(blockDay: Long, localBalanceMsat: MilliSatoshi, localUpdates: Long, remoteUpdates: Long, localSigOfRemoteLCSS: ByteVector64) extends HostedChannelMessage

// PHC

case class QueryPublicHostedChannels(chainHash: ByteVector32) extends RoutingMessage with HasChainHash

case class ReplyPublicHostedChannelsEnd(chainHash: ByteVector32) extends RoutingMessage with HasChainHash

// SWAP IN-OUT plugin

sealed trait SwapIn extends LightningMessage

case object SwapInRequest extends SwapIn

case class SwapInResponse(btcAddress: String) extends SwapIn

case class PendingDeposit(btcAddress: String, txid: ByteVector32, amount: Satoshi,
                          stamp: Long = System.currentTimeMillis.milliseconds.toSeconds)

case class SwapInState(balance: MilliSatoshi, maxWithdrawable: MilliSatoshi, activeFeeReserve: MilliSatoshi,
                       inFlightAmount: MilliSatoshi, pendingChainDeposits: List[PendingDeposit] = Nil) extends SwapIn


sealed trait SwapOut extends LightningMessage

case class BlockTargetAndFee(blockTarget: Int, fee: Satoshi)

case class SwapOutFeerates(feerates: List[BlockTargetAndFee] = Nil) extends SwapOut

case class SwapOutRequest(amount: Satoshi, btcAddress: String, blockTarget: Int) extends SwapOut

case class SwapOutResponse(amount: Satoshi, fee: Satoshi, paymentRequest: String) extends SwapOut
