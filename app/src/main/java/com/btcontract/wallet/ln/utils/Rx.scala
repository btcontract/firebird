package com.btcontract.wallet.ln.utils

import scala.concurrent.duration._
import rx.lang.scala.schedulers.IOScheduler
import scala.concurrent.duration.Duration
import rx.lang.scala.Observable


object Rx {
  def initDelay[T](next: Observable[T], startMillis: Long, timeoutMillis: Long): Observable[T] = {
    val adjustedTimeout = startMillis + timeoutMillis - System.currentTimeMillis
    val delayLeft = if (adjustedTimeout < 5L) 5L else adjustedTimeout
    Observable.just(null).delay(delayLeft.millis).flatMap(_ => next)
  }

  def retry[T](obs: Observable[T], pick: (Throwable, Int) => Duration, times: Range): Observable[T] =
    obs.retryWhen(_.zipWith(Observable from times)(pick) flatMap Observable.timer)

  def ioQueue: Observable[Null] = Observable.just(null).subscribeOn(IOScheduler.apply)
  def pickInc(errorOrUnit: Any, next: Int): Duration = next.seconds
}