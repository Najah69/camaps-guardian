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
    const val ID_ALERT_SENSOR = 1005
    const val ID_ALERT_XDRIP = 1006
    const val ID_SHIZUKU = 1007

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CH_WATCH,
                ctx.tr(R.string.ch_watch_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = ctx.tr(R.string.ch_watch_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CH_ALERT,
                ctx.tr(R.string.ch_alert_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = ctx.tr(R.string.ch_alert_desc)
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
            .setContentTitle(ctx.tr(R.string.notif_bt_title))
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(action(ctx, ActionActivity.MODE_BT))
            .setFullScreenIntent(action(ctx, ActionActivity.MODE_BT), true)
            .addAction(
                0,
                ctx.tr(R.string.notif_bt_action),
                action(ctx, ActionActivity.MODE_BT)
            )
            .build()
        post(ctx, ID_ALERT_BT, n)
    }

    fun alertApp(ctx: Context, detail: String) {
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_guardian)
            .setContentTitle(ctx.tr(R.string.notif_app_title))
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(action(ctx, ActionActivity.MODE_APP))
            .setFullScreenIntent(action(ctx, ActionActivity.MODE_APP), true)
            .addAction(
                0,
                ctx.tr(R.string.notif_app_action),
                action(ctx, ActionActivity.MODE_APP)
            )
            .build()
        post(ctx, ID_ALERT_APP, n)
    }

    /**
     * Aucun appareil connecté : Guardian ne peut rien réparer, il ne peut que prévenir.
     * Identifiant propre (les rappels de batterie ne l'écrasent pas), priorité haute et
     * catégorie alarme, sans écran plein : le rappel revient tant que la coupure dure.
     * Renvoie false si Android n'a pas pu l'afficher.
     */
    fun alertSensor(ctx: Context, title: String, text: String): Boolean {
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_guardian)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(ctx))
            .build()
        return post(ctx, ID_ALERT_SENSOR, n)
    }

    /**
     * xDrip+ ne collecte plus : identifiant propre, priorité haute, catégorie alarme, sans
     * écran plein. Rappelée tant que la panne dure. Renvoie false si Android ne l'a pas affichée.
     */
    fun alertXdrip(ctx: Context, title: String, text: String): Boolean {
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_guardian)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(ctx))
            .build()
        return post(ctx, ID_ALERT_XDRIP, n)
    }

    /**
     * Shizuku arrêté (en général après un redémarrage du téléphone) : simple rappel, priorité
     * normale — Guardian continue de surveiller, avec moins de moyens. Toucher ouvre Shizuku
     * (ou Guardian, quand il s'agit d'autoriser Shizuku).
     */
    fun shizukuHint(ctx: Context, title: String, text: String, openShizuku: Boolean) {
        val launch = if (openShizuku) {
            runCatching { ctx.packageManager.getLaunchIntentForPackage(Shell.SHIZUKU_PKG) }.getOrNull()
        } else {
            null
        }
        val tap = if (launch != null) {
            PendingIntent.getActivity(
                ctx, 12, launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags()
            )
        } else {
            openApp(ctx)
        }
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_guardian)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(tap)
            .build()
        post(ctx, ID_SHIZUKU, n)
    }

    /** Les notifications de l'application sont-elles autorisées et le canal d'alerte actif ? */
    fun alertsEnabled(ctx: Context): Boolean = try {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 26 ||
            nm.getNotificationChannel(CH_ALERT)?.importance != NotificationManager.IMPORTANCE_NONE)
    } catch (t: Throwable) {
        true
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

    private fun post(ctx: Context, id: Int, n: Notification): Boolean =
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(id, n)
            true
        } catch (t: Throwable) {
            Logbook.add(
                ctx.tr(R.string.cat_notif),
                ctx.tr(R.string.notif_failed, t.javaClass.simpleName)
            )
            false
        }
}
