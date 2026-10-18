package com.najah.camapsguardian.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.najah.camapsguardian.ActionActivity
import com.najah.camapsguardian.R
import com.najah.camapsguardian.ui.MainActivity

object Notifier {

    const val CH_WATCH = "surveillance"
    const val CH_ALERT = "alertes"

    const val ID_FOREGROUND = 1001
    const val ID_ALERT_BT = 1002
    const val ID_ALERT_APP = 1003
    const val ID_INFO = 1004

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_WATCH, "Surveillance", NotificationManager.IMPORTANCE_LOW).apply {
                description = "État de la ronde de surveillance (silencieux)"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Alertes", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Bluetooth perdu ou CamAPS FX arrêté — action nécessaire"
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun flags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 11,
        Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        flags()
    )

    private fun action(ctx: Context, mode: String): PendingIntent = PendingIntent.getActivity(
        ctx, if (mode == ActionActivity.MODE_BT) 12 else 13,
        Intent(ctx, ActionActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(ActionActivity.EXTRA_MODE, mode),
        flags()
    )

    /** Notification permanente du service de ronde. */
    fun foreground(ctx: Context, title: String, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_WATCH)
            .setSmallIcon(R.drawable.ic_stat_guardian)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(ctx))
            .build()

    fun alertBt(ctx: Context, detail: String) {
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_guardian)
            .setContentTitle("Bluetooth éteint — action requise")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(action(ctx, ActionActivity.MODE_BT))
            .setFullScreenIntent(action(ctx, ActionActivity.MODE_BT), true)
            .addAction(0, "Rallumer le Bluetooth", action(ctx, ActionActivity.MODE_BT))
            .build()
        post(ctx, ID_ALERT_BT, n)
    }

    fun alertApp(ctx: Context, detail: String) {
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_guardian)
            .setContentTitle("CamAPS FX arrêté — action requise")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(action(ctx, ActionActivity.MODE_APP))
            .setFullScreenIntent(action(ctx, ActionActivity.MODE_APP), true)
            .addAction(0, "Relancer CamAPS FX", action(ctx, ActionActivity.MODE_APP))
            .build()
        post(ctx, ID_ALERT_APP, n)
    }

    fun info(ctx: Context, title: String, text: String) {
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_guardian)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        post(ctx, ID_INFO, n)
    }

    fun cancel(ctx: Context, id: Int) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(id)
        } catch (t: Throwable) {
            // sans conséquence
        }
    }

    private fun post(ctx: Context, id: Int, n: Notification) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(id, n)
        } catch (t: Throwable) {
            Logbook.add("notif", "notification impossible : ${t.javaClass.simpleName}")
        }
    }
}
