package com.btcontract.wallet.ln.wire

import scodec.codecs._
import fr.acinq.eclair.wire.CommonCodecs._
import fr.acinq.eclair.wire.HostedMessagesCodecs._
import fr.acinq.eclair.wire.LightningMessageCodecs._
import fr.acinq.eclair.wire.{LastCrossSignedState, LightningMessage}
import fr.acinq.bitcoin.ByteVector32
import scodec.bits.ByteVector
import scodec.Codec


case class HostedState(channelId: ByteVector32,
                       nextLocalUpdates: Vector[LightningMessage],
                       nextRemoteUpdates: Vector[LightningMessage],
                       lastCrossSignedState: LastCrossSignedState)

object ExtCodecs {
  val hostedStateCodec: Codec[HostedState] = {
    (bytes32 withContext "channelId") ::
      (vectorOfN(uint16, lightningMessageCodec) withContext "nextLocalUpdates") ::
      (vectorOfN(uint16, lightningMessageCodec) withContext "nextRemoteUpdates") ::
      (lastCrossSignedStateCodec withContext "lastCrossSignedState")
  }.as[HostedState]
}
