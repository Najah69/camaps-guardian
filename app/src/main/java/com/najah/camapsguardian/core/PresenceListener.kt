package com.najah.camapsguardian.core

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.najah.camapsguardian.R
import java.util.Collections

/**
 * Écoute des notifications : la notification persistante de CamAPS FX est le meilleur
 * indicateur, sans root, que sa boucle tourne encore.
 */
class PresenceListener : NotificationListenerService() {

    companion object {
        private val active = Collections.synchronizedSet(HashSet<String>())
        private val seen = Collections.synchronizedMap(HashMap<String, Long>())

        @Volatile
        var connected: Boolean = false

        @Volatile
        private var instance: PresenceListener? = null

        /** Paquets ayant une notification à l'écran, ou null si l'accès n'est pas accordé. */
        fun activePackages(): Set<String>? {
            if (!connected) return null
            instance?.refreshQuietly()
            synchronized(active) { return HashSet(active) }
        }

        /**
         * Vrai si l'un de [pkgs] a une notification **publiée après** [since] : la preuve qu'il a
         * redémarré. Une notification restée affichée depuis avant un arrêt forcé ne compte pas.
         */
        fun postedSince(pkgs: List<String>, since: Long): Boolean = try {
            instance?.activeNotifications?.any { it.packageName in pkgs && it.postTime > since } == true
        } catch (t: Throwable) {
            false
        }

        fun lastSeen(pkgs: List<String>): Long =
            pkgs.mapNotNull { seen[it] }.maxOrNull() ?: 0L

        fun isConnected(): Boolean = connected

        /**
         * Demande à Android de rebrancher l'écouteur de Guardian (permis pour sa propre
         * application) : après un arrêt forcé, Android peut l'oublier tout en le laissant coché.
         */
        fun rebind(ctx: Context) {
            runCatching {
                requestRebind(ComponentName(ctx, PresenceListener::class.java))
            }
        }
    }

    override fun onListenerConnected() {
        connected = true
        instance = this
        refreshQuietly()
        Logbook.add(tr(R.string.cat_notif), tr(R.string.log_notif_granted))
    }

    override fun onListenerDisconnected() {
        connected = false
        synchronized(active) { active.clear() }
        Logbook.add(tr(R.string.cat_notif), tr(R.string.log_notif_lost))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val now = System.currentTimeMillis()
        synchronized(active) { active.add(sbn.packageName) }
        seen[sbn.packageName] = now
        observe(sbn, now, posted = true)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // On laisse l'ensemble se rafraîchir : une notification « ongoing » peut être republiée.
        seen[sbn.packageName] = System.currentTimeMillis()
    }

    /**
     * Suivi de xDrip+ : sa notification permanente affiche la dernière glycémie (titre) et
     * la variation (texte) ; celle de CamAPS FX est republiée à chaque nouvelle valeur du capteur.
     */
    private fun observe(sbn: StatusBarNotification, now: Long, posted: Boolean) {
        try {
            if (sbn.packageName == XdripWatch.PKG && sbn.isOngoing) {
                val ex = sbn.notification.extras
                val text = "${ex.getCharSequence(Notification.EXTRA_TITLE) ?: ""}|" +
                    "${ex.getCharSequence(Notification.EXTRA_TEXT) ?: ""}"
                if (text != "|") XdripWatch.onXdrip(text, now)
            } else if (posted && sbn.isOngoing &&
                (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 &&
                Prefs.get(this).value.targetList.contains(sbn.packageName)
            ) {
                XdripWatch.onCamaps(now)
            }
        } catch (t: Throwable) {
            // une notification illisible ne doit rien casser
        }
    }

    private fun refreshQuietly() {
        try {
            val list = activeNotifications ?: return
            val now = System.currentTimeMillis()
            synchronized(active) {
                active.clear()
                list.forEach {
                    active.add(it.packageName)
                    seen[it.packageName] = now
                }
            }
            list.forEach { observe(it, now, posted = false) }
        } catch (t: Throwable) {
            // pas encore connecté
        }
    }
}
