package com.najah.camapsguardian.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.najah.camapsguardian.R

/** État de la collecte de xDrip+ (source « Companion App » : il lit la notification de CamAPS FX). */
enum class XState { OK, STALE, UNBOUND, REVOKED, DEAD, MISSING, UNKNOWN }

data class XInfo(val state: XState, val evidence: String)

fun Context.xLabel(s: XState): String = tr(
    when (s) {
        XState.OK -> R.string.xs_ok
        XState.STALE -> R.string.xs_stale
        XState.UNBOUND -> R.string.xs_unbound
        XState.REVOKED -> R.string.xs_revoked
        XState.DEAD -> R.string.xs_dead
        XState.MISSING -> R.string.xs_missing
        XState.UNKNOWN -> R.string.xs_unknown
    }
)

/**
 * Observations faites par [PresenceListener] : le contenu de la notification permanente de
 * xDrip+ (glycémie, flèche, variation) et les mises à jour de celle de CamAPS FX.
 * Quand CamAPS publie plusieurs valeurs sans que xDrip+ ne bouge, xDrip+ ne collecte plus.
 */
object XdripWatch {

    const val PKG = "com.eveningoutpost.dexdrip"
    val LISTENER = ComponentName(PKG, "$PKG.services.UiBasedCollector")

    @Volatile
    var content: String? = null
        private set

    /** Dernier changement de la notification de xDrip+ (0 = jamais vue). */
    @Volatile
    var changedAt: Long = 0L
        private set

    /** Mises à jour de CamAPS FX depuis [windowStart]. */
    @Volatile
    var camapsUpdates: Int = 0
        private set

    @Volatile
    var windowStart: Long = 0L
        private set

    @Volatile
    private var lastCamapsAt = 0L

    fun onXdrip(text: String, now: Long) {
        if (text == content) return
        val first = content == null
        content = text
        // Première lecture après un démarrage de Guardian : c'est la valeur déjà affichée,
        // pas une nouvelle glycémie. Elle sert de référence sans compter comme « changement ».
        if (first) {
            restart(now)
            return
        }
        changedAt = now
        restart(now)
    }

    fun onCamaps(now: Long) {
        // La notification et son résumé de groupe arrivent ensemble : une minute d'écart minimum.
        if (now - lastCamapsAt < 60_000L) return
        lastCamapsAt = now
        if (windowStart == 0L) windowStart = now
        camapsUpdates++
    }

    /** Nouvelle fenêtre d'observation (nouvelle valeur xDrip+ ou réparation qui vient d'être faite). */
    fun restart(now: Long) {
        camapsUpdates = 0
        windowStart = now
    }
}

class XdripCtl(private val ctx: Context) {

    companion object {
        /** Mises à jour de CamAPS sans nouvelle valeur xDrip+ avant de conclure à une panne. */
        const val STALE_UPDATES = 3
        const val STALE_MIN = 14
    }

    data class Fix(val done: Boolean, val how: String)

    fun isInstalled(): Boolean = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(XdripWatch.PKG, 0)
        true
    } catch (t: Throwable) {
        false
    }

    /**
     * [probe] vient de [Privileged.probe] (null sans Shizuku/root). [deep] autorise la
     * vérification privilégiée de l'écouteur (une commande dumpsys : pas à chaque rafraîchissement d'écran).
     */
    fun evaluate(now: Long, probe: Privileged.AppState? = null, deep: Boolean = true): XInfo {
        if (!isInstalled()) return XInfo(XState.MISSING, ctx.tr(R.string.ev_xdrip_missing))
        val notes = mutableListOf<String>()
        if (XdripWatch.changedAt > 0L) {
            notes += ctx.tr(R.string.ev_xdrip_last, Fmt.ago(ctx, XdripWatch.changedAt))
        }
        if (XdripWatch.camapsUpdates > 0) {
            notes += ctx.tr(R.string.ev_xdrip_updates, XdripWatch.camapsUpdates)
        }
        val stale = XdripWatch.camapsUpdates >= STALE_UPDATES &&
            now - XdripWatch.windowStart >= STALE_MIN * 60_000L

        val listener = XdripWatch.LISTENER.flattenToString()
        val enabled = Sys.listenerEnabled(ctx, listener)
        if (enabled == false) {
            return XInfo(XState.REVOKED, (listOf(ctx.tr(R.string.ev_xdrip_revoked)) + notes).joinToString(" · "))
        }
        if (probe != null) {
            if (probe.stopped == true || !probe.running) {
                notes.add(0, ctx.tr(if (probe.stopped == true) R.string.ev_xdrip_stopped else R.string.ev_xdrip_no_process))
                return XInfo(XState.DEAD, notes.joinToString(" · "))
            }
            if (deep && Privileged.listenerLive(listener) == false) {
                notes.add(0, ctx.tr(R.string.ev_xdrip_unbound))
                return XInfo(XState.UNBOUND, notes.joinToString(" · "))
            }
        } else {
            val active = PresenceListener.activePackages()
                ?: return XInfo(XState.UNKNOWN, ctx.tr(R.string.ev_no_notif_access))
            if (!active.contains(XdripWatch.PKG)) {
                notes.add(0, ctx.tr(R.string.ev_xdrip_no_notification))
                return XInfo(XState.DEAD, notes.joinToString(" · "))
            }
        }
        return XInfo(if (stale) XState.STALE else XState.OK, notes.joinToString(" · "))
    }

    /**
     * Répare : relance xDrip+ s'il est arrêté, puis rebranche son écouteur de notifications —
     * par Shizuku/root (immédiat, même verrouillé), sinon par les Réglages via le service
     * d'accessibilité (au déverrouillage si besoin). xDrip+ n'est jamais arrêté.
     */
    fun repair(state: XState, now: Long): Fix {
        val steps = mutableListOf<String>()
        if (state == XState.DEAD) {
            steps += ctx.tr(if (launch()) R.string.fix_launched else R.string.fix_launch_failed)
        }
        val listener = XdripWatch.LISTENER.flattenToString()
        if (Shell.canElevate()) {
            val r = Privileged.rebindListener(listener, state == XState.REVOKED)
            if (r.ok) {
                XdripWatch.restart(now)
                steps += ctx.tr(R.string.fix_rebound, r.how)
                return Fix(true, steps.joinToString(", "))
            }
            steps += ctx.tr(R.string.fix_rebind_failed, r.how, r.output.take(60))
        }
        val label = ctx.tr(R.string.ui_xdrip)
        return when (SettingsFixer.request(ctx, XdripWatch.LISTENER, label)) {
            SettingsFixer.Request.STARTED -> {
                steps += ctx.tr(R.string.fix_settings)
                Fix(true, steps.joinToString(", "))
            }
            SettingsFixer.Request.WAIT_UNLOCK -> {
                steps += ctx.tr(R.string.fix_wait_unlock)
                Fix(false, steps.joinToString(", "))
            }
            SettingsFixer.Request.BUSY -> {
                steps += ctx.tr(R.string.fix_busy)
                Fix(true, steps.joinToString(", "))
            }
            SettingsFixer.Request.UNAVAILABLE -> {
                steps += ctx.tr(R.string.fix_no_way)
                Fix(false, steps.joinToString(", "))
            }
        }
    }

    /** Ouvre xDrip+ (comme depuis le lanceur) : redémarre son processus et ses services. */
    fun launch(): Boolean {
        val intent = try {
            ctx.packageManager.getLaunchIntentForPackage(XdripWatch.PKG)
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
                return true
            } catch (t: Throwable) {
                // repli ci-dessous
            }
        }
        return Shell.runPrivileged("monkey -p ${XdripWatch.PKG} -c android.intent.category.LAUNCHER 1").ok
    }
}
