package com.btcontract.wallet.ln.fsm

import scala.concurrent.duration._
import com.btcontract.wallet.ln.crypto.Tools._
import com.btcontract.wallet.ln.crypto.StateMachine
import com.btcontract.wallet.ln.fsm.SwapOutFeeratesHandler._
import fr.acinq.eclair.wire.{Init, NodeAnnouncement, SwapOut, SwapOutFeerates, SwapOutRequest}
import com.btcontract.wallet.ln.{CommsTower, ConnectionListener, HostedChannel}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import com.btcontract.wallet.ln.utils.Rx
import java.util.concurrent.Executors
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.Features


object SwapOutFeeratesHandler {
  val WAITING_FIRST_RESPONSE = "feerates-state-waiting-first-response"
  val WAITING_REST_OF_RESPONSES = "feerates-state-waiting-rest-of-responses"
  val FINALIZED = "feerates-state-finalized"

  val CMDTimeout = "feerates-cmd-timeout"
  val CMDCancel = "feerates-cmd-cancel"
  val CMDSearch = "feerates-cmd-search"

  case class NoSwapOutSupport(worker: CommsTower.Worker)
  case class YesSwapOutSupport(worker: CommsTower.Worker, msg: SwapOut)
  case class SwapOutResponseExt(msg: SwapOutFeerates, ann: NodeAnnouncement)

  type SwapOutResponseOpt = Option[SwapOutResponseExt]
  case class FeeratesData(results: Map[NodeAnnouncement, SwapOutResponseOpt], cmdStart: CMDStart)
  case class CMDStart(channels: Set[HostedChannel] = Set.empty)
  final val minChainFee = Satoshi(253)
}

abstract class SwapOutFeeratesHandler(ourInit: Init) extends StateMachine[FeeratesData] { me =>
  implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
  def process(changeMessage: Any): Unit = scala.concurrent.Future(me doProcess changeMessage)

  lazy private val swapOutListener = new ConnectionListener {
    // Disconnect logic is already handled in ChannelMaster base listener
    override def onOperational(worker: CommsTower.Worker, theirInit: Init): Unit = {
      val swapInSupported = Features.canUseFeature(ourInit.features, theirInit.features, Features.ChainSwap)
      if (swapInSupported) worker.handler process SwapOutRequest else me process NoSwapOutSupport(worker)
    }

    override def onSwapOutMessage(worker: CommsTower.Worker, msg: SwapOut): Unit =
      me process YesSwapOutSupport(worker, msg)
  }

  def onFound(offers: Vector[SwapOutResponseExt] = Vector.empty): Unit
  def onNoProviderSwapOutSupport: Unit
  def onTimeoutAndNoResponse: Unit

  def doProcess(change: Any): Unit = (change, state) match {
    case (NoSwapOutSupport(worker), WAITING_FIRST_RESPONSE | WAITING_REST_OF_RESPONSES) =>
      become(data.copy(results = data.results - worker.ann), state)
      me process CMDSearch

    case (YesSwapOutSupport(worker, msg: SwapOutFeerates), WAITING_FIRST_RESPONSE | WAITING_REST_OF_RESPONSES)
      // Provider has sent feerates which are too low, tx won't likely ever confirm
      if msg.feerates.feerates.forall(params => minChainFee > params.fee) =>
      become(data.copy(results = data.results - worker.ann), state)
      me process CMDSearch

    case (YesSwapOutSupport(worker, msg: SwapOutFeerates), WAITING_FIRST_RESPONSE) =>
      val results1 = data.results.updated(worker.ann, SwapOutResponseExt(msg, worker.ann).toSome)
      become(data.copy(results = results1), WAITING_REST_OF_RESPONSES) // Start waiting for the rest of responses
      Rx.ioQueue.delay(5.seconds).foreach(_ => me process CMDTimeout) // Decrease timeout for the rest of responses
      me process CMDSearch

    case (YesSwapOutSupport(worker, msg: SwapOutFeerates), WAITING_REST_OF_RESPONSES) =>
      val results1 = data.results.updated(worker.ann, SwapOutResponseExt(msg, worker.ann).toSome)
      become(data.copy(results = results1), state)
      me process CMDSearch

    case (CMDSearch, WAITING_FIRST_RESPONSE | WAITING_REST_OF_RESPONSES) => doSearch(data, force = false)
    case (CMDTimeout, WAITING_FIRST_RESPONSE | WAITING_REST_OF_RESPONSES) => doSearch(data, force = true)

    case (CMDCancel, WAITING_FIRST_RESPONSE | WAITING_REST_OF_RESPONSES) =>
      // Do not disconnect from remote peer because we have a channel with them, but remove SwapIn listener
      for (chan <- data.cmdStart.channels) CommsTower.listeners(chan.data.announce.nodeSpecificPkap) -= swapOutListener
      become(data, FINALIZED)

    case (cmd: CMDStart, null) =>
      become(freshData = FeeratesData(results = cmd.channels.map(_.data.announce.na -> None).toMap, cmd), WAITING_FIRST_RESPONSE)
      for (chan <- cmd.channels) CommsTower.listen(Set(swapOutListener), chan.data.announce.nodeSpecificPkap, chan.data.announce.na, ourInit)
      Rx.ioQueue.delay(30.seconds).foreach(_ => me process CMDTimeout)

    case _ =>
  }

  private def doSearch(data: FeeratesData, force: Boolean): Unit = {
    // Remove yet unknown responses, unsupporting peers have been removed earlier
    val responses: Vector[SwapOutResponseExt] = data.results.values.flatten.toVector

    if (responses.size == data.results.size) {
      // All responses have been collected
      onFound(offers = responses)
      me process CMDCancel
    } else if (data.results.isEmpty) {
      // No provider supports this right now
      onNoProviderSwapOutSupport
      me process CMDCancel
    } else if (responses.nonEmpty && force) {
      // We have partial responses, but timed out
      onFound(offers = responses)
      me process CMDCancel
    } else if (force) {
      // Timed out with no responses
      onTimeoutAndNoResponse
      me process CMDCancel
    }
  }
}