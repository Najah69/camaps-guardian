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
import com.najah.camapsguardian.R

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

    companion object {
        /** Valeur renvoyée par [launch] en cas d'échec (comparée par l'appelant). */
        const val FAILED = "failed"
    }

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
        return ctx.tr(R.string.log_target_fixed, found.joinToString(", "))
    }

    // ------------------------------------------------------------ présence

    fun evaluate(now: Long = System.currentTimeMillis()): PresenceInfo {
        val p = prefs.value
        if (p.targetList.isEmpty()) {
            return PresenceInfo(Presence.NO_TARGET, ctx.tr(R.string.ev_no_target))
        }
        val pkgs = installed()
        if (pkgs.isEmpty()) {
            return PresenceInfo(Presence.MISSING, ctx.tr(R.string.ev_not_installed))
        }

        val notes = mutableListOf<String>()

        // 1. Notification persistante
        val active = PresenceListener.activePackages()
        if (active != null) {
            val hit = pkgs.firstOrNull { active.contains(it) }
            if (hit != null) {
                return PresenceInfo(
                    Presence.ALIVE,
                    ctx.tr(R.string.ev_notification_active, hit),
                    hit
                )
            }
            notes += ctx.tr(R.string.ev_no_notification)
            val seen = PresenceListener.lastSeen(pkgs)
            if (seen > 0L) notes += ctx.tr(R.string.ev_last_notification, Fmt.ago(ctx, seen))
        } else {
            notes += ctx.tr(R.string.ev_no_notif_access)
        }

        // 2. Historique des arrêts
        if (Build.VERSION.SDK_INT >= 30) {
            exitReason(pkgs, now)?.let { (fatal, text) ->
                notes += text
                if (fatal) {
                    return PresenceInfo(Presence.NOT_ALIVE, notes.joinToString(" · "))
                }
            }
        }

        // 3. Dernier passage au premier plan
        val last = if (Sys.hasUsageAccess(ctx)) lastForeground(pkgs) else 0L
        if (Sys.hasUsageAccess(ctx)) {
            if (last > 0L) {
                notes += ctx.tr(R.string.ev_last_foreground, Fmt.ago(ctx, last))
                if (now - last < 3 * 60_000L) {
                    return PresenceInfo(Presence.ALIVE, notes.joinToString(" · "))
                }
            } else {
                notes += ctx.tr(R.string.ev_never_foreground)
            }
        } else {
            notes += ctx.tr(R.string.ev_no_usage_access)
        }

        // Conclusion : sans notification active et sans activité récente → l'application ne tourne plus.
        return if (active != null && (now - last > 3 * 60_000L || last == 0L)) {
            PresenceInfo(Presence.NOT_ALIVE, notes.joinToString(" · "))
        } else {
            PresenceInfo(Presence.UNKNOWN, notes.joinToString(" · "))
        }
    }

    /** Renvoie [arrêt fatal, texte] du dernier arrêt récent, ou null s'il n'y en a pas. */
    @RequiresApi(30)
    private fun exitReason(pkgs: List<String>, now: Long): Pair<Boolean, String>? {
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
                ApplicationExitInfo.REASON_CRASH -> ctx.tr(R.string.ev_reason_crash)
                ApplicationExitInfo.REASON_CRASH_NATIVE -> ctx.tr(R.string.ev_reason_native_crash)
                ApplicationExitInfo.REASON_ANR -> ctx.tr(R.string.ev_reason_anr)
                ApplicationExitInfo.REASON_LOW_MEMORY -> ctx.tr(R.string.ev_reason_low_memory)
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
                    ctx.tr(R.string.ev_reason_resources)
                ApplicationExitInfo.REASON_SIGNALED -> ctx.tr(R.string.ev_reason_signalled)
                ApplicationExitInfo.REASON_USER_REQUESTED -> ctx.tr(R.string.ev_reason_user)
                ApplicationExitInfo.REASON_EXIT_SELF -> ctx.tr(R.string.ev_reason_self)
                ApplicationExitInfo.REASON_OTHER -> ctx.tr(R.string.ev_reason_other)
                else -> ctx.tr(R.string.ev_reason_code, recent.reason)
            }
            val fatal = when (recent.reason) {
                ApplicationExitInfo.REASON_CRASH,
                ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR,
                ApplicationExitInfo.REASON_LOW_MEMORY,
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> true
                else -> false
            }
            val text = ctx.tr(
                R.string.ev_exit,
                pkg,
                reason,
                Fmt.ago(ctx, recent.timestamp)
            )
            return fatal to text
        }
        return null
    }

    /**
     * Vrai si [pkgs] a redémarré après [since] : passage au premier plan, démarrage de son service
     * au premier plan, ou notification publiée depuis. Sert après un arrêt forcé.
     */
    fun restartedSince(pkgs: List<String>, since: Long): Boolean {
        if (PresenceListener.postedSince(pkgs, since)) return true
        if (!Sys.hasUsageAccess(ctx)) return false
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return false
        return try {
            val events = usm.queryEvents(since, System.currentTimeMillis()) ?: return false
            val e = UsageEvents.Event()
            var found = false
            while (events.getNextEvent(e)) {
                if (e.timeStamp > since && pkgs.contains(e.packageName) &&
                    (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                        (Build.VERSION.SDK_INT >= 29 &&
                            e.eventType == UsageEvents.Event.FOREGROUND_SERVICE_START))
                ) {
                    found = true
                    break
                }
            }
            found
        } catch (t: Throwable) {
            false
        }
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
        if (pkg.isEmpty()) return FAILED
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
                Logbook.add(
                    ctx.tr(R.string.cat_camaps),
                    ctx.tr(R.string.log_camaps_intent_ok, pkg)
                )
                return "intent"
            } catch (t: Throwable) {
                Logbook.add(
                    ctx.tr(R.string.cat_camaps),
                    ctx.tr(
                        R.string.log_camaps_intent_refused,
                        t.javaClass.simpleName,
                        t.message ?: ""
                    )
                )
            }
        } else {
            Logbook.add(
                ctx.tr(R.string.cat_camaps),
                ctx.tr(R.string.log_camaps_no_launch_activity, pkg)
            )
        }
        // Repli privilégié : monkey sait démarrer l'activité de lancement.
        val r = Shell.runPrivileged("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
        if (r.ok) {
            Logbook.add(
                ctx.tr(R.string.cat_camaps),
                ctx.tr(R.string.log_camaps_monkey, r.how)
            )
            return "monkey/${r.how}"
        }
        return FAILED
    }
}
