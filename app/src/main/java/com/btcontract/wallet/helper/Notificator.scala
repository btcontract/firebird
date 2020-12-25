package com.btcontract.wallet.helper

import android.app.{Notification, NotificationManager, PendingIntent}
import android.content.{BroadcastReceiver, Context, Intent}
import com.btcontract.wallet.{MainActivity, R, WalletApp}
import com.btcontract.wallet.ln.crypto.Tools.none
import androidx.core.app.NotificationCompat


class Notificator extends BroadcastReceiver {
  def onReceive(ct: Context, intent: Intent): Unit = try {
    val targetActivityIntent: Intent = new Intent(ct, MainActivity.mainActivityClass)
    val targetIntent: PendingIntent = PendingIntent.getActivity(ct, 0, targetActivityIntent, PendingIntent.FLAG_UPDATE_CURRENT)
    val manager: NotificationManager = ct.getSystemService(Context.NOTIFICATION_SERVICE).asInstanceOf[NotificationManager]

    val notification: Notification =
      new NotificationCompat.Builder(ct, WalletApp.Notificator.CHANNEL_ID)
        .setContentIntent(targetIntent).setSmallIcon(R.drawable.ic_alert_triangle_white_24dp)
        .setContentTitle(ct getString R.string.in_flight_notify_title)
        .setContentText(ct getString R.string.in_flight_notify_body)
        .setAutoCancel(true)
        .build

    manager.notify(WalletApp.Notificator.NOTIFICATION_ID, notification)
  } catch none
}