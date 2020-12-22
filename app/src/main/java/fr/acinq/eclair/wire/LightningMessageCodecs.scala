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

import com.btcontract.wallet.ln.wire.UpdateAddTlv
import fr.acinq.eclair.wire.CommonCodecs._
import fr.acinq.eclair.{Features, wire}
import scodec.bits.ByteVector
import scodec.codecs._
import scodec.Codec

/**
 * Created by PM on 15/11/2016.
 */
object LightningMessageCodecs {

  val featuresCodec: Codec[Features] = varsizebinarydata.xmap[Features](
    { bytes => Features(bytes) },
    { features => features.toByteVector }
  )

  /** For historical reasons, features are divided into two feature bitmasks. We only send from the second one, but we allow receiving in both. */
  val combinedFeaturesCodec: Codec[Features] = (
    ("globalFeatures" | varsizebinarydata) ::
      ("localFeatures" | varsizebinarydata)).as[(ByteVector, ByteVector)].xmap[Features](
    { case (gf, lf) =>
      val length = gf.length.max(lf.length)
      Features(gf.padLeft(length) | lf.padLeft(length))
    },
    { features => (ByteVector.empty, features.toByteVector) })

  val initCodec: Codec[Init] = (("features" | combinedFeaturesCodec) :: ("tlvStream" | InitTlvCodecs.initTlvCodec)).as[Init]

  val errorCodec: Codec[Error] = (
    ("channelId" | bytes32) ::
      ("data" | varsizebinarydata)).as[Error]

  val pingCodec: Codec[Ping] = (
    ("pongLength" | uint16) ::
      ("data" | varsizebinarydata)).as[Ping]

  val pongCodec: Codec[Pong] =
    ("data" | varsizebinarydata).as[Pong]

  val channelReestablishCodec: Codec[ChannelReestablish] = (
    ("channelId" | bytes32) ::
      ("nextLocalCommitmentNumber" | uint64overflow) ::
      ("nextRemoteRevocationNumber" | uint64overflow) ::
      ("yourLastPerCommitmentSecret" | privateKey) ::
      ("myCurrentPerCommitmentPoint" | publicKey)).as[ChannelReestablish]

  val openChannelCodec: Codec[OpenChannel] = (
    ("chainHash" | bytes32) ::
      ("temporaryChannelId" | bytes32) ::
      ("fundingSatoshis" | satoshi) ::
      ("pushMsat" | millisatoshi) ::
      ("dustLimitSatoshis" | satoshi) ::
      ("maxHtlcValueInFlightMsat" | uint64) ::
      ("channelReserveSatoshis" | satoshi) ::
      ("htlcMinimumMsat" | millisatoshi) ::
      ("feeratePerKw" | uint32) ::
      ("toSelfDelay" | cltvExpiryDelta) ::
      ("maxAcceptedHtlcs" | uint16) ::
      ("fundingPubkey" | publicKey) ::
      ("revocationBasepoint" | publicKey) ::
      ("paymentBasepoint" | publicKey) ::
      ("delayedPaymentBasepoint" | publicKey) ::
      ("htlcBasepoint" | publicKey) ::
      ("firstPerCommitmentPoint" | publicKey) ::
      ("channelFlags" | byte) ::
      ("tlvStream" | OpenChannelTlv.openTlvCodec)).as[OpenChannel]

  val acceptChannelCodec: Codec[AcceptChannel] = (
    ("temporaryChannelId" | bytes32) ::
      ("dustLimitSatoshis" | satoshi) ::
      ("maxHtlcValueInFlightMsat" | uint64) ::
      ("channelReserveSatoshis" | satoshi) ::
      ("htlcMinimumMsat" | millisatoshi) ::
      ("minimumDepth" | uint32) ::
      ("toSelfDelay" | cltvExpiryDelta) ::
      ("maxAcceptedHtlcs" | uint16) ::
      ("fundingPubkey" | publicKey) ::
      ("revocationBasepoint" | publicKey) ::
      ("paymentBasepoint" | publicKey) ::
      ("delayedPaymentBasepoint" | publicKey) ::
      ("htlcBasepoint" | publicKey) ::
      ("firstPerCommitmentPoint" | publicKey) ::
      ("tlvStream" | AcceptChannelTlv.acceptTlvCodec)).as[AcceptChannel]

  val fundingCreatedCodec: Codec[FundingCreated] = (
    ("temporaryChannelId" | bytes32) ::
      ("fundingTxid" | bytes32) ::
      ("fundingOutputIndex" | uint16) ::
      ("signature" | bytes64)).as[FundingCreated]

  val fundingSignedCodec: Codec[FundingSigned] = (
    ("channelId" | bytes32) ::
      ("signature" | bytes64)).as[FundingSigned]

  val fundingLockedCodec: Codec[FundingLocked] = (
    ("channelId" | bytes32) ::
      ("nextPerCommitmentPoint" | publicKey)).as[FundingLocked]

  val shutdownCodec: Codec[wire.Shutdown] = (
    ("channelId" | bytes32) ::
      ("scriptPubKey" | varsizebinarydata)).as[Shutdown]

  val closingSignedCodec: Codec[ClosingSigned] = (
    ("channelId" | bytes32) ::
      ("feeSatoshis" | satoshi) ::
      ("signature" | bytes64)).as[ClosingSigned]

  val updateAddHtlcCodec: Codec[UpdateAddHtlc] = (
    ("channelId" | bytes32) ::
      ("id" | uint64overflow) ::
      ("amountMsat" | millisatoshi) ::
      ("paymentHash" | bytes32) ::
      ("expiry" | cltvExpiry) ::
      ("onionRoutingPacket" | OnionCodecs.paymentOnionPacketCodec) ::
      ("tlvStream" | UpdateAddTlv.codec)).as[UpdateAddHtlc]

  val updateFulfillHtlcCodec: Codec[UpdateFulfillHtlc] = (
    ("channelId" | bytes32) ::
      ("id" | uint64overflow) ::
      ("paymentPreimage" | bytes32)).as[UpdateFulfillHtlc]

  val updateFailHtlcCodec: Codec[UpdateFailHtlc] = (
    ("channelId" | bytes32) ::
      ("id" | uint64overflow) ::
      ("reason" | varsizebinarydata)).as[UpdateFailHtlc]

  val updateFailMalformedHtlcCodec: Codec[UpdateFailMalformedHtlc] = (
    ("channelId" | bytes32) ::
      ("id" | uint64overflow) ::
      ("onionHash" | bytes32) ::
      ("failureCode" | uint16)).as[UpdateFailMalformedHtlc]

  val commitSigCodec: Codec[CommitSig] = (
    ("channelId" | bytes32) ::
      ("signature" | bytes64) ::
      ("htlcSignatures" | listofsignatures)).as[CommitSig]

  val revokeAndAckCodec: Codec[RevokeAndAck] = (
    ("channelId" | bytes32) ::
      ("perCommitmentSecret" | privateKey) ::
      ("nextPerCommitmentPoint" | publicKey)
    ).as[RevokeAndAck]

  val updateFeeCodec: Codec[UpdateFee] = (
    ("channelId" | bytes32) ::
      ("feeratePerKw" | uint32)).as[UpdateFee]

  val announcementSignaturesCodec: Codec[AnnouncementSignatures] = (
    ("channelId" | bytes32) ::
      ("shortChannelId" | shortchannelid) ::
      ("nodeSignature" | bytes64) ::
      ("bitcoinSignature" | bytes64)).as[AnnouncementSignatures]

  val channelAnnouncementWitnessCodec =
    ("features" | featuresCodec) ::
      ("chainHash" | bytes32) ::
      ("shortChannelId" | shortchannelid) ::
      ("nodeId1" | publicKey) ::
      ("nodeId2" | publicKey) ::
      ("bitcoinKey1" | publicKey) ::
      ("bitcoinKey2" | publicKey) ::
      ("unknownFields" | bytes)

  val channelAnnouncementCodec: Codec[ChannelAnnouncement] = (
    ("nodeSignature1" | bytes64) ::
      ("nodeSignature2" | bytes64) ::
      ("bitcoinSignature1" | bytes64) ::
      ("bitcoinSignature2" | bytes64) ::
      channelAnnouncementWitnessCodec).as[ChannelAnnouncement]

  val nodeAnnouncementWitnessCodec =
    ("features" | featuresCodec) ::
      ("timestamp" | uint32) ::
      ("nodeId" | publicKey) ::
      ("rgbColor" | rgb) ::
      ("alias" | zeropaddedstring(32)) ::
      ("addresses" | listofnodeaddresses) ::
      ("unknownFields" | bytes)

  val nodeAnnouncementCodec: Codec[NodeAnnouncement] = (
    ("signature" | bytes64) ::
      nodeAnnouncementWitnessCodec).as[NodeAnnouncement]

  val channelUpdateChecksumCodec =
    ("chainHash" | bytes32) ::
      ("shortChannelId" | shortchannelid) ::
      (("messageFlags" | byte) >>:~ { messageFlags =>
        ("channelFlags" | byte) ::
          ("cltvExpiryDelta" | cltvExpiryDelta) ::
          ("htlcMinimumMsat" | millisatoshi) ::
          ("feeBaseMsat" | millisatoshi32) ::
          ("feeProportionalMillionths" | uint32) ::
          ("htlcMaximumMsat" | conditional((messageFlags & 1) != 0, millisatoshi))
      })

  val channelUpdateWitnessCodec =
    ("chainHash" | bytes32) ::
      ("shortChannelId" | shortchannelid) ::
      ("timestamp" | uint32) ::
      (("messageFlags" | byte) >>:~ { messageFlags =>
        ("channelFlags" | byte) ::
          ("cltvExpiryDelta" | cltvExpiryDelta) ::
          ("htlcMinimumMsat" | millisatoshi) ::
          ("feeBaseMsat" | millisatoshi32) ::
          ("feeProportionalMillionths" | uint32) ::
          ("htlcMaximumMsat" | conditional((messageFlags & 1) != 0, millisatoshi)) ::
          ("unknownFields" | bytes)
      })

  val channelUpdateCodec: Codec[ChannelUpdate] = (
    ("signature" | bytes64) ::
      channelUpdateWitnessCodec).as[ChannelUpdate]

  val encodedShortChannelIdsCodec: Codec[EncodedShortChannelIds] =
    discriminated[EncodedShortChannelIds].by(byte)
      .\(0) {
        case a@EncodedShortChannelIds(_, Nil) => a // empty list is always encoded with encoding type 'uncompressed' for compatibility with other implementations
        case a@EncodedShortChannelIds(EncodingType.UNCOMPRESSED, _) => a
      }((provide[EncodingType](EncodingType.UNCOMPRESSED) :: list(shortchannelid)).as[EncodedShortChannelIds])
      .\(1) {
        case a@EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, _) => a
      }((provide[EncodingType](EncodingType.COMPRESSED_ZLIB) :: zlib(list(shortchannelid))).as[EncodedShortChannelIds])


  val queryShortChannelIdsCodec: Codec[QueryShortChannelIds] = {
    Codec(
      ("chainHash" | bytes32) ::
        ("shortChannelIds" | variableSizeBytes(uint16, encodedShortChannelIdsCodec)) ::
        ("tlvStream" | QueryShortChannelIdsTlv.codec)
    ).as[QueryShortChannelIds]
  }

  val replyShortChanelIdsEndCodec: Codec[ReplyShortChannelIdsEnd] = (
    ("chainHash" | bytes32) ::
      ("complete" | byte)
    ).as[ReplyShortChannelIdsEnd]

  val queryChannelRangeCodec: Codec[QueryChannelRange] = {
    Codec(
      ("chainHash" | bytes32) ::
        ("firstBlockNum" | uint32) ::
        ("numberOfBlocks" | uint32) ::
        ("tlvStream" | QueryChannelRangeTlv.codec)
    ).as[QueryChannelRange]
  }

  val replyChannelRangeCodec: Codec[ReplyChannelRange] = {
    Codec(
      ("chainHash" | bytes32) ::
        ("firstBlockNum" | uint32) ::
        ("numberOfBlocks" | uint32) ::
        ("complete" | byte) ::
        ("shortChannelIds" | variableSizeBytes(uint16, encodedShortChannelIdsCodec)) ::
        ("tlvStream" | ReplyChannelRangeTlv.codec)
    ).as[ReplyChannelRange]
  }

  val gossipTimestampFilterCodec: Codec[GossipTimestampFilter] = (
    ("chainHash" | bytes32) ::
      ("firstTimestamp" | uint32) ::
      ("timestampRange" | uint32)
    ).as[GossipTimestampFilter]

  //

  val lightningMessageCodec: DiscriminatorCodec[LightningMessage, Int] =
    discriminated[LightningMessage].by(uint16)
      .typecase(16, initCodec)
      .typecase(18, pingCodec)
      .typecase(19, pongCodec)
      .typecase(32, openChannelCodec)
      .typecase(33, acceptChannelCodec)
      .typecase(34, fundingCreatedCodec)
      .typecase(35, fundingSignedCodec)
      .typecase(36, fundingLockedCodec)
      .typecase(38, shutdownCodec)
      .typecase(39, closingSignedCodec)
      .typecase(132, commitSigCodec)
      .typecase(133, revokeAndAckCodec)
      .typecase(134, updateFeeCodec)
      .typecase(136, channelReestablishCodec)
      .typecase(256, channelAnnouncementCodec)
      .typecase(257, nodeAnnouncementCodec)
      .typecase(258, channelUpdateCodec)
      .typecase(259, announcementSignaturesCodec)
      .typecase(261, queryShortChannelIdsCodec)
      .typecase(262, replyShortChanelIdsEndCodec)
      .typecase(263, queryChannelRangeCodec)
      .typecase(264, replyChannelRangeCodec)
      .typecase(265, gossipTimestampFilterCodec)
      // SwapIn
      .typecase(55037, provide(SwapInRequest))
      .typecase(55035, SwapCodecs.swapInResponseCodec)
      .typecase(55033, SwapCodecs.swapInPaymentRequestCodec)
      .typecase(55031, SwapCodecs.swapInPaymentDeniedCodec)
      .typecase(55029, SwapCodecs.swapInStateCodec)
      // SwapOut
      .typecase(55027, provide(SwapOutRequest))
      .typecase(55025, SwapCodecs.swapOutFeeratesCodec)
      .typecase(55023, SwapCodecs.swapOutTransactionRequestCodec)
      .typecase(55021, SwapCodecs.swapOutTransactionResponseCodec)
      .typecase(55019, SwapCodecs.swapOutTransactionDeniedCodec)
      // HC
      .typecase(65535, HostedMessagesCodecs.invokeHostedChannelCodec)
      .typecase(65533, HostedMessagesCodecs.initHostedChannelCodec)
      .typecase(65531, HostedMessagesCodecs.lastCrossSignedStateCodec)
      .typecase(65529, HostedMessagesCodecs.stateUpdateCodec)
      .typecase(65527, HostedMessagesCodecs.stateOverrideCodec)
      .typecase(65525, HostedMessagesCodecs.hostedChannelBrandingCodec)
      .typecase(65523, HostedMessagesCodecs.refundPendingCodec)
      .typecase(65521, HostedMessagesCodecs.announcementSignatureCodec)
      .typecase(65519, HostedMessagesCodecs.resizeChannelCodec)
      .typecase(65517, HostedMessagesCodecs.queryPublicHostedChannelsCodec)
      .typecase(65515, HostedMessagesCodecs.replyPublicHostedChannelsEndCodec)
      // PHC sync (duplicate codecs, fine because we will only receive these)
      .typecase(65513, channelAnnouncementCodec) // Gossip
      .typecase(65511, channelAnnouncementCodec) // Sync
      .typecase(65509, channelUpdateCodec) // Gossip
      .typecase(65507, channelUpdateCodec) // Sync
      // HC-adjusted normal messages
      .typecase(65505, updateAddHtlcCodec)
      .typecase(65503, updateFulfillHtlcCodec)
      .typecase(65501, updateFailHtlcCodec)
      .typecase(65499, updateFailMalformedHtlcCodec)
      .typecase(65497, errorCodec)
}

object HostedMessagesCodecs {
  val invokeHostedChannelCodec = {
    (bytes32 withContext "chainHash") ::
      (varsizebinarydata withContext "refundScriptPubKey") ::
      (varsizebinarydata withContext "secret")
  }.as[InvokeHostedChannel]

  val initHostedChannelCodec = {
    (uint64 withContext "maxHtlcValueInFlightMsat") ::
      (millisatoshi withContext "htlcMinimumMsat") ::
      (uint16 withContext "maxAcceptedHtlcs") ::
      (millisatoshi withContext "channelCapacityMsat") ::
      (uint16 withContext "liabilityDeadlineBlockdays") ::
      (satoshi withContext "minimalOnchainRefundAmountSatoshis") ::
      (millisatoshi withContext "initialClientBalanceMsat")
  }.as[InitHostedChannel]

  val hostedChannelBrandingCodec = {
    (rgb withContext "rgbColor") ::
      (varsizebinarydata withContext "pngIcon") ::
      (variableSizeBytes(uint16, utf8) withContext "contactInfo")
  }.as[HostedChannelBranding]

  val lastCrossSignedStateCodec = {
    (varsizebinarydata withContext "refundScriptPubKey") ::
      (initHostedChannelCodec withContext "initHostedChannel") ::
      (uint32 withContext "blockDay") ::
      (millisatoshi withContext "localBalanceMsat") ::
      (millisatoshi withContext "remoteBalanceMsat") ::
      (uint32 withContext "localUpdates") ::
      (uint32 withContext "remoteUpdates") ::
      (listOfN(uint16, LightningMessageCodecs.updateAddHtlcCodec) withContext "incomingHtlcs") ::
      (listOfN(uint16, LightningMessageCodecs.updateAddHtlcCodec) withContext "outgoingHtlcs") ::
      (bytes64 withContext "remoteSigOfLocal") ::
      (bytes64 withContext "localSigOfRemote")
  }.as[LastCrossSignedState]

  val stateUpdateCodec = {
    (uint32 withContext "blockDay") ::
      (uint32 withContext "localUpdates") ::
      (uint32 withContext "remoteUpdates") ::
      (bytes64 withContext "localSigOfRemoteLCSS")
  }.as[StateUpdate]

  val stateOverrideCodec = {
    (uint32 withContext "blockDay") ::
      (millisatoshi withContext "localBalanceMsat") ::
      (uint32 withContext "localUpdates") ::
      (uint32 withContext "remoteUpdates") ::
      (bytes64 withContext "localSigOfRemoteLCSS")
  }.as[StateOverride]

  val refundPendingCodec =
    (uint32 withContext "startedAt").as[RefundPending]

  val announcementSignatureCodec = {
    (bytes64 withContext "nodeSignature") ::
      (bool withContext "wantsReply")
  }.as[AnnouncementSignature]

  val resizeChannelCodec = {
    (satoshi withContext "newCapacity") ::
      (bytes64 withContext "clientSig")
  }.as[ResizeChannel]

  val queryPublicHostedChannelsCodec =
    (bytes32 withContext "chainHash").as[QueryPublicHostedChannels]

  val replyPublicHostedChannelsEndCodec =
    (bytes32 withContext "chainHash").as[ReplyPublicHostedChannelsEnd]
}

object SwapCodecs {
  private val text = variableSizeBytes(uint16, utf8)

  val swapInResponseCodec = {
    ("btcAddress" | text) ::
      ("minChainDeposit" | satoshi)
  }.as[SwapInResponse]

  val swapInPaymentRequestCodec =
    ("paymentRequest" | text).as[SwapInPaymentRequest]

  val swapInPaymentDeniedCodec = {
    ("paymentRequest" | text) ::
      ("reason" | text)
  }.as[SwapInPaymentDenied]

  val pendingDepositCodec = {
    ("btcAddress" | text) ::
      ("txid" | bytes32) ::
      ("amount" | satoshi) ::
      ("stamp" | uint32)
  }.as[PendingDeposit]

  val swapInStateCodec = {
    ("balance" | millisatoshi) ::
      ("inFlight" | millisatoshi) ::
      ("pendingChainDeposits" | listOfN(uint16, pendingDepositCodec))
  }.as[SwapInState]

  // SwapOut

  val blockTargetAndFeeCodec = {
    ("blockTarget" | uint16) ::
      ("fee" | satoshi)
  }.as[BlockTargetAndFee]

  val keyedBlockTargetAndFeeCodec = {
    ("feerates" | listOfN(uint16, blockTargetAndFeeCodec)) ::
      ("feerateKey" | bytes32)
  }.as[KeyedBlockTargetAndFee]

  val swapOutFeeratesCodec = {
    ("feerates" | keyedBlockTargetAndFeeCodec) ::
      ("providerCanHandle" | satoshi) ::
      ("minWithdrawable" | satoshi)
  }.as[SwapOutFeerates]

  val swapOutTransactionRequestCodec = {
    ("amount" | satoshi) ::
      ("btcAddress" | text) ::
      ("blockTarget" | uint16) ::
      ("feerateKey" | bytes32)
  }.as[SwapOutTransactionRequest]

  val swapOutTransactionResponseCodec = {
    ("paymentRequest" | text) ::
      ("amount" | satoshi) ::
      ("fee" | satoshi)
  }.as[SwapOutTransactionResponse]

  val swapOutTransactionDeniedCodec = {
    ("btcAddress" | text) ::
      ("reason" | text)
  }.as[SwapOutTransactionDenied]
}