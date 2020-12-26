package com.btcontract.wallet.ln.fsm

import scala.concurrent.duration._
import com.btcontract.wallet.ln.{CommsTower, ConnectionListener, HostedChannel}
import fr.acinq.eclair.wire.{Init, SwapIn, SwapInPaymentDenied, SwapInPaymentRequest, SwapInState}
import com.btcontract.wallet.ln.crypto.Tools.runAnd
import fr.acinq.eclair.payment.PaymentRequest
import com.btcontract.wallet.ln.utils.Rx
import rx.lang.scala.Subscription


abstract class SwapInHandler(channel: HostedChannel, ourInit: Init, paymentRequest: PaymentRequest, id: Long) { me =>
  def finish: Unit = runAnd(shutdownTimer.unsubscribe)(CommsTower.listeners(channel.data.announce.nodeSpecificPkap) -= swapInListener)
  CommsTower.listen(Set(swapInListener), channel.data.announce.nodeSpecificPkap, channel.data.announce.na, ourInit)
  val shutdownTimer: Subscription = Rx.ioQueue.delay(30.seconds).doOnCompleted(finish).subscribe(_ => onTimeout)

  lazy private val swapInListener = new ConnectionListener {
    // Disconnect logic is already handled in ChannelMaster base listener
    // We don't check if SwapOut is supported here, it has already been done
    override def onOperational(worker: CommsTower.Worker, theirInit: Init): Unit = {
      val swapInRequest = SwapInPaymentRequest(PaymentRequest.write(paymentRequest), id)
      worker.handler process swapInRequest
    }

    override def onSwapInMessage(worker: CommsTower.Worker, msg: SwapIn): Unit = msg match {
      case message: SwapInState if message.processing.exists(_.id == id) => runAnd(finish)(onProcessing)
      case message: SwapInPaymentDenied if message.id == id => runAnd(finish)(me onDenied message)
      case _ => // Do nothing, it's unrelated
    }
  }

  def onDenied(msg: SwapInPaymentDenied): Unit
  def onProcessing: Unit
  def onTimeout: Unit
}
