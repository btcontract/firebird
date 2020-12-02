package com.btcontract.wallet.ln

import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.channel._
import com.softwaremill.quicklens._
import com.btcontract.wallet.ln.crypto._
import com.btcontract.wallet.ln.crypto.Tools._
import com.btcontract.wallet.ln.HostedChannel._
import com.btcontract.wallet.ln.ChanErrorCodes._
import com.btcontract.wallet.ln.ChannelListener._
import fr.acinq.eclair.router.Announcements
import java.util.concurrent.Executors
import fr.acinq.bitcoin.ByteVector64
import scala.concurrent.Future
import scodec.bits.ByteVector
import scala.util.Failure


object HostedChannel {
  val WAIT_FOR_INIT = "WAIT-FOR-INIT"
  val WAIT_FOR_ACCEPT = "WAIT-FOR-ACCEPT"
  // All states below are persisted
  val SUSPENDED = "SUSPENDED"
  val SLEEPING = "SLEEPING"
  val OPEN = "OPEN"

  // Single stacking thread for all channels, must be used when asking channels for pending payments to avoid race conditions
  implicit val channelContext: scala.concurrent.ExecutionContextExecutor = scala.concurrent.ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def isOperational(chan: HostedChannel): Boolean = chan.data match { case hc: HostedCommits => hc.getError.isEmpty case _ => false }
  def isOperationalAndOpen(chan: HostedChannel): Boolean = isOperational(chan) && OPEN == chan.state
}

case class ChanAndCommits(chan: HostedChannel, commits: HostedCommits)
case class CommitsAndMax(commits: Vector[ChanAndCommits], maxReceivable: MilliSatoshi)

abstract class HostedChannel extends StateMachine[ChannelData] { me =>
  def isBlockDayOutOfSync(blockDay: Long): Boolean = math.abs(blockDay - currentBlockDay) > 1
  def process(cs: Any *): Unit = Future(cs foreach doProcess) onComplete { case Failure(why) => events.onException(me -> why) case _ => }
  def chanAndCommitsOpt: Option[ChanAndCommits] = data match { case hc: HostedCommits => ChanAndCommits(me, hc).toSome case _ => None }

  def currentBlockDay: Long
  def SEND(msg: LightningMessage *): Unit
  def STORE(data: HostedCommits): HostedCommits

  def BECOME(data1: ChannelData, state1: String): Unit = {
    // Transition must be defined before vars are updated
    val trans = Tuple4(me, data1, state, state1)
    super.become(data1, state1)
    events.onBecome(trans)
  }

  def STORESENDBECOME(data1: HostedCommits, state1: String, lnMessage: LightningMessage *): Unit = {
    // In certain situations we need this specific sequence, it's a helper method to make it oneliner
    // store goes first to ensure we retain an updated data before revealing it if anything goes wrong

    STORE(data1)
    SEND(lnMessage:_*)
    BECOME(data1, state1)
  }

  private var isChainHeightKnown: Boolean = false
  private var isSocketConnected: Boolean = false
  var listeners = Set.empty[ChannelListener]

  val events: ChannelListener = new ChannelListener {
    override def onProcessSuccess: PartialFunction[Incoming, Unit] = { case ps => for (lst <- listeners if lst.onProcessSuccess isDefinedAt ps) lst onProcessSuccess ps }
    override def onException: PartialFunction[Malfunction, Unit] = { case failure => for (lst <- listeners if lst.onException isDefinedAt failure) lst onException failure }
    override def onBecome: PartialFunction[Transition, Unit] = { case transition => for (lst <- listeners if lst.onBecome isDefinedAt transition) lst onBecome transition }
    override def fulfillReceived(fulfill: UpdateFulfillHtlc): Unit = for (lst <- listeners) lst fulfillReceived fulfill
    override def stateUpdated(hc: HostedCommits): Unit = for (lst <- listeners) lst stateUpdated hc
  }

  def doProcess(change: Any): Unit = {
    Tuple3(data, change, state) match {
      case (wait @ WaitRemoteHostedReply(_, refundScriptPubKey, secret), CMD_SOCKET_ONLINE, WAIT_FOR_INIT) =>
        if (isChainHeightKnown) me SEND InvokeHostedChannel(LNParams.chainHash, refundScriptPubKey, secret)
        if (isChainHeightKnown) BECOME(wait, WAIT_FOR_ACCEPT)
        isSocketConnected = true


      case (wait @ WaitRemoteHostedReply(_, refundScriptPubKey, secret), CMD_CHAIN_TIP_KNOWN, WAIT_FOR_INIT) =>
        if (isSocketConnected) me SEND InvokeHostedChannel(LNParams.chainHash, refundScriptPubKey, secret)
        if (isSocketConnected) BECOME(wait, WAIT_FOR_ACCEPT)
        isChainHeightKnown = true


      case (WaitRemoteHostedReply(ext, refundScriptPubKey, _), init: InitHostedChannel, WAIT_FOR_ACCEPT) =>
        if (init.liabilityDeadlineBlockdays < LNParams.minHostedLiabilityBlockdays) throw new LightningException("Their liability deadline is too low")
        if (init.initialClientBalanceMsat > init.channelCapacityMsat) throw new LightningException("Their init balance for us is larger than capacity")
        if (init.minimalOnchainRefundAmountSatoshis > LNParams.minHostedOnChainRefund) throw new LightningException("Their min refund is too high")
        if (init.channelCapacityMsat < LNParams.minHostedOnChainRefund) throw new LightningException("Their proposed channel capacity is too low")
        if (UInt64(100000000L) > init.maxHtlcValueInFlightMsat) throw new LightningException("Their max value in-flight is too low")
        if (init.htlcMinimumMsat > 546000L.msat) throw new LightningException("Their minimal payment size is too high")
        if (init.maxAcceptedHtlcs < 1) throw new LightningException("They can accept too few payments")

        val localHalfSignedHC =
          restoreCommits(LastCrossSignedState(refundScriptPubKey, init, currentBlockDay, init.initialClientBalanceMsat,
            init.channelCapacityMsat - init.initialClientBalanceMsat, localUpdates = 0L, remoteUpdates = 0L, incomingHtlcs = Nil, outgoingHtlcs = Nil,
            localSigOfRemote = ByteVector64.Zeroes, remoteSigOfLocal = ByteVector64.Zeroes).withLocalSigOfRemote(data.announce.nodeSpecificPrivKey), ext)

        SEND(localHalfSignedHC.lastCrossSignedState.stateUpdate)
        BECOME(WaitRemoteHostedStateUpdate(ext, localHalfSignedHC), state)


      case (WaitRemoteHostedStateUpdate(_, localHalfSignedHC), remoteSU: StateUpdate, WAIT_FOR_ACCEPT) =>
        val localCompleteLCSS = localHalfSignedHC.lastCrossSignedState.copy(remoteSigOfLocal = remoteSU.localSigOfRemoteLCSS)
        val isRightRemoteUpdateNumber = localHalfSignedHC.lastCrossSignedState.remoteUpdates == remoteSU.localUpdates
        val isRightLocalUpdateNumber = localHalfSignedHC.lastCrossSignedState.localUpdates == remoteSU.remoteUpdates
        val isRemoteSigOk = localCompleteLCSS.verifyRemoteSig(localHalfSignedHC.announce.na.nodeId)

        if (me isBlockDayOutOfSync remoteSU.blockDay) throw new LightningException("Their blockday is wrong")
        if (!isRightRemoteUpdateNumber) throw new LightningException("Their remote update number is wrong")
        if (!isRightLocalUpdateNumber) throw new LightningException("Their local update number is wrong")
        if (!isRemoteSigOk) throw new LightningException("Their signature is wrong")
        val hc1 = localHalfSignedHC.copy(lastCrossSignedState = localCompleteLCSS)
        BECOME(STORE(hc1), OPEN)


      case (wait: WaitRemoteHostedReply, remoteLCSS: LastCrossSignedState, WAIT_FOR_ACCEPT) =>
        // We have expected InitHostedChannel but got LastCrossSignedState so this channel exists already
        // make sure our signature match and if so then become OPEN using host supplied state data
        val isLocalSigOk = remoteLCSS.verifyRemoteSig(data.announce.nodeSpecificPubKey)
        val isRemoteSigOk = remoteLCSS.reverse.verifyRemoteSig(wait.announce.na.nodeId)
        val hc = restoreCommits(remoteLCSS.reverse, wait.announce)

        if (!isRemoteSigOk) localSuspend(hc, ERR_HOSTED_WRONG_REMOTE_SIG)
        else if (!isLocalSigOk) localSuspend(hc, ERR_HOSTED_WRONG_LOCAL_SIG)
        else STORESENDBECOME(hc, OPEN, hc.lastCrossSignedState)

      // CHANNEL IS ESTABLISHED

      case (hc: HostedCommits, addHtlc: UpdateAddHtlc, OPEN) =>
        // They have sent us an incoming payment, do not store yet
        BECOME(hc.receiveAdd(addHtlc), OPEN)


      // Process their fulfill in any state to make sure we always get a preimage
      // fails/fulfills when SUSPENDED are ignored because they may fulfill afterwards
      case (hc: HostedCommits, fulfill: UpdateFulfillHtlc, SLEEPING | OPEN | SUSPENDED) =>
        val isPresent = hc.nextLocalSpec.outgoingAdds.exists(add => add.id == fulfill.id && add.paymentHash == fulfill.paymentHash)
        // Technically peer may send a preimage any time, even if new LCSS has not been reached yet: always resolve anyway
        if (isPresent) BECOME(hc.addRemoteProposal(fulfill), state)
        events.fulfillReceived(fulfill)


      case (hc: HostedCommits, fail: UpdateFailHtlc, OPEN) =>
        // For both types of Fail we only consider them when channel is OPEN and only accept them if our outging payment has not been resolved already
        val isNotResolvedYet = hc.localSpec.findHtlcById(fail.id, isIncoming = false).isDefined && hc.nextLocalSpec.findHtlcById(fail.id, isIncoming = false).isDefined
        if (isNotResolvedYet) BECOME(hc.addRemoteProposal(fail), OPEN) else throw new LightningException("Peer failed an HTLC which is either not cross-signed or does not exist")


      case (hc: HostedCommits, fail: UpdateFailMalformedHtlc, OPEN) =>
        if (fail.failureCode.&(FailureMessageCodecs.BADONION) == 0) throw new LightningException("Wrong failure code for malformed onion")
        val isNotResolvedYet = hc.localSpec.findHtlcById(fail.id, isIncoming = false).isDefined && hc.nextLocalSpec.findHtlcById(fail.id, isIncoming = false).isDefined
        if (isNotResolvedYet) BECOME(hc.addRemoteProposal(fail), OPEN) else throw new LightningException("Peer malformed-failed an HTLC which is either not cross-signed or does not exist")


      case (hc: HostedCommits, cmd: CMD_ADD_HTLC, currentState) =>
        if (OPEN != currentState) throw CMDAddImpossible(cmd, ERR_NOT_OPEN)
        val hostedCommits1 \ updateAddHtlc = hc sendAdd cmd
        BECOME(hostedCommits1, state)
        SEND(updateAddHtlc)


      case (hc: HostedCommits, CMD_PROCEED, OPEN) if hc.nextLocalUpdates.nonEmpty =>
        val nextHC = hc.nextLocalUnsignedLCSS(currentBlockDay).withLocalSigOfRemote(data.announce.nodeSpecificPrivKey)
        SEND(nextHC.stateUpdate)


      case (hc: HostedCommits, remoteSU: StateUpdate, OPEN)
        if hc.lastCrossSignedState.remoteSigOfLocal != remoteSU.localSigOfRemoteLCSS =>
        val lcss1 = hc.nextLocalUnsignedLCSS(remoteSU.blockDay).copy(remoteSigOfLocal = remoteSU.localSigOfRemoteLCSS).withLocalSigOfRemote(data.announce.nodeSpecificPrivKey)
        val hc1 = hc.copy(lastCrossSignedState = lcss1, localSpec = hc.nextLocalSpec, nextLocalUpdates = Vector.empty, nextRemoteUpdates = Vector.empty)
        val isRemoteSigOk = lcss1.verifyRemoteSig(hc.announce.na.nodeId)

        if (me isBlockDayOutOfSync remoteSU.blockDay) localSuspend(hc, ERR_HOSTED_WRONG_BLOCKDAY)
        else if (remoteSU.remoteUpdates < lcss1.localUpdates) STORESENDBECOME(hc, OPEN, lcss1.stateUpdate)
        else if (!isRemoteSigOk) localSuspend(hc, ERR_HOSTED_WRONG_REMOTE_SIG)
        else {
          // State was updated, resolved HTLC may be present
          STORESENDBECOME(hc1, OPEN, lcss1.stateUpdate)
          events.stateUpdated(hc1)
        }


      // Normal channel fetches on-chain when CLOSED, hosted sends a preimage and signals on UI when SUSPENDED
      case (hc: HostedCommits, CMD_FULFILL_HTLC(preimage, add), OPEN | SUSPENDED) if hc.pendingIncoming.contains(add) =>
        val updateFulfill = UpdateFulfillHtlc(hc.announce.nodeSpecificHostedChanId, add.id, preimage)
        STORESENDBECOME(hc.addLocalProposal(updateFulfill), state, updateFulfill)


      // This will make pending incoming HTLC in a SUSPENDED channel invisible to `pendingIncoming` method which is desired
      case (hc: HostedCommits, CMD_FAIL_MALFORMED_HTLC(onionHash, code, add), OPEN | SUSPENDED) if hc.pendingIncoming.contains(add) =>
        val updateFailMalformed = UpdateFailMalformedHtlc(hc.announce.nodeSpecificHostedChanId, add.id, onionHash, code)
        STORESENDBECOME(hc.addLocalProposal(updateFailMalformed), state, updateFailMalformed)


      case (hc: HostedCommits, CMD_FAIL_HTLC(reason, add), OPEN | SUSPENDED) if hc.pendingIncoming.contains(add) =>
        val updateFail = UpdateFailHtlc(hc.announce.nodeSpecificHostedChanId, add.id, reason)
        STORESENDBECOME(hc.addLocalProposal(updateFail), state, updateFail)


      case (hc: HostedCommits, CMD_SOCKET_ONLINE, SLEEPING | SUSPENDED) =>
        if (isChainHeightKnown) SEND(hc.getError getOrElse hc.invokeMsg)
        isSocketConnected = true


      case (hc: HostedCommits, CMD_CHAIN_TIP_KNOWN, SLEEPING | SUSPENDED) =>
        if (isSocketConnected) SEND(hc.getError getOrElse hc.invokeMsg)
        isChainHeightKnown = true


      case (hc: HostedCommits, CMD_SOCKET_OFFLINE, OPEN) =>
        isSocketConnected = false
        BECOME(hc, SLEEPING)


      case (hc: HostedCommits, CMD_CHAIN_TIP_LOST, OPEN) =>
        isChainHeightKnown = false
        BECOME(hc, SLEEPING)


      case (hc: HostedCommits, _: InitHostedChannel, SLEEPING) =>
        // Peer has lost this channel, they may re-sync from our LCSS
        SEND(hc.lastCrossSignedState)


      // Technically they can send a remote LCSS before us explicitly asking for it first
      // this may be a problem if chain height is not yet known and we have incoming HTLCs
      case (hc: HostedCommits, remoteLCSS: LastCrossSignedState, SLEEPING) if isChainHeightKnown =>
        val weAreEven = hc.lastCrossSignedState.remoteUpdates == remoteLCSS.localUpdates && hc.lastCrossSignedState.localUpdates == remoteLCSS.remoteUpdates
        val weAreAhead = hc.lastCrossSignedState.remoteUpdates > remoteLCSS.localUpdates || hc.lastCrossSignedState.localUpdates > remoteLCSS.remoteUpdates
        val isLocalSigOk = remoteLCSS.verifyRemoteSig(data.announce.nodeSpecificPubKey)
        val isRemoteSigOk = remoteLCSS.reverse.verifyRemoteSig(hc.announce.na.nodeId)

        if (!isRemoteSigOk) localSuspend(hc, ERR_HOSTED_WRONG_REMOTE_SIG)
        else if (!isLocalSigOk) localSuspend(hc, ERR_HOSTED_WRONG_LOCAL_SIG)
        else if (weAreAhead || weAreEven) {
          val hc1 = hc.copy(nextRemoteUpdates = Vector.empty)
          SEND(hc.lastCrossSignedState +: hc.nextLocalUpdates:_*)
          BECOME(hc1, OPEN)
        } else {
          val localUpdatesAcked = remoteLCSS.remoteUpdates - hc.lastCrossSignedState.localUpdates
          val remoteUpdatesAcked = remoteLCSS.localUpdates - hc.lastCrossSignedState.remoteUpdates

          val remoteUpdatesAccounted = hc.nextRemoteUpdates take remoteUpdatesAcked.toInt
          val localUpdatesAccounted = hc.nextLocalUpdates take localUpdatesAcked.toInt
          val localUpdatesLeftover = hc.nextLocalUpdates drop localUpdatesAcked.toInt

          val hc1 = hc.copy(nextLocalUpdates = localUpdatesAccounted, nextRemoteUpdates = remoteUpdatesAccounted)
          val syncedLCSS = hc1.nextLocalUnsignedLCSS(remoteLCSS.blockDay).copy(localSigOfRemote = remoteLCSS.remoteSigOfLocal, remoteSigOfLocal = remoteLCSS.localSigOfRemote)
          val syncedCommits = hc1.copy(lastCrossSignedState = syncedLCSS, localSpec = hc1.nextLocalSpec, nextLocalUpdates = localUpdatesLeftover, nextRemoteUpdates = Vector.empty)
          if (syncedLCSS.reverse != remoteLCSS) STORESENDBECOME(restoreCommits(remoteLCSS.reverse, hc.announce), OPEN, remoteLCSS.reverse) // We are too far behind, restore from their data
          else STORESENDBECOME(syncedCommits, OPEN, syncedLCSS +: localUpdatesLeftover:_*) // We are behind but our own future cross-signed state can be restored
        }


      case (hc: HostedCommits, ann: NodeAnnouncement, SLEEPING | SUSPENDED)
        if hc.announce.na.nodeId == ann.nodeId && Announcements.checkSig(ann) =>
        val data1 = hc.modify(_.announce) setTo NodeAnnouncementExt(ann)
        data = STORE(data1)


      case (hc: HostedCommits, upd: ChannelUpdate, OPEN | SLEEPING) if hc.updateOpt.forall(_.timestamp < upd.timestamp) =>
        val shortIdMatches = hostedShortChanId(hc.announce.nodeSpecificPubKey.value, hc.announce.na.nodeId.value) == upd.shortChannelId
        if (shortIdMatches) data = STORE(hc.modify(_.updateOpt) setTo upd.toSome)


      case (hc: HostedCommits, remoteError: Error, WAIT_FOR_ACCEPT | OPEN | SLEEPING) if hc.remoteError.isEmpty =>
        BECOME(STORE(hc.modify(_.remoteError) setTo remoteError.toSome), SUSPENDED)


      case (hc: HostedCommits, CMD_HOSTED_STATE_OVERRIDE(remoteSO), SUSPENDED) if isSocketConnected =>
        // User has manually accepted a proposed remote override, now make sure all provided parameters check out
        val localBalance = hc.lastCrossSignedState.initHostedChannel.channelCapacityMsat - remoteSO.localBalanceMsat

        val completeLocalLCSS =
          hc.lastCrossSignedState.copy(incomingHtlcs = Nil, outgoingHtlcs = Nil,
            localBalanceMsat = localBalance, remoteBalanceMsat = remoteSO.localBalanceMsat,
            localUpdates = remoteSO.remoteUpdates, remoteUpdates = remoteSO.localUpdates,
            blockDay = remoteSO.blockDay, remoteSigOfLocal = remoteSO.localSigOfRemoteLCSS)
            .withLocalSigOfRemote(data.announce.nodeSpecificPrivKey)

        if (localBalance < 0L.msat) throw new LightningException("Provided updated local balance is larger than capacity")
        if (remoteSO.localUpdates < hc.lastCrossSignedState.remoteUpdates) throw new LightningException("Provided local update number from remote host is wrong")
        if (remoteSO.remoteUpdates < hc.lastCrossSignedState.localUpdates) throw new LightningException("Provided remote update number from remote host is wrong")
        if (remoteSO.blockDay < hc.lastCrossSignedState.blockDay) throw new LightningException("Provided override blockday from remote host is not acceptable")
        require(completeLocalLCSS.verifyRemoteSig(hc.announce.na.nodeId), "Provided override signature from remote host is wrong")
        STORESENDBECOME(restoreCommits(completeLocalLCSS, hc.announce), OPEN, completeLocalLCSS.stateUpdate)


      case (null, wait: WaitRemoteHostedReply, null) => super.become(wait, WAIT_FOR_INIT)
      case (null, hc: HostedCommits, null) if hc.getError.isDefined => super.become(hc, SUSPENDED)
      case (null, hc: HostedCommits, null) => super.become(hc, SLEEPING)
      case _ =>
    }

    // Change has been processed without failures
    events onProcessSuccess Tuple3(me, data, change)
  }

  def restoreCommits(localLCSS: LastCrossSignedState, ext: NodeAnnouncementExt): HostedCommits = {
    val inHtlcs = for (updateAddHtlc <- localLCSS.incomingHtlcs) yield Htlc(incoming = true, updateAddHtlc)
    val outHtlcs = for (updateAddHtlc <- localLCSS.outgoingHtlcs) yield Htlc(incoming = false, updateAddHtlc)
    val localSpec = CommitmentSpec(feeratePerKw = 0L, localLCSS.localBalanceMsat, localLCSS.remoteBalanceMsat, htlcs = (inHtlcs ++ outHtlcs).toSet)
    HostedCommits(ext, localLCSS, nextLocalUpdates = Vector.empty, nextRemoteUpdates = Vector.empty, localSpec, updateOpt = None, localError = None, remoteError = None)
  }

  def localSuspend(hc: HostedCommits, errCode: String): Unit = {
    val localError = Error(hc.announce.nodeSpecificHostedChanId, ByteVector fromValidHex errCode)
    val hc1 = if (hc.localError.isDefined) hc else hc.copy(localError = localError.toSome)
    STORESENDBECOME(hc1, SUSPENDED, localError)
  }
}

object ChannelListener {
  type Malfunction = (HostedChannel, Throwable)
  type Incoming = (HostedChannel, ChannelData, Any)
  type Transition = (HostedChannel, ChannelData, String, String)
}

trait ChannelListener {
  def onProcessSuccess: PartialFunction[Incoming, Unit] = none
  def onException: PartialFunction[Malfunction, Unit] = none
  def onBecome: PartialFunction[Transition, Unit] = none

  def fulfillReceived(fulfill: UpdateFulfillHtlc): Unit = none
  def stateUpdated(hc: HostedCommits): Unit = none
}