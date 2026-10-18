package com.najah.camapsguardian.core

import android.content.Context
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

            if (!p0.enabled && !manual) return@withContext "Surveillance désactivée"
            if (!manual && now - p0.lastCheckAt in 0L until 20_000L) {
                return@withContext "Ronde récente (${(now - p0.lastCheckAt) / 1000} s)"
            }

            Logbook.add("ronde", "contrôle — $trigger")

            val bt = BluetoothCtl(ctx)
            val cam = CamapsCtl(ctx)
            cam.ensureTargets()?.let { Logbook.add("camaps", it) }
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
                notes += "pas de Bluetooth sur cet appareil"
            } else if (!btOn) {
                if (p0.btAuto) {
                    when (bt.ensureOn()) {
                        BtEnable.ENABLED -> {
                            btOn = true
                            btFixes++
                            lastRecovery = now
                            actions += "Bluetooth rallumé"
                        }
                        BtEnable.ON -> btOn = true
                        BtEnable.NEEDS_USER -> {
                            actions += "Bluetooth éteint : accord utilisateur requis"
                            Notifier.alertBt(
                                ctx,
                                "Le Bluetooth est éteint et Android refuse de le rallumer seul. " +
                                    "Touchez pour l'activer."
                            )
                        }
                        BtEnable.FAILED -> actions += "Bluetooth indisponible"
                    }
                } else {
                    actions += "Bluetooth éteint (correctif désactivé)"
                }
            }

            // ---------------------------------------------------------- 2. Connexions
            var devices = bt.connectedDevices()
            val bonded = bt.bondedDevices()

            // ---------------------------------------------------------- 3. CamAPS FX
            val presence = cam.evaluate(now)
            when (presence.state) {
                Presence.ALIVE -> {
                    if (deadStreak > 0) Logbook.add("camaps", "de nouveau vivant")
                    deadStreak = 0
                    Notifier.cancel(ctx, Notifier.ID_ALERT_APP)
                }
                Presence.NOT_ALIVE -> {
                    deadStreak++
                    notes += "CamAPS muet (${deadStreak}×) : ${presence.evidence}"
                    val target = presence.pkg ?: cam.installed().firstOrNull()
                    if (p0.appAuto && deadStreak >= 2 && canRecover && target != null) {
                        val how = cam.launch(target)
                        if (how == "échec") {
                            actions += "relance CamAPS impossible"
                        } else {
                            relaunches++
                            lastRecovery = now
                            canRecover = false
                            deadStreak = 0
                            actions += "CamAPS relancé ($how)"
                        }
                    } else if (p0.appAuto && deadStreak >= 2 && !canRecover) {
                        actions += "relance CamAPS différée (intervalle)"
                    }
                    if (deadStreak >= 3) {
                        Notifier.alertApp(
                            ctx,
                            "CamAPS FX ne répond plus (${presence.evidence}). " +
                                "Touchez pour le ramener au premier plan."
                        )
                    }
                }
                Presence.UNKNOWN -> notes += "état CamAPS inconnu : ${presence.evidence}"
                Presence.MISSING -> {
                    notes += "CamAPS FX introuvable (nom de paquet ?)"
                    deadStreak = 0
                }
                Presence.NO_TARGET -> {
                    notes += "aucune cible configurée"
                    deadStreak = 0
                }
            }

            // ---------------------------------------------------------- 4. Perte de connexion
            if (btOn && bt.hasHardware()) {
                val count = devices ?: -1
                if (count == 0) {
                    lossStreak++
                    notes += "aucun appareil Bluetooth connecté (${lossStreak}×, $bonded appairé(s))"
                    // Ramener au premier plan une application déjà vivante ne rétablit rien
                    // et sortirait le téléphone de sa poche toutes les dix minutes : on ne
                    // relance que si CamAPS est muet ou son état inconnu, et quelques fois
                    // seulement — au-delà, le problème vient du capteur, pas de l'application.
                    val relaunchable = presence.state == Presence.NOT_ALIVE ||
                        presence.state == Presence.UNKNOWN
                    if (!relaunchable) {
                        notes += "CamAPS vivant : pas de relance pour la perte Bluetooth"
                    }
                    if (p0.relaunchOnBtLoss && relaunchable &&
                        lossStreak in 2..4 && canRecover
                    ) {
                        val target = cam.installed().firstOrNull()
                        if (target != null && cam.launch(target) != "échec") {
                            relaunches++
                            lastRecovery = now
                            canRecover = false
                            actions += "CamAPS relancé (reconnexion Bluetooth)"
                        }
                    }
                    if (lossStreak == 6) {
                        Notifier.alertBt(
                            ctx,
                            "Aucun capteur connecté depuis $lossStreak rondes. " +
                                "Vérifiez le capteur et le transmetteur : CamAPS Guardian ne " +
                                "peut pas rétablir la liaison à votre place."
                        )
                    }
                    if (p0.aggressiveCycle && lossStreak >= 4 && canRecover) {
                        if (bt.cycle()) {
                            actions += "cycle Bluetooth"
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
                notes += "batterie faible ${battery.percent} %"
                if (now - p0.lastHintAt > 30 * 60_000L) {
                    Notifier.info(
                        ctx,
                        "Batterie faible (${battery.percent} %)",
                        "La surveillance continue, mais branchez le téléphone : la boucle " +
                            "CamAPS FX en dépend."
                    )
                    prefs.update { it.copy(lastHintAt = now) }
                }
            }
            if (!Sys.ignoringBattery(ctx) && now - p0.lastHintAt > 24 * 3600_000L) {
                Notifier.info(
                    ctx,
                    "Autorisez la surveillance",
                    "Réglez la batterie sur « Sans restriction » pour CamAPS Guardian, sinon " +
                        "Android peut arrêter la ronde."
                )
                prefs.update { it.copy(lastHintAt = now) }
            }

            // ---------------------------------------------------------- 6. Bilan
            val summary = buildSummary(btOn, devices, presence.state, battery, actions)
            if (notes.isNotEmpty()) Logbook.add("ronde", "notes : ${notes.joinToString(" · ")}")

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
            Logbook.add("ronde", summary)
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
        sb.append(if (btOn) "BT actif" else "BT éteint")
        devices?.let { sb.append(" · $it connecté(s)") }
        sb.append(" · ")
        sb.append(
            when (presence) {
                Presence.ALIVE -> "CamAPS actif"
                Presence.NOT_ALIVE -> "CamAPS muet"
                Presence.UNKNOWN -> "CamAPS ?"
                Presence.MISSING -> "CamAPS absent"
                Presence.NO_TARGET -> "cible ?"
            }
        )
        if (battery.percent >= 0) sb.append(" · ${battery.percent} %")
        if (actions.isNotEmpty()) sb.append(" · ").append(actions.joinToString(", "))
        return sb.toString()
    }
}
