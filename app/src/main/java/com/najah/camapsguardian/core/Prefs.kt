package com.najah.camapsguardian.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tous les réglages et l'état durable du watchdog.
 * Une seule instance par processus (le service et l'interface partagent la même).
 */
data class P(
    val enabled: Boolean = false,
    /** Intervalle de la ronde de contrôle, en minutes (5, 10, 15, 30). */
    val checkMinutes: Int = 10,
    /** Intervalle minimal entre deux tentatives de rétablissement, en minutes. */
    val recoverMinutes: Int = 10,
    val btAuto: Boolean = true,
    val appAuto: Boolean = true,
    val relaunchOnBtLoss: Boolean = true,
    val aggressiveCycle: Boolean = false,
    val wakeScreen: Boolean = false,
    val targets: String = DEFAULT_TARGETS,
    val lastCheckAt: Long = 0L,
    val lastRecoveryAt: Long = 0L,
    val nextCheckAt: Long = 0L,
    val lastHintAt: Long = 0L,
    val btLossStreak: Int = 0,
    val appDeadStreak: Int = 0,
    val checks: Int = 0,
    val btFixes: Int = 0,
    val relaunches: Int = 0,
    val lastSummary: String = ""
) {
    val targetList: List<String>
        get() = targets.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        const val DEFAULT_TARGETS = "com.camdiab.fx_alert.mgdl,com.camdiab.fx.alert.mgdl,com.camdiab.fx.alert,com.camdiab.fx"
        val INTERVALS = listOf(5, 10, 15, 30)
    }
}

class Prefs private constructor(private val sp: SharedPreferences) {

    private val _state = MutableStateFlow(read())
    val state: StateFlow<P> = _state
    val value: P get() = _state.value

    fun update(block: (P) -> P): P {
        val next = block(_state.value)
        write(next)
        _state.value = next
        return next
    }

    private fun read() = P(
        enabled = sp.getBoolean("enabled", false),
        checkMinutes = sp.getInt("checkMinutes", 10),
        recoverMinutes = sp.getInt("recoverMinutes", 10),
        btAuto = sp.getBoolean("btAuto", true),
        appAuto = sp.getBoolean("appAuto", true),
        relaunchOnBtLoss = sp.getBoolean("relaunchOnBtLoss", true),
        aggressiveCycle = sp.getBoolean("aggressiveCycle", false),
        wakeScreen = sp.getBoolean("wakeScreen", false),
        targets = sp.getString("targets", P.DEFAULT_TARGETS) ?: P.DEFAULT_TARGETS,
        lastCheckAt = sp.getLong("lastCheckAt", 0L),
        lastRecoveryAt = sp.getLong("lastRecoveryAt", 0L),
        nextCheckAt = sp.getLong("nextCheckAt", 0L),
        lastHintAt = sp.getLong("lastHintAt", 0L),
        btLossStreak = sp.getInt("btLossStreak", 0),
        appDeadStreak = sp.getInt("appDeadStreak", 0),
        checks = sp.getInt("checks", 0),
        btFixes = sp.getInt("btFixes", 0),
        relaunches = sp.getInt("relaunches", 0),
        lastSummary = sp.getString("lastSummary", "") ?: ""
    )

    private fun write(p: P) {
        sp.edit()
            .putBoolean("enabled", p.enabled)
            .putInt("checkMinutes", p.checkMinutes)
            .putInt("recoverMinutes", p.recoverMinutes)
            .putBoolean("btAuto", p.btAuto)
            .putBoolean("appAuto", p.appAuto)
            .putBoolean("relaunchOnBtLoss", p.relaunchOnBtLoss)
            .putBoolean("aggressiveCycle", p.aggressiveCycle)
            .putBoolean("wakeScreen", p.wakeScreen)
            .putString("targets", p.targets)
            .putLong("lastCheckAt", p.lastCheckAt)
            .putLong("lastRecoveryAt", p.lastRecoveryAt)
            .putLong("nextCheckAt", p.nextCheckAt)
            .putLong("lastHintAt", p.lastHintAt)
            .putInt("btLossStreak", p.btLossStreak)
            .putInt("appDeadStreak", p.appDeadStreak)
            .putInt("checks", p.checks)
            .putInt("btFixes", p.btFixes)
            .putInt("relaunches", p.relaunches)
            .putString("lastSummary", p.lastSummary)
            .apply()
    }

    companion object {
        @Volatile
        private var instance: Prefs? = null

        fun get(ctx: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(
                    ctx.applicationContext.getSharedPreferences("guardian", Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}
