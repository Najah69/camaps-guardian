package com.najah.camapsguardian.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Réveil par alarme : relance le service de ronde. */
class WakeReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TICK = "com.najah.camapsguardian.action.TICK"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val p = Prefs.get(ctx).value
        if (!p.enabled) return
        Logbook.init(ctx)
        Logbook.add("alarme", "réveil (${intent.action})")
        // On réarme immédiatement : même si le service refuse de démarrer, la ronde suivante est prévue.
        runCatching { Alarms.schedule(ctx, p.checkMinutes) }
        WatchdogService.start(ctx, "alarme")
    }
}

/** Démarrage du téléphone ou mise à jour de l'application. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        Logbook.init(ctx)
        val p = Prefs.get(ctx).value
        Logbook.add(
            "boot",
            "${intent.action} — surveillance ${if (p.enabled) "active" else "inactive"}"
        )
        if (!p.enabled) return
        runCatching { Alarms.schedule(ctx, p.checkMinutes) }
        WatchdogService.start(ctx, "démarrage")
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
                Logbook.add("système", "heure modifiée → réarmement")
                runCatching { Alarms.schedule(ctx, p.checkMinutes) }
            }
            else -> {
                runCatching { Alarms.schedule(ctx, p.checkMinutes) }
                if (WatchdogService.running) {
                    WatchdogService.start(ctx, "événement système")
                }
            }
        }
    }
}
