package com.btcontract.wallet

import java.util.{Timer, TimerTask}
import scala.util.{Failure, Success}
import com.btcontract.wallet.ln.crypto.Tools.{none, runAnd}
import concurrent.ExecutionContext.Implicits.global
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import scala.language.implicitConversions
import android.content.pm.PackageManager
import android.view.View.OnClickListener
import androidx.core.app.ActivityCompat
import org.aviran.cookiebar2.CookieBar
import scala.concurrent.Future
import android.content.Intent
import android.os.Bundle
import android.view.View


trait WalletActivity extends AppCompatActivity { me =>
  override def onCreate(savedActivityState: Bundle): Unit = {
    Thread setDefaultUncaughtExceptionHandler new UncaughtHandler(this)
    super.onCreate(savedActivityState)
    INIT(savedActivityState)
  }

  override def onDestroy: Unit = {
    super.onDestroy
    timer.cancel
  }

  val timer = new Timer
  val goTo: Class[_] => Any = target => {
    this startActivity new Intent(this, target)
    WalletApp.DoNotEraseValue
  }

  val exitTo: Class[_] => Any = target => {
    this startActivity new Intent(this, target)
    runAnd(WalletApp.DoNotEraseValue)(finish)
  }

  implicit def UITask(exec: => Any): TimerTask = {
    val runnableExec = new Runnable { override def run: Unit = exec }
    new TimerTask { def run: Unit = me runOnUiThread runnableExec }
  }

  def checkExternalData: Unit
  def INIT(state: Bundle): Unit

  def toast(code: Int): Unit =
    toast(me getString code)

  def toast(msg: String): Unit = try {
    val cb = CookieBar.rebuild(me).setMessage(msg)
    cb.setCookiePosition(CookieBar.BOTTOM).show
  } catch none

  def share(text: String): Unit = startActivity {
    val share = new Intent setAction Intent.ACTION_SEND
    share.setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
  }

  def onButtonTap(exec: => Unit): OnClickListener = new OnClickListener {
    def onClick(view: View): Unit = exec
  }

  def runInFutureProcessOnUI[T](fun: => T, no: Throwable => Unit)(ok: T => Unit): Unit = Future(fun) onComplete {
    case Success(result) => UITask(ok apply result).run case Failure(error) => UITask(no apply error).run
  }

  // Scanner

  final val scannerRequestCode = 101

  type GrantResults = Array[Int]
  override def onRequestPermissionsResult(reqCode: Int, permissions: Array[String], grantResults: GrantResults): Unit = {
    if (reqCode == scannerRequestCode && grantResults.nonEmpty && grantResults.head == PackageManager.PERMISSION_GRANTED) callScanner(null)
  }

  def callScanner(nullableView: View): Unit = {
    val allowed = ContextCompat.checkSelfPermission(me, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    if (allowed) new sheets.ScannerBottomSheet(me).show(getSupportFragmentManager, "scanner-bottom-sheet-fragment")
    else ActivityCompat.requestPermissions(me, Array(android.Manifest.permission.CAMERA), scannerRequestCode)
  }
}
