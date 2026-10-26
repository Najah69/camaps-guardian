package com.najah.camapsguardian.core

import android.content.Context
import com.najah.camapsguardian.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Photographie de l'état, pour l'interface. */
data class Snap(
    val ready: Boolean = false,
    val enabled: Boolean = false,
    val running: Boolean = false,
    val checkMinutes: Int = 10,
    val recoverMinutes: Int = 10,
    val btAuto: Boolean = true,
    val appAuto: Boolean = true,
    val relaunchOnBtLoss: Boolean = true,
    val aggressiveCycle: Boolean = false,
    val btOn: Boolean = false,
    val btHardware: Boolean = true,
    val btDevices: Int? = null,
    val btBonded: Int? = null,
    val presence: Presence = Presence.UNKNOWN,
    val presenceEvidence: String = "",
    val installed: List<String> = emptyList(),
    val targets: String = "",
    val battery: Int = -1,
    val charging: Boolean = false,
    val doze: Boolean = false,
    val ignoringBattery: Boolean = false,
    val overlay: Boolean = false,
    val usageAccess: Boolean = false,
    val notifAccess: Boolean = false,
    val btPerm: Boolean = false,
    val notifPerm: Boolean = true,
    val exactAlarms: Boolean = true,
    val root: Boolean = false,
    val shizuku: Boolean = false,
    val shizukuRunning: Boolean = false,
    val a11y: Boolean = false,
    val xdripWatch: Boolean = true,
    val xdrip: XState = XState.UNKNOWN,
    val xdripEvidence: String = "",
    val xdripRepairs: Int = 0,
    val lastCheck: Long = 0L,
    val nextCheck: Long = 0L,
    val checks: Int = 0,
    val btFixes: Int = 0,
    val relaunches: Int = 0,
    val lastSummary: String = ""
)

object Status {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(Snap())
    val state: StateFlow<Snap> = _state

    fun refresh(ctx: Context) {
        scope.launch {
            val app = ctx.applicationContext
            val p = Prefs.get(app).value
            val bt = BluetoothCtl(app)
            val cam = CamapsCtl(app)
            val battery = Sys.battery(app)
            val xdrip = XdripCtl(app).evaluate(System.currentTimeMillis(), probe = null, deep = false)
            val presence =
                if (p.targetList.isEmpty()) {
                    PresenceInfo(Presence.NO_TARGET, app.tr(R.string.ev_no_target))
                } else {
                    cam.evaluate()
                }
            _state.value = Snap(
                ready = true,
                enabled = p.enabled,
                running = WatchdogService.running,
                checkMinutes = p.checkMinutes,
                recoverMinutes = p.recoverMinutes,
                btAuto = p.btAuto,
                appAuto = p.appAuto,
                relaunchOnBtLoss = p.relaunchOnBtLoss,
                aggressiveCycle = p.aggressiveCycle,
                btOn = bt.isOn(),
                btHardware = bt.hasHardware(),
                btDevices = bt.connectedDevices(),
                btBonded = bt.bondedDevices(),
                presence = presence.state,
                presenceEvidence = presence.evidence,
                installed = cam.installed(),
                targets = p.targets,
                battery = battery.percent,
                charging = battery.charging,
                doze = Sys.inDoze(app),
                ignoringBattery = Sys.ignoringBattery(app),
                overlay = Sys.canOverlay(app),
                usageAccess = Sys.hasUsageAccess(app),
                notifAccess = Sys.hasNotifAccess(app),
                btPerm = Sys.hasBtPermission(app),
                notifPerm = Sys.hasNotifPermission(app),
                exactAlarms = Sys.canExactAlarms(app),
                root = Shell.hasRoot(),
                shizuku = Shell.hasShizuku(),
                shizukuRunning = Shell.shizukuRunning(),
                a11y = app.settingsFixerEnabled(),
                xdripWatch = p.xdripWatch,
                xdrip = xdrip.state,
                xdripEvidence = xdrip.evidence,
                xdripRepairs = p.xdripRepairs,
                lastCheck = p.lastCheckAt,
                nextCheck = p.nextCheckAt,
                checks = p.checks,
                btFixes = p.btFixes,
                relaunches = p.relaunches,
                lastSummary = p.lastSummary
            )
        }
    }
}
