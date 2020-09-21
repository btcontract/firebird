package com.btcontract.wallet.ln

import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.channel._
import scala.concurrent.duration._
import com.softwaremill.quicklens._
import com.btcontract.wallet.ln.crypto.Tools._
import com.btcontract.wallet.ln.PaymentMaster._
import com.btcontract.wallet.ln.PaymentFailure._

import rx.lang.scala.{Subscription, Observable => Obs}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import fr.acinq.eclair.router.Graph.GraphStructure.{DescAndCapacity, GraphEdge}
import com.btcontract.wallet.ln.ChannelListener.{Incoming, Malfunction, Transition}
import fr.acinq.eclair.crypto.Sphinx.{DecryptedPacket, FailurePacket, PaymentPacket}
import com.btcontract.wallet.ln.crypto.{CMDAddImpossible, CanBeRepliedTo, StateMachine, Tools}
import com.btcontract.wallet.ln.HostedChannel.{OPEN, SLEEPING, SUSPENDED, WAIT_FOR_ACCEPT, isOperational, isOperationalAndOpen}
import fr.acinq.eclair.router.Router.{ChannelDesc, NoRouteAvailable, Route, RouteFound, RouteParams, RouteRequest, RouteResponse}
import fr.acinq.eclair.wire.OnionCodecs.MissingRequiredTlv
import com.btcontract.wallet.helper.ThrottledWork
import fr.acinq.eclair.payment.OutgoingPacket
import fr.acinq.eclair.router.Announcements
import fr.acinq.bitcoin.Crypto.PublicKey
import java.util.concurrent.Executors
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.crypto.Sphinx
import scala.util.Random.shuffle
import scala.collection.mutable
import scodec.bits.ByteVector
import scodec.Attempt


object PaymentMaster {
  val INIT = "state-init"
  val PENDING = "state-pending"
  val ABORTED = "state-aborted"
  val SUCCEEDED = "state-succeeded"

  val EXPECTING_PAYMENTS = "state-expecting-payments"
  val WAITING_FOR_ROUTE = "state-waiting-for-route"

  val CMDClearFailHistory = "cmd-clear-fail-history"
  val CMDChanGotOnline = "cmd-chan-got-online"
  val CMDAskForRoute = "cmd-ask-for-route"
}

object PaymentFailure {
  final val NOT_ENOUGH_CAPACITY = 1
  final val RUN_OUT_OF_RETRY_ATTEMPTS = 2
  final val PEER_COULD_NOT_PARSE_ONION = 3
  final val NOT_RETRYING_NO_DETAILS = 4
  final val TOO_MANY_TIMES = 1000
}

sealed trait PaymentFailure
case class UnreadableRemoteFailure(route: Route) extends PaymentFailure
case class RemoteFailure(route: Route, packet: Sphinx.DecryptedFailurePacket) extends PaymentFailure
case class LocalFailure(errorStatus: Int) extends PaymentFailure

sealed trait PartStatus {
  def tuple = Tuple2(partId, this)
  def partId: ByteVector
}

case class InFlightInfo(cmd: CMD_ADD_HTLC, route: Route)
case class WaitForBetterConditions(partId: ByteVector, amount: MilliSatoshi) extends PartStatus
case class WaitForRouteOrInFlight(partId: ByteVector, amount: MilliSatoshi, chan: HostedChannel, flight: Option[InFlightInfo],
                                  localFailed: List[HostedChannel] = Nil, remoteAttempts: Int = 0) extends PartStatus {

  def oneMoreRemoteAttempt(newHostedChannel: HostedChannel): WaitForRouteOrInFlight = copy(flight = None, remoteAttempts = remoteAttempts + 1, chan = newHostedChannel)
  def oneMoreLocalAttempt(newHostedChannel: HostedChannel): WaitForRouteOrInFlight = copy(flight = None, localFailed = allFailedChans, chan = newHostedChannel)
  lazy val amountWithFees: MilliSatoshi = flight match { case Some(info) => info.route.weight.costs.head case None => amount }
  lazy val allFailedChans: List[HostedChannel] = chan :: localFailed
}

case class PaymentSenderData(cmd: CMD_SEND_MPP, parts: Map[ByteVector, PartStatus], failures: Vector[PaymentFailure] = Vector.empty) {
  def withRemoteFailure(route: Route, pkt: Sphinx.DecryptedFailurePacket): PaymentSenderData = copy(failures = RemoteFailure(route, pkt) +: failures)
  def withLocalFailure(reason: Int): PaymentSenderData = copy(failures = LocalFailure(reason) +: failures)
  def withoutPartId(partId: ByteVector): PaymentSenderData = copy(parts = parts - partId)

  def inFlights: Iterable[InFlightInfo] = parts.values collect { case WaitForRouteOrInFlight(_, _, _, Some(flight), _, _) => flight }
  def successfulUpdates: Iterable[ChannelUpdate] = inFlights.flatMap(_.route.hops).map(_.update)
  def totalFee: MilliSatoshi = inFlights.map(_.route.fee).sum
}

case class SplitIntoHalves(amount: MilliSatoshi)
case class NodeFailed(failedNodeId: PublicKey, increment: Int)
case class ChannelFailed(failedDescAndCap: DescAndCapacity, increment: Int)

case class CMD_SEND_MPP(paymentHash: ByteVector32, totalAmount: MilliSatoshi,
                        targetNodeId: PublicKey, paymentSecret: ByteVector32 = ByteVector32.Zeroes,
                        targetExpiry: CltvExpiry = CltvExpiry(0), assistedEdges: Set[GraphEdge] = Set.empty)


class ChannelMaster(payBag: PaymentInfoBag, chanBag: ChannelBag, val pf: PathFinder, cl: ChainLink) extends ChannelListener { me =>
  private[this] val dummyPaymentSenderData = PaymentSenderData(CMD_SEND_MPP(ByteVector32.Zeroes, MilliSatoshi(0L), invalidPubKey), Map.empty)
  private[this] val preliminaryResolveMemo = memoize(preliminaryResolve)
  private[this] val getPaymentInfoMemo = memoize(payBag.getPaymentInfo)

  var all: Vector[HostedChannel] = chanBag.all.map(mkHostedChannel)
  var listeners: Set[ChannelMasterListener] = Set.empty

  val events: ChannelMasterListener = new ChannelMasterListener {
    override def outgoingFailed(data: PaymentSenderData): Unit = for (lst <- listeners) lst.outgoingFailed(data)
    override def outgoingSucceeded(data: PaymentSenderData): Unit = for (lst <- listeners) lst.outgoingSucceeded(data)

    override def incomingSucceeded(paymentHash: ByteVector32): Unit = {
      // Clear for correct access to payments updated to PaymentInfo.SUCCESS
      for (lst <- listeners) lst.incomingSucceeded(paymentHash)
      preliminaryResolveMemo.clear
      getPaymentInfoMemo.clear
    }
  }

  val incomingTimeoutWorker: ThrottledWork[ByteVector, Any] = new ThrottledWork[ByteVector, Any] {
    def process(hash: ByteVector, res: Any): Unit = all.headOption.foreach(_ process CMD_INCOMING_TIMEOUT)
    def work(hash: ByteVector): Obs[Null] = RxUtils.ioQueue.delay(60.seconds)
    def error(canNotHappen: Throwable): Unit = none
  }

  val realConnectionListener: ConnectionListener = new ConnectionListener {
    // For messages we should differentiate by channelId, but we don't since only one hosted channel per node is allowed
    override def onOperational(worker: CommsTower.Worker): Unit = fromNode(worker.ann.nodeId).foreach(_ process CMD_SOCKET_ONLINE)
    override def onMessage(worker: CommsTower.Worker, msg: LightningMessage): Unit = fromNode(worker.ann.nodeId).foreach(_ process msg)
    override def onHostedMessage(worker: CommsTower.Worker, msg: HostedChannelMessage): Unit = fromNode(worker.ann.nodeId).foreach(_ process msg)

    override def onDisconnect(worker: CommsTower.Worker): Unit = {
      fromNode(worker.ann.nodeId).foreach(_ process CMD_SOCKET_OFFLINE)
      RxUtils.ioQueue.delay(5.seconds).foreach(_ => initConnect)
    }
  }

  def mkHostedChannel(cd: ChannelData): HostedChannel = new HostedChannel {
    def SEND(msg: LightningMessage *): Unit = for (work <- CommsTower.workers get data.announce.nodeSpecificPkap) msg foreach work.handler.process
    def STORE(channelData: HostedCommits): HostedCommits = chanBag.put(channelData.announce.nodeSpecificHostedChanId, channelData)
    def currentBlockDay: Long = cl.currentChainTip / LNParams.blocksPerDay
    listeners = Set(me, PaymentMaster)
    doProcess(cd)
  }

  def fromNode(nodeId: PublicKey): Vector[HostedChannel] = for (chan <- all if chan.data.announce.na.nodeId == nodeId) yield chan
  def findById(from: Vector[HostedChannel], chanId: ByteVector32): Option[HostedChannel] = from.find(_.data.announce.nodeSpecificHostedChanId == chanId)
  def initConnect: Unit = for (channel <- all) CommsTower.listen(Set(realConnectionListener), channel.data.announce.nodeSpecificPkap, channel.data.announce.na)
  def pendingHtlcs: Vector[UpdateAddHtlc] = all.flatMap(_.chanAndCommitsOpt).flatMap(_.commits.pendingOutgoing)

  def maxReceivableInfo: Option[CommitsAndMax] = {
    val canReceive = all.flatMap(_.chanAndCommitsOpt).filter(_.commits.updateOpt.isDefined).sortBy(_.commits.nextLocalSpec.toRemote)
    // Example: (5, 50, 60, 100) -> (50, 60, 100), receivalble = 50*3 = 150 (the idea is for smallest remaining channel to be able to handle an evenly split amount)
    val withoutSmall = canReceive.dropWhile(_.commits.nextLocalSpec.toRemote * canReceive.size < canReceive.last.commits.nextLocalSpec.toRemote).takeRight(4)
    val candidates = for (cs <- withoutSmall.indices map withoutSmall.drop) yield CommitsAndMax(cs, cs.head.commits.nextLocalSpec.toRemote * cs.size)
    maxByOption[CommitsAndMax, MilliSatoshi](candidates, _.maxReceivable)
  }

  def checkIfSendable(paymentHash: ByteVector32, amount: MilliSatoshi): Int = {
    val fulfilledLongTimeAgo = payBag.getPaymentInfo(paymentHash).map(_.status).contains(SUCCEEDED)
    val fulfilledInRuntime = PaymentMaster.data.payments.get(paymentHash).exists(fsm => SUCCEEDED == fsm.state)
    val pendingInSender = PaymentMaster.data.payments.get(paymentHash).exists(fsm => PENDING == fsm.state || INIT == fsm.state)
    val pendingInChannel = pendingHtlcs.exists(_.paymentHash == paymentHash)

    if (PaymentMaster.totalSendable < amount) PaymentInfo.NOT_SENDABLE_LOW_BALANCE
    else if (fulfilledLongTimeAgo || fulfilledInRuntime) PaymentInfo.NOT_SENDABLE_SUCCESS
    else if (pendingInChannel || pendingInSender) PaymentInfo.NOT_SENDABLE_IN_FLIGHT
    else PaymentInfo.SENDABLE
  }

  // RESOLVING INCOMING MESSAGES

  def incorrectDetails(add: UpdateAddHtlc) = IncorrectOrUnknownPaymentDetails(add.amountMsat, cl.currentChainTip)
  def failFinalPayloadSpec(fail: FailureMessage, finalPayloadSpec: FinalPayloadSpec): CMD_FAIL_HTLC = failHtlc(finalPayloadSpec.packet, fail, finalPayloadSpec.add)
  def failHtlc(packet: DecryptedPacket, fail: FailureMessage, add: UpdateAddHtlc): CMD_FAIL_HTLC = CMD_FAIL_HTLC(FailurePacket.create(packet.sharedSecret, fail), add)

  private def processIncoming: Unit = {
    val allIncomingResolves: Vector[AddResolution] = all.flatMap(_.chanAndCommitsOpt).flatMap(_.commits.pendingIncoming).map(preliminaryResolveMemo)
    val badRightAway: Vector[BadAddResolution] = allIncomingResolves collect { case badAddResolution: BadAddResolution => badAddResolution }
    val maybeGood: Vector[FinalPayloadSpec] = allIncomingResolves collect { case finalPayloadSpec: FinalPayloadSpec => finalPayloadSpec }

    // Grouping by payment hash assumes we never ask for two different payments with the same hash!
    val results = maybeGood.groupBy(_.add.paymentHash).map(_.swap).mapValues(getPaymentInfoMemo) map {
      case payments \ None => for (pay <- payments) yield failFinalPayloadSpec(incorrectDetails(pay.add), pay)
      case payments \ _ if payments.map(_.payload.totalAmount).toSet.size > 1 => for (pay <- payments) yield failFinalPayloadSpec(incorrectDetails(pay.add), pay)
      case payments \ Some(info) if payments.exists(_.payload.totalAmount < info.amountOrZero) => for (pay <- payments) yield failFinalPayloadSpec(incorrectDetails(pay.add), pay)
      case payments \ Some(info) if !payments.flatMap(_.payload.paymentSecret).forall(info.pr.paymentSecret.contains) => for (pay <- payments) yield failFinalPayloadSpec(incorrectDetails(pay.add), pay)
      case payments \ Some(info) if payments.map(_.add.amountMsat).sum >= payments.head.payload.totalAmount => for (payToFulfill <- payments) yield CMD_FULFILL_HTLC(info.preimage, payToFulfill.add)
      // This is an unexpected extra-payment or something like OFFLINE channel with fulfilled payment becoming OPEN or post-restart incoming leftovers, silently fullfil these
      case payments \ Some(info) if info.isIncoming && info.status == SUCCEEDED => for (pay <- payments) yield CMD_FULFILL_HTLC(info.preimage, pay.add)
      case payments \ _ if incomingTimeoutWorker.hasFinishedOrNeverStarted => for (pay <- payments) yield failFinalPayloadSpec(PaymentTimeout, pay)
      case _ => Vector.empty
    }

    // This method should always be executed in channel context
    // Using doProcess makes sure no external message gets intertwined in resolution
    for (cmd <- results.flatten) findById(all, cmd.add.channelId).foreach(_ doProcess cmd)
    for (cmd <- badRightAway) findById(all, cmd.add.channelId).foreach(_ doProcess cmd)
    for (chan <- all) chan doProcess CMD_PROCEED
  }

  private def preliminaryResolve(add: UpdateAddHtlc): AddResolution =
    PaymentPacket.peel(LNParams.keys.fakeInvoiceKey(add.paymentHash), add.paymentHash, add.onionRoutingPacket) match {
      case Right(packet) if packet.isLastPacket => OnionCodecs.finalPerHopPayloadCodec.decode(packet.payload.bits) match {
        case Attempt.Failure(error: MissingRequiredTlv) => failHtlc(packet, InvalidOnionPayload(error.tag, offset = 0), add)
        case _: Attempt.Failure => failHtlc(packet, InvalidOnionPayload(tag = UInt64(0), offset = 0), add)

        case Attempt.Successful(payload) if payload.value.expiry != add.cltvExpiry => failHtlc(packet, FinalIncorrectCltvExpiry(add.cltvExpiry), add)
        case Attempt.Successful(payload) if payload.value.amount != add.amountMsat => failHtlc(packet, incorrectDetails(add), add)
        case Attempt.Successful(payload) => FinalPayloadSpec(packet, payload.value, add)
      }

      case Right(packet) => failHtlc(packet, incorrectDetails(add), add)
      case Left(error) => CMD_FAIL_MALFORMED_HTLC(error.onionHash, error.code, add)
    }

  // CHANNEL LISTENER

  override def stateUpdated(hc: HostedCommits): Unit = {
    val allChansAndCommits: Vector[ChanAndCommits] = all.flatMap(_.chanAndCommitsOpt)
    val allFulfilledHashes = allChansAndCommits.flatMap(_.commits.localSpec.localFulfilled) // Shards fulfilled on last state update
    val allIncomingHashes = allChansAndCommits.flatMap(_.commits.localSpec.incomingAdds).map(_.paymentHash) // Shards still unfinalized
    allFulfilledHashes.diff(allIncomingHashes).foreach(events.incomingSucceeded) // Notify about those where no pending payments left
    processIncoming
  }

  override def onProcessSuccess: PartialFunction[Incoming, Unit] = {
    // An incoming payment arrives so we prolong waiting for the rest of shards
    case (_, _, add: UpdateAddHtlc) => incomingTimeoutWorker replaceWork add.paymentHash
    case (_, _, CMD_INCOMING_TIMEOUT) => processIncoming
  }

  override def onBecome: PartialFunction[Transition, Unit] = {
    // Offline channel does not react to our commands so we resend them on reconnect
    case (_, _, WAIT_FOR_ACCEPT | SLEEPING, OPEN | SUSPENDED) => processIncoming
  }

  // SENDING OUTGOING PAYMENTS

  case class PaymentMasterData(payments: Map[ByteVector32, PaymentSender],
                               chanFailedAtAmount: Map[ChannelDesc, MilliSatoshi] = Map.empty withDefaultValue Long.MaxValue.msat,
                               nodeFailedWithUnknownUpdateTimes: Map[PublicKey, Int] = Map.empty withDefaultValue 0,
                               chanFailedTimes: Map[ChannelDesc, Int] = Map.empty withDefaultValue 0)

  object PaymentMaster extends StateMachine[PaymentMasterData] with CanBeRepliedTo with ChannelListener { self =>
    implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
    def process(changeMessage: Any): Unit = scala.concurrent.Future(self doProcess changeMessage)
    become(PaymentMasterData(Map.empty), EXPECTING_PAYMENTS)

    def doProcess(change: Any): Unit = (change, state) match {
      // Rememeber traces of previous failure times to exclude those channels faster if they keep failing
      case (CMDClearFailHistory, _) if data.payments.values.forall(fsm => SUCCEEDED == fsm.state || ABORTED == fsm.state) =>
        val data1 = data.copy(chanFailedTimes = data.chanFailedTimes.mapValues(_ / 2), chanFailedAtAmount = Map.empty)
        become(data1, state)

      case (cmd: CMD_SEND_MPP, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
        // Make pathfinder aware of payee-provided routing hints
        for (edge <- cmd.assistedEdges) pf process edge
        relayOrCreateSender(cmd.paymentHash, cmd)
        self process CMDAskForRoute

      case (CMDChanGotOnline, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
        // Payments may still have awaiting parts due to offline channels
        data.payments.values.foreach(_ doProcess CMDChanGotOnline)
        self process CMDAskForRoute

      case (CMDAskForRoute | PathFinder.NotifyOperational, EXPECTING_PAYMENTS) =>
        // This is a proxy to always send command in payment master thread
        // IMPLICIT GUARD: this message is ignored in all other states
        data.payments.values.foreach(_ doProcess CMDAskForRoute)

      case (req: RouteRequest, EXPECTING_PAYMENTS) =>
        // IMPLICIT GUARD: ignore in other states, payment will be able to re-send later
        val currentUsedCapacities: mutable.Map[DescAndCapacity, MilliSatoshi] = getUsedCapacities
        val currentUsedDescs = mapKeys[DescAndCapacity, MilliSatoshi, ChannelDesc](currentUsedCapacities, _.desc, defVal = 0L.msat)
        val ignoreChansFailedTimes = data.chanFailedTimes collect { case desc \ failTimes if failTimes >= pf.routerConf.maxChannelFailures => desc }
        val ignoreChansCanNotHandle = currentUsedCapacities collect { case DescAndCapacity(desc, capacity) \ used if used + req.amount >= capacity => desc }
        val ignoreChansFailedAtAmount = data.chanFailedAtAmount collect { case desc \ failedAt if failedAt - currentUsedDescs(desc) - req.reserve <= req.amount => desc }
        val ignoreNodes = data.nodeFailedWithUnknownUpdateTimes collect { case nodeId \ failTimes if failTimes >= pf.routerConf.maxStrangeNodeFailures => nodeId }
        val ignoreChans = ignoreChansFailedTimes.toSet ++ ignoreChansCanNotHandle ++ ignoreChansFailedAtAmount
        val request1 = req.copy(ignoreNodes = ignoreNodes.toSet, ignoreChannels = ignoreChans)
        pf process Tuple2(self, request1)
        become(data, WAITING_FOR_ROUTE)

      case (PathFinder.NotifyRejected, WAITING_FOR_ROUTE) =>
        // Pathfinder is not yet ready, switch local state back
        // pathfinder is expected to notify us once it gets ready
        become(data, EXPECTING_PAYMENTS)

      case (response: RouteResponse, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
        data.payments.get(response.paymentHash).foreach(_ doProcess response)
        // Switch state to allow new route requests to come through
        become(data, EXPECTING_PAYMENTS)
        self process CMDAskForRoute

      case (ChannelFailed(descAndCapacity, increment), EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
        // At this point an affected InFlight status IS STILL PRESENT so failedAtAmount = sum(inFlight)
        val newChanFailedAtAmount = data.chanFailedAtAmount(descAndCapacity.desc) min getUsedCapacities(descAndCapacity)
        val atTimes1 = data.chanFailedTimes.updated(descAndCapacity.desc, data.chanFailedTimes(descAndCapacity.desc) + increment)
        val atAmount1 = data.chanFailedAtAmount.updated(descAndCapacity.desc, newChanFailedAtAmount)
        become(data.copy(chanFailedAtAmount = atAmount1, chanFailedTimes = atTimes1), state)

      case (NodeFailed(nodeId, increment), EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
        val newNodeFailedTimes = data.nodeFailedWithUnknownUpdateTimes(nodeId) + increment
        val atTimes1 = data.nodeFailedWithUnknownUpdateTimes.updated(nodeId, newNodeFailedTimes)
        become(data.copy(nodeFailedWithUnknownUpdateTimes = atTimes1), state)

      case (error: CMDAddImpossible, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
        data.payments.get(error.cmd.paymentHash).foreach(_ doProcess error)
        self process CMDAskForRoute

      case (fulfill: UpdateFulfillHtlc, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
        relayOrCreateSender(fulfill.paymentHash, fulfill)
        self process CMDAskForRoute

      case (fail: RemoteFailed, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
        relayOrCreateSender(fail.ourAdd.paymentHash, fail)
        self process CMDAskForRoute

      case _ =>
    }

    // Executed in channelContext
    override def stateUpdated(hc: HostedCommits): Unit = {
      hc.localSpec.remoteMalformed.foreach(process)
      hc.localSpec.remoteFailed.foreach(process)
    }

    override def fulfillReceived(fulfill: UpdateFulfillHtlc): Unit = self process fulfill
    override def onException: PartialFunction[Malfunction, Unit] = { case (_, error: CMDAddImpossible) => self process error }
    override def onBecome: PartialFunction[Transition, Unit] = { case (_, _, SLEEPING | SUSPENDED, OPEN) => self process CMDChanGotOnline }

    private def relayOrCreateSender(paymentHash: ByteVector32, msg: Any): Unit = data.payments.get(paymentHash) match {
      case None => withSender(new PaymentSender, paymentHash, msg) // Can happen after restart with leftoverts in channels
      case Some(sender) => sender doProcess msg // Normal case, sender FSM is present
    }

    private def withSender(sender: PaymentSender, paymentHash: ByteVector32, msg: Any): Unit = {
      val payments1 = data.payments.updated(paymentHash, sender)
      become(data.copy(payments = payments1), state)
      sender doProcess msg
    }

    def getUsedCapacities: mutable.Map[DescAndCapacity, MilliSatoshi] = {
      // This gets supposedly used capacities of external channels in a routing graph
      // we need this to exclude channels which definitely can't route a given amount right now
      val accum = mutable.Map.empty[DescAndCapacity, MilliSatoshi] withDefaultValue MilliSatoshi(0L)
      val descsAndCaps = data.payments.values.flatMap(_.data.inFlights).flatMap(_.route.amountPerDescAndCap)
      descsAndCaps foreach { case amount \ dnc => accum(dnc) += amount }
      accum
    }

    def totalSendable: MilliSatoshi =
      getSendable(all filter isOperational).values.sum

    def currentSendable: mutable.Map[ChanAndCommits, MilliSatoshi] =
      getSendable(all filter isOperationalAndOpen)

    def currentSendableExcept(wait: WaitForRouteOrInFlight): mutable.Map[ChanAndCommits, MilliSatoshi] =
      getSendable(all filter isOperationalAndOpen diff wait.allFailedChans)

    // This gets what can be sent through given channels with waiting parts taken into account
    def getSendable(chans: Vector[HostedChannel] = Vector.empty): mutable.Map[ChanAndCommits, MilliSatoshi] = {
      val waits: mutable.Map[HostedChannel, MilliSatoshi] = mutable.Map.empty[HostedChannel, MilliSatoshi] withDefaultValue 0L.msat
      val finals: mutable.Map[ChanAndCommits, MilliSatoshi] = mutable.Map.empty[ChanAndCommits, MilliSatoshi] withDefaultValue 0L.msat

      data.payments.values.flatMap(_.data.parts.values) collect { case wait: WaitForRouteOrInFlight => waits(wait.chan) += wait.amountWithFees }
      // Adding waiting amounts and then removing outgoing adds is necessary to always have an accurate view because access to channel data is concurrent
      shuffle(chans).flatMap(_.chanAndCommitsOpt).foreach(cnc => finals(cnc) = feeFreeBalance(cnc) - waits(cnc.chan) + cnc.commits.nextLocalSpec.outgoingAddsSum)
      finals filter { case cnc \ sendable => sendable >= cnc.commits.lastCrossSignedState.initHostedChannel.htlcMinimumMsat }
    }

    def feeFreeBalance(cnc: ChanAndCommits): MilliSatoshi = {
      val theoreticalMaxHtlcs = cnc.commits.lastCrossSignedState.initHostedChannel.maxAcceptedHtlcs
      val withoutBaseFee = cnc.commits.nextLocalSpec.toLocal - LNParams.routerConf.searchMaxFeeBase * theoreticalMaxHtlcs
      withoutBaseFee - withoutBaseFee * LNParams.routerConf.searchMaxFeePct
    }
  }

  class PaymentSender extends StateMachine[PaymentSenderData] { self =>
    private[this] val maxRemote = pf.routerConf.maxRemoteAttempts
    become(dummyPaymentSenderData, INIT)

    def doProcess(msg: Any): Unit = (msg, state) match {
      case (cmd: CMD_SEND_MPP, INIT | ABORTED) => assignToChans(PaymentMaster.currentSendable, PaymentSenderData(cmd, Map.empty), cmd.totalAmount)
      case (localError: CMDAddImpossible, ABORTED) => self abortAndNotify data.withoutPartId(localError.cmd.internalId)
      case (reject: RemoteFailed, ABORTED) => self abortAndNotify data.withoutPartId(reject.partId)

      case (reject: RemoteFailed, INIT) =>
        val data1 = data.modify(_.cmd.paymentHash).setTo(reject.ourAdd.paymentHash)
        self abortAndNotify data1.withLocalFailure(NOT_RETRYING_NO_DETAILS)

      case (fulfill: UpdateFulfillHtlc, INIT) =>
        // An idempotent transition, fires a success event with implanted hash
        val data1 = data.modify(_.cmd.paymentHash).setTo(fulfill.paymentHash)
        events.outgoingSucceeded(data1)
        become(data1, SUCCEEDED)

      case (_: UpdateFulfillHtlc, PENDING | ABORTED) =>
        // An idempotent transition, fires a success event
        events.outgoingSucceeded(data)
        become(data, SUCCEEDED)

      case (CMDChanGotOnline, PENDING) =>
        data.parts.values collectFirst { case WaitForBetterConditions(partId, amount) =>
          assignToChans(PaymentMaster.currentSendable, data.withoutPartId(partId), amount)
        }

      case (CMDAskForRoute, PENDING) =>
        data.parts.values collectFirst { case wait: WaitForRouteOrInFlight if wait.flight.isEmpty =>
          val fakeLocalEdge = Tools.mkFakeLocalEdge(from = LNParams.keys.routingPubKey, toPeer = wait.chan.data.announce.na.nodeId)
          val params = RouteParams(pf.routerConf.searchMaxFeeBase, pf.routerConf.searchMaxFeePct, pf.routerConf.firstPassMaxRouteLength, pf.routerConf.firstPassMaxCltv)
          PaymentMaster process RouteRequest(data.cmd.paymentHash, wait.partId, LNParams.keys.routingPubKey, data.cmd.targetNodeId, wait.amount, params.getMaxFee(wait.amount), fakeLocalEdge, params)
        }

      case (fail: NoRouteAvailable, PENDING) =>
        data.parts.values collectFirst { case wait: WaitForRouteOrInFlight if wait.flight.isEmpty && wait.partId == fail.partId =>
          PaymentMaster currentSendableExcept wait collectFirst { case cnc \ chanSendable if chanSendable >= wait.amount => cnc.chan } match {
            case Some(anotherCapableChan) => become(data.copy(parts = data.parts + wait.oneMoreLocalAttempt(anotherCapableChan).tuple), PENDING)
            case None if canBeSplit(wait.amount) => become(data.withoutPartId(wait.partId), PENDING) doProcess SplitIntoHalves(wait.amount)
            case None => self abortAndNotify data.withoutPartId(wait.partId).withLocalFailure(RUN_OUT_OF_RETRY_ATTEMPTS)
          }
        }

      case (found: RouteFound, PENDING) =>
        data.parts.values collectFirst { case wait: WaitForRouteOrInFlight if wait.flight.isEmpty && wait.partId == found.partId =>
          val finalPayload = Onion.createMultiPartPayload(wait.amount, data.cmd.totalAmount, data.cmd.targetExpiry, data.cmd.paymentSecret)
          val inFlightInfo = InFlightInfo(OutgoingPacket.buildCommand(wait.partId, data.cmd.paymentHash, found.route.hops, finalPayload), found.route)
          become(data.copy(parts = data.parts + wait.copy(flight = inFlightInfo.toSome).tuple), PENDING)
          wait.chan.process(inFlightInfo.cmd, CMD_PROCEED)
        }

      case (CMDAddImpossible(cmd, code), PENDING) =>
        data.parts.values collectFirst { case wait: WaitForRouteOrInFlight if wait.flight.isDefined && wait.partId == cmd.internalId =>
          PaymentMaster currentSendableExcept wait collectFirst { case cnc \ chanSendable if chanSendable >= wait.amount => cnc.chan } match {
            case Some(anotherCapableChan) => become(data.copy(parts = data.parts + wait.oneMoreLocalAttempt(anotherCapableChan).tuple), PENDING)
            case None if ChanErrorCodes.ERR_NOT_OPEN == code => assignToChans(PaymentMaster.currentSendable, data.withoutPartId(wait.partId), wait.amount)
            case None => self abortAndNotify data.withoutPartId(wait.partId).withLocalFailure(RUN_OUT_OF_RETRY_ATTEMPTS)
          }
        }

      case (malform: MalformAndAdd, PENDING) =>
        data.parts.values collectFirst { case wait: WaitForRouteOrInFlight if wait.flight.isDefined && wait.partId == malform.partId =>
          PaymentMaster currentSendableExcept wait collectFirst { case cnc \ chanSendable if chanSendable >= wait.amount => cnc.chan } match {
            case Some(anotherCapableChan) => become(data.copy(parts = data.parts + wait.oneMoreLocalAttempt(anotherCapableChan).tuple), PENDING)
            case None => self abortAndNotify data.withoutPartId(wait.partId).withLocalFailure(PEER_COULD_NOT_PARSE_ONION)
          }
        }

      case (fail: FailAndAdd, PENDING) =>
        data.parts.values collectFirst { case wait @ WaitForRouteOrInFlight(partId, _, _, Some(info), _, _) if partId == fail.partId =>
          Sphinx.FailurePacket.decrypt(packet = fail.theirFail.reason, info.cmd.packetAndSecrets.sharedSecrets) map {
            case finalPkt: Sphinx.DecryptedFailurePacket if finalPkt.originNode == data.cmd.targetNodeId =>
              self abortAndNotify data.withoutPartId(partId).withRemoteFailure(info.route, finalPkt)

            case pkt @ Sphinx.DecryptedFailurePacket(nodeId, failure: Update) =>
              // Pathfinder channels must be loaded at this point since we have already used it to construct a route
              val originalNodeIdOpt = pf.data.channels.get(failure.update.shortChannelId).map(_ getNodeIdSameSideAs failure.update)
              val isSignatureFine = originalNodeIdOpt.contains(nodeId) && Announcements.checkSig(failure.update, nodeId)
              val data1 = data.withRemoteFailure(info.route, pkt)

              if (isSignatureFine) {
                pf process failure.update
                info.route getEdgeForNode nodeId match {
                  case Some(edge) if edge.update.shortChannelId != failure.update.shortChannelId =>
                    // This is fine: remote node has used a different channel than the one we have initially requested
                    // But remote node may send such errors infinitely so increment this specific type of failure
                    PaymentMaster doProcess ChannelFailed(edge.toDescAndCapacity, increment = 1)
                    PaymentMaster doProcess NodeFailed(failedNodeId = nodeId, increment = 1)
                    resolveRemoteFail(data1, wait)

                  case Some(edge) if edge.update.core == failure.update.core =>
                    // Remote node returned the same update we used, channel is most likely imbalanced
                    // Note: we may have it disabled and new update comes enabled: still same update
                    PaymentMaster doProcess ChannelFailed(edge.toDescAndCapacity, increment = 1)
                    resolveRemoteFail(data1, wait)

                  case _ =>
                    // New update is enabled: refreshed in graph, not penalized here
                    // New update is disabled: removed from graph, not penalized here
                    resolveRemoteFail(data1, wait)
                }
              } else {
                // Invalid sig is a severe violation, ban sender node
                PaymentMaster doProcess NodeFailed(nodeId, TOO_MANY_TIMES)
                resolveRemoteFail(data1, wait)
              }

            case pkt @ Sphinx.DecryptedFailurePacket(nodeId, _: Node) =>
              PaymentMaster doProcess NodeFailed(nodeId, increment = TOO_MANY_TIMES)
              resolveRemoteFail(data.withRemoteFailure(info.route, pkt), wait)

            case pkt @ Sphinx.DecryptedFailurePacket(nodeId, _) =>
              // A non-specific failure, ignore channel; note that we are guaranteed to find a failed edge for returned nodeId
              PaymentMaster doProcess ChannelFailed(info.route.getEdgeForNode(nodeId).get.toDescAndCapacity, increment = TOO_MANY_TIMES)
              resolveRemoteFail(data.withRemoteFailure(info.route, pkt), wait)

          } getOrElse {
            val failure = UnreadableRemoteFailure(info.route)
            val nodesInBetween = info.route.hops.map(_.desc.b).drop(1).dropRight(1)

            if (nodesInBetween.isEmpty) {
              // Garbage is sent by our peer or final payee, fail a payment
              val data1 = data.copy(failures = failure +: data.failures)
              self abortAndNotify data1.withoutPartId(partId)
            } else {
              // We don't know which exact remote node is sending garbage, exclude a random one
              PaymentMaster doProcess NodeFailed(shuffle(nodesInBetween).head, increment = TOO_MANY_TIMES)
              resolveRemoteFail(data.copy(failures = failure +: data.failures), wait)
            }
          }
        }

      case (split: SplitIntoHalves, PENDING) =>
        val partOne: MilliSatoshi = split.amount / 2
        val partTwo: MilliSatoshi = split.amount - partOne
        // Must be run sequentially as these methods mutate data
        // as a result, both `currentSendable` and `data` are updated
        assignToChans(PaymentMaster.currentSendable, data, partOne)
        assignToChans(PaymentMaster.currentSendable, data, partTwo)

      case _ =>
    }

    def canBeSplit(totalAmount: MilliSatoshi): Boolean = totalAmount / 2 > pf.routerConf.mppMinPartAmount
    private def assignToChans(sendable: mutable.Map[ChanAndCommits, MilliSatoshi], data1: PaymentSenderData, amt: MilliSatoshi): Unit = {
      // This is a terminal method in a sense that it either successfully assigns an amount to channels or turns a payment info failed state
      // this method always sets a new partId to assigned parts so old payment statuses in data must be cleared before calling it

      sendable.foldLeft(Map.empty[ByteVector, PartStatus] -> amt) {
        case (collected @ (accumulator, leftover), cnc \ chanSendable) if leftover > 0L.msat =>
          // If leftover becomes less than theoretical sendable minimum then we must bump it upwards
          val minSendable = cnc.commits.lastCrossSignedState.initHostedChannel.htlcMinimumMsat
          // Example: leftover=500, minSendable=10, chanSendable=200 -> sending 200
          // Example: leftover=300, minSendable=10, chanSendable=400 -> sending 300
          // Example: leftover=6, minSendable=10, chanSendable=200 -> sending 10
          // Example: leftover=6, minSendable=10, chanSendable=8 -> skipping
          val finalAmount = leftover max minSendable min chanSendable

          if (finalAmount >= minSendable) {
            val wait = WaitForRouteOrInFlight(randomBytes(8), finalAmount, cnc.chan, None)
            (accumulator + wait.tuple, leftover - finalAmount)
          } else collected

        case collected \ _ =>
          // No more amount to assign
          // Propagate what's collected
          collected

      } match {
        case parts \ leftover if leftover <= 0L.msat =>
          // A whole mount has been fully split across our local channels
          // leftover may be slightly negative due to min sendable corrections
          become(data1.copy(parts = data1.parts ++ parts), PENDING)

        case _ \ rest if PaymentMaster.totalSendable - PaymentMaster.currentSendable.values.sum >= rest =>
          // Amount has not been fully split, but it is possible to split it once some channel becomes OPEN
          become(data1.copy(parts = data1.parts + WaitForBetterConditions(randomBytes(8), amt).tuple), PENDING)

        case _ =>
          // A non-zero leftover is present with no more channels left
          // partId should already have been removed upstream at this point
          self abortAndNotify data1.withLocalFailure(NOT_ENOUGH_CAPACITY)
      }
    }

    private def resolveRemoteFail(data1: PaymentSenderData, wait: WaitForRouteOrInFlight): Unit =
      PaymentMaster.currentSendable collectFirst { case cnc \ sendable if sendable >= wait.amount => cnc.chan } match {
        case Some(chan) if wait.remoteAttempts < maxRemote => become(data.copy(parts = data.parts + wait.oneMoreRemoteAttempt(chan).tuple), PENDING)
        case _ if canBeSplit(wait.amount) => become(data.withoutPartId(wait.partId), PENDING) doProcess SplitIntoHalves(wait.amount)
        case _ => self abortAndNotify data.withoutPartId(wait.partId).withLocalFailure(RUN_OUT_OF_RETRY_ATTEMPTS)
      }

    private def abortAndNotify(data1: PaymentSenderData): Unit = {
      val notInChannel = pendingHtlcs.forall(_.paymentHash != data1.cmd.paymentHash)
      if (notInChannel && data1.inFlights.isEmpty) events.outgoingFailed(data1)
      become(data1, ABORTED)
    }
  }

  // Wire up everything here

  pf.listeners += PaymentMaster
  cl.listeners += new ChainLinkListener {
    var shutdownTimer: Option[Subscription] = None

    override def onChainTipKnown: Unit = {
      // Remove pending shutdown timer and notify channels
      for (subscription <- shutdownTimer) subscription.unsubscribe
      for (chan <- all) chan process CMD_CHAIN_TIP_KNOWN
    }

    override def onTotalDisconnect: Unit = {
      // Once we're disconnected, wait for 6 hours and then put channels into SLEEPING state if there's no reconnect
      // sending CMD_CHAIN_TIP_LOST puts a channel into SLEEPING state where it does not react to new payments
      val delay = RxUtils.initDelay(Obs from all, System.currentTimeMillis, 3600 * 6 * 1000L)
      shutdownTimer = delay.subscribe(_ process CMD_CHAIN_TIP_LOST).toSome
    }
  }
}

trait ChannelMasterListener {
  def incomingSucceeded(paymentHash: ByteVector32): Unit = none
  def outgoingSucceeded(data: PaymentSenderData): Unit = none
  def outgoingFailed(data: PaymentSenderData): Unit = none
}