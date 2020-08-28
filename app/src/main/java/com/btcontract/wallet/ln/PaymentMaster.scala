package com.btcontract.wallet.ln

import fr.acinq.eclair._
import com.btcontract.wallet.ln.crypto.Tools._
import com.btcontract.wallet.ln.PaymentMaster._
import com.btcontract.wallet.ln.PaymentFailure._
import fr.acinq.eclair.{CltvExpiry, MilliSatoshi}
import fr.acinq.eclair.router.{Announcements, RouteCalculation}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import fr.acinq.eclair.wire.{Node, Onion, Update, UpdateFulfillHtlc}
import com.btcontract.wallet.ln.ChannelListener.{Malfunction, Transition}
import com.btcontract.wallet.ln.HostedChannel.{OPEN, SLEEPING, SUSPENDED}
import fr.acinq.eclair.router.Graph.GraphStructure.{DescAndCapacity, GraphEdge}
import com.btcontract.wallet.ln.crypto.{CMDAddImpossible, CanBeRepliedTo, StateMachine, Tools}
import fr.acinq.eclair.router.Router.{ChannelDesc, NoRouteAvailable, Route, RouteFound, RouteRequest, RouteResponse}
import com.btcontract.wallet.ln.HostedChannel.isOperationalAndOpen
import fr.acinq.eclair.payment.OutgoingPacket
import fr.acinq.eclair.channel.CMD_ADD_HTLC
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
  final val NOROUTE = Route(Vector.empty, Nil)
  final val TOO_MANY_TIMES = 1000
}


sealed trait PaymentFailure { def route: Route }
case class LocalFailure(route: Route, errorStatus: Int) extends PaymentFailure
case class RemoteFailure(route: Route, packet: Sphinx.DecryptedFailurePacket) extends PaymentFailure
case class UnreadableRemoteFailure(route: Route) extends PaymentFailure


sealed trait PartStatus {
  def tuple = Tuple2(partId, this)
  def partId: ByteVector
}

case class WaitForChannelToOpen(partId: ByteVector, amount: MilliSatoshi) extends PartStatus
case class WaitForRoute(partId: ByteVector, targetChannel: HostedChannel, amount: MilliSatoshi) extends PartStatus
case class InFlight(partId: ByteVector, targetChannel: HostedChannel, route: Route, cmd: CMD_ADD_HTLC) extends PartStatus {
  lazy val payeeAmount: MilliSatoshi = route.amounts.last
}

case class PaymentSenderData(cmd: CMD_SEND_MPP,
                             localAttemptsLeft: Int,
                             remoteAttemptsLeft: Map[ByteVector, Int],
                             parts: Map[ByteVector, PartStatus] = Map.empty,
                             failures: Vector[PaymentFailure] = Vector.empty) {

  def withLocalFailure(route: Route, reason: Int): PaymentSenderData = copy(failures = LocalFailure(route, reason) +: failures)
  def withRemoteFailure(route: Route, pkt: Sphinx.DecryptedFailurePacket): PaymentSenderData = copy(failures = RemoteFailure(route, pkt) +: failures)
  def oneLessLocalAttempt(partId: ByteVector): PaymentSenderData = copy(localAttemptsLeft = localAttemptsLeft - 1, parts = parts - partId)
}

case class PaymentMasterData(payments: Map[ByteVector32, PaymentSender],
                             chanFailedAtAmount: Map[ChannelDesc, MilliSatoshi] = Map.empty withDefaultValue Long.MaxValue.msat,
                             nodeFailedWithUnknownUpdateTimes: Map[PublicKey, Int] = Map.empty withDefaultValue 0,
                             chanFailedTimes: Map[ChannelDesc, Int] = Map.empty withDefaultValue 0)

case class HalveSplit(amount: MilliSatoshi)
case class NodeFailed(failedNodeId: PublicKey, increment: Int)
case class ChannelFailed(failedDescAndCap: DescAndCapacity, increment: Int)
case class CMD_SEND_MPP(paymentHash: ByteVector32, paymentSecret: ByteVector32, targetNodeId: PublicKey,
                        totalAmount: MilliSatoshi, assistedEdges: Set[GraphEdge], targetExpiry: CltvExpiry)


object PaymentMaster {
  val INIT = "state-init"
  val PENDING = "state-pending"
  val ABORTED = "state-aborted"
  val SUCCEEDED = "state-succeeded"
  val EXPECTING_PAYMENTS = "state-expecting-payments"
  val WAITING_FOR_ROUTE = "state-waiting-for-route"
  val CMDChanGotOnline = "cmd-chan-got-online"
  val CMDAskForRoute = "cmd-ask-for-route"

  type RefinedSendables = mutable.Map[ChanAndCommits, MilliSatoshi]
}

class PaymentSender(master: PaymentMaster) extends StateMachine[PaymentSenderData] { me =>
  // No separate stacking thread, all calles are executed within PaymentMaster context
  become(null, INIT)

  def doProcess(msg: Any): Unit = (msg, state) match {
    case (_: UpdateFulfillHtlc, INIT | PENDING | ABORTED) =>
      // Stop reacting to any incoming messages
      become(data, SUCCEEDED)

    case (cmd: CMD_SEND_MPP, INIT) =>
      val localLeft = master.cm.pf.routerConf.maxLocalAttempts
      val remotePerPartLeft = master.cm.pf.routerConf.maxRemoteAttemptsPerPart
      val data = PaymentSenderData(cmd, localLeft, Map.empty withDefaultValue remotePerPartLeft)
      assignToChans(master.refinedSendables(Nil), data, cmd.totalAmount)

    case (CMDAskForRoute, PENDING) =>
      data.parts.values collectFirst { case WaitForRoute(partId, chan, amt) =>
        val routeParams = RouteCalculation.getDefaultRouteParams(master.cm.pf.routerConf)
        master doProcess RouteRequest(data.cmd.paymentHash, partId, LNParams.keys.routingPubKey,
          data.cmd.targetNodeId, amt, routeParams.getMaxFee(amt), chan.fakeEdge, routeParams)
      }

    case (CMDChanGotOnline, PENDING) =>
      data.parts.values collectFirst { case WaitForChannelToOpen(partId, amt) =>
        assignToChans(master.refinedSendables(Nil), data.copy(parts = data.parts - partId), amt)
      }

    case (fail: NoRouteAvailable, PENDING) =>
      data.parts.values collectFirst { case WaitForRoute(partId, chan, amt) if partId == fail.partId =>
        // It may happen that failed payment is too small for a split, attept to send it through other channel then
        if (me canBeSplit amt) become(data.copy(parts = data.parts - partId), PENDING) doProcess HalveSplit(amt)
        else assignToChans(master.refinedSendables(chan :: Nil), data.oneLessLocalAttempt(partId), amt)
      }

    case (found: RouteFound, PENDING) =>
      data.parts.values collectFirst { case WaitForRoute(partId, chan, amt) if partId == found.partId =>
        val finalPayload = Onion.createMultiPartPayload(amt, data.cmd.totalAmount, data.cmd.targetExpiry, data.cmd.paymentSecret)
        val cmdAddNewHtlc = OutgoingPacket.buildCommand(partId, data.cmd.paymentHash, found.route.hops, finalPayload)
        val data1 = data.copy(parts = data.parts + InFlight(partId, chan, found.route, cmdAddNewHtlc).tuple)
        chan process cmdAddNewHtlc
        become(data1, PENDING)
      }

    case (error: CMDAddImpossible, PENDING) =>
      data.parts.values collectFirst { case wait @ InFlight(partId, chan, route, _) if partId == error.cmd.internalId =>
        // Channel seemed fine before route request but failed on actual adding of payment, retry once again unless we have run out of local retry limit
        // in case of running out of local limit we also remove `InFlight` status from parts map, an ABORTED payment without `InFlight` parts can be retried
        if (data.localAttemptsLeft > 0) assignToChans(master.refinedSendables(chan :: Nil), data.oneLessLocalAttempt(partId), wait.payeeAmount)
        else become(data.withLocalFailure(route, RUN_OUT_OF_RETRY_ATTEMPTS), ABORTED)
      }

    case (malform: MalformAndAdd, PENDING) =>
      data.parts.values collectFirst { case wait @ InFlight(partId, chan, route, _) if partId == malform.internalId =>
        // We still want to try to maybe route this payment through other local channel if this one is malicious
        val data1 = data.copy(parts = data.parts - partId).withLocalFailure(route, PEER_COULD_NOT_PARSE_ONION)
        assignToChans(master.refinedSendables(chan :: Nil), data1, wait.payeeAmount)
      }

    case (fail: FailAndAdd, PENDING) =>
      data.parts.values collectFirst { case wait @ InFlight(partId, _, route, cmd) if partId == fail.internalId =>
        Sphinx.FailurePacket.decrypt(fail.theirFail.reason, cmd.packetAndSecrets.sharedSecrets) map {
          case pkt: Sphinx.DecryptedFailurePacket if pkt.originNode == data.cmd.targetNodeId =>
            // Final recipient has rejected a payment, halt and do not try again
            become(data.withRemoteFailure(route, pkt), ABORTED)

          case pkt @ Sphinx.DecryptedFailurePacket(nodeId, failure: Update) =>
            val sigFine = Announcements.checkSig(failure.update, nodeId)
            val data1 = data.withRemoteFailure(route, pkt)
            // Updates known/enabled, removes disabled
            master.cm.pf process failure.update

            (sigFine, route.getEdgeForNode(nodeId).get) match {
              case true \ edge if edge.update.shortChannelId != failure.update.shortChannelId =>
                // This is fine: remote node has used a different channel than the one we have initially requested
                // But remote node may send such errors infinitely so increment this specific type of failure
                master doProcess ChannelFailed(edge.toDescAndCapacity, increment = 1)
                master doProcess NodeFailed(failedNodeId = nodeId, increment = 1)
                resolveRouteFail(data1, wait)

              case true \ edge if Announcements.areSame(edge.update, failure.update) =>
                // Remote node returned the same update we used, channel is most likely imbalanced
                // Note: we may have it disabled and new update comes enabled: still same update
                master doProcess ChannelFailed(edge.toDescAndCapacity, increment = 1)
                resolveRouteFail(data1, wait)

              case true \ _ =>
                // New update is enabled: refreshed in graph, not penalized here
                // New update is disabled: removed from graph, not penalized here
                resolveRouteFail(data1, wait)

              case false \ _ =>
                // Invalid sig is a severe violation, ban sender node
                master doProcess NodeFailed(nodeId, TOO_MANY_TIMES)
                resolveRouteFail(data1, wait)
            }

          case pkt @ Sphinx.DecryptedFailurePacket(nodeId, _: Node) =>
            master doProcess NodeFailed(nodeId, increment = TOO_MANY_TIMES)
            resolveRouteFail(data.withRemoteFailure(route, pkt), wait)

          case pkt @ Sphinx.DecryptedFailurePacket(nodeId, _) =>
            // A non-specific failure, ignore channel, we are guaranteed to find a failed edge for returned nodeId
            master doProcess ChannelFailed(route.getEdgeForNode(nodeId).get.toDescAndCapacity, increment = TOO_MANY_TIMES)
            resolveRouteFail(data.withRemoteFailure(route, pkt), wait)

        } getOrElse {
          // Note: this aborts a payment if our peer or final recipient is sending garbage
          val data1 = data.copy(failures = UnreadableRemoteFailure(route) +: data.failures)
          val nodesInBetween = route.hops.map(_.desc.b).drop(1).dropRight(1)

          if (nodesInBetween.nonEmpty) {
            val unluckyNodeId = shuffle(nodesInBetween).head
            // We don't know which exact remote node is sending garbage, exclude a random one
            master doProcess NodeFailed(failedNodeId = unluckyNodeId, increment = TOO_MANY_TIMES)
            resolveRouteFail(data1, wait)
          } else become(data1, ABORTED)
        }
      }

    case (split: HalveSplit, PENDING) =>
      val partOne: MilliSatoshi = split.amount / 2
      val partTwo: MilliSatoshi = split.amount - partOne
      // Must be run sequentially as these methods mutate data
      assignToChans(master.refinedSendables(Nil), data, partOne)
      assignToChans(master.refinedSendables(Nil), data, partTwo)

    case _ =>
  }

  def canBeSplit(amt: MilliSatoshi): Boolean = {
    amt / 2 > master.cm.pf.routerConf.mppMinPartAmount
  }

  private def randomId: ByteVector = ByteVector.view(Tools.random getBytes 8)
  private def assignToChans(refined: RefinedSendables, data1: PaymentSenderData, amt: MilliSatoshi): Unit = {
    // Randomly splits an amount if necessary, omits channels where split would be zero or too small, updates state machine once completed
    // assigns WaitForRoute for each part or WaitForChannelToOpen for total amount if split fails but can be resumed on some channel becoming OPEN

    refined.foldLeft(Map.empty[ByteVector, PartStatus] -> amt) {
      case (collected @ (accumulator, leftover), cnc \ refinedSendable) if leftover > MilliSatoshi(0L) =>
        val minSendable = cnc.commits.lastCrossSignedState.initHostedChannel.htlcMinimumMsat
        val assignedAmt = refinedSendable min leftover

        if (assignedAmt >= minSendable) {
          // Only attepmt channels which definitely can handle a given amount
          // check this here to avoid zero/micro amounts which can be problematic
          val wait = WaitForRoute(randomId, cnc.chan, assignedAmt).tuple
          (accumulator + wait, leftover - assignedAmt)
        } else collected

      case collected \ _ =>
        // No more amount to assign
        // Propagate what's collected
        collected

    } match {
      case parts \ MilliSatoshi(0L) =>
        // Amount has been fully split across our local channels
        become(data1.copy(parts = data1.parts ++ parts), state)

      case _ \ rest if master.cm.canAlsoSendOnceChanOpens >= rest =>
        // Amount has not been fully split, but can be once some channel becomes OPEN
        // Instead of partial-send right now a whole part is set to wait for better conditions
        become(data1.copy(parts = data1.parts + WaitForChannelToOpen(randomId, amt).tuple), state)

      case _ =>
        // A non-zero leftover is present with no more good channels left
        become(data1.withLocalFailure(NOROUTE, NOT_ENOUGH_CAPACITY), ABORTED)
    }
  }

  private def resolveRouteFail(data1: PaymentSenderData, wait: InFlight): Unit =
    // When payment is failed remotely we make a number of attempts to re-send it as a whole before splitting it into smaller parts
    master.refinedSendables(Nil) collectFirst { case cnc \ refinedSendable if refinedSendable >= wait.payeeAmount => cnc.chan } match {

      case Some(newChan) if data1.remoteAttemptsLeft(wait.partId) > 0 =>
        // A channel which can handle a whole amount exists and we still have remote attempts left for this specific part
        val remoteAttemptsLeft1 = data1.remoteAttemptsLeft.updated(wait.partId, data1.remoteAttemptsLeft(wait.partId) - 1)
        val parts1 = data1.parts + WaitForRoute(wait.partId, targetChannel = newChan, wait.payeeAmount).tuple
        become(data1.copy(remoteAttemptsLeft = remoteAttemptsLeft1, parts = parts1), state)

      case _ if canBeSplit(wait.payeeAmount) =>
        // No channel or run out of remote attempts so we further split this part to boost its chances
        become(data1.copy(parts = data1.parts - wait.partId), state) doProcess HalveSplit(wait.payeeAmount)

      case _ =>
        // No channel or run out of remote attempts and this part is not splittable
        become(data1.withLocalFailure(wait.route, RUN_OUT_OF_RETRY_ATTEMPTS), ABORTED)
    }
}

class PaymentMaster(val cm: ChannelMaster) extends StateMachine[PaymentMasterData] with CanBeRepliedTo with ChannelListener { me =>
  implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def process(changeMessage: Any): Unit = scala.concurrent.Future(me doProcess changeMessage)
  become(PaymentMasterData(Map.empty), EXPECTING_PAYMENTS)

  def doProcess(change: Any): Unit = (change, state) match {
    case (cmd: CMD_SEND_MPP, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      // Enrich graph with assisted edges so it can find paths to destination
      for (extraEdge <- cmd.assistedEdges) cm.pf process extraEdge
      makeSender(new PaymentSender(me), cmd)
      me process CMDAskForRoute

    case (CMDChanGotOnline, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      // Payments may have awaiting parts due to offline channels
      relayToAll(CMDChanGotOnline)
      me process CMDAskForRoute

    case (CMDAskForRoute, EXPECTING_PAYMENTS) =>
      // IMPLICIT GUARD: ignore in other states
      relayToAll(CMDAskForRoute)

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

    case (response: RouteResponse, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      // Switch state to allow new route requests to come through
      relayTo(response.paymentHash, response)
      become(data, EXPECTING_PAYMENTS)
      me process CMDAskForRoute

    case (ChannelFailed(descAndCapacity, increment), EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      // At this point an affected InFlight status IS STILL PRESENT so failedAtAmount = sum(inFlight)
      val newFailedAtAmount = data.chanFailedAtAmount(descAndCapacity.desc) min getUsedCapacities(descAndCapacity)
      val atTimes1 = data.chanFailedTimes.updated(descAndCapacity.desc, data.chanFailedTimes(descAndCapacity.desc) + increment)
      val atAmount1 = data.chanFailedAtAmount.updated(descAndCapacity.desc, newFailedAtAmount)
      become(data.copy(chanFailedAtAmount = atAmount1, chanFailedTimes = atTimes1), state)

    case (NodeFailed(nodeId, increment), EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      val newNodeFailedTimes = data.nodeFailedWithUnknownUpdateTimes(nodeId) + increment
      val atTimes1 = data.nodeFailedWithUnknownUpdateTimes.updated(nodeId, newNodeFailedTimes)
      become(data.copy(nodeFailedWithUnknownUpdateTimes = atTimes1), state)

    case (fulfill: UpdateFulfillHtlc, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      relayTo(fulfill.paymentHash, fulfill)
      me process CMDAskForRoute

    case (chanError: CMDAddImpossible, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      relayTo(chanError.cmd.paymentHash, chanError)
      me process CMDAskForRoute

    case (malform: MalformAndAdd, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      relayTo(malform.ourAdd.paymentHash, malform)
      me process CMDAskForRoute

    case (fail: FailAndAdd, EXPECTING_PAYMENTS | WAITING_FOR_ROUTE) =>
      relayTo(fail.ourAdd.paymentHash, fail)
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

  private def relayToAll(changeMessage: Any): Unit = data.payments.values.foreach(_ doProcess changeMessage)
  private def relayTo(hash: ByteVector32, change: Any): Unit = data.payments.get(hash).foreach(_ doProcess change)

  private def makeSender(sender: PaymentSender, cmd: CMD_SEND_MPP): Unit = {
    val payments1 = data.payments.updated(cmd.paymentHash, sender)
    become(data.copy(payments = payments1), state)
    sender doProcess cmd
  }

  def getUsedCapacities: mutable.Map[DescAndCapacity, MilliSatoshi] = {
    // This gets supposedly used capacities of external channels in a routing graph
    // we need this to exclude channels which definitely can't route a given amount right now
    val accum = mutable.Map.empty[DescAndCapacity, MilliSatoshi] withDefaultValue 0L.msat

    for {
      sender <- data.payments.values
      InFlight(_, _, route, _) <- sender.data.parts.values
      amount \ descAndCapacity <- route.amountPerDescAndCap
    } accum(descAndCapacity) += amount
    accum
  }

  def refinedSendables(except: List[HostedChannel] = Nil): RefinedSendables = {
    // This gets what can be sent through each local OPEN channel right now given that WaitForRoute parts may be present
    val waits: mutable.Map[HostedChannel, MilliSatoshi] = mutable.Map.empty[HostedChannel, MilliSatoshi] withDefaultValue 0L.msat
    val finals: mutable.Map[ChanAndCommits, MilliSatoshi] = mutable.Map.empty[ChanAndCommits, MilliSatoshi] withDefaultValue 0L.msat

    // (1) Collect good channels (2) collect waitAmount for each (3) balance - waitAmount
    val cncs = shuffle(cm.all).filter(isOperationalAndOpen).filterNot(except.contains).flatMap(_.chanAndCommitsOpt)
    data.payments.values.flatMap(_.data.parts.values) collect { case WaitForRoute(_, chan, amt) => waits(chan) += amt }
    for (chanAndCommits <- cncs) finals(chanAndCommits) = cm.maxSendable(chanAndCommits) - waits(chanAndCommits.chan)
    finals
  }
}
