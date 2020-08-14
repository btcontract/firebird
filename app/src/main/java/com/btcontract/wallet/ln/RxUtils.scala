package com.btcontract.wallet.ln

import scala.concurrent.duration._
import rx.lang.scala.schedulers.IOScheduler
import rx.lang.scala.{Observable => Obs}


object RxUtils {
  def initDelay[T](next: Obs[T], startMillis: Long, timeoutMillis: Long): Obs[T] = {
    val adjustedTimeout = startMillis + timeoutMillis - System.currentTimeMillis
    val delayLeft = if (adjustedTimeout < 5L) 5L else adjustedTimeout
    Obs.just(null).delay(delayLeft.millis).flatMap(_ => next)
  }

  def retry[T](obs: Obs[T], pick: (Throwable, Int) => Duration, times: Range): Obs[T] =
    obs.retryWhen(_.zipWith(Obs from times)(pick) flatMap Obs.timer)

  def ioQueue: Obs[Null] = Obs.just(null).subscribeOn(IOScheduler.apply)
  def pickInc(errorOrUnit: Any, next: Int): Duration = next.seconds
}