package com.btcontract.wallet.helper

import rx.lang.scala.Subscription
import rx.lang.scala.{Observable => Obs}


abstract class ThrottledWork[T, V] {
  private var lastWork: Option[T] = None
  private var subscription: Option[Subscription] = None
  def hasFinishedOrNeverStarted: Boolean = subscription.isEmpty

  def work(input: T): Obs[V]
  def error(error: Throwable): Unit
  def process(data: T, res: V): Unit

  private def doProcess(data: T, res: V): Unit = {
    // First nullify sunscription, the process callback
    subscription = None
    process(data, res)
  }

  def addWork(data: T): Unit = if (subscription.isEmpty) {
    // Previous work has already finished or has never started, schedule a new one and then look if more work is added once this one is done
    val newSubscription = work(data).doOnSubscribe { lastWork = None }.doAfterTerminate { lastWork foreach addWork }.subscribe(res => doProcess(data, res), error)
    subscription = Some(newSubscription)
  } else {
    // Current work has not finished yet
    // schedule new work once this is done
    lastWork = Some(data)
  }

  def replaceWork(data: T): Unit = if (subscription.isEmpty) {
    // Previous work has already finished or was interrupted or has never started
    val newSubscription = work(data).subscribe(res => doProcess(data, res), error)
    subscription = Some(newSubscription)
  } else {
    // Current work has not finished yet
    // disconnect subscription and replace
    for (s <- subscription) s.unsubscribe
    subscription = None
    replaceWork(data)
  }
}