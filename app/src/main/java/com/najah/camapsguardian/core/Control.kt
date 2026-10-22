package com.najah.camapsguardian.core

import android.content.Context
import com.najah.camapsguardian.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Actions de haut niveau, partagées entre l'interface et le service. */
object Control {

    fun enable(ctx: Context) {
        val p = Prefs.get(ctx).update { it.copy(enabled = true) }
        Logbook.add(
            ctx.tr(R.string.cat_control),
            ctx.tr(R.string.log_control_enabled, p.checkMinutes)
        )
        History.event(System.currentTimeMillis(), History.EV_ON)
        Alarms.schedule(ctx, p.checkMinutes)
        WatchdogService.start(ctx, ctx.tr(R.string.log_trigger_activation))
    }

    fun disable(ctx: Context) {
        Prefs.get(ctx).update { it.copy(enabled = false, nextCheckAt = 0L) }
        Logbook.add(ctx.tr(R.string.cat_control), ctx.tr(R.string.log_control_disabled))
        History.event(System.currentTimeMillis(), History.EV_OFF)
        Alarms.cancel(ctx)
        WatchdogService.stop(ctx)
    }

    fun setInterval(ctx: Context, minutes: Int) {
        val p = Prefs.get(ctx).update { it.copy(checkMinutes = minutes) }
        Logbook.add(
            ctx.tr(R.string.cat_control),
            ctx.tr(R.string.log_control_interval, minutes)
        )
        if (p.enabled) Alarms.schedule(ctx, minutes)
    }

    fun setRecover(ctx: Context, minutes: Int) {
        Prefs.get(ctx).update { it.copy(recoverMinutes = minutes) }
        Logbook.add(
            ctx.tr(R.string.cat_control),
            ctx.tr(R.string.log_control_recover, minutes)
        )
    }

    fun setFlag(ctx: Context, block: (P) -> P) {
        Prefs.get(ctx).update(block)
    }

    suspend fun checkNow(ctx: Context): String = withContext(Dispatchers.IO) {
        Watchdog(ctx).check(ctx.tr(R.string.log_trigger_manual), manual = true)
    }

    suspend fun relaunchCamaps(ctx: Context): String = withContext(Dispatchers.IO) {
        val cam = CamapsCtl(ctx)
        val target = cam.installed().firstOrNull()
            ?: return@withContext ctx.tr(R.string.ev_not_installed)
        cam.launch(target)
    }

    suspend fun detectCamaps(ctx: Context): List<String> = withContext(Dispatchers.IO) {
        val found = CamapsCtl(ctx).detect()
        if (found.isNotEmpty()) {
            Prefs.get(ctx).update { it.copy(targets = found.joinToString(",")) }
            Logbook.add(
                ctx.tr(R.string.cat_camaps),
                ctx.tr(R.string.log_camaps_detected, found.joinToString(", "))
            )
        }
        found
    }
}
