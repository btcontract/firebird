package com.btcontract.wallet.ln

import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import com.btcontract.wallet.ln.crypto.Tools._
import com.btcontract.wallet.ln.ChanErrorCodes._
import com.btcontract.wallet.ln.CommitmentSpec.{LNDirectionalMessage, PreimageAndAdd}
import com.btcontract.wallet.ln.crypto.{CMDAddImpossible, LightningException}
import com.btcontract.wallet.ln.wire.{HostedState, UpdateAddTlv}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64}
import fr.acinq.eclair.channel.CMD_ADD_HTLC
import scodec.bits.{BitVector, ByteVector}


case class Htlc(incoming: Boolean, add: UpdateAddHtlc) {
  require(incoming || add.internalId.isDefined, "Outgoing add must contain an internal id")
}

trait RemoteFailed {
  val ourAdd: UpdateAddHtlc
  val partId: ByteVector = ourAdd.internalId.get.data
}

case class FailAndAdd(theirFail: UpdateFailHtlc, ourAdd: UpdateAddHtlc) extends RemoteFailed
case class MalformAndAdd(theirMalform: UpdateFailMalformedHtlc, ourAdd: UpdateAddHtlc) extends RemoteFailed

case class CommitmentSpec(feeratePerKw: Long, toLocal: MilliSatoshi, toRemote: MilliSatoshi, htlcs: Set[Htlc] = Set.empty,
                          remoteFailed: Set[FailAndAdd] = Set.empty, remoteMalformed: Set[MalformAndAdd] = Set.empty,
                          localFulfilled: Set[ByteVector32] = Set.empty) {

  def findHtlcById(id: Long, isIncoming: Boolean): Option[Htlc] = htlcs.find(htlc => htlc.add.id == id && htlc.incoming == isIncoming)
  lazy val outgoingAddsSum: MilliSatoshi = (0L.msat /: outgoingAdds) { case accumulator \ outAdd => accumulator + outAdd.amountMsat }
  lazy val incomingAddsSum: MilliSatoshi = (0L.msat /: incomingAdds) { case accumulator \ inAdd => accumulator + inAdd.amountMsat }
  lazy val outgoingAdds: Set[UpdateAddHtlc] = htlcs collect { case Htlc(false, add) => add }
  lazy val incomingAdds: Set[UpdateAddHtlc] = htlcs collect { case Htlc(true, add) => add }
}

object CommitmentSpec {
  type LNDirectionalMessage = (LightningMessage, Boolean)
  type PreimageAndAdd = (ByteVector32, UpdateAddHtlc)

  def fulfill(cs: CommitmentSpec, isIncoming: Boolean, m: UpdateFulfillHtlc): CommitmentSpec = cs.findHtlcById(m.id, isIncoming) match {
    case Some(their) if their.incoming => cs.copy(toLocal = cs.toLocal + their.add.amountMsat, localFulfilled = cs.localFulfilled + m.paymentHash, htlcs = cs.htlcs - their)
    case Some(htlc) => cs.copy(toRemote = cs.toRemote + htlc.add.amountMsat, htlcs = cs.htlcs - htlc)
    case None => cs
  }

  def fail(cs: CommitmentSpec, isIncoming: Boolean, m: UpdateFailHtlc): CommitmentSpec = cs.findHtlcById(m.id, isIncoming) match {
    case Some(theirAddHtlc) if theirAddHtlc.incoming => cs.copy(toRemote = cs.toRemote + theirAddHtlc.add.amountMsat, htlcs = cs.htlcs - theirAddHtlc)
    case Some(htlc) => cs.copy(remoteFailed = cs.remoteFailed + FailAndAdd(m, htlc.add), toLocal = cs.toLocal + htlc.add.amountMsat, htlcs = cs.htlcs - htlc)
    case None => cs
  }

  def failMalformed(cs: CommitmentSpec, isIncoming: Boolean, m: UpdateFailMalformedHtlc): CommitmentSpec = cs.findHtlcById(m.id, isIncoming) match {
    case Some(theirAddHtlc) if theirAddHtlc.incoming => cs.copy(toRemote = cs.toRemote + theirAddHtlc.add.amountMsat, htlcs = cs.htlcs - theirAddHtlc)
    case Some(htlc) => cs.copy(remoteMalformed = cs.remoteMalformed + MalformAndAdd(m, htlc.add), toLocal = cs.toLocal + htlc.add.amountMsat, htlcs = cs.htlcs - htlc)
    case None => cs
  }

  def plusOutgoing(m: UpdateAddHtlc, cs: CommitmentSpec): CommitmentSpec = cs.copy(htlcs = cs.htlcs + Htlc(incoming = false, add = m), toLocal = cs.toLocal - m.amountMsat)
  def plusIncoming(m: UpdateAddHtlc, cs: CommitmentSpec): CommitmentSpec = cs.copy(htlcs = cs.htlcs + Htlc(incoming = true, add = m), toRemote = cs.toRemote - m.amountMsat)
  def reduce(local: Vector[LightningMessage], remote: Vector[LightningMessage], spec1: CommitmentSpec): CommitmentSpec = {

    val spec2 = spec1.copy(remoteFailed = Set.empty, remoteMalformed = Set.empty, localFulfilled = Set.empty)
    val spec3 = (spec2 /: local) { case (spec, add: UpdateAddHtlc) => plusOutgoing(add, spec) case spec \ _ => spec }
    val spec4 = (spec3 /: remote) { case (spec, add: UpdateAddHtlc) => plusIncoming(add, spec) case spec \ _ => spec }

    val spec5 = (spec4 /: local) {
      case (spec, message: UpdateFulfillHtlc) => fulfill(spec, isIncoming = true, message)
      case (spec, message: UpdateFailMalformedHtlc) => failMalformed(spec, isIncoming = true, message)
      case (spec, message: UpdateFailHtlc) => fail(spec, isIncoming = true, message)
      case spec \ _ => spec
    }

    (spec5 /: remote) {
      case (spec, message: UpdateFulfillHtlc) => fulfill(spec, isIncoming = false, message)
      case (spec, message: UpdateFailMalformedHtlc) => failMalformed(spec, isIncoming = false, message)
      case (spec, message: UpdateFailHtlc) => fail(spec, isIncoming = false, message)
      case spec \ _ => spec
    }
  }
}

object HostedFeatures {
  final val ANNOUNCE_CHANNEL = 0
  final val LENGTH_BITS: Int = 4 * 8
  def setBit(bit: Int): BitVector = BitVector.low(LENGTH_BITS).set(bit).reverse
  def isSet(bits: BitVector, bit: Int): Boolean = LENGTH_BITS == bits.size && bits.reverse.get(bit)
  final val IS_BASIC: BitVector = BitVector.fromValidBin("00000000000000000000000000000000")
  final val IS_ANNOUNCE_CHANNEL: BitVector = IS_BASIC | setBit(ANNOUNCE_CHANNEL)
}

sealed trait ChannelData { val announce: NodeAnnouncementExt }
case class WaitRemoteHostedStateUpdate(announce: NodeAnnouncementExt, hc: HostedCommits) extends ChannelData
case class WaitRemoteHostedReply(announce: NodeAnnouncementExt, refundScriptPubKey: ByteVector, secret: ByteVector) extends ChannelData
case class HostedCommits(announce: NodeAnnouncementExt, lastCrossSignedState: LastCrossSignedState, futureUpdates: Vector[LNDirectionalMessage],
                         localSpec: CommitmentSpec, updateOpt: Option[ChannelUpdate], brandingOpt: Option[HostedChannelBranding], localError: Option[Error],
                         remoteError: Option[Error], startedAt: Long = System.currentTimeMillis) extends ChannelData {

  lazy val Tuple4(nextLocalUpdates, nextRemoteUpdates, nextTotalLocal, nextTotalRemote) =
    (Tuple4(Vector.empty[LightningMessage], Vector.empty[LightningMessage], lastCrossSignedState.localUpdates, lastCrossSignedState.remoteUpdates) /: futureUpdates) {
      case (localMessages, remoteMessages, totalLocalNumber, totalRemoteNumber) \ (msg \ true) => (localMessages :+ msg, remoteMessages, totalLocalNumber + 1, totalRemoteNumber)
      case (localMessages, remoteMessages, totalLocalNumber, totalRemoteNumber) \ (msg \ false) => (localMessages, remoteMessages :+ msg, totalLocalNumber, totalRemoteNumber + 1)
    }

  lazy val revealedPreimages: Seq[PreimageAndAdd] = for {
    UpdateFulfillHtlc(_, id, paymentPreimage) <- nextLocalUpdates
    Htlc(true, add) <- localSpec.findHtlcById(id, isIncoming = true)
  } yield paymentPreimage -> add

  lazy val nextLocalSpec: CommitmentSpec = CommitmentSpec.reduce(nextLocalUpdates, nextRemoteUpdates, localSpec)
  lazy val invokeMsg = InvokeHostedChannel(LNParams.chainHash, lastCrossSignedState.refundScriptPubKey, ByteVector.empty, HostedFeatures.IS_BASIC)
  lazy val pendingIncoming: Set[UpdateAddHtlc] = localSpec.incomingAdds intersect nextLocalSpec.incomingAdds // Cross-signed but not yet resolved by us
  lazy val pendingOutgoing: Set[UpdateAddHtlc] = localSpec.outgoingAdds union nextLocalSpec.outgoingAdds // Cross-signed and new payments offered by us

  def nextLocalUnsignedLCSS(blockDay: Long): LastCrossSignedState = {
    val incomingHtlcs \ outgoingHtlcs = nextLocalSpec.htlcs.toList.partition(_.incoming)
    LastCrossSignedState(lastCrossSignedState.refundScriptPubKey, lastCrossSignedState.initHostedChannel,
      blockDay, nextLocalSpec.toLocal, nextLocalSpec.toRemote, nextTotalLocal, nextTotalRemote,
      incomingHtlcs = incomingHtlcs.map(_.add), outgoingHtlcs = outgoingHtlcs.map(_.add),
      localSigOfRemote = ByteVector64.Zeroes, remoteSigOfLocal = ByteVector64.Zeroes)
  }

  def findState(remoteLCSS: LastCrossSignedState): Seq[HostedCommits] = for {
    // Find a hypothetic future state which matches their current update numbers

    previousIndex <- futureUpdates.indices drop 1
    previousHC = copy(futureUpdates = futureUpdates take previousIndex)
    if previousHC.nextLocalUnsignedLCSS(remoteLCSS.blockDay).isEven(remoteLCSS)
  } yield previousHC

  def getError: Option[Error] = localError.orElse(remoteError)
  def addProposal(futureUpdateMessage: LNDirectionalMessage): HostedCommits = copy(futureUpdates = futureUpdates :+ futureUpdateMessage)
  def newLocalBalance(so: StateOverride): MilliSatoshi = lastCrossSignedState.initHostedChannel.channelCapacityMsat - so.localBalanceMsat
  def hostedState = HostedState(announce.nodeSpecificHostedChanId, nextLocalUpdates, nextRemoteUpdates, lastCrossSignedState)

  def sendAdd(cmd: CMD_ADD_HTLC): (HostedCommits, UpdateAddHtlc) = {
    // All local UpdateAddHtlc MUST have an internalId for MPP to work correctly!
    val internalId: TlvStream[Tlv] = TlvStream(UpdateAddTlv.InternalId(cmd.internalId) :: Nil)
    // Let's add this change and see if the new state violates any of constraints including those imposed by them on us
    val add = UpdateAddHtlc(announce.nodeSpecificHostedChanId, nextTotalLocal + 1, cmd.firstAmount, cmd.paymentHash, cmd.cltvExpiry, cmd.packetAndSecrets.packet, internalId)
    val commits1: HostedCommits = addProposal(add.asLocal)

    if (commits1.nextLocalSpec.toLocal < 0L.msat) throw CMDAddImpossible(cmd, ERR_NOT_ENOUGH_BALANCE)
    if (cmd.payload.amount < lastCrossSignedState.initHostedChannel.htlcMinimumMsat) throw CMDAddImpossible(cmd, ERR_AMOUNT_TOO_SMALL)
    if (UInt64(commits1.nextLocalSpec.outgoingAddsSum.toLong) > lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat) throw CMDAddImpossible(cmd, ERR_TOO_MUCH_IN_FLIGHT)
    if (commits1.nextLocalSpec.outgoingAdds.size > lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs) throw CMDAddImpossible(cmd, ERR_TOO_MANY_HTLC)
    commits1 -> add
  }

  def receiveAdd(add: UpdateAddHtlc): ChannelData = {
    val commits1: HostedCommits = addProposal(add.asRemote)
    if (add.id != nextTotalRemote + 1) throw new LightningException
    if (commits1.nextLocalSpec.toRemote < 0L.msat) throw new LightningException
    if (UInt64(commits1.nextLocalSpec.incomingAddsSum.toLong) > lastCrossSignedState.initHostedChannel.maxHtlcValueInFlightMsat) throw new LightningException
    if (commits1.nextLocalSpec.incomingAdds.size > lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs) throw new LightningException
    commits1
  }
}