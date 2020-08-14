package com.btcontract.wallet

import java.io.{PrintWriter, StringWriter}
import java.lang.Thread.UncaughtExceptionHandler
import android.content.Intent
import android.app.Activity


object UncaughtHandler {
  val ERROR_REPORT = "error-report"
  def toText(exc: Throwable): String = {
    val stackTraceWriter = new StringWriter
    exc printStackTrace new PrintWriter(stackTraceWriter)
    stackTraceWriter.toString
  }
}

class UncaughtHandler(ctxt: Activity) extends UncaughtExceptionHandler {
  def uncaughtException(thread: Thread, exc: Throwable): Unit = {
    // TODO: replace null in Intent with emergency activity
    val content = UncaughtHandler toText exc
    val intent = new Intent(ctxt, null)

    ctxt startActivity intent.putExtra(UncaughtHandler.ERROR_REPORT, content)
    android.os.Process killProcess android.os.Process.myPid
    System exit 10
  }
}