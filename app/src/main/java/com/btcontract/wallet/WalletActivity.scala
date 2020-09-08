package com.btcontract.wallet

import R.string._
import java.util.{Timer, TimerTask}
import android.text.{Html, Spanned}
import scala.util.{Failure, Success}
import android.view.{View, ViewGroup}
import android.app.{AlertDialog, Dialog}
import android.widget.{LinearLayout, TextView}
import android.content.{DialogInterface, Intent}
import com.btcontract.wallet.ln.crypto.Tools.{none, runAnd}
import com.btcontract.wallet.WalletActivity.StringOps
import concurrent.ExecutionContext.Implicits.global
import androidx.appcompat.app.AppCompatActivity
import android.text.method.LinkMovementMethod
import androidx.core.content.ContextCompat
import scala.language.implicitConversions
import android.content.pm.PackageManager
import android.view.View.OnClickListener
import androidx.core.app.ActivityCompat
import org.aviran.cookiebar2.CookieBar
import android.app.AlertDialog.Builder
import scala.concurrent.Future
import scodec.bits.ByteVector
import android.os.Bundle


object WalletActivity {
  implicit class StringOps(source: String) {
    def s2hex: String = ByteVector.view(source getBytes "UTF-8").toHex
    def noSpaces: String = source.replace(" ", "").replace("\u00A0", "")
    def html: Spanned = Html.fromHtml(source)
  }
}

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

  def checkExternalData: Unit
  def INIT(state: Bundle): Unit

  def toast(code: Int): Unit =
    toast(me getString code)

  def toast(msg: CharSequence): Unit = try {
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

  implicit def UITask(exec: => Any): TimerTask = {
    val runnableExec = new Runnable { override def run: Unit = exec }
    new TimerTask { def run: Unit = me runOnUiThread runnableExec }
  }

  // Builders

  def rm(prev: Dialog)(exe: => Unit): Unit = {
    // Add some delay between popup switches
    timer.schedule(exe, 225)
    prev.dismiss
  }

  implicit def str2View(textFieldData: CharSequence): LinearLayout = {
    val view = getLayoutInflater.inflate(R.layout.frag_top_tip, null).asInstanceOf[LinearLayout]
    clickableTextField(view findViewById R.id.titleTip) setText textFieldData
    view setBackgroundColor 0x22AAAAAA
    view
  }

  def clickableTextField(view: View): TextView = {
    val field: TextView = view.asInstanceOf[TextView]
    field setMovementMethod LinkMovementMethod.getInstance
    field
  }

  def updateView2Blue(oldView: View, newText: String): View = {
    val titleTip = oldView.findViewById(R.id.titleTip).asInstanceOf[TextView]
    oldView setBackgroundColor ContextCompat.getColor(me, R.color.material_blue_500)
    titleTip setText s"<font color=#FFFFFF>$newText</font>".html
    oldView
  }

  def simpleTextBuilder(msg: CharSequence): Builder = new Builder(me).setMessage(msg)
  def simpleTextWithNegBuilder(neg: Int, msg: CharSequence): Builder = simpleTextBuilder(msg).setNegativeButton(neg, null)

  def titleBodyAsViewBuilder(title: View, body: View): Builder = new Builder(me).setCustomTitle(title).setView(body)
  def titleBodyAsViewWithNegBuilder(neg: Int, title: View, body: View): Builder = titleBodyAsViewBuilder(title, body).setNegativeButton(neg, null)
  def onFail(error: CharSequence): Unit = UITask(me showForm titleBodyAsViewWithNegBuilder(dialog_ok, null, error).create).run
  def onFail(error: Throwable): Unit = onFail(error.getMessage)

  def mkCheckForm(ok: AlertDialog => Unit, no: => Unit, bld: Builder,
                  okRes: Int, noRes: Int): AlertDialog = {

    // Create alert dialog where NEGATIVE button removes a dialog AND calls a respected provided function
    // both POSITIVE and NEGATIVE buttons may be omitted by providing -1 as their resource ids
    if (-1 != noRes) bld.setNegativeButton(noRes, null)
    if (-1 != okRes) bld.setPositiveButton(okRes, null)

    val alert = showForm(bld.create)
    val posAct = me onButtonTap ok(alert)
    val negAct = me onButtonTap rm(alert)(no)
    if (-1 != noRes) alert getButton DialogInterface.BUTTON_NEGATIVE setOnClickListener negAct
    if (-1 != okRes) alert getButton DialogInterface.BUTTON_POSITIVE setOnClickListener posAct
    alert
  }

  def mkCheckFormNeutral(ok: AlertDialog => Unit, no: => Unit, neutral: AlertDialog => Unit,
                         bld: Builder, okRes: Int, noRes: Int, neutralRes: Int): AlertDialog = {

    if (-1 != neutralRes) bld.setNeutralButton(neutralRes, null)
    val alert = mkCheckForm(ok, no, bld, okRes, noRes)
    val neutralAct = me onButtonTap neutral(alert)

    // Extend base dialog with a special NEUTRAL button, may be omitted by providing -1
    if (-1 != neutralRes) alert getButton DialogInterface.BUTTON_NEUTRAL setOnClickListener neutralAct
    alert
  }

  def showForm(alertDialog: AlertDialog): AlertDialog = {
    // This may be called after a host activity is destroyed and thus it may throw
    alertDialog.getWindow.getAttributes.windowAnimations = R.style.SlidingDialog
    alertDialog setCanceledOnTouchOutside false

    // First, make sure it does not blow up if called on destroyed activity, then bound its width in case if this is a tablet, finally attempt to make dialog links clickable
    try alertDialog.show catch none finally if (WalletApp.app.scrWidth > 2.3) alertDialog.getWindow.setLayout(WalletApp.app.maxDialog.toInt, ViewGroup.LayoutParams.WRAP_CONTENT)
    try clickableTextField(alertDialog findViewById android.R.id.message) catch none
    alertDialog
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