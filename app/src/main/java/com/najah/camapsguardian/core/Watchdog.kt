package com.najah.camapsguardian.core

import android.content.ComponentName
import android.content.Context
import com.najah.camapsguardian.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Une ronde de surveillance : on regarde, on répare si possible, on note tout.
 * Aucune action n'arrête jamais CamAPS FX (jamais de force-stop) : on le ramène
 * simplement au premier plan pour qu'il se reconnecte.
 */
class Watchdog(private val ctx: Context) {

    companion object {
        /** Rondes muettes d'affilée avant la première alerte « aucun capteur ». */
        const val SENSOR_ALERT_ROUNDS = 6
        /** Intervalle de rappel tant que la coupure dure. */
        const val SENSOR_REMINDER_MIN = 15
        /** Délai minimal entre deux réparations de xDrip+, puis vérification après une réparation. */
        const val XDRIP_REPAIR_MIN = 5
        const val XDRIP_VERIFY_MIN = 6
        /** Panne qui résiste aux réparations alors que xDrip+ tourne : on espace (cause ailleurs). */
        const val XDRIP_SLOW_REPAIR_MIN = 30
        /** Rappel tant que Shizuku reste arrêté. */
        const val SHIZUKU_REMINDER_H = 4
    }

    private val prefs: Prefs get() = Prefs.get(ctx)

    suspend fun check(trigger: String, manual: Boolean = false): String =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val p0 = prefs.value

            if (!p0.enabled && !manual) {
                return@withContext ctx.tr(R.string.log_round_disabled)
            }
            if (!manual && now - p0.lastCheckAt in 0L until 20_000L) {
                return@withContext ctx.tr(
                    R.string.log_round_recent,
                    (now - p0.lastCheckAt) / 1000
                )
            }

            Logbook.add(ctx.tr(R.string.cat_round), ctx.tr(R.string.log_round_check, trigger))

            val bt = BluetoothCtl(ctx)
            val cam = CamapsCtl(ctx)
            cam.ensureTargets()?.let { Logbook.add(ctx.tr(R.string.cat_camaps), it) }
            val actions = mutableListOf<String>()
            val notes = mutableListOf<String>()
            val flags = StringBuilder()
            if (manual) flags.append(History.F_MANUAL)

            var btFixes = p0.btFixes
            var relaunches = p0.relaunches
            var deadStreak = p0.appDeadStreak
            var lossStreak = p0.btLossStreak
            var lastRecovery = p0.lastRecoveryAt
            var sensorLostAt = p0.sensorLostAt
            var sensorAlerts = p0.sensorAlerts
            var lastSensorAlert = p0.lastSensorAlertAt
            var canRecover = manual ||
                now - p0.lastRecoveryAt >= p0.recoverMinutes.coerceAtLeast(1) * 60_000L

            // ---------------------------------------------------------- 0. Privilèges (Shizuku / root)
            // Écouteur de Guardian : sans lui, CamAPS et xDrip+ deviennent invisibles.
            if (!PresenceListener.isConnected() && Sys.hasNotifAccess(ctx)) {
                notes += ctx.tr(R.string.log_self_rebind)
                PresenceListener.rebind(ctx)
                if (Shell.canElevate()) {
                    Privileged.rebindListener(
                        ComponentName(ctx, PresenceListener::class.java).flattenToString(), false
                    )
                }
            }
            shizukuReminder(now)
            val xd = XdripCtl(ctx)
            val watchXdrip = p0.xdripWatch && xd.isInstalled()
            val probes = Privileged.probe(
                cam.installed() + listOfNotNull(XdripWatch.PKG.takeIf { watchXdrip }, ctx.packageName)
            )
            probes?.values?.forEach { st -> actions += Privileged.protect(ctx, st) }
            // Tueurs d'applications du constructeur (com.pri.screenoff.killer, 29/10/2026).
            actions += KillerGuard.enforce(ctx)

            // ---------------------------------------------------------- 1. Bluetooth
            var btOn = bt.isOn()
            if (!bt.hasHardware()) {
                notes += ctx.tr(R.string.log_no_bt_hardware)
            } else if (!btOn) {
                if (p0.btAuto) {
                    when (bt.ensureOn()) {
                        BtEnable.ENABLED -> {
                            btOn = true
                            btFixes++
                            lastRecovery = now
                            flags.append(History.F_BT_FIX)
                            actions += ctx.tr(R.string.log_bt_on)
                        }
                        BtEnable.ON -> btOn = true
                        BtEnable.NEEDS_USER -> {
                            flags.append(History.F_NEEDS_USER)
                            actions += ctx.tr(R.string.log_bt_needs_user)
                            Notifier.alertBt(ctx, ctx.tr(R.string.notif_bt_detail))
                        }
                        BtEnable.FAILED -> actions += ctx.tr(R.string.log_bt_unavailable)
                    }
                } else {
                    actions += ctx.tr(R.string.log_bt_off_fix_disabled)
                }
            }

            // ---------------------------------------------------------- 2. Connexions
            var devices = bt.connectedDevices()
            val bonded = bt.bondedDevices()

            // ---------------------------------------------------------- 3. CamAPS FX
            var presence = cam.evaluate(now)
            // Avec Shizuku/root, on sait si le processus tourne : pas besoin d'attendre deux rondes.
            var certain = false
            val camProbe = (presence.pkg ?: cam.installed().firstOrNull())?.let { probes?.get(it) }
            if (camProbe != null) {
                if (camProbe.stopped == true || !camProbe.running) {
                    certain = true
                    presence = PresenceInfo(
                        Presence.NOT_ALIVE,
                        ctx.tr(
                            if (camProbe.stopped == true) R.string.ev_camaps_stopped
                            else R.string.ev_camaps_no_process
                        ) + " · " + presence.evidence,
                        camProbe.pkg
                    )
                } else if (presence.state == Presence.UNKNOWN) {
                    presence = presence.copy(
                        evidence = ctx.tr(R.string.ev_camaps_process) + " · " + presence.evidence
                    )
                }
            }
            // Un arrêt forcé reçu est une certitude : tant que CamAPS n'a pas prouvé qu'il a
            // redémarré, il est mort — même si sa notification est encore affichée (29/10/2026).
            val camPkg = presence.pkg ?: cam.installed().firstOrNull()
            val forcedAt = camPkg?.let { ForceStops.since(it, now) }
            if (camPkg != null && forcedAt != null) {
                if (cam.restartedSince(listOf(camPkg), forcedAt)) {
                    ForceStops.clear(camPkg)
                } else {
                    certain = true
                    presence = PresenceInfo(
                        Presence.NOT_ALIVE,
                        ctx.tr(R.string.ev_camaps_force_stopped_event, Fmt.ago(ctx, forcedAt)) +
                            " · " + presence.evidence,
                        camPkg
                    )
                }
            }
            when (presence.state) {
                Presence.ALIVE -> {
                    if (deadStreak > 0) {
                        Logbook.add(
                            ctx.tr(R.string.cat_camaps),
                            ctx.tr(R.string.log_camaps_alive_again)
                        )
                    }
                    deadStreak = 0
                    Notifier.cancel(ctx, Notifier.ID_ALERT_APP)
                }
                Presence.NOT_ALIVE -> {
                    deadStreak++
                    notes += ctx.tr(
                        R.string.log_camaps_mute,
                        deadStreak,
                        presence.evidence
                    )
                    val target = presence.pkg ?: cam.installed().firstOrNull()
                    val needed = if (certain) 1 else 2
                    if (p0.appAuto && deadStreak >= needed && canRecover && target != null) {
                        val how = cam.launch(target)
                        if (how == CamapsCtl.FAILED) {
                            actions += ctx.tr(R.string.log_camaps_relaunch_failed)
                        } else {
                            relaunches++
                            lastRecovery = now
                            canRecover = false
                            deadStreak = 0
                            flags.append(History.F_RELAUNCH)
                            actions += ctx.tr(R.string.log_camaps_relaunched, how)
                        }
                    } else if (p0.appAuto && deadStreak >= needed && !canRecover) {
                        actions += ctx.tr(R.string.log_camaps_relaunch_deferred)
                    }
                    if (deadStreak >= 3) {
                        Notifier.alertApp(
                            ctx,
                            ctx.tr(R.string.notif_app_detail, presence.evidence)
                        )
                    }
                }
                Presence.UNKNOWN -> {
                    notes += ctx.tr(R.string.log_camaps_unknown, presence.evidence)
                }
                Presence.MISSING -> {
                    notes += ctx.tr(R.string.log_camaps_missing)
                    deadStreak = 0
                }
                Presence.NO_TARGET -> {
                    notes += ctx.tr(R.string.log_no_target)
                    deadStreak = 0
                }
            }

            // ---------------------------------------------------------- 4. Perte de connexion
            if (btOn && bt.hasHardware()) {
                val count = devices ?: -1
                if (count == 0) {
                    lossStreak++
                    notes += ctx.tr(R.string.log_bt_no_device, lossStreak, bonded?.toString() ?: "?")
                    // Ramener au premier plan une application déjà vivante ne rétablit rien
                    // et sortirait le téléphone de sa poche toutes les dix minutes : on ne
                    // relance que si CamAPS est muet ou son état inconnu, et quelques fois
                    // seulement — au-delà, le problème vient du capteur, pas de l'application.
                    val relaunchable = presence.state == Presence.NOT_ALIVE ||
                        presence.state == Presence.UNKNOWN
                    if (!relaunchable) {
                        notes += ctx.tr(R.string.log_camaps_live_no_relaunch)
                    }
                    if (p0.relaunchOnBtLoss && relaunchable &&
                        lossStreak in 2..4 && canRecover
                    ) {
                        val target = cam.installed().firstOrNull()
                        if (target != null && cam.launch(target) != CamapsCtl.FAILED) {
                            relaunches++
                            lastRecovery = now
                            canRecover = false
                            flags.append(History.F_RELAUNCH)
                            actions += ctx.tr(R.string.log_camaps_relaunched_bt)
                        }
                    }
                    if (sensorLostAt == 0L) sensorLostAt = now
                    // Première alerte après quelques rondes, puis rappel toutes les 15 min tant
                    // que rien n'est reconnecté (une alerte déjà partie compte même si un cycle
                    // Bluetooth a remis la série à zéro). Une minute de tolérance pour l'alarme.
                    val due = if (sensorAlerts == 0) lossStreak >= SENSOR_ALERT_ROUNDS
                    else now - lastSensorAlert >= (SENSOR_REMINDER_MIN - 1) * 60_000L
                    if (due) {
                        val lost = ReportPdf.duration(ctx, now - sensorLostAt)
                        val shown = Notifier.alertSensor(
                            ctx,
                            ctx.tr(R.string.notif_no_sensor_title, lost),
                            ctx.tr(R.string.notif_no_sensor_text, sensorAlerts + 1, SENSOR_REMINDER_MIN)
                        )
                        val next = now + SENSOR_REMINDER_MIN * 60_000L
                        Alarms.scheduleSensorReminder(ctx, next)
                        if (shown) {
                            sensorAlerts++
                            lastSensorAlert = now
                        }
                        Logbook.add(
                            ctx.tr(R.string.cat_notif),
                            when {
                                !shown -> ctx.tr(R.string.log_sensor_alert_failed, Fmt.time(next))
                                !Notifier.alertsEnabled(ctx) ->
                                    ctx.tr(R.string.log_sensor_alert_muted, sensorAlerts, lost, Fmt.time(next))
                                else -> ctx.tr(R.string.log_sensor_alert, sensorAlerts, lost, Fmt.time(next))
                            }
                        )
                    }
                    if (p0.aggressiveCycle && lossStreak >= 4 && canRecover) {
                        if (bt.cycle()) {
                            flags.append(History.F_CYCLE)
                            actions += ctx.tr(R.string.log_bt_cycle)
                            lastRecovery = now
                            canRecover = false
                            lossStreak = 0
                            devices = bt.connectedDevices()
                        }
                    }
                } else if (count > 0) {
                    lossStreak = 0
                    Notifier.cancel(ctx, Notifier.ID_ALERT_BT)
                    if (sensorLostAt != 0L) {
                        if (sensorAlerts > 0) {
                            Logbook.add(
                                ctx.tr(R.string.cat_notif),
                                ctx.tr(
                                    R.string.log_sensor_back,
                                    ReportPdf.duration(ctx, now - sensorLostAt),
                                    sensorAlerts
                                )
                            )
                        }
                        Notifier.cancel(ctx, Notifier.ID_ALERT_SENSOR)
                        Alarms.cancelSensorReminder(ctx)
                        sensorLostAt = 0L
                        sensorAlerts = 0
                    }
                }
            }

            // ---------------------------------------------------------- 4 bis. xDrip+
            var xdripBadSince = p0.xdripBadSince
            var xdripStreak = p0.xdripStreak
            var xdripAlerts = p0.xdripAlerts
            var lastXdripAlert = p0.lastXdripAlertAt
            var lastXdripRepair = p0.lastXdripRepairAt
            var xdripRepairs = p0.xdripRepairs
            if (!watchXdrip && xdripBadSince != 0L) {
                Notifier.cancel(ctx, Notifier.ID_ALERT_XDRIP)
                Alarms.cancelXdripCheck(ctx)
                xdripBadSince = 0L
                xdripStreak = 0
                xdripAlerts = 0
            }
            if (watchXdrip) {
                val xi = xd.evaluate(now, probes?.get(XdripWatch.PKG))
                when (xi.state) {
                    XState.OK -> {
                        xdripStreak = 0
                        flags.append(History.F_XDRIP_OK)
                        if (xdripBadSince != 0L) {
                            // Réparé seulement quand une nouvelle glycémie est vraiment arrivée.
                            if (XdripWatch.changedAt > xdripBadSince) {
                                Logbook.add(
                                    ctx.tr(R.string.cat_xdrip),
                                    ctx.tr(
                                        R.string.log_xdrip_back,
                                        ReportPdf.duration(ctx, now - xdripBadSince),
                                        xdripAlerts
                                    )
                                )
                                Notifier.cancel(ctx, Notifier.ID_ALERT_XDRIP)
                                Alarms.cancelXdripCheck(ctx)
                                xdripBadSince = 0L
                                xdripAlerts = 0
                            } else {
                                notes += ctx.tr(R.string.log_xdrip_waiting)
                                Alarms.scheduleXdripCheck(ctx, now + XDRIP_VERIFY_MIN * 60_000L)
                            }
                        }
                    }
                    XState.MISSING, XState.UNKNOWN -> {
                        notes += ctx.tr(R.string.log_xdrip_state, ctx.xLabel(xi.state), xi.evidence)
                    }
                    else -> {
                        if (xdripBadSince == 0L) xdripBadSince = now
                        xdripStreak++
                        flags.append(History.F_XDRIP)
                        notes += ctx.tr(R.string.log_xdrip_state, ctx.xLabel(xi.state), xi.evidence)
                        // Sans capteur connecté, CamAPS n'a pas de valeur à transmettre : xDrip+ n'y est pour rien.
                        val sensorOff = xi.state == XState.STALE && lossStreak > 0
                        // Seule la panne déduite (STALE) est espacée : un arrêt forcé, un accès retiré
                        // ou un écouteur débranché sont constatés, on répare tout de suite (une minute
                        // d'écart seulement, contre les boucles).
                        val spacingMs = when {
                            xi.state != XState.STALE -> 60_000L
                            xdripStreak > 2 -> XDRIP_SLOW_REPAIR_MIN * 60_000L - 30_000L
                            else -> XDRIP_REPAIR_MIN * 60_000L - 30_000L
                        }
                        var fixed = false
                        val how: String
                        if (sensorOff) {
                            how = ctx.tr(R.string.fix_sensor_off)
                        } else if (manual || now - lastXdripRepair >= spacingMs) {
                            val fix = xd.repair(xi.state, now)
                            fixed = fix.done
                            how = fix.how
                            if (fix.done) {
                                lastXdripRepair = now
                                xdripRepairs++
                                flags.append(History.F_XDRIP_FIX)
                            }
                            actions += ctx.tr(R.string.log_xdrip_repair, fix.how)
                        } else {
                            how = ctx.tr(R.string.fix_deferred)
                        }
                        // Alerte tout de suite si rien n'a pu être réparé, sinon si la panne survit
                        // à une réparation ; puis rappel toutes les 15 min tant qu'elle dure.
                        val due = (!fixed || xdripStreak >= 2) &&
                            (xdripAlerts == 0 || now - lastXdripAlert >= (SENSOR_REMINDER_MIN - 1) * 60_000L)
                        if (due) {
                            val lost = ReportPdf.duration(ctx, now - xdripBadSince)
                            val shown = Notifier.alertXdrip(
                                ctx,
                                ctx.tr(R.string.notif_xdrip_title, lost),
                                ctx.tr(R.string.notif_xdrip_text, ctx.xLabel(xi.state), how, xdripAlerts + 1)
                            )
                            if (shown) {
                                xdripAlerts++
                                lastXdripAlert = now
                            }
                            Logbook.add(
                                ctx.tr(R.string.cat_notif),
                                if (shown) ctx.tr(R.string.log_xdrip_alert, xdripAlerts, lost)
                                else ctx.tr(R.string.log_xdrip_alert_failed)
                            )
                        }
                        val next = if (fixed) XDRIP_VERIFY_MIN else SENSOR_REMINDER_MIN
                        Alarms.scheduleXdripCheck(ctx, now + next * 60_000L)
                    }
                }
            }

            // ---------------------------------------------------------- 5. Batterie / veille
            val battery = Sys.battery(ctx)
            if (battery.percent in 0..14 && !battery.charging) {
                notes += ctx.tr(R.string.log_battery_low, battery.percent)
                if (now - p0.lastHintAt > 30 * 60_000L) {
                    Notifier.info(
                        ctx,
                        ctx.tr(R.string.notif_low_battery_title, battery.percent),
                        ctx.tr(R.string.notif_low_battery_text)
                    )
                    prefs.update { it.copy(lastHintAt = now) }
                }
            }
            if (!Sys.ignoringBattery(ctx) && now - p0.lastHintAt > 24 * 3600_000L) {
                Notifier.info(
                    ctx,
                    ctx.tr(R.string.notif_allow_title),
                    ctx.tr(R.string.notif_allow_text)
                )
                prefs.update { it.copy(lastHintAt = now) }
            }

            // ---------------------------------------------------------- 6. Bilan
            val summary = buildSummary(btOn, devices, presence.state, battery, actions)
            if (notes.isNotEmpty()) {
                Logbook.add(
                    ctx.tr(R.string.cat_round),
                    ctx.tr(R.string.log_notes, notes.joinToString(" · "))
                )
            }

            prefs.update {
                it.copy(
                    lastCheckAt = now,
                    checks = it.checks + 1,
                    btFixes = btFixes,
                    relaunches = relaunches,
                    appDeadStreak = deadStreak,
                    btLossStreak = lossStreak,
                    sensorLostAt = sensorLostAt,
                    sensorAlerts = sensorAlerts,
                    lastSensorAlertAt = lastSensorAlert,
                    xdripBadSince = xdripBadSince,
                    xdripStreak = xdripStreak,
                    xdripAlerts = xdripAlerts,
                    lastXdripAlertAt = lastXdripAlert,
                    lastXdripRepairAt = lastXdripRepair,
                    xdripRepairs = xdripRepairs,
                    lastRecoveryAt = lastRecovery,
                    lastSummary = summary
                )
            }
            Logbook.add(ctx.tr(R.string.cat_round), summary)
            History.round(
                at = now,
                bt = if (!bt.hasHardware()) -1 else if (btOn) 1 else 0,
                devices = devices ?: -1,
                presence = presence.state,
                battery = battery.percent,
                charging = if (battery.percent < 0) -1 else if (battery.charging) 1 else 0,
                flags = flags.toString()
            )
            return@withContext summary
        }

    /**
     * Sans root, Shizuku s'arrête à chaque redémarrage du téléphone. S'il a déjà servi à
     * Guardian, on le rappelle : tout de suite, puis toutes les [SHIZUKU_REMINDER_H] heures.
     */
    private fun shizukuReminder(now: Long) {
        if (!Shell.shizukuInstalled(ctx)) return
        val p = prefs.value
        if (Shell.hasShizuku()) {
            if (!p.shizukuSeen || p.shizukuLostAt != 0L) {
                if (p.shizukuLostAt != 0L) {
                    Logbook.add(
                        ctx.tr(R.string.cat_shell),
                        ctx.tr(R.string.log_shizuku_back, ReportPdf.duration(ctx, now - p.shizukuLostAt))
                    )
                }
                Notifier.cancel(ctx, Notifier.ID_SHIZUKU)
                prefs.update { it.copy(shizukuSeen = true, shizukuLostAt = 0L) }
            }
            return
        }
        if (!p.shizukuSeen) return
        val first = p.shizukuLostAt == 0L
        if (!first && now - p.lastShizukuHintAt < SHIZUKU_REMINDER_H * 3_600_000L - 60_000L) return
        val lostAt = if (first) now else p.shizukuLostAt
        // Shizuku tourne mais n'autorise plus Guardian : c'est dans Guardian qu'il faut agir.
        val running = Shell.shizukuRunning()
        Notifier.shizukuHint(
            ctx,
            ctx.tr(if (running) R.string.notif_shizuku_denied_title else R.string.notif_shizuku_title),
            ctx.tr(if (running) R.string.notif_shizuku_denied_text else R.string.notif_shizuku_text),
            openShizuku = !running
        )
        Logbook.add(
            ctx.tr(R.string.cat_shell),
            ctx.tr(R.string.log_shizuku_hint, ReportPdf.duration(ctx, now - lostAt))
        )
        prefs.update { it.copy(shizukuLostAt = lostAt, lastShizukuHintAt = now) }
    }

    private fun buildSummary(
        btOn: Boolean,
        devices: Int?,
        presence: Presence,
        battery: Sys.Battery,
        actions: List<String>
    ): String {
        val sb = StringBuilder()
        sb.append(ctx.tr(if (btOn) R.string.sum_bt_on else R.string.sum_bt_off))
        devices?.let { sb.append(" · ").append(ctx.tr(R.string.sum_connected, it)) }
        sb.append(" · ")
        sb.append(
            ctx.tr(
                when (presence) {
                    Presence.ALIVE -> R.string.sum_camaps_alive
                    Presence.NOT_ALIVE -> R.string.sum_camaps_mute
                    Presence.UNKNOWN -> R.string.sum_camaps_unknown
                    Presence.MISSING -> R.string.sum_camaps_missing
                    Presence.NO_TARGET -> R.string.sum_camaps_target
                }
            )
        )
        if (battery.percent >= 0) sb.append(" · ").append(battery.percent).append(" %")
        if (actions.isNotEmpty()) sb.append(" · ").append(actions.joinToString(", "))
        return sb.toString()
    }
}
