package com.najah.camapsguardian.core

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.najah.camapsguardian.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Service de ronde, en avant-plan permanent.
 *
 * Défenses en profondeur pour qu'il ne s'arrête pas :
 *  - START_STICKY + stopWithTask=false (il survit au balayage de l'application) ;
 *  - type specialUse|connectedDevice (aucun délai maximal imposé par Android) ;
 *  - alarme-horloge qui le relance (WakeReceiver) même après un arrêt système ;
 *  - battement de cœur interne qui vérifie que la chaîne d'alarmes existe toujours ;
 *  - réarmement et relance dans onDestroy / onTaskRemoved ;
 *  - tâche JobScheduler persistante en dernier recours.
 */
class WatchdogService : Service() {

    companion object {
        const val ACTION_START = "com.najah.camapsguardian.START"
        const val ACTION_TICK = "com.najah.camapsguardian.TICK"
        const val ACTION_STOP = "com.najah.camapsguardian.STOP"
        const val EXTRA_TRIGGER = "trigger"

        @Volatile
        var running: Boolean = false
            private set

        fun start(ctx: Context, trigger: String = ctx.tr(R.string.log_trigger_service)) {
            val i = Intent(ctx, WatchdogService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TRIGGER, trigger)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (t: Throwable) {
                Logbook.add(
                    ctx.tr(R.string.cat_service),
                    ctx.tr(R.string.log_start_refused, t.javaClass.simpleName)
                )
                runCatching { Alarms.schedule(ctx, 1) }
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, WatchdogService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var work: Job? = null
    private var lock: PowerManager.WakeLock? = null
    private var events: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        Logbook.init(this)
        Notifier.ensureChannels(this)
        startForeground(
            Notifier.ID_FOREGROUND,
            Notifier.foreground(
                this,
                tr(R.string.notif_foreground_title),
                tr(R.string.notif_foreground_starting)
            )
        )
        registerEvents()
        scope.launch { heartbeat() }
        Logbook.add(
            tr(R.string.cat_service),
            tr(R.string.log_service_started, Sys.deviceLabel())
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Logbook.add(tr(R.string.cat_service), tr(R.string.log_service_stop_requested))
            shutdown()
            return START_NOT_STICKY
        }

        val p = Prefs.get(this).value
        if (!p.enabled) {
            Logbook.add(tr(R.string.cat_service), tr(R.string.log_service_disabled))
            shutdown()
            return START_NOT_STICKY
        }

        val trigger = intent?.getStringExtra(EXTRA_TRIGGER)
            ?: intent?.action
            ?: tr(R.string.log_trigger_service)

        tick(trigger)
        return START_STICKY
    }

    // ------------------------------------------------------------- ronde

    private fun tick(trigger: String) {
        if (work?.isActive == true) {
            Logbook.add(tr(R.string.cat_service), tr(R.string.log_round_busy))
            return
        }
        work = scope.launch {
            val wl = wakeLock()
            try {
                if (!wl.isHeld) wl.acquire(180_000L)
                val summary = Watchdog(applicationContext).check(trigger)
                val p = Prefs.get(applicationContext).value
                Alarms.schedule(applicationContext, p.checkMinutes)
                refreshNotification(summary, p)
                Logbook.add(tr(R.string.cat_service), tr(R.string.log_round_end))
            } catch (t: Throwable) {
                Logbook.add(
                    tr(R.string.cat_service),
                    tr(R.string.log_round_error, t.javaClass.simpleName, t.message ?: "")
                )
                runCatching {
                    Alarms.schedule(applicationContext, Prefs.get(applicationContext).value.checkMinutes)
                }
            } finally {
                runCatching { if (wl.isHeld) wl.release() }
            }
        }
    }

    // ------------------------------------------------------------- battement

    private suspend fun heartbeat() {
        while (scope.isActive) {
            delay(60_000L)
            try {
                val p = Prefs.get(applicationContext).value
                if (!p.enabled) {
                    Logbook.add(
                        tr(R.string.cat_service),
                        tr(R.string.log_heartbeat_disabled)
                    )
                    shutdown()
                    return
                }
                if (p.nextCheckAt == 0L ||
                    p.nextCheckAt < System.currentTimeMillis() - 120_000L
                ) {
                    Logbook.add(tr(R.string.cat_service), tr(R.string.log_heartbeat_rearm))
                    Alarms.schedule(applicationContext, p.checkMinutes)
                }
                refreshNotification(p.lastSummary, p)
            } catch (t: Throwable) {
                Logbook.add(
                    tr(R.string.cat_service),
                    tr(R.string.log_heartbeat_error, t.message ?: "")
                )
            }
        }
    }

    private fun refreshNotification(summary: String, p: P) {
        val next = if (p.nextCheckAt > 0L) Fmt.time(p.nextCheckAt) else "—"
        val text = buildString {
            append(summary.ifBlank { tr(R.string.notif_foreground_waiting) })
            append('\n')
            append(tr(R.string.notif_foreground_next, next, p.checkMinutes))
        }
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(
                Notifier.ID_FOREGROUND,
                Notifier.foreground(this, tr(R.string.notif_foreground_title), text)
            )
        } catch (t: Throwable) {
            // sans conséquence
        }
    }

    // ------------------------------------------------------------- événements

    private fun registerEvents() {
        if (events != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val p = Prefs.get(applicationContext).value
                if (!p.enabled) return
                // On limite la fréquence : pas de ronde à chaque déverrouillage.
                if (System.currentTimeMillis() - p.lastCheckAt < 90_000L) return
                Logbook.add(tr(R.string.cat_event), i.action ?: "?")
                tick(tr(R.string.log_event, (i.action ?: "").substringAfterLast('.')))
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        try {
            // Ces diffusions sont protégées (seul le système peut les émettre) : on garde
            // l'export pour garantir la livraison sur toutes les versions d'Android.
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            events = receiver
        } catch (t: Throwable) {
            Logbook.add(
                tr(R.string.cat_service),
                tr(R.string.log_receiver_failed, t.message ?: "")
            )
        }
    }

    private fun wakeLock(): PowerManager.WakeLock {
        val existing = lock
        if (existing != null) return existing
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CamAPSGuardian::ronde")
        wl.setReferenceCounted(false)
        lock = wl
        return wl
    }

    // ------------------------------------------------------------- cycle de vie

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = Prefs.get(this).value
        if (p.enabled) {
            Logbook.add(tr(R.string.cat_service), tr(R.string.log_app_removed))
            runCatching { Alarms.schedule(this, 1) }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        events?.let { runCatching { unregisterReceiver(it) } }
        events = null
        val p = Prefs.get(this).value
        if (p.enabled) {
            Logbook.add(tr(R.string.cat_service), tr(R.string.log_service_destroyed))
            runCatching { Alarms.schedule(this, 1) }
        }
        runCatching { if (lock?.isHeld == true) lock?.release() }
        scope.cancel()
        super.onDestroy()
    }

    private fun shutdown() {
        runCatching { Alarms.cancel(this) }
        running = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
