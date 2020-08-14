package com.btcontract.wallet

import com.btcontract.wallet.R.string._
import com.btcontract.wallet.ln.crypto.Tools.{none, runAnd}
import org.ndeftools.util.activity.NfcReaderActivity
import com.btcontract.wallet.WalletApp.app
import android.content.Intent
import org.ndeftools.Message
import android.os.Bundle
import android.view.View


class MainActivity extends NfcReaderActivity with WalletActivity {
  def INIT(state: Bundle): Unit = {
    setContentView(R.layout.activity_main)
  }

  def callHauler(view: View): Unit = {
    this goTo classOf[FloatActivityTest]
  }

  // NFC AND SHARE

  private[this] def readFail(readingError: Throwable): Unit = runAnd(app quickToast err_nothing_useful)(proceedToWallet)
  def readNdefMessage(msg: Message): Unit = <(WalletApp recordValue ndefMessageString(msg), readFail)(_ => proceedToWallet)

  override def onNoNfcIntentFound: Unit = {
    val processIntent = (getIntent.getFlags & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
    val dataOpt = Seq(getIntent.getDataString, getIntent getStringExtra Intent.EXTRA_TEXT).find(data => null != data)
    if (processIntent) <(dataOpt foreach WalletApp.recordValue, readFail)(_ => proceedToWallet) else proceedToWallet
  }

  def onNfcStateEnabled: Unit = none
  def onNfcStateDisabled: Unit = none
  def onNfcFeatureNotFound: Unit = none
  def onNfcStateChange(ok: Boolean): Unit = none
  def readEmptyNdefMessage: Unit = readFail(null)
  def readNonNdefMessage: Unit = readFail(null)

  def proceedToWallet: Unit = {}
}

