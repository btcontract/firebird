package com.btcontract.wallet.ln.wire

import scodec.codecs._
import fr.acinq.eclair.wire.CommonCodecs._
import fr.acinq.eclair.wire.HostedMessagesCodecs._
import fr.acinq.eclair.wire.LightningMessageCodecs._
import fr.acinq.eclair.wire.{LastCrossSignedState, LightningMessage}
import fr.acinq.bitcoin.Crypto.PublicKey
import scodec.Codec


case class HostedState(nodeId1: PublicKey,
                       nodeId2: PublicKey,
                       nextLocalUpdates: List[LightningMessage],
                       nextRemoteUpdates: List[LightningMessage],
                       lastCrossSignedState: LastCrossSignedState)

object ExtCodecs {
  val hostedStateCodec: Codec[HostedState] = {
    (publicKey withContext "nodeId1") ::
      (publicKey withContext "nodeId2") ::
      (listOfN(uint16, lightningMessageCodec) withContext "nextLocalUpdates") ::
      (listOfN(uint16, lightningMessageCodec) withContext "nextRemoteUpdates") ::
      (lastCrossSignedStateCodec withContext "lastCrossSignedState")
  }.as[HostedState]
}
