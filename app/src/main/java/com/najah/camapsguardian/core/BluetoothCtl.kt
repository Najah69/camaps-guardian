package com.najah.camapsguardian.core

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class BtEnable { ON, ENABLED, NEEDS_USER, FAILED }

/**
 * Pilotage du Bluetooth.
 *
 * Trois stratégies, dans l'ordre :
 *  1. BluetoothAdapter.enable() — fonctionne si l'application ne cible pas Android 13+
 *     (d'où targetSdk 32) ;
 *  2. commande système via root ou Shizuku ;
 *  3. sinon : notification plein écran qui demande l'accord de l'utilisateur.
 */
class BluetoothCtl(private val ctx: Context) {

    private val manager: BluetoothManager? =
        ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    val adapter: BluetoothAdapter? get() = manager?.adapter

    fun hasHardware(): Boolean = adapter != null

    fun isOn(): Boolean = try {
        adapter?.isEnabled == true
    } catch (t: Throwable) {
        false
    }

    /** Nombre d'appareils Bluetooth actuellement connectés (GATT), null si non déterminable. */
    fun connectedDevices(): Int? {
        val m = manager ?: return null
        if (!Sys.hasBtPermission(ctx)) return null
        return try {
            val gatt = m.getConnectedDevices(BluetoothProfile.GATT)
            val server = m.getConnectedDevices(BluetoothProfile.GATT_SERVER)
            (gatt + server).map { it.address }.toSet().size
        } catch (t: Throwable) {
            null
        }
    }

    fun bondedDevices(): Int? = try {
        adapter?.bondedDevices?.size
    } catch (t: Throwable) {
        null
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    suspend fun ensureOn(timeoutMs: Long = 12_000L): BtEnable = withContext(Dispatchers.IO) {
        if (isOn()) return@withContext BtEnable.ON
        val a = adapter ?: return@withContext BtEnable.FAILED

        try {
            val result = a.enable()
            Logbook.add("bt", "BluetoothAdapter.enable() → $result")
        } catch (t: Throwable) {
            Logbook.add("bt", "enable() refusé : ${t.javaClass.simpleName} ${t.message ?: ""}")
        }
        if (waitOn(3500L)) return@withContext BtEnable.ENABLED

        for (cmd in listOf("cmd bluetooth_manager enable", "svc bluetooth enable")) {
            val r = Shell.runPrivileged(cmd)
            if (r.ok) {
                Logbook.add("bt", "« $cmd » via ${r.how} → ${r.output.take(80)}")
                if (waitOn(4000L)) return@withContext BtEnable.ENABLED
            } else {
                Logbook.add("bt", "« $cmd » via ${r.how} refusé (${r.output.take(60)})")
            }
        }
        return@withContext BtEnable.NEEDS_USER
    }

    /** Extinction puis rallumage (mode agressif, jamais automatique sans accord explicite). */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    suspend fun cycle(): Boolean = withContext(Dispatchers.IO) {
        val a = adapter ?: return@withContext false
        Logbook.add("bt", "cycle Bluetooth demandé")
        try {
            a.disable()
        } catch (t: Throwable) {
            Shell.runPrivileged("cmd bluetooth_manager disable")
        }
        SystemClock.sleep(2500L)
        val r = ensureOn(15_000L)
        Logbook.add("bt", "cycle terminé : ${r.name}")
        r == BtEnable.ENABLED || r == BtEnable.ON
    }

    private fun waitOn(ms: Long): Boolean {
        val end = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < end) {
            if (isOn()) return true
            SystemClock.sleep(250L)
        }
        return isOn()
    }
}
