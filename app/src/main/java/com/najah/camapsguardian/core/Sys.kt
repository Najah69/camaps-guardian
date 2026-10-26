package com.najah.camapsguardian.core

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Interrogation de l'état du téléphone et des autorisations. */
object Sys {

    fun power(ctx: Context) = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager

    /** « Batterie sans restriction » : indispensable pour que la ronde survive. */
    fun ignoringBattery(ctx: Context): Boolean = try {
        power(ctx).isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (t: Throwable) {
        false
    }

    /** Mode Doze (veille profonde) : les alarmes-horloge percent quand même. */
    fun inDoze(ctx: Context): Boolean = try {
        power(ctx).isDeviceIdleMode
    } catch (t: Throwable) {
        false
    }

    fun canOverlay(ctx: Context): Boolean = try {
        Settings.canDrawOverlays(ctx)
    } catch (t: Throwable) {
        false
    }

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
                )
            }
        } catch (t: Throwable) {
            AppOpsManager.MODE_DEFAULT
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun hasNotifAccess(ctx: Context): Boolean {
        val flat = enabledListeners(ctx) ?: return false
        return flat.split(':').any { it.contains(ctx.packageName) }
    }

    private fun enabledListeners(ctx: Context): String? = try {
        Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
    } catch (t: Throwable) {
        null
    }

    /** L'écouteur donné (« paquet/classe ») est-il coché ? null si le réglage est illisible. */
    fun listenerEnabled(ctx: Context, component: String): Boolean? {
        val flat = enabledListeners(ctx) ?: return null
        return flat.split(':').any { it == component }
    }

    fun hasNotifPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    fun hasBtPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

    fun canExactAlarms(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        } else true
    } catch (t: Throwable) {
        true
    }

    data class Battery(val percent: Int, val charging: Boolean, val tempC: Float)

    fun battery(ctx: Context): Battery = try {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = plugged != 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        Battery(percent, charging, temp)
    } catch (t: Throwable) {
        Battery(-1, false, 0f)
    }

    fun deviceLabel(): String = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
}
