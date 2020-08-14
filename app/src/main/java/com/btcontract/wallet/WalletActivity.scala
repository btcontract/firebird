package com.btcontract.wallet

import scala.util.{Failure, Success}
import com.btcontract.wallet.ln.crypto.Tools.{runAnd, none}
import concurrent.ExecutionContext.Implicits.global
import androidx.appcompat.app.AppCompatActivity
import com.btcontract.wallet.WalletApp.app
import scala.language.implicitConversions
import org.aviran.cookiebar2.CookieBar
import scala.concurrent.Future
import android.content.Intent
import java.util.TimerTask
import android.os.Bundle
import android.view.View


trait WalletActivity extends AppCompatActivity { me =>
  override def onCreate(savedActivityState: Bundle): Unit = {
    Thread setDefaultUncaughtExceptionHandler new UncaughtHandler(this)
    super.onCreate(savedActivityState)
    INIT(savedActivityState)
  }

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

  def INIT(savedInstanceState: Bundle): Unit

  def finishMe(top: View): Unit = finish
  def toast(code: Int): Unit = toast(me getString code)

  def toast(msg: String): Unit = try {
    val cb = CookieBar.rebuild(me).setMessage(msg)
    cb.setCookiePosition(CookieBar.BOTTOM).show
  } catch none

  def share(text: String): Unit = startActivity {
    val share = new Intent setAction Intent.ACTION_SEND
    share.setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
  }

  // Run computation in Future, deal with results on UI thread
  def <[T](fun: => T, no: Throwable => Unit)(ok: T => Unit): Unit = Future(fun) onComplete {
    case Success(rs) => UITask(ok apply rs).run case Failure(ex) => UITask(no apply ex).run
  }
}
