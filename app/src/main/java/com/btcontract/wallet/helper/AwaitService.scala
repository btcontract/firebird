package com.btcontract.wallet.helper

import android.content.{Context, Intent}
import android.app.{NotificationManager, Service}
import com.btcontract.wallet.ln.crypto.Tools.runAnd


object AwaitService {
  val classof: Class[AwaitService] = classOf[AwaitService]
  val CHANNEL_ID = "awaitChannelId"
  val SHOW_AMOUNT = "showAmount"
  val CANCEL = "awaitCancel"
  val NOTIFICATION_ID = 14
}

class AwaitService extends Service { me =>
  override def onBind(intent: Intent): Null = null

  override def onDestroy: Unit = runAnd(super.onDestroy) {
    val service = getSystemService(Context.NOTIFICATION_SERVICE)
    service.asInstanceOf[NotificationManager] cancel AwaitService.NOTIFICATION_ID
  }

  override def onStartCommand(serviceIntent: Intent, flags: Int, id: Int): Int = {
    if (serviceIntent.getAction != AwaitService.CANCEL) start(serviceIntent)
    else runAnd(me stopForeground true)(stopSelf)
    Service.START_NOT_STICKY
  }

  def start(intent: Intent): Unit = {
    ???
  }
}