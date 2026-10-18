package com.najah.camapsguardian.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Actions de haut niveau, partagées entre l'interface et le service. */
object Control {

    fun enable(ctx: Context) {
        val p = Prefs.get(ctx).update { it.copy(enabled = true) }
        Logbook.add("contrôle", "surveillance ACTIVÉE (contrôle ${p.checkMinutes} min)")
        Alarms.schedule(ctx, p.checkMinutes)
        WatchdogService.start(ctx, "activation")
    }

    fun disable(ctx: Context) {
        Prefs.get(ctx).update { it.copy(enabled = false, nextCheckAt = 0L) }
        Logbook.add("contrôle", "surveillance DÉSACTIVÉE")
        Alarms.cancel(ctx)
        WatchdogService.stop(ctx)
    }

    fun setInterval(ctx: Context, minutes: Int) {
        val p = Prefs.get(ctx).update { it.copy(checkMinutes = minutes) }
        Logbook.add("contrôle", "intervalle de contrôle : $minutes min")
        if (p.enabled) Alarms.schedule(ctx, minutes)
    }

    fun setRecover(ctx: Context, minutes: Int) {
        Prefs.get(ctx).update { it.copy(recoverMinutes = minutes) }
        Logbook.add("contrôle", "intervalle de rétablissement : $minutes min")
    }

    fun setFlag(ctx: Context, block: (P) -> P) {
        Prefs.get(ctx).update(block)
    }

    suspend fun checkNow(ctx: Context): String = withContext(Dispatchers.IO) {
        Watchdog(ctx).check("manuel", manual = true)
    }

    suspend fun relaunchCamaps(ctx: Context): String = withContext(Dispatchers.IO) {
        val cam = CamapsCtl(ctx)
        val target = cam.installed().firstOrNull()
            ?: return@withContext "CamAPS FX introuvable sur ce téléphone"
        cam.launch(target)
    }

    suspend fun detectCamaps(ctx: Context): List<String> = withContext(Dispatchers.IO) {
        val found = CamapsCtl(ctx).detect()
        if (found.isNotEmpty()) {
            Prefs.get(ctx).update { it.copy(targets = found.joinToString(",")) }
            Logbook.add("camaps", "paquet(s) détecté(s) : ${found.joinToString(", ")}")
        }
        found
    }
}
