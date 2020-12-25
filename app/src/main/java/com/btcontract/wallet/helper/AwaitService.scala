package com.btcontract.wallet.helper

import android.content.{Context, Intent}
import android.app.{NotificationManager, PendingIntent, Service}
import com.btcontract.wallet.ln.crypto.Tools.runAnd
import androidx.core.app.NotificationCompat
import com.btcontract.wallet.MainActivity
import com.btcontract.wallet.R


object AwaitService {
  val awaitServiceClass: Class[AwaitService] = classOf[AwaitService]
  val AMOUNT_TO_DISPLAY = "amountToDisplay"
  val CHANNEL_ID = "awaitChannelId"
  val NOTIFICATION_ID = 14

  val ACTION_CANCEL = "actionCancel"
  val ACTION_SHOW = "actionShow"
}

class AwaitService extends Service { me =>
  override def onBind(intent: Intent): Null = null

  override def onDestroy: Unit = runAnd(super.onDestroy) {
    val service = getSystemService(Context.NOTIFICATION_SERVICE)
    service.asInstanceOf[NotificationManager] cancel AwaitService.NOTIFICATION_ID
  }

  override def onStartCommand(serviceIntent: Intent, flags: Int, id: Int): Int = {
    if (serviceIntent.getAction != AwaitService.ACTION_CANCEL) start(serviceIntent)
    else runAnd(me stopForeground true)(stopSelf)
    Service.START_NOT_STICKY
  }

  def start(intent: Intent): Unit = {
    val awaitedPaymentSum = intent.getStringExtra(AwaitService.AMOUNT_TO_DISPLAY)
    val disaplyIntent: PendingIntent = PendingIntent.getActivity(me, 0, new Intent(me, MainActivity.mainActivityClass), 0)
    val cancelIntent: PendingIntent = PendingIntent.getService(me, 0, new Intent(me, AwaitService.awaitServiceClass).setAction(AwaitService.ACTION_CANCEL), 0)

    startForeground(AwaitService.NOTIFICATION_ID, new NotificationCompat.Builder(me, AwaitService.CHANNEL_ID).setContentIntent(disaplyIntent)
      .addAction(android.R.drawable.ic_menu_close_clear_cancel, getResources getString R.string.dialog_cancel, cancelIntent)
      .setSmallIcon(R.drawable.ic_history_black_24dp).setContentTitle(getResources getString R.string.notify_title)
      .setContentText(getResources getString R.string.notify_body format awaitedPaymentSum).build)
  }
}