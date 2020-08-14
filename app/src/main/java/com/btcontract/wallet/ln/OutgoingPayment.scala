package com.btcontract.wallet.ln

import fr.acinq.eclair._
import com.softwaremill.quicklens._
import com.btcontract.wallet.ln.crypto.Tools._
import com.btcontract.wallet.ln.ChanErrorCodes._
import com.btcontract.wallet.ln.OutgoingPayment._

import scodec.bits.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.crypto.Sphinx
import java.util.concurrent.Executors
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.channel.CMD_PROCEED
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.crypto.Sphinx.PacketAndSecrets
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop

import fr.acinq.eclair.payment.{LocalFailure, OutgoingPacket, PaymentFailure, RemoteFailure, UnreadableRemoteFailure}
import fr.acinq.eclair.router.Router.{Ignore, Route, RouteParams, RouteRequest, RouteResponse}
import com.btcontract.wallet.ln.crypto.{CMDAddImpossible, CanBeRepliedTo, StateMachine}
import fr.acinq.eclair.wire.{ChannelUpdate, Onion, Update, UpdateFulfillHtlc}
import fr.acinq.eclair.{CltvExpiry, MilliSatoshi}
import scala.util.{Failure, Success}


object OutgoingPayment {
  type ExtraHops = Seq[ExtraHop]
  val WAITING_FOR_DETAILS = "waiting-for-details"
  val WAITING_FOR_ROUTE_REQUEST = "waiting-for-route-request"
  val WAITING_FOR_ROUTE_RESPONSE = "waiting-for-route-request"
  val PROCESSING_PAYMENT = "processing-payment"
  val PAYMENT_DONE = "payment-done"
}

case class RouteMeta(route: Route, cltvExpiry: CltvExpiry, ps: PacketAndSecrets)
object FailedWhileDone extends RuntimeException("Payment failed in cancelled state")
object PeerCouldNotParseOnion extends RuntimeException("Peer could not parse an onion")
object RunOutOfRetryAttempts extends RuntimeException("Run out of retry attempts")

sealed trait OutgoingPaymentData {
  val pending: Map[ByteVector, RouteMeta]
  val failures: Vector[PaymentFailure]
}

case class PaymentDoneData(pending: Map[ByteVector, RouteMeta], failures: Vector[PaymentFailure], isFulfilled: Boolean) extends OutgoingPaymentData
case class PaymentProgressData(request: CMD_SEND_MPP, attempts: Int, pending: Map[ByteVector, RouteMeta], ignore: Ignore, failures: Vector[PaymentFailure] = Vector.empty) extends OutgoingPaymentData
case class CMD_SEND_MPP(paymentSecret: ByteVector32, targetNodeId: PublicKey, totalAmount: MilliSatoshi, targetExpiry: CltvExpiry, assistedRoutes: Seq[ExtraHops], routeParams: RouteParams)

class OutgoingPayment(paymentHash: ByteVector32, pf: PathFinder, cm: ChannelMaster) extends StateMachine[OutgoingPaymentData] with CanBeRepliedTo { me =>
  implicit val channelContext: scala.concurrent.ExecutionContextExecutor = scala.concurrent.ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def process(changeMessage: Any): Unit = scala.concurrent.Future(me doProcess changeMessage)
  become(null, WAITING_FOR_DETAILS)

  def doProcess(change: Any): Unit =
    Tuple3(data, change, state) match {
      case (null, done: PaymentDoneData, WAITING_FOR_DETAILS) =>
        // Used to gracefully fail remaining payments upon restart
        become(done.copy(isFulfilled = false), PAYMENT_DONE)

      case (null, request: CMD_SEND_MPP, WAITING_FOR_DETAILS) =>
        val initProgress = PaymentProgressData(request, pf.routerConf.searchAttempts, pending = Map.empty, Ignore.empty)
        val routeRequest = makeRequest(request.totalAmount, request.routeParams.getMaxFee(request.totalAmount), initProgress)
        askForRoutes(routeRequest, initProgress)

      case (progress: PaymentProgressData, Success(response: RouteResponse), WAITING_FOR_ROUTE_RESPONSE) =>
        val CMD_SEND_MPP(paymentSecret, _, totalAmount, targetExpiry, _, _) = progress.request
        val remainingAmount \ remainingMaxFee = remainingToSend(progress)

        if (response.routes.map(_.amount).sum != remainingAmount) {
          // Another payment has failed while we were waiting for routes, retry
          val routeRequest = makeRequest(remainingAmount, remainingMaxFee, progress)
          pf process Tuple2(me, routeRequest)
        } else {
          val chan2Cmd = for {
            route <- response.routes.par
            finalPayload = Onion.createMultiPartPayload(route.amount, totalAmount, targetExpiry, paymentSecret)
            cmdAddHtlc = OutgoingPacket.buildCommand(random getBytes 4, paymentHash, route.hops, finalPayload)
          } yield cm.findForRoute(route) -> cmdAddHtlc

          val chans \ finalProgress =
            response.routes.zip(chan2Cmd).foldLeft(Set.empty[HostedChannel] -> progress) {
              case (cs, progress1) \ Tuple2(paymentRoute, Some(targetChannel) \ cmdAddCommand) =>
                val meta = RouteMeta(paymentRoute, cmdAddCommand.cltvExpiry, cmdAddCommand.packetAndSecrets)
                val progress2 = progress1.modify(_.pending) setTo progress1.pending.updated(cmdAddCommand.internalId, meta)
                targetChannel process cmdAddCommand
                (cs + targetChannel, progress2)

              case csAndProgress \ Tuple2(_, None \ cmdAddCommand) =>
                me process CMDAddImpossible(cmdAddCommand, ERR_NOT_OPEN)
                csAndProgress
            }

          become(finalProgress, PROCESSING_PAYMENT)
          for (chan <- chans) chan doProcess CMD_PROCEED
        }

      case (PaymentProgressData(_, _, pending, _, failures), Failure(reason), WAITING_FOR_ROUTE_RESPONSE) =>
        val done = PaymentDoneData(pending, LocalFailure(Nil, reason) +: failures, isFulfilled = false)
        if (done.pending.isEmpty) cm.events.outgoingFailed(paymentHash)
        become(done, PAYMENT_DONE)

      case (progress: PaymentProgressData, routeRequest: RouteRequest, WAITING_FOR_ROUTE_REQUEST) =>
        // This will start a route search and will drop any subsequent request until this one is computed
        // also makes sure we can not keep asking for routes infinitely no matter the reason

        if (progress.attempts > 0) {
          val progress1 = progress.copy(attempts = progress.attempts - 1)
          become(progress1, WAITING_FOR_ROUTE_RESPONSE)
          pf process Tuple2(me, routeRequest)
        } else {
          val doneData = PaymentDoneData(progress.pending, progress.failures, isFulfilled = false)
          val doneData1 = updateWithLocalError(doneData, RunOutOfRetryAttempts, ByteVector.empty)
          if (doneData1.pending.isEmpty) cm.events.outgoingFailed(paymentHash)
          become(doneData1, PAYMENT_DONE)
        }

      case (progress: PaymentProgressData, CMDAddImpossible(cmd, _, _), PROCESSING_PAYMENT) =>
        val progress1 = progress.copy(pending = progress.pending - cmd.internalId)
        askForRoutes(makeRequest(progress1), progress1)

      case (PaymentProgressData(_, _, pending, _, failures), fulfill: UpdateFulfillHtlc, _) =>
        val doneData = PaymentDoneData(pending, failures, isFulfilled = true)
        cm.events.outgoingSucceeded(fulfill.paymentHash)
        become(doneData, PAYMENT_DONE)

      case (progress: PaymentProgressData, fail: MalformAndAdd, _) =>
        // We still want to try to maybe route this payment through other local channels
        val progress1 = updateWithLocalError(progress, PeerCouldNotParseOnion, fail.internalId)
        askForRoutes(makeRequest(progress1), progress1)

      case (progress: PaymentProgressData, fail: FailAndAdd, _) if progress.pending.contains(fail.internalId) =>
        Sphinx.FailurePacket.decrypt(fail.theirFail.reason, progress.pending(fail.internalId).ps.sharedSecrets) map {
          case anyErrorPacket: Sphinx.DecryptedFailurePacket if anyErrorPacket.originNode == progress.request.targetNodeId =>
            val PaymentProgressData(_, _, pending, _, failures) = updateWithRemoteError(progress, anyErrorPacket, fail.internalId)
            become(PaymentDoneData(pending, failures, isFulfilled = false), PAYMENT_DONE)

          case pkt @ Sphinx.DecryptedFailurePacket(nodeId, failureMessage: Update) =>
            val isEnabled = Announcements.isEnabled(failureMessage.update.channelFlags)
            val isSigValid = Announcements.checkSig(failureMessage.update, nodeId)

            // We need to correctly process these cases:
            // - private and enabled: updated in assited routes
            // - private and disabled: removed from assisted routes
            // - public and enabled: updated in graph and db
            // - public and disabled: removed from graph

            if (isSigValid) {
              val progress1 = progress.pending(fail.internalId).route.getChannelUpdateForNode(nodeId) match {
                case Some(oldChannelUpdate) if Announcements.areSame(oldChannelUpdate, failureMessage.update) => updateWithRemoteError(progress, pkt, fail.internalId) // This channel is exhaused // TODO: this is problematic if channel is assisted, we should not exclude it but attempt with smaller amount
                case Some(_) if PaymentFailure.hasAlreadyFailed(nodeId, progress.failures, pf.routerConf.nodeFailTolerance) => ignoreNode(progress, nodeId, fail.internalId) // Remote node acts strange
                case _ => updateWithRemoteErrorNoIgnore(progress, pkt, fail.internalId) // Do not ignore but keep failure in history
              }

              val assistedRoutes1 = if (isEnabled) {
                progress1.request.assistedRoutes map { route =>
                  // Update a single affected item in related assisted route if update is enabled
                  val index = route.indexWhere(_.shortChannelId == failureMessage.update.shortChannelId)
                  if (index < 0) route else route.updated(index, route(index) updateWith failureMessage.update)
                }
              } else {
                progress1.request.assistedRoutes filterNot { route =>
                  // Otherwise exclude a whole affected route if update is disabled
                  route.exists(_.shortChannelId == failureMessage.update.shortChannelId)
                }
              }

              pf process failureMessage.update
              // Bad update will be ignored next time, Enabled/Disabled will be added to or removed from graph
              // If update is related to assisted route then graph won't be affected but we'll have updated hints locally
              val progress2 = progress1.modify(_.request.assistedRoutes).setTo(assistedRoutes1)
              askForRoutes(makeRequest(progress2), progress2)
            } else {
              // Node has given us an invalid sig, this is clearly a violation
              val progress1 = ignoreNode(progress, nodeId, fail.internalId)
              askForRoutes(makeRequest(progress1), progress1)
            }

          case otherErrorPacket =>
            // Simply record the rest of errors and ask for a new routes until no more attempts left
            val progress1 = updateWithRemoteError(progress, otherErrorPacket, fail.internalId)
            askForRoutes(makeRequest(progress1), progress1)
        } getOrElse {
          // Blacklist all nodes except our direct peer and final recipient
          val progress1 = updateUnreadable(progress, fail.internalId)
          askForRoutes(makeRequest(progress1), progress1)
        }

      case (done: PaymentDoneData, fulfill: UpdateFulfillHtlc, PAYMENT_DONE) =>
        // We display payment as fulfilled on getting a preimage, pending may have leftovers, this is fine
        // also this may happen if we got preimage, closed an app, restarted and still have leftovers
        if (!done.isFulfilled) cm.events.outgoingSucceeded(fulfill.paymentHash)
        become(done.copy(isFulfilled = true), PAYMENT_DONE)

      case (done: PaymentDoneData, fail: MalformAndAdd, PAYMENT_DONE) =>
        val done1 = updateWithLocalError(done, PeerCouldNotParseOnion, fail.internalId)
        if (!done.isFulfilled && done1.pending.isEmpty) cm.events.outgoingFailed(paymentHash)
        become(done1, PAYMENT_DONE)

      case (done: PaymentDoneData, fail: FailAndAdd, PAYMENT_DONE) =>
        val done1 = updateWithLocalError(done, FailedWhileDone, fail.internalId)
        if (!done.isFulfilled && done1.pending.isEmpty) cm.events.outgoingFailed(paymentHash)
        become(done1, PAYMENT_DONE)

      case _ =>
    }

  private def ignoreNode(progress: PaymentProgressData, nodeId: PublicKey, internalId: ByteVector) =
    progress.copy(ignore = progress.ignore + nodeId, pending = progress.pending - internalId)

  private def updateUnreadable(progress: PaymentProgressData, internalId: ByteVector) = {
    val unreadable = UnreadableRemoteFailure(progress.pending.get(internalId).map(routeMeta => routeMeta.route.hops) getOrElse List.empty)
    progress.copy(failures = unreadable +: progress.failures, ignore = PaymentFailure.updateIgnored(unreadable, progress.ignore), pending = progress.pending - internalId)
  }

  // Put to failuers, but do not ignore: this makes hasAlreadyFailed work correctly because it looks at failure history
  // We don't ignore an update on first fail but instead send it to graph: disabled update will be removed, enabled one will be applied
  private def updateWithRemoteErrorNoIgnore(progress: PaymentProgressData, failurePacket: Sphinx.DecryptedFailurePacket, internalId: ByteVector) = {
    val remoteFailure = RemoteFailure(route = progress.pending.get(internalId).map(routeMeta => routeMeta.route.hops) getOrElse List.empty, failurePacket)
    progress.copy(failures = remoteFailure +: progress.failures, pending = progress.pending - internalId)
  }

  private def updateWithRemoteError(progress: PaymentProgressData, failurePacket: Sphinx.DecryptedFailurePacket, internalId: ByteVector) = {
    val remoteFailure = RemoteFailure(route = progress.pending.get(internalId).map(routeMeta => routeMeta.route.hops) getOrElse List.empty, failurePacket)
    progress.copy(failures = remoteFailure +: progress.failures, ignore = PaymentFailure.updateIgnored(remoteFailure, progress.ignore), pending = progress.pending - internalId)
  }

  private def updateWithLocalError(progress: PaymentProgressData, error: Throwable, internalId: ByteVector) = {
    val failure = LocalFailure(route = progress.pending.get(internalId).map(routeMeta => routeMeta.route.hops) getOrElse List.empty, error)
    progress.copy(failures = failure +: progress.failures, ignore = PaymentFailure.updateIgnored(failure, progress.ignore), pending = progress.pending - internalId)
  }

  private def updateWithLocalError(done: PaymentDoneData, error: Throwable, internalId: ByteVector) = {
    val failure = LocalFailure(done.pending.get(internalId).map(routeMeta => routeMeta.route.hops) getOrElse List.empty, error)
    done.copy(failures = failure +: done.failures, pending = done.pending - internalId)
  }

  private def askForRoutes(request: RouteRequest, progress: PaymentProgressData): Unit = {
    // An optimization to drop new route requests while we still waiting for an old route response to arrive
    become(progress, if (state == WAITING_FOR_ROUTE_RESPONSE) WAITING_FOR_ROUTE_RESPONSE else WAITING_FOR_ROUTE_REQUEST)
    me process request
  }

  private def makeRequest(progress: PaymentProgressData): RouteRequest = {
    val remainingAmount \ remainingMaxFee = remainingToSend(progress)
    makeRequest(remainingAmount, remainingMaxFee, progress)
  }

  private def makeRequest(amount: MilliSatoshi, fees: MilliSatoshi, prog: PaymentProgressData): RouteRequest =
    RouteRequest(LNParams.keys.ourRoutingSourceNodeId, prog.request.targetNodeId, amount, fees, prog.request.assistedRoutes,
      prog.ignore, prog.request.routeParams, prog.pending.values.map(_.route).toSeq)

  private def remainingToSend(progress: PaymentProgressData): (MilliSatoshi, MilliSatoshi) = {
    val amount = progress.request.totalAmount - progress.pending.values.map(_.route.amount).sum
    val maxFee = progress.request.routeParams.getMaxFee(progress.request.totalAmount)
    (amount, maxFee - progress.pending.values.map(_.route.fee).sum)
  }

  def maxCltvExpiryMetaOpt: Option[RouteMeta] = data match { case data: OutgoingPaymentData => maxByOption[RouteMeta, CltvExpiry](data.pending.values, _.cltvExpiry) case _ => None }
  def successfulUpdates: Vector[ChannelUpdate] = data match { case data: OutgoingPaymentData => data.pending.values.toVector.flatMap(_.route.hops).map(_.lastUpdate) case _ => Vector.empty }
  def cumulativeFee: MilliSatoshi = data match { case data: OutgoingPaymentData => data.pending.values.map(_.route.fee).sum case _ => 0.msat }
  def failures: Vector[PaymentFailure] = data match { case data: OutgoingPaymentData => data.failures case _ => Vector.empty }
}
