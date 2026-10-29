package com.najah.camapsguardian.core

import android.content.Context
import com.najah.camapsguardian.R

/**
 * Neutralise les « tueurs d'applications » des surcouches constructeur.
 *
 * Le 29/10/2026 à 03:01, `com.pri.screenoff.killer` (composant système du Hammer, qui arrête des
 * applications à l'extinction de l'écran) a arrêté CamAPS FX de force : la boucle fermée est restée
 * coupée une cinquantaine de minutes. C'est une application système **persistante** (uid system) :
 * `pm disable-user` ne l'empêche pas de redémarrer (vérifié le 29/10 après redémarrage). Elle est
 * donc **retirée pour l'utilisateur** (`pm uninstall --user 0`, réversible par
 * `cmd package install-existing`), effectif au redémarrage suivant. Guardian vérifie à chaque ronde
 * qu'elle n'est pas revenue (mise à jour du système). Sans Shizuku ni root, il le signale.
 */
object KillerGuard {

    /** Paquets connus pour arrêter CamAPS FX. Réactivation à la main : `pm enable <paquet>`. */
    val KILLERS = listOf("com.pri.screenoff.killer")

    /** Désactive les tueurs actifs ; rend ce qui a été fait (vide si tout était déjà en ordre). */
    fun enforce(ctx: Context): List<String> {
        val pm = ctx.packageManager
        val done = mutableListOf<String>()
        for (pkg in KILLERS) {
            // Installé pour l'utilisateur, même « désactivé » : il redémarre quand même (persistant).
            try {
                pm.getApplicationEnabledSetting(pkg)
            } catch (e: IllegalArgumentException) {
                continue // absent, ou déjà retiré pour cet utilisateur
            }
            if (Shell.canElevate()) {
                val r = Shell.runPrivileged("pm uninstall --user 0 $pkg")
                done += if (r.ok) {
                    ctx.tr(R.string.log_killer_disabled, pkg)
                } else {
                    ctx.tr(R.string.log_killer_failed, pkg, r.output.trim().take(80))
                }
            } else {
                Notifier.info(ctx, ctx.tr(R.string.notif_killer_title), ctx.tr(R.string.notif_killer_text, pkg))
                done += ctx.tr(R.string.log_killer_needs_shizuku, pkg)
            }
        }
        return done
    }
}

/**
 * Les arrêts forcés reçus (`ACTION_PACKAGE_RESTARTED`) : une **certitude**, là où la notification
 * persistante n'est qu'un indice — elle a fait croire CamAPS « actif » pendant 44 minutes le
 * 29/10/2026. Un arrêt reste retenu tant qu'aucune preuve de redémarrage postérieure n'existe.
 */
object ForceStops {

    private const val MEMORY_MS = 2 * 3600_000L
    private val stops = java.util.Collections.synchronizedMap(HashMap<String, Long>())

    fun mark(pkg: String, at: Long = System.currentTimeMillis()) {
        stops[pkg] = at
    }

    /** Heure de l'arrêt forcé de [pkg] s'il est récent, sinon null. */
    fun since(pkg: String, now: Long = System.currentTimeMillis()): Long? =
        stops[pkg]?.takeIf { now - it in 0L..MEMORY_MS }

    fun clear(pkg: String) {
        stops.remove(pkg)
    }
}
