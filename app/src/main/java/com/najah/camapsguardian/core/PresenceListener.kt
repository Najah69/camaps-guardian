package com.najah.camapsguardian.core

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
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

        fun lastSeen(pkgs: List<String>): Long =
            pkgs.mapNotNull { seen[it] }.maxOrNull() ?: 0L

        fun isConnected(): Boolean = connected
    }

    override fun onListenerConnected() {
        connected = true
        instance = this
        refreshQuietly()
        Logbook.add("notif", "accès aux notifications accordé")
    }

    override fun onListenerDisconnected() {
        connected = false
        synchronized(active) { active.clear() }
        Logbook.add("notif", "accès aux notifications perdu")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        synchronized(active) { active.add(sbn.packageName) }
        seen[sbn.packageName] = System.currentTimeMillis()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // On laisse l'ensemble se rafraîchir : une notification « ongoing » peut être republiée.
        seen[sbn.packageName] = System.currentTimeMillis()
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
        } catch (t: Throwable) {
            // pas encore connecté
        }
    }
}
