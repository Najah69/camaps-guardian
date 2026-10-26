package com.najah.camapsguardian.core

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.najah.camapsguardian.R

/**
 * Réparation sans Shizuku : ouvre la page « Accès aux notifications » d'une application
 * dans les Réglages, décoche puis recoche son interrupteur (avec les deux confirmations),
 * et revient en arrière — exactement le geste à la main. Android n'affiche pas les
 * Réglages sur l'écran verrouillé : la demande attend alors le prochain déverrouillage.
 *
 * Le service n'agit que dans les Réglages, que sur demande de Guardian, et seulement si
 * la page affichée porte le nom de l'application visée.
 */
class SettingsFixer : AccessibilityService() {

    enum class Request { STARTED, WAIT_UNLOCK, BUSY, UNAVAILABLE }

    private enum class Phase { OPEN, OFF, ON }

    companion object {
        private const val SETTINGS = "com.android.settings"
        private const val ACTION_DETAIL = "android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS"
        private const val EXTRA_COMPONENT = "android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME"
        private const val TIMEOUT_MS = 25_000L
        private const val MAX_CLICKS = 8

        @Volatile
        private var instance: SettingsFixer? = null

        @Volatile
        private var pending: Pair<ComponentName, String>? = null

        fun isActive(): Boolean = instance != null

        fun request(ctx: Context, target: ComponentName, label: String): Request {
            val svc = instance ?: return Request.UNAVAILABLE
            if (Build.VERSION.SDK_INT < 30) return Request.UNAVAILABLE
            if (svc.running) return Request.BUSY
            if (locked(ctx)) {
                pending = target to label
                return Request.WAIT_UNLOCK
            }
            svc.handler.post { svc.begin(target, label) }
            return Request.STARTED
        }

        private fun locked(ctx: Context): Boolean = try {
            val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            km.isKeyguardLocked || !pm.isInteractive
        } catch (t: Throwable) {
            true
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false
    private var target: ComponentName? = null
    private var label = ""
    private var phase = Phase.OPEN
    private var startedAt = 0L
    private var clicks = 0
    private var unlock: BroadcastReceiver? = null

    override fun onServiceConnected() {
        // Android peut rappeler cette méthode plusieurs fois de suite (constaté : 4 fois en
        // 12 s) : un seul message au journal et un seul récepteur de déverrouillage.
        if (instance !== this) Logbook.add(tr(R.string.cat_notif), tr(R.string.log_a11y_on))
        instance = this
        unlock?.let { runCatching { unregisterReceiver(it) } }
        unlock = null
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val p = pending ?: return
                pending = null
                Logbook.add(tr(R.string.cat_notif), tr(R.string.log_fix_unlock))
                // Laisser le lanceur s'afficher avant d'ouvrir les Réglages.
                handler.postDelayed({ if (!running) begin(p.first, p.second) }, 1500L)
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                this, r, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED
            )
            unlock = r
        }
    }

    override fun onDestroy() {
        instance = null
        unlock?.let { runCatching { unregisterReceiver(it) } }
        unlock = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Rien : la réparation avance par sondage (step), pas au fil des événements.
    }

    override fun onInterrupt() {}

    private fun begin(target: ComponentName, label: String) {
        if (running) return
        running = true
        this.target = target
        this.label = label
        phase = Phase.OPEN
        startedAt = System.currentTimeMillis()
        clicks = 0
        Logbook.add(tr(R.string.cat_notif), tr(R.string.log_fix_start, label))
        val i = Intent(ACTION_DETAIL)
            .putExtra(EXTRA_COMPONENT, target.flattenToString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        try {
            startActivity(i)
        } catch (t: Throwable) {
            finish(false, tr(R.string.fix_reason_intent))
            return
        }
        handler.postDelayed(::step, 900L)
    }

    private fun step() {
        if (!running) return
        if (System.currentTimeMillis() - startedAt > TIMEOUT_MS) {
            finish(false, tr(R.string.fix_reason_timeout, phase.name))
            return
        }
        if (clicks > MAX_CLICKS) {
            finish(false, tr(R.string.fix_reason_clicks))
            return
        }
        val roots = settingsRoots()
        val button = roots.firstNotNullOfOrNull {
            it.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull()
        }
        val page = roots.any { it.findAccessibilityNodeInfosByText(label).isNotEmpty() }
        val sw = if (page) roots.firstNotNullOfOrNull { findSwitch(it) } else null
        var delay = 600L
        when (phase) {
            Phase.OPEN -> if (sw != null) phase = if (sw.isChecked) Phase.OFF else Phase.ON
            Phase.OFF -> when {
                button != null -> { click(button); delay = 900L }
                sw == null -> {}
                sw.isChecked -> { click(sw); delay = 900L }
                else -> phase = Phase.ON
            }
            Phase.ON -> when {
                button != null -> { click(button); delay = 900L }
                sw == null -> {}
                !sw.isChecked -> { click(sw); delay = 900L }
                else -> {
                    finish(true, "")
                    return
                }
            }
        }
        handler.postDelayed(::step, delay)
    }

    private fun settingsRoots(): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        runCatching { windows.mapNotNullTo(list) { it.root } }
        runCatching { rootInActiveWindow?.let { list += it } }
        return list.filter { it.packageName?.toString() == SETTINGS }
    }

    /** Le premier interrupteur de la page : l'interrupteur principal « Autoriser l'accès ». */
    private fun findSwitch(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val cls = node.className?.toString() ?: ""
        if (node.isCheckable && cls.contains("Switch")) return node
        for (i in 0 until node.childCount) {
            findSwitch(node.getChild(i))?.let { return it }
        }
        return null
    }

    private fun click(node: AccessibilityNodeInfo) {
        clicks++
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        (n ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun finish(ok: Boolean, reason: String) {
        running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
        if (ok) {
            if (target == XdripWatch.LISTENER) XdripWatch.restart(System.currentTimeMillis())
            Logbook.add(tr(R.string.cat_notif), tr(R.string.log_fix_done, label))
        } else {
            Logbook.add(tr(R.string.cat_notif), tr(R.string.log_fix_failed, label, reason))
        }
    }
}

/** Le service d'accessibilité de Guardian est-il activé dans les réglages ? */
fun Context.settingsFixerEnabled(): Boolean = try {
    val flat = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
    flat.split(':').any {
        ComponentName.unflattenFromString(it) == ComponentName(this, SettingsFixer::class.java)
    }
} catch (t: Throwable) {
    false
}
