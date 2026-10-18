package com.najah.camapsguardian.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.najah.camapsguardian.R

/** Réveil par alarme : relance le service de ronde. */
class WakeReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TICK = "com.najah.camapsguardian.action.TICK"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val p = Prefs.get(ctx).value
        if (!p.enabled) return
        Logbook.init(ctx)
        Logbook.add(ctx.tr(R.string.cat_alarm), ctx.tr(R.string.log_wake, intent.action ?: ""))
        // On réarme immédiatement : même si le service refuse de démarrer, la ronde suivante est prévue.
        runCatching { Alarms.schedule(ctx, p.checkMinutes) }
        WatchdogService.start(ctx, ctx.tr(R.string.log_trigger_alarm))
    }
}

/** Démarrage du téléphone ou mise à jour de l'application. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        Logbook.init(ctx)
        val p = Prefs.get(ctx).value
        Logbook.add(
            ctx.tr(R.string.cat_boot),
            ctx.tr(
                R.string.log_boot,
                intent.action ?: "",
                ctx.tr(if (p.enabled) R.string.ui_monitoring_on else R.string.ui_monitoring_off)
            )
        )
        if (!p.enabled) return
        runCatching { Alarms.schedule(ctx, p.checkMinutes) }
        WatchdogService.start(ctx, ctx.tr(R.string.log_trigger_start))
    }
}

/**
 * Changements d'heure et événements d'alimentation.
 * Depuis Android 12, on ne démarre un service en avant-plan depuis ces diffusions que s'il
 * tourne déjà : sinon on se contente de réarmer l'alarme.
 */
class SystemReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val p = Prefs.get(ctx).value
        if (!p.enabled) return
        when (intent.action) {
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED -> {
                Logbook.add(
                    ctx.tr(R.string.cat_system),
                    ctx.tr(R.string.log_time_changed)
                )
                runCatching { Alarms.schedule(ctx, p.checkMinutes) }
            }
            else -> {
                runCatching { Alarms.schedule(ctx, p.checkMinutes) }
                if (WatchdogService.running) {
                    WatchdogService.start(ctx, (intent.action ?: "").substringAfterLast('.'))
                }
            }
        }
    }
}
