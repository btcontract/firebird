package com.btcontract.wallet.ln

import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.channel._
import com.softwaremill.quicklens._
import com.btcontract.wallet.ln.crypto.Tools._
import com.btcontract.wallet.ln.HostedChannel._
import com.btcontract.wallet.ln.ChanErrorCodes._
import com.btcontract.wallet.ln.ChannelListener._
import com.btcontract.wallet.ln.crypto.{CMDAddImpossible, LightningException, StateMachine}
import com.btcontract.wallet.ln.CommitmentSpec.LNDirectionalMessage
import fr.acinq.eclair.router.Graph.GraphStructure.GraphEdge
import fr.acinq.eclair.router.Router.ChannelDesc
import fr.acinq.eclair.router.Announcements
import java.util.concurrent.Executors
import fr.acinq.bitcoin.ByteVector64
import scala.concurrent.Future
import scodec.bits.ByteVector


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
  def isOperationalAndOpen(chan: HostedChannel): Boolean = isOperational(chan)
  type ChansAndMax = (Vector[HostedChannel], MilliSatoshi)
}

abstract class HostedChannel extends StateMachine[ChannelData] { me =>
  def isBlockDayOutOfSync(blockDay: Long): Boolean = math.abs(blockDay - currentBlockDay) > 1
  def process(changes: Any *): Unit = Future(changes foreach doProcess) onFailure { case failure => events onException this -> failure }
  def pendingOutgoing: Set[UpdateAddHtlc] = data match { case hc: HostedCommits => hc.localSpec.outgoingAdds ++ hc.nextLocalSpec.outgoingAdds case _ => Set.empty } // Cross-signed + new payments offered by us
  def pendingIncoming: Set[UpdateAddHtlc] = data match { case hc: HostedCommits => hc.localSpec.incomingAdds intersect hc.nextLocalSpec.incomingAdds case _ => Set.empty } // Cross-signed but not yet resolved by us
  def remoteBalance: MilliSatoshi = data match { case hc: HostedCommits => hc.nextLocalSpec.toRemote case _ => 0.msat }
  def localBalance: MilliSatoshi = data match { case hc: HostedCommits => hc.nextLocalSpec.toLocal case _ => 0.msat }
  def getCommits: Option[HostedCommits] = data match { case hc: HostedCommits => Some(hc) case _ => None }

  def getEdge: Option[GraphEdge] =
    for {
      channelData <- getCommits
      channelUpdate <- channelData.updateOpt
      // We use a separate NodeId for each peer, but for local graph we use a ChannelDesc with a stable NodeId
      channelDesc = ChannelDesc(channelUpdate.shortChannelId, LNParams.keys.ourRoutingSourceNodeId, data.announce.na.nodeId)
    } yield GraphEdge(balanceOpt = Some(channelData.nextLocalSpec.toLocal), desc = channelDesc, update = channelUpdate)

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

  var permanentOffline: Boolean = true
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
        permanentOffline = false


      case (wait @ WaitRemoteHostedReply(_, refundScriptPubKey, secret), CMD_CHAIN_TIP_KNOWN, WAIT_FOR_INIT) =>
        if (isSocketConnected) me SEND InvokeHostedChannel(LNParams.chainHash, refundScriptPubKey, secret)
        if (isSocketConnected) BECOME(wait, WAIT_FOR_ACCEPT)
        isChainHeightKnown = true


      case (WaitRemoteHostedReply(ext, refundScriptPubKey, _), init: InitHostedChannel, WAIT_FOR_ACCEPT) =>
        require(Features areSupported Features(init.features), "Unsupported features found, you should probably update an app")
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

        me SEND localHalfSignedHC.lastCrossSignedState.stateUpdate(isTerminal = true)
        BECOME(WaitRemoteHostedStateUpdate(ext, localHalfSignedHC), state)


      case (WaitRemoteHostedStateUpdate(_, hc), remoteSU: StateUpdate, WAIT_FOR_ACCEPT) =>
        val localCompleteLCSS = hc.lastCrossSignedState.copy(remoteSigOfLocal = remoteSU.localSigOfRemoteLCSS)
        val isRightRemoteUpdateNumber = hc.lastCrossSignedState.remoteUpdates == remoteSU.localUpdates
        val isRightLocalUpdateNumber = hc.lastCrossSignedState.localUpdates == remoteSU.remoteUpdates
        val isRemoteSigOk = localCompleteLCSS.verifyRemoteSig(hc.announce.na.nodeId)

        if (me isBlockDayOutOfSync remoteSU.blockDay) throw new LightningException("Their blockday is wrong")
        if (!isRightRemoteUpdateNumber) throw new LightningException("Their remote update number is wrong")
        if (!isRightLocalUpdateNumber) throw new LightningException("Their local update number is wrong")
        if (!isRemoteSigOk) throw new LightningException("Their signature is wrong")
        val hc1 = hc.copy(lastCrossSignedState = localCompleteLCSS)
        // CMDProceed is on ChannelManager
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
        BECOME(hc.receiveAdd(addHtlc), state)


      // Process their fulfill in any state to make sure we always get a preimage
      // fails/fulfills when SUSPENDED are ignored because they may fulfill afterwards
      case (hc: HostedCommits, fulfill: UpdateFulfillHtlc, SLEEPING | OPEN | SUSPENDED) =>
        val isPresent = hc.nextLocalSpec.outgoingAdds.exists(add => add.id == fulfill.id && add.paymentHash == fulfill.paymentHash)
        // Technically peer may send a preimage any time, even if new LCSS has not been reached yet so we only have HTLC in outgoing: always resolve
        if (isPresent) BECOME(hc.addProposal(fulfill.asRemote), state) else throw new LightningException("Peer fulfilled an HTLC which does not exist")
        events.fulfillReceived(fulfill)


      case (hc: HostedCommits, fail: UpdateFailHtlc, OPEN) =>
        // For both types of Fail we only consider them when channel is OPEN and only accept them if our outging payment has not been resolved already
        val isNotResolvedYet = hc.localSpec.findHtlcById(fail.id, isIncoming = false).isDefined && hc.nextLocalSpec.findHtlcById(fail.id, isIncoming = false).isDefined
        if (isNotResolvedYet) BECOME(hc.addProposal(fail.asRemote), state) else throw new LightningException("Peer failed an HTLC which is either not cross-signed or does not exist")


      case (hc: HostedCommits, fail: UpdateFailMalformedHtlc, OPEN) =>
        if (fail.failureCode.&(FailureMessageCodecs.BADONION) == 0) throw new LightningException("Wrong failure code for malformed onion")
        val isNotResolvedYet = hc.localSpec.findHtlcById(fail.id, isIncoming = false).isDefined && hc.nextLocalSpec.findHtlcById(fail.id, isIncoming = false).isDefined
        if (isNotResolvedYet) BECOME(hc.addProposal(fail.asRemote), state) else throw new LightningException("Peer malformed-failed an HTLC which is either not cross-signed or does not exist")


      case (hc: HostedCommits, cmd: CMD_ADD_HTLC, currentState) =>
        if (OPEN != currentState) throw CMDAddImpossible(cmd, ERR_NOT_OPEN)
        val hostedCommits1 \ updateAddHtlc = hc sendAdd cmd
        BECOME(hostedCommits1, state)
        me SEND updateAddHtlc


      case (hc: HostedCommits, CMD_PROCEED, OPEN) if hc.futureUpdates.nonEmpty =>
        val nextHC = hc.nextLocalUnsignedLCSS(currentBlockDay).withLocalSigOfRemote(data.announce.nodeSpecificPrivKey)
        // Tell them not to resolve updates yet even though they may have a cross-signed state because they may also be sending changes
        me SEND nextHC.stateUpdate(isTerminal = false)


      case (hc: HostedCommits, StateUpdate(blockDay, _, remoteUpdates, localSigOfRemoteLCSS, isTerminal), OPEN)
        // GUARD: only proceed if signture is defferent because they will send a few non-terminal duplicates
        if hc.lastCrossSignedState.remoteSigOfLocal != localSigOfRemoteLCSS =>

        val lcss1 = hc.nextLocalUnsignedLCSS(blockDay).copy(remoteSigOfLocal = localSigOfRemoteLCSS).withLocalSigOfRemote(data.announce.nodeSpecificPrivKey)
        val hc1 = hc.copy(lastCrossSignedState = lcss1, localSpec = hc.nextLocalSpec, futureUpdates = Vector.empty)
        val isRemoteSigOk = lcss1.verifyRemoteSig(hc.announce.na.nodeId)

        if (me isBlockDayOutOfSync blockDay) localSuspend(hc, ERR_HOSTED_WRONG_BLOCKDAY)
        else if (remoteUpdates < lcss1.localUpdates) me SEND lcss1.stateUpdate(isTerminal = false)
        else if (!isRemoteSigOk) localSuspend(hc, ERR_HOSTED_WRONG_REMOTE_SIG)
        else if (!isTerminal) me SEND lcss1.stateUpdate(isTerminal = true)
        else {
          val nextLocalSU = lcss1.stateUpdate(isTerminal = true)
          STORESENDBECOME(hc1, state, nextLocalSU)
          events.stateUpdated(hc1)
        }


      // Normal channel fetches on-chain when CLOSED, hosted sends a preimage and signals on UI when SUSPENDED
      case (hc: HostedCommits, CMD_FULFILL_HTLC(preimage, add), OPEN | SUSPENDED) if pendingIncoming.contains(add) =>
        val updateFulfill = UpdateFulfillHtlc(hc.announce.nodeSpecificHostedChanId, add.id, paymentPreimage = preimage)
        STORESENDBECOME(hc.addProposal(updateFulfill.asLocal), state, updateFulfill)

      // This will make pending incoming HTLC in a SUSPENDED channel invisible to `pendingIncoming` method which is desired
      case (hc: HostedCommits, CMD_FAIL_MALFORMED_HTLC(onionHash, code, add), OPEN | SUSPENDED) if pendingIncoming.contains(add) =>
        val updateFailMalformed = UpdateFailMalformedHtlc(hc.announce.nodeSpecificHostedChanId, add.id, onionHash, failureCode = code)
        STORESENDBECOME(hc.addProposal(updateFailMalformed.asLocal), state, updateFailMalformed)


      case (hc: HostedCommits, CMD_FAIL_HTLC(reason, add), OPEN | SUSPENDED) if pendingIncoming.contains(add) =>
        val updateFail = UpdateFailHtlc(hc.announce.nodeSpecificHostedChanId, add.id, reason)
        STORESENDBECOME(hc.addProposal(updateFail.asLocal), state, updateFail)


      case (hc: HostedCommits, CMD_SOCKET_ONLINE, SLEEPING | SUSPENDED) =>
        if (isChainHeightKnown) me SEND hc.getError.getOrElse(hc.invokeMsg)
        isSocketConnected = true
        permanentOffline = false


      case (hc: HostedCommits, CMD_CHAIN_TIP_KNOWN, SLEEPING | SUSPENDED) =>
        if (isSocketConnected) me SEND hc.getError.getOrElse(hc.invokeMsg)
        isChainHeightKnown = true


      case (hc: HostedCommits, CMD_SOCKET_OFFLINE, OPEN) =>
        isSocketConnected = false
        BECOME(hc, SLEEPING)


      case (hc: HostedCommits, _: InitHostedChannel, SLEEPING) =>
        // Remote peer has lost this channel, they should re-sync from our LCSS
        syncAndResend(hc, hc.futureUpdates, hc.lastCrossSignedState, hc.localSpec)


      // Technically they can send a remote LCSS before us explicitly asking for it first
      // this may be a problem if chain height is not yet known and we have incoming HTLCs to resolve
      case (hc: HostedCommits, remoteLCSS: LastCrossSignedState, SLEEPING) if isChainHeightKnown =>
        val isLocalSigOk = remoteLCSS.verifyRemoteSig(data.announce.nodeSpecificPubKey)
        val isRemoteSigOk = remoteLCSS.reverse.verifyRemoteSig(hc.announce.na.nodeId)
        val weAreAhead = hc.lastCrossSignedState.isAhead(remoteLCSS)
        val weAreEven = hc.lastCrossSignedState.isEven(remoteLCSS)

        if (!isRemoteSigOk) localSuspend(hc, ERR_HOSTED_WRONG_REMOTE_SIG)
        else if (!isLocalSigOk) localSuspend(hc, ERR_HOSTED_WRONG_LOCAL_SIG)
        // They have our current or previous state, resend all our future updates and keep the current state
        else if (weAreAhead || weAreEven) syncAndResend(hc, hc.futureUpdates, hc.lastCrossSignedState, hc.localSpec)
        else hc.findState(remoteLCSS).headOption match {

          case Some(hc1) =>
            val leftOvers = hc.futureUpdates.diff(hc1.futureUpdates)
            // They have our future state, settle on it and resend local leftovers
            syncAndResend(hc1, leftOvers, remoteLCSS.reverse, hc1.nextLocalSpec)

          case None =>
            // We are far behind, restore state and resolve incoming adds
            val hc1 = restoreCommits(remoteLCSS.reverse, hc.announce)
            // HTLC resolution and CMDProceed is on ChannelManager
            STORESENDBECOME(hc1, OPEN, hc1.lastCrossSignedState)
        }


      case (hc: HostedCommits, ann: NodeAnnouncement, SLEEPING | SUSPENDED)
        if hc.announce.na.nodeId == ann.nodeId && Announcements.checkSig(ann) =>
        val data1 = hc.modify(_.announce) setTo NodeAnnouncementExt(ann)
        data = me STORE data1


      case (hc: HostedCommits, upd: ChannelUpdate, OPEN | SLEEPING)
        if hc.updateOpt.forall(_.timestamp < upd.timestamp) =>
        val data1 = hc.modify(_.updateOpt) setTo Some(upd)
        data = me STORE data1


      case (hc: HostedCommits, brand: HostedChannelBranding, OPEN | SLEEPING) =>
        val data1 = hc.modify(_.brandingOpt) setTo Some(brand)
        data = me STORE data1


      case (hc: HostedCommits, remoteError: Error, WAIT_FOR_ACCEPT | OPEN | SLEEPING) =>
        val hc1 = hc.modify(_.remoteError) setTo Some(remoteError)
        BECOME(me STORE hc1, SUSPENDED)


      // User has accepted a proposed remote override, now make sure all provided parameters check out
      case (hc: HostedCommits, CMD_HOSTED_STATE_OVERRIDE(remoteOverride), SUSPENDED) if isSocketConnected =>
        val localBalance = hc.newLocalBalance(remoteOverride)

        val restoredLCSS =
          hc.lastCrossSignedState.copy(incomingHtlcs = Nil, outgoingHtlcs = Nil,
            localBalanceMsat = localBalance, remoteBalanceMsat = remoteOverride.localBalanceMsat,
            localUpdates = remoteOverride.remoteUpdates, remoteUpdates = remoteOverride.localUpdates,
            blockDay = remoteOverride.blockDay, remoteSigOfLocal = remoteOverride.localSigOfRemoteLCSS)
            .withLocalSigOfRemote(data.announce.nodeSpecificPrivKey)

        val nextLocalSU = restoredLCSS.stateUpdate(isTerminal = true)
        if (localBalance < 0.msat) throw new LightningException("Provided updated local balance is larger than capacity")
        if (remoteOverride.localUpdates < hc.lastCrossSignedState.remoteUpdates) throw new LightningException("Provided local update number from remote host is wrong")
        if (remoteOverride.remoteUpdates < hc.lastCrossSignedState.localUpdates) throw new LightningException("Provided remote update number from remote host is wrong")
        if (remoteOverride.blockDay < hc.lastCrossSignedState.blockDay) throw new LightningException("Provided override blockday from remote host is not acceptable")
        require(restoredLCSS.verifyRemoteSig(hc.announce.na.nodeId), "Provided override signature from remote host is wrong")
        STORESENDBECOME(restoreCommits(restoredLCSS, hc.announce), OPEN, nextLocalSU)


      case (null, wait: WaitRemoteHostedReply, null) => super.become(wait, WAIT_FOR_INIT)
      case (null, hc: HostedCommits, null) if hc.getError.isDefined => super.become(hc, SUSPENDED)
      case (null, hc: HostedCommits, null) => super.become(hc, SLEEPING)
      case _ =>
    }

    // Change has been processed without failures
    events onProcessSuccess Tuple3(me, data, change)
  }

  def syncAndResend(hc: HostedCommits, leftovers: Vector[LNDirectionalMessage], lcss: LastCrossSignedState, spec: CommitmentSpec): Unit = {
    // Forget about remote, re-send our LCSS and all non-cross-signed local updates, finally sign if local leftovers are indeed present
    val hc1 = hc.copy(futureUpdates = leftovers.filter { case _ \ isLocal => isLocal }, lastCrossSignedState = lcss, localSpec = spec)
    SEND(hc1.lastCrossSignedState +: hc1.nextLocalUpdates:_*)
    // CMDProceed is on ChannelManager
    BECOME(hc1, OPEN)
  }

  def restoreCommits(localLCSS: LastCrossSignedState, ext: NodeAnnouncementExt): HostedCommits = {
    val inHtlcs = for (updateAddHtlc <- localLCSS.incomingHtlcs) yield Htlc(incoming = true, updateAddHtlc)
    val outHtlcs = for (updateAddHtlc <- localLCSS.outgoingHtlcs) yield Htlc(incoming = false, updateAddHtlc)
    val localSpec = CommitmentSpec(feeratePerKw = 0L, localLCSS.localBalanceMsat, localLCSS.remoteBalanceMsat, htlcs = (inHtlcs ++ outHtlcs).toSet)
    HostedCommits(ext, localLCSS, futureUpdates = Vector.empty, localSpec, updateOpt = None, brandingOpt = None, localError = None, remoteError = None, System.currentTimeMillis)
  }

  // CMDProceed is on ChannelManager
  def localSuspend(hc: HostedCommits, errCode: ByteVector): Unit = {
    val localError = Error(hc.announce.nodeSpecificHostedChanId, errCode)
    val hc1 = hc.modify(_.localError) setTo Some(localError)
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