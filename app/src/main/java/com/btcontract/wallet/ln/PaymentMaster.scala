package com.btcontract.wallet.ln

import fr.acinq.eclair._
import com.btcontract.wallet.ln.crypto.Tools._
import com.btcontract.wallet.ln.PaymentMaster._
import com.btcontract.wallet.ln.PaymentFailure._
import fr.acinq.eclair.{CltvExpiry, MilliSatoshi}
import fr.acinq.eclair.channel.{CMD_ADD_HTLC, CMD_PROCEED}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import com.btcontract.wallet.ln.ChannelListener.{Malfunction, Transition}
import com.btcontract.wallet.ln.HostedChannel.{OPEN, SLEEPING, SUSPENDED}
import fr.acinq.eclair.router.Graph.GraphStructure.{DescAndCapacity, GraphEdge}
import com.btcontract.wallet.ln.HostedChannel.{isOperational, isOperationalAndOpen}
import fr.acinq.eclair.wire.{ChannelUpdate, Node, Onion, Update, UpdateFulfillHtlc}
import com.btcontract.wallet.ln.crypto.{CMDAddImpossible, CanBeRepliedTo, StateMachine, Tools}
import fr.acinq.eclair.router.Router.{ChannelDesc, NoRouteAvailable, Route, RouteFound, RouteParams, RouteRequest, RouteResponse}
import fr.acinq.eclair.router.Graph.RichWeight
import fr.acinq.eclair.payment.OutgoingPacket
import fr.acinq.eclair.router.Announcements
import fr.acinq.bitcoin.Crypto.PublicKey
import java.util.concurrent.Executors
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.bitcoin.ByteVector32
import scala.util.Random.shuffle
import scala.collection.mutable
import scodec.bits.ByteVector


object PaymentFailure {
  final val NOT_ENOUGH_CAPACITY = 1
  final val RUN_OUT_OF_RETRY_ATTEMPTS = 2
  final val PEER_COULD_NOT_PARSE_ONION = 3
  final val TOO_MANY_TIMES = 1000

  final val NOWEIGHT = RichWeight(Vector.empty, 0, CltvExpiryDelta(0), 0D)
  final val NOROUTE = Route(weight = NOWEIGHT, hops = Nil)
}


sealed trait PaymentFailure { def route: Route }
case class LocalFailure(route: Route, errorStatus: Int) extends PaymentFailure
case class RemoteFailure(route: Route, packet: Sphinx.DecryptedFailurePacket) extends PaymentFailure
case class UnreadableRemoteFailure(route: Route) extends PaymentFailure


sealed trait PartStatus {
  def tuple = Tuple2(partId, this)
  def partId: ByteVector
}

case class InFlightInfo(cmd: CMD_ADD_HTLC, route: Route)
case class WaitForBetterConditions(partId: ByteVector, amount: MilliSatoshi) extends PartStatus
case class WaitForRouteOrInFlight(partId: ByteVector, amount: MilliSatoshi, chan: HostedChannel, flight: Option[InFlightInfo], localAttempts: Int = 0, remoteAttempts: Int = 0) extends PartStatus {
  def oneMoreRemoteAttempt(newHostedChannel: HostedChannel): WaitForRouteOrInFlight = copy(flight = None, remoteAttempts = remoteAttempts + 1, chan = newHostedChannel)
  def oneMoreLocalAttempt(newHostedChannel: HostedChannel): WaitForRouteOrInFlight = copy(flight = None, localAttempts = localAttempts + 1, chan = newHostedChannel)
}

case class PaymentSenderData(cmd: CMD_SEND_MPP, parts: Map[ByteVector, PartStatus], failures: Vector[PaymentFailure] = Vector.empty) {
  def withRemoteFailure(route: Route, pkt: Sphinx.DecryptedFailurePacket): PaymentSenderData = copy(failures = RemoteFailure(route, pkt) +: failures)
  def withLocalFailure(route: Route, reason: Int): PaymentSenderData = copy(failures = LocalFailure(route, reason) +: failures)
  def withoutPartId(partId: ByteVector): PaymentSenderData = copy(parts = parts - partId)

  def inFlights: Iterable[InFlightInfo] = parts.values collect { case wait: WaitForRouteOrInFlight if wait.flight.isDefined => wait.flight.get }
  def maxCltvExpiryDeltaOpt: Option[InFlightInfo] = maxByOption[InFlightInfo, CltvExpiryDelta](inFlights, _.route.weight.cltv)
  def successfulUpdates: Iterable[ChannelUpdate] = inFlights.flatMap(_.route.hops).map(_.update)
  def totalFee: MilliSatoshi = inFlights.map(_.route.fee).sum
}

case class PaymentMasterData(payments: Map[ByteVector32, PaymentSender],
                             chanFailedAtAmount: Map[ChannelDesc, MilliSatoshi] = Map.empty withDefaultValue Long.MaxValue.msat,
                             nodeFailedWithUnknownUpdateTimes: Map[PublicKey, Int] = Map.empty withDefaultValue 0,
                             chanFailedTimes: Map[ChannelDesc, Int] = Map.empty withDefaultValue 0) {

  // Rememeber traces of previous failure times for channels to exclude them faster in subsequent payments
  def forgetFailures: PaymentMasterData = copy(chanFailedTimes = chanFailedTimes.mapValues(_ / 2), chanFailedAtAmount = Map.empty)
}

case class HalveSplit(amount: MilliSatoshi)
case class NodeFailed(failedNodeId: PublicKey, increment: Int)
case class ChannelFailed(failedDescAndCap: DescAndCapacity, increment: Int)
case class CMD_SEND_MPP(paymentHash: ByteVector32, totalAmount: MilliSatoshi, targetNodeId: PublicKey,
                        paymentSecret: ByteVector32 = ByteVector32.Zeroes, targetExpiry: CltvExpiry = CltvExpiry(0),
                        assistedEdges: Set[GraphEdge] = Set.empty)


object PaymentMaster {
  val INIT = "state-init"
  val PENDING = "state-pending"
  val ABORTED = "state-aborted"
  val SUCCEEDED = "state-succeeded"
  val EXPECTING_PAYMENTS = "state-expecting-payments"
  val WAITING_FOR_ROUTE = "state-waiting-for-route"
  val CMDChanGotOnline = "cmd-chan-got-online"
  val CMDAskForRoute = "cmd-ask-for-route"
}

class PaymentSender(master: PaymentMaster) extends StateMachine[PaymentSenderData] { me =>
  // No separate stacking thread, all calles are executed within PaymentMaster context
  private[this] val maxRemote = master.cm.pf.routerConf.maxRemoteAttempts
  private[this] val maxLocal = master.cm.pf.routerConf.maxLocalAttempts
  become(null, INIT)

  def doProcess(msg: Any): Unit = (msg, state) match {
    case (cmd: CMD_SEND_MPP, INIT | ABORTED) => assignToChans(master.currentSendable, PaymentSenderData(cmd, parts = Map.empty), cmd.totalAmount)
    case (localError: CMDAddImpossible, ABORTED) => me abortAndNotify data.withoutPartId(localError.cmd.internalId)
    case (reject: RemoteReject, ABORTED) => me abortAndNotify data.withoutPartId(reject.partId)

    case (reject: RemoteReject, INIT) =>
      // Only catches if this is a new PaymentSender waiting for commands and having a null data
      val data1 = PaymentSenderData(CMD_SEND_MPP(reject.ourAdd.paymentHash, 0.msat, dummyPubKey), parts = Map.empty)
      me abortAndNotify data1.withLocalFailure(NOROUTE, RUN_OUT_OF_RETRY_ATTEMPTS)

    case (fulfill: UpdateFulfillHtlc, INIT) =>
      // Only catches if this is a new PaymentSender waiting for commands and having a null data
      val data1 = PaymentSenderData(CMD_SEND_MPP(fulfill.paymentHash, 0.msat, dummyPubKey), parts = Map.empty)
      master.cm.events.outgoingSucceeded(data1)
      become(data1, SUCCEEDED)

    case (_: UpdateFulfillHtlc, PENDING | ABORTED) =>
      // An idempotent transition, fires a success event
      master.cm.events.outgoingSucceeded(data)
      become(data, SUCCEEDED)

    case (CMDChanGotOnline, PENDING) =>
      data.parts.values collectFirst { case WaitForBetterConditions(partId, amount) =>
        assignToChans(master.currentSendable, data.withoutPartId(partId), amount)
      }

    case (CMDAskForRoute, PENDING) =>
      data.parts.values collectFirst { case wait: WaitForRouteOrInFlight if wait.flight.isEmpty =>
        val params = RouteParams(master.cm.pf.routerConf.searchMaxFeeBase, master.cm.pf.routerConf.searchMaxFeePct, master.cm.pf.routerConf.firstPassMaxRouteLength, master.cm.pf.routerConf.firstPassMaxCltv)
        master process RouteRequest(data.cmd.paymentHash, wait.partId, LNParams.keys.routingPubKey, data.cmd.targetNodeId, wait.amount, params.getMaxFee(wait.amount), wait.chan.fakeEdge, params)
      }

    case (fail: NoRouteAvailable, PENDING) =>
      data.parts.values collectFirst { case wait: WaitForRouteOrInFlight if wait.flight.isEmpty && wait.partId == fail.partId =>
        master.currentSendableExcept(wait.chan) collectFirst { case cnc \ chanSendable if chanSendable >= wait.amount => cnc.chan } match {
          case Some(chan) if wait.localAttempts < maxLocal => become(data.copy(parts = data.parts + wait.oneMoreLocalAttempt(chan).tuple), PENDING)
          case _ if canBeSplit(wait.amount) => become(data.withoutPartId(wait.partId), PENDING) doProcess HalveSplit(wait.amount)
          case _ => me abortAndNotify data.withoutPartId(wait.partId).withLocalFailure(NOROUTE, RUN_OUT_OF_RETRY_ATTEMPTS)
        }
      }

    case (found: RouteFound, PENDING) =>
      data.parts.values collectFirst { case wait: WaitForRouteOrInFlight if wait.flight.isEmpty && wait.partId == found.partId =>
        val finalPayload = Onion.createMultiPartPayload(wait.amount, data.cmd.totalAmount, data.cmd.targetExpiry, data.cmd.paymentSecret)
        val inFlightInfo = InFlightInfo(OutgoingPacket.buildCommand(wait.partId, data.cmd.paymentHash, found.route.hops, finalPayload), found.route)
        become(data.copy(parts = data.parts + wait.copy(flight = inFlightInfo.toSome).tuple), PENDING)
        wait.chan.process(inFlightInfo.cmd, CMD_PROCEED)
      }

    case (err: CMDAddImpossible, PENDING) =>
      data.parts.values collectFirst { case wait: WaitForRouteOrInFlight if wait.flight.isDefined && wait.partId == err.cmd.internalId =>
        master.currentSendableExcept(wait.chan) collectFirst { case cnc \ chanSendable if chanSendable >= wait.amount => cnc.chan } match {
          case Some(chan) if wait.localAttempts < maxLocal => become(data.copy(parts = data.parts + wait.oneMoreLocalAttempt(chan).tuple), PENDING)
          case _ => assignToChans(master.currentSendableExcept(wait.chan), data.withoutPartId(wait.partId), wait.amount)
        }
      }

    case (malform: MalformAndAdd, PENDING) =>
      data.parts.values collectFirst { case wait: WaitForRouteOrInFlight if wait.flight.isDefined && wait.partId == malform.partId =>
        master.currentSendableExcept(wait.chan) collectFirst { case cnc \ chanSendable if chanSendable >= wait.amount => cnc.chan } match {
          case Some(anotherCapableChan) if wait.localAttempts < maxLocal => become(data.copy(parts = data.parts + wait.oneMoreLocalAttempt(anotherCapableChan).tuple), PENDING)
          case _ => assignToChans(master.currentSendableExcept(wait.chan), data.withoutPartId(wait.partId).withLocalFailure(wait.flight.get.route, PEER_COULD_NOT_PARSE_ONION), wait.amount)
        }
      }

    case (fail: FailAndAdd, PENDING) =>
      data.parts.values collectFirst { case wait @ WaitForRouteOrInFlight(partId, _, _, Some(info), _, _) if partId == fail.partId =>
        Sphinx.FailurePacket.decrypt(packet = fail.theirFail.reason, info.cmd.packetAndSecrets.sharedSecrets) map {
          case finalPkt: Sphinx.DecryptedFailurePacket if finalPkt.originNode == data.cmd.targetNodeId =>
            me abortAndNotify data.withoutPartId(partId).withRemoteFailure(info.route, finalPkt)

          case pkt @ Sphinx.DecryptedFailurePacket(nodeId, failure: Update) =>
            val isSignatureFine = Announcements.checkSig(failure.update, nodeId)
            val data1 = data.withRemoteFailure(info.route, pkt)
            // Updates known/enabled, removes disabled
            master.cm.pf process failure.update

            (isSignatureFine, info.route.getEdgeForNode(nodeId).get) match {
              case true \ edge if edge.update.shortChannelId != failure.update.shortChannelId =>
                // This is fine: remote node has used a different channel than the one we have initially requested
                // But remote node may send such errors infinitely so increment this specific type of failure
                master doProcess ChannelFailed(edge.toDescAndCapacity, increment = 1)
                master doProcess NodeFailed(failedNodeId = nodeId, increment = 1)
                resolveRemoteFail(data1, wait)

              case true \ edge if edge.update.core == failure.update.core =>
                // Remote node returned the same update we used, channel is most likely imbalanced
                // Note: we may have it disabled and new update comes enabled: still same update
                master doProcess ChannelFailed(edge.toDescAndCapacity, increment = 1)
                resolveRemoteFail(data1, wait)

              case true \ _ =>
                // New update is enabled: refreshed in graph, not penalized here
                // New update is disabled: removed from graph, not penalized here
                resolveRemoteFail(data1, wait)

              case false \ _ =>
                // Invalid sig is a severe violation, ban sender node
                master doProcess NodeFailed(nodeId, TOO_MANY_TIMES)
                resolveRemoteFail(data1, wait)
            }

          case pkt @ Sphinx.DecryptedFailurePacket(nodeId, _: Node) =>
            master doProcess NodeFailed(nodeId, increment = TOO_MANY_TIMES)
            resolveRemoteFail(data.withRemoteFailure(info.route, pkt), wait)

          case pkt @ Sphinx.DecryptedFailurePacket(nodeId, _) =>
            // A non-specific failure, ignore channel; note that we are guaranteed to find a failed edge for returned nodeId
            master doProcess ChannelFailed(info.route.getEdgeForNode(nodeId).get.toDescAndCapacity, increment = TOO_MANY_TIMES)
            resolveRemoteFail(data.withRemoteFailure(info.route, pkt), wait)

        } getOrElse {
          val failure = UnreadableRemoteFailure(info.route)
          val nodesInBetween = info.route.hops.map(_.desc.b).drop(1).dropRight(1)

          if (nodesInBetween.isEmpty) {
            // Garbage is sent by our peer or final payee, fail a payment
            val data1 = data.copy(failures = failure +: data.failures)
            me abortAndNotify data1.withoutPartId(partId)
          } else {
            // We don't know which exact remote node is sending garbage, exclude a random one
            master doProcess NodeFailed(shuffle(nodesInBetween).head, increment = TOO_MANY_TIMES)
            resolveRemoteFail(data.copy(failures = failure +: data.failures), wait)
          }
        }
      }

    case (split: HalveSplit, PENDING) =>
      val partOne: MilliSatoshi = split.amount / 2
      val partTwo: MilliSatoshi = split.amount - partOne
      // Must be run sequentially as these methods mutate data
      assignToChans(master.currentSendable, data, partOne)
      assignToChans(master.currentSendable, data, partTwo)

    case _ =>
  }

  def isFinalized: Boolean = ABORTED == state || SUCCEEDED == state
  def randomId: ByteVector = ByteVector.view(Tools.random getBytes 8)
  def canBeSplit(totalAmount: MilliSatoshi): Boolean = totalAmount / 2 > master.cm.pf.routerConf.mppMinPartAmount
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
          val wait = WaitForRouteOrInFlight(randomId, finalAmount, cnc.chan, None)
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

      case _ \ rest if master.canSendInPrinciple - master.canSendNow >= rest =>
        // Amount has not been fully split, but can be once some channel becomes OPEN
        // Instead of partial-sending a whole part is set out to wait for better local conditions
        become(data1.copy(parts = data1.parts + WaitForBetterConditions(randomId, amt).tuple), PENDING)

      case _ =>
        // A non-zero leftover is present with no more channels left
        // partId should already have been removed upstream at this point
        me abortAndNotify data1.withLocalFailure(NOROUTE, NOT_ENOUGH_CAPACITY)
    }
  }

  private def resolveRemoteFail(data1: PaymentSenderData, wait: WaitForRouteOrInFlight): Unit =
    master.currentSendable collectFirst { case cnc \ chanSendable if chanSendable >= wait.amount => cnc.chan } match {
      case Some(chan) if wait.remoteAttempts < maxRemote => become(data.copy(parts = data.parts + wait.oneMoreRemoteAttempt(chan).tuple), PENDING)
      case _ if canBeSplit(wait.amount) => become(data.withoutPartId(wait.partId), PENDING) doProcess HalveSplit(wait.amount)
      case _ => me abortAndNotify data.withoutPartId(wait.partId).withLocalFailure(NOROUTE, RUN_OUT_OF_RETRY_ATTEMPTS)
    }

  private def abortAndNotify(data1: PaymentSenderData): Unit = {
    // An idempotent transition, fires a failure event once no more in-flight parts are left
    if (data1.inFlights.isEmpty) master.cm.events.outgoingFailed(data1)
    become(data1, ABORTED)
  }
}

class PaymentMaster(val cm: ChannelMaster) extends StateMachine[PaymentMasterData] with CanBeRepliedTo with ChannelListener { me =>
  implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def process(changeMessage: Any): Unit = scala.concurrent.Future(me doProcess changeMessage)
  become(PaymentMasterData(Map.empty), EXPECTING_PAYMENTS)
  // Listen to NotifyOperational events
  cm.pf.listeners += me

  def doProcess(change: Any): Unit = (change, state) match {
    case (cmd: CMD_SEND_MPP, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      // Forget previous failures if this is a new round of payment sending
      val noPendingPaymentsLeft = data.payments.values.forall(_.isFinalized)
      if (noPendingPaymentsLeft) become(data.forgetFailures, state)
      // Make pathfinder aware of payee-provided routing hints
      for (edge <- cmd.assistedEdges) cm.pf process edge
      relayOrCreateSender(cmd.paymentHash, cmd)
      me process CMDAskForRoute

    case (CMDChanGotOnline, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      // Payments may still have awaiting parts due to offline channels
      data.payments.values.foreach(_ doProcess CMDChanGotOnline)
      me process CMDAskForRoute

    case (CMDAskForRoute | PathFinder.NotifyOperational, EXPECTING_PAYMENTS) =>
      // This is a proxy to always send command in payment master thread
      // IMPLICIT GUARD: this message is ignored in other states
      data.payments.values.foreach(_ doProcess CMDAskForRoute)

    case (req: RouteRequest, EXPECTING_PAYMENTS) =>
      // IMPLICIT GUARD: ignore in other states, payment will be able to re-send later
      val currentUsedCapacities: mutable.Map[DescAndCapacity, MilliSatoshi] = getUsedCapacities
      val currentUsedDescs: mutable.Map[ChannelDesc, MilliSatoshi] = for (dac \ used <- currentUsedCapacities) yield dac.desc -> used
      val ignoreChansFailedTimes = data.chanFailedTimes collect { case desc \ failTimes if failTimes >= cm.pf.routerConf.maxChannelFailures => desc }
      val ignoreChansCanNotHandle = currentUsedCapacities collect { case DescAndCapacity(desc, capacity) \ used if used + req.amount >= capacity => desc }
      val ignoreChansFailedAtAmount = data.chanFailedAtAmount collect { case desc \ failedAt if failedAt - currentUsedDescs(desc) - req.reserve <= req.amount => desc }
      val ignoreNodes = data.nodeFailedWithUnknownUpdateTimes collect { case nodeId \ failTimes if failTimes >= cm.pf.routerConf.maxStrangeNodeFailures => nodeId }
      val ignoreChans = ignoreChansFailedTimes.toSet ++ ignoreChansCanNotHandle ++ ignoreChansFailedAtAmount
      val request1 = req.copy(ignoreNodes = ignoreNodes.toSet, ignoreChannels = ignoreChans)
      cm.pf process Tuple2(me, request1)
      become(data, WAITING_FOR_ROUTE)

    case (PathFinder.NotifyRejected, WAITING_FOR_ROUTE) =>
      // Pathfinder is not yet ready, switch local state back
      // pathfinder is expected to notify us once it gets ready
      become(data, EXPECTING_PAYMENTS)

    case (response: RouteResponse, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      data.payments.get(response.paymentHash).foreach(_ doProcess response)
      // Switch state to allow new route requests to come through
      become(data, EXPECTING_PAYMENTS)
      me process CMDAskForRoute

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
      me process CMDAskForRoute

    case (fulfill: UpdateFulfillHtlc, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      relayOrCreateSender(fulfill.paymentHash, fulfill)
      me process CMDAskForRoute

    case (fail: RemoteReject, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      relayOrCreateSender(fail.ourAdd.paymentHash, fail)
      me process CMDAskForRoute

    case _ =>
  }

  // Executed in channelContext
  override def stateUpdated(hc: HostedCommits): Unit = {
    hc.localSpec.remoteMalformed.foreach(process)
    hc.localSpec.remoteFailed.foreach(process)
  }

  override def fulfillReceived(fulfill: UpdateFulfillHtlc): Unit = me process fulfill
  override def onException: PartialFunction[Malfunction, Unit] = { case (_, error: CMDAddImpossible) => me process error }
  override def onBecome: PartialFunction[Transition, Unit] = { case (_, _, SLEEPING | SUSPENDED, OPEN) => me process CMDChanGotOnline }

  private def relayOrCreateSender(paymentHash: ByteVector32, msg: Any): Unit = data.payments.get(paymentHash) match {
    case None => withSender(new PaymentSender(me), paymentHash, msg) // Can happen after restart with leftoverts in channels
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

  // What can be sent after fees and in-flight payments are taken into account
  def canSendNow: MilliSatoshi = getSendable(cm.all filter isOperationalAndOpen).values.sum
  def canSendInPrinciple: MilliSatoshi = getSendable(cm.all filter isOperational).values.sum

  def currentSendable: mutable.Map[ChanAndCommits, MilliSatoshi] = {
    val channels = cm.all.filter(isOperationalAndOpen)
    getSendable(channels)
  }

  def currentSendableExcept(chan: HostedChannel): mutable.Map[ChanAndCommits, MilliSatoshi] = {
    val channels = cm.all.diff(chan :: Nil).filter(isOperationalAndOpen)
    getSendable(channels)
  }

  // This gets what can be sent through given channels with waiting parts taken into account
  def getSendable(chans: Vector[HostedChannel] = Vector.empty): mutable.Map[ChanAndCommits, MilliSatoshi] = {
    val waits: mutable.Map[HostedChannel, MilliSatoshi] = mutable.Map.empty[HostedChannel, MilliSatoshi] withDefaultValue 0L.msat
    val finals: mutable.Map[ChanAndCommits, MilliSatoshi] = mutable.Map.empty[ChanAndCommits, MilliSatoshi] withDefaultValue 0L.msat

    data.payments.values.flatMap(_.data.parts.values) collect { case wait: WaitForRouteOrInFlight => waits(wait.chan) += wait.amount }
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
