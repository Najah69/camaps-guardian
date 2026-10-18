package com.najah.camapsguardian.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi

enum class Presence { ALIVE, NOT_ALIVE, UNKNOWN, MISSING, NO_TARGET }

data class PresenceInfo(
    val state: Presence,
    val evidence: String,
    val pkg: String? = null
)

/**
 * Détection de l'état de CamAPS FX, sans privilège particulier.
 *
 * Trois signaux, du plus fiable au plus faible :
 *  1. la notification permanente de CamAPS (accès aux notifications) ;
 *  2. l'historique des arrêts du système (plantage / ANR / mémoire) ;
 *  3. la dernière fois que CamAPS est passé au premier plan (accès aux stats d'usage).
 */
class CamapsCtl(private val ctx: Context) {

    private val pm: PackageManager get() = ctx.packageManager
    private val prefs: Prefs get() = Prefs.get(ctx)

    fun isInstalled(pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(pkg, 0)
        true
    } catch (t: Throwable) {
        false
    }

    fun installed(): List<String> = prefs.value.targetList.filter { isInstalled(it) }

    fun label(pkg: String): String = try {
        @Suppress("DEPRECATION")
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (t: Throwable) {
        pkg
    }

    /** Recherche les paquets ressemblant à CamAPS FX (utile si le nom exact change). */
    fun detect(): List<String> = try {
        @Suppress("DEPRECATION")
        pm.getInstalledPackages(0)
            .map { it.packageName }
            .filter { p ->
                val n = p.lowercase()
                n.contains("camdiab") || n.contains("camaps")
            }
            .sorted()
    } catch (t: Throwable) {
        emptyList()
    }

    /**
     * Répare une liste de cibles devenue fausse (CamAPS FX renommé par une mise à jour) :
     * si aucun paquet listé n'est installé, on adopte ceux qui ressemblent à CamAPS FX.
     * Renvoie le message à journaliser, ou null s'il n'y a rien à faire.
     */
    fun ensureTargets(): String? {
        val current = prefs.value.targetList
        if (current.any { isInstalled(it) }) return null
        val found = detect()
        if (found.isEmpty()) return null
        prefs.update { it.copy(targets = found.joinToString(",")) }
        return "cible corrigée : ${found.joinToString(", ")}"
    }

    // ------------------------------------------------------------ présence

    fun evaluate(now: Long = System.currentTimeMillis()): PresenceInfo {
        val p = prefs.value
        if (p.targetList.isEmpty()) {
            return PresenceInfo(Presence.NO_TARGET, "aucune cible configurée")
        }
        val pkgs = installed()
        if (pkgs.isEmpty()) {
            return PresenceInfo(Presence.MISSING, "CamAPS FX introuvable sur ce téléphone")
        }

        val notes = mutableListOf<String>()

        // 1. Notification persistante
        val active = PresenceListener.activePackages()
        if (active != null) {
            val hit = pkgs.firstOrNull { active.contains(it) }
            if (hit != null) {
                return PresenceInfo(Presence.ALIVE, "notification active ($hit)", hit)
            }
            notes += "aucune notification active"
            val seen = PresenceListener.lastSeen(pkgs)
            if (seen > 0L) notes += "dernière notification ${Fmt.ago(seen)}"
        } else {
            notes += "accès aux notifications non accordé"
        }

        // 2. Historique des arrêts
        if (Build.VERSION.SDK_INT >= 30) {
            exitReason(pkgs, now)?.let { ev ->
                notes += ev
                if (ev.contains("plant", true) ||
                    ev.contains("fig", true) ||
                    ev.contains("mémoire", true)
                ) {
                    return PresenceInfo(Presence.NOT_ALIVE, notes.joinToString(" · "))
                }
            }
        }

        // 3. Dernier passage au premier plan
        val last = if (Sys.hasUsageAccess(ctx)) lastForeground(pkgs) else 0L
        if (Sys.hasUsageAccess(ctx)) {
            if (last > 0L) {
                notes += "dernier premier plan ${Fmt.ago(last)}"
                if (now - last < 3 * 60_000L) {
                    return PresenceInfo(Presence.ALIVE, notes.joinToString(" · "))
                }
            } else {
                notes += "jamais vu au premier plan"
            }
        } else {
            notes += "accès aux stats d'usage non accordé"
        }

        // Conclusion : sans notification active et sans activité récente → l'application ne tourne plus.
        return if (active != null && (now - last > 3 * 60_000L || last == 0L)) {
            PresenceInfo(Presence.NOT_ALIVE, notes.joinToString(" · "))
        } else {
            PresenceInfo(Presence.UNKNOWN, notes.joinToString(" · "))
        }
    }

    @RequiresApi(30)
    private fun exitReason(pkgs: List<String>, now: Long): String? {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        for (pkg in pkgs) {
            val list = try {
                am.getHistoricalProcessExitReasons(pkg, 0, 5)
            } catch (t: Throwable) {
                null
            } ?: continue
            // On ne retient que les arrêts récents : au-delà, les autres signaux suffisent.
            val recent = list.firstOrNull { now - it.timestamp < 20 * 60_000L } ?: continue
            val reason = when (recent.reason) {
                ApplicationExitInfo.REASON_CRASH -> "plantage"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "plantage natif"
                ApplicationExitInfo.REASON_ANR -> "application figée (ANR)"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "manque de mémoire"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "ressources excessives"
                ApplicationExitInfo.REASON_SIGNALED -> "tuée par un signal"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "arrêt demandé"
                ApplicationExitInfo.REASON_EXIT_SELF -> "arrêt volontaire"
                ApplicationExitInfo.REASON_OTHER -> "arrêt"
                else -> "raison ${recent.reason}"
            }
            return "$pkg : $reason ${Fmt.ago(recent.timestamp)}"
        }
        return null
    }

    private fun lastForeground(pkgs: List<String>): Long {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return 0L
        val now = System.currentTimeMillis()
        var best = 0L
        try {
            val events = usm.queryEvents(now - 12 * 3600_000L, now) ?: return 0L
            val e = UsageEvents.Event()
            while (events.getNextEvent(e)) {
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED && pkgs.contains(e.packageName)) {
                    if (e.timeStamp > best) best = e.timeStamp
                }
            }
        } catch (t: Throwable) {
            return 0L
        }
        return best
    }

    // ------------------------------------------------------------ relance

    /** Ramène CamAPS FX au premier plan. Ne l'arrête jamais (aucun force-stop). */
    fun launch(pkg: String): String {
        if (pkg.isEmpty()) return "échec"
        val intent = try {
            pm.getLaunchIntentForPackage(pkg)
        } catch (t: Throwable) {
            null
        }
        if (intent != null) {
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            try {
                ctx.startActivity(intent)
                Logbook.add("camaps", "relance par intent direct ($pkg)")
                return "intent"
            } catch (t: Throwable) {
                Logbook.add(
                    "camaps",
                    "intent refusé : ${t.javaClass.simpleName} ${t.message ?: ""}"
                )
            }
        } else {
            Logbook.add("camaps", "aucune activité de lancement pour $pkg")
        }
        // Repli privilégié : monkey sait démarrer l'activité de lancement.
        val r = Shell.runPrivileged("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
        if (r.ok) {
            Logbook.add("camaps", "relance par monkey (${r.how})")
            return "monkey/${r.how}"
        }
        return "échec"
    }
}
