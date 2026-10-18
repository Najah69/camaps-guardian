package com.najah.camapsguardian.core

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

            var btFixes = p0.btFixes
            var relaunches = p0.relaunches
            var deadStreak = p0.appDeadStreak
            var lossStreak = p0.btLossStreak
            var lastRecovery = p0.lastRecoveryAt
            var canRecover = manual ||
                now - p0.lastRecoveryAt >= p0.recoverMinutes.coerceAtLeast(1) * 60_000L

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
                            actions += ctx.tr(R.string.log_bt_on)
                        }
                        BtEnable.ON -> btOn = true
                        BtEnable.NEEDS_USER -> {
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
            val presence = cam.evaluate(now)
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
                    if (p0.appAuto && deadStreak >= 2 && canRecover && target != null) {
                        val how = cam.launch(target)
                        if (how == CamapsCtl.FAILED) {
                            actions += ctx.tr(R.string.log_camaps_relaunch_failed)
                        } else {
                            relaunches++
                            lastRecovery = now
                            canRecover = false
                            deadStreak = 0
                            actions += ctx.tr(R.string.log_camaps_relaunched, how)
                        }
                    } else if (p0.appAuto && deadStreak >= 2 && !canRecover) {
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
                            actions += ctx.tr(R.string.log_camaps_relaunched_bt)
                        }
                    }
                    if (lossStreak == 6) {
                        Notifier.info(
                            ctx,
                            ctx.tr(R.string.notif_no_sensor_title, lossStreak),
                            ctx.tr(R.string.notif_no_sensor_text)
                        )
                    }
                    if (p0.aggressiveCycle && lossStreak >= 4 && canRecover) {
                        if (bt.cycle()) {
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
                    lastRecoveryAt = lastRecovery,
                    lastSummary = summary
                )
            }
            Logbook.add(ctx.tr(R.string.cat_round), summary)
            return@withContext summary
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
