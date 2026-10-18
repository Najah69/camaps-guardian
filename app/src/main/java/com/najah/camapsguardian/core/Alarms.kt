package com.najah.camapsguardian.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.najah.camapsguardian.ui.MainActivity

/**
 * Chaîne d'alarmes qui fait vivre la ronde.
 *
 * AlarmManager.setAlarmClock() est utilisé en priorité : c'est la seule alarme
 * qui traverse Doze et l'optimisation de batterie sans autorisation spéciale.
 * Une seconde alarme (3 min plus tard) sert de filet de sécurité, et une tâche
 * JobScheduler persistante (15 min) rattrape tout le reste, y compris après
 * un redémarrage.
 */
object Alarms {

    private const val RC_CLOCK = 4101
    private const val RC_BACKUP = 4102
    private const val RC_SHOW = 4103
    private const val JOB_ID = 4201

    private fun tick(ctx: Context, rc: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, rc,
        Intent(ctx, WakeReceiver::class.java).setAction(WakeReceiver.ACTION_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun show(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, RC_SHOW,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(ctx: Context, minutes: Int) {
        val safe = minutes.coerceIn(1, 240)
        val at = System.currentTimeMillis() + safe * 60_000L
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show(ctx)), tick(ctx, RC_CLOCK))
            Logbook.add("alarme", "prochaine ronde ${Fmt.time(at)} (alarmClock, $safe min)")
        } catch (t: Throwable) {
            Logbook.add("alarme", "setAlarmClock refusé (${t.message}) → setExactAndAllowWhileIdle")
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, tick(ctx, RC_CLOCK))
            } catch (t2: Throwable) {
                try {
                    @Suppress("DEPRECATION")
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, tick(ctx, RC_CLOCK))
                } catch (t3: Throwable) {
                    Logbook.add("alarme", "aucune alarme posée : ${t3.message}")
                }
            }
        }

        // Filet de sécurité : 3 minutes plus tard, si la première alarme a été perdue.
        try {
            am.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, at + 180_000L, tick(ctx, RC_BACKUP)
            )
        } catch (t: Throwable) {
            runCatching {
                @Suppress("DEPRECATION")
                am.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, at + 180_000L, tick(ctx, RC_BACKUP)
                )
            }
        }

        Prefs.get(ctx).update { it.copy(nextCheckAt = at) }
        scheduleJob(ctx)
    }

    /** Tâche persistante : survit au redémarrage et aux nettoyages de mémoire. */
    fun scheduleJob(ctx: Context) {
        try {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            js.cancel(JOB_ID)
            val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, WakeJobService::class.java))
                .setPeriodic(15 * 60_000L, 5 * 60_000L)
                .setPersisted(true)
                .build()
            js.schedule(info)
        } catch (t: Throwable) {
            Logbook.add("alarme", "JobScheduler indisponible : ${t.message}")
        }
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.cancel(tick(ctx, RC_CLOCK)) }
        runCatching { am.cancel(tick(ctx, RC_BACKUP)) }
        runCatching {
            (ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).cancel(JOB_ID)
        }
        Prefs.get(ctx).update { it.copy(nextCheckAt = 0L) }
        Logbook.add("alarme", "alarmes annulées")
    }
}
