package com.najah.camapsguardian.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.najah.camapsguardian.R
import com.najah.camapsguardian.core.Control
import com.najah.camapsguardian.core.Fmt
import com.najah.camapsguardian.core.History
import com.najah.camapsguardian.core.Logbook
import com.najah.camapsguardian.core.P
import com.najah.camapsguardian.core.Period
import com.najah.camapsguardian.core.Presence
import com.najah.camapsguardian.core.ReportPdf
import com.najah.camapsguardian.core.Shell
import com.najah.camapsguardian.core.Snap
import com.najah.camapsguardian.core.Status
import com.najah.camapsguardian.core.XState
import com.najah.camapsguardian.core.tr
import com.najah.camapsguardian.core.xLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Ok = Color(0xFF37D67A)
private val Warn = Color(0xFFFFB020)
private val Bad = Color(0xFFFF6B6B)
private val Dim = Color(0xFF9FB0C0)

@Composable
fun GuardianScreen() {
    val ctx = LocalContext.current
    val snap by Status.state.collectAsStateWithLifecycle()
    val logs by Logbook.flow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        Status.refresh(ctx)
        while (true) {
            delay(3000L)
            Status.refresh(ctx)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Header(snap) { on ->
                if (on) Control.enable(ctx) else Control.disable(ctx)
                Status.refresh(ctx)
            }

            StatusPanel(snap)

            IntervalPanel(snap)

            RecoveryPanel(ctx, snap)

            PermissionPanel(ctx, snap)

            Panel(stringResource(R.string.ui_actions)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (!busy) {
                                busy = true
                                scope.launch {
                                    message = Control.checkNow(ctx)
                                    busy = false
                                    Status.refresh(ctx)
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            stringResource(
                                if (busy) R.string.ui_checking else R.string.ui_check_now
                            ),
                            fontSize = 12.sp
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                message = ctx.tr(
                                    R.string.ui_camaps_prefix,
                                    Control.relaunchCamaps(ctx)
                                )
                                Status.refresh(ctx)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.ui_restart_camaps), fontSize = 12.sp)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val found = Control.detectCamaps(ctx)
                                message = if (found.isEmpty()) {
                                    ctx.tr(R.string.ui_no_package_found)
                                } else {
                                    ctx.tr(R.string.ui_found, found.joinToString(", "))
                                }
                                Status.refresh(ctx)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.ui_detect_camaps), fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = {
                            Logbook.clear()
                            message = ctx.tr(R.string.ui_log_cleared)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.ui_clear_log), fontSize = 12.sp)
                    }
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            message = runCatching {
                                withContext(Dispatchers.IO) { Logbook.export(ctx) }
                            }.fold(
                                { ctx.tr(R.string.ui_log_exported, it) },
                                { ctx.tr(R.string.ui_log_export_failed, it.message ?: it.javaClass.simpleName) }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.ui_export_log), fontSize = 12.sp)
                }
                if (message.isNotBlank()) {
                    Text(message, fontSize = 11.sp, color = Dim)
                }
            }

            ReportPanel()

            LogPanel(logs)

            Footnote()
        }
    }
}

// ------------------------------------------------------------------ en-tête

@Composable
private fun Header(snap: Snap, onToggle: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(R.string.app_name),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            stringResource(R.string.app_sub),
            fontSize = 12.sp,
            color = Dim
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(
                        if (snap.enabled) R.string.ui_monitoring_on else R.string.ui_monitoring_off
                    ),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (snap.enabled) Ok else Warn
                )
                Text(
                    stringResource(
                        if (snap.enabled) {
                            if (snap.running) R.string.ui_service_running
                            else R.string.ui_service_restarted
                        } else {
                            R.string.ui_no_round_scheduled
                        }
                    ),
                    fontSize = 11.sp,
                    color = Dim
                )
            }
            Switch(checked = snap.enabled, onCheckedChange = onToggle)
        }
    }
}

// ------------------------------------------------------------------ état

@Composable
private fun StatusPanel(snap: Snap) {
    val ctx = LocalContext.current
    Panel(stringResource(R.string.ui_state)) {
        KeyLine(
            label = stringResource(R.string.ui_bluetooth),
            value = stringResource(
                when {
                    !snap.btHardware -> R.string.ui_bt_unavailable
                    snap.btOn -> R.string.ui_bt_on
                    else -> R.string.ui_bt_off
                }
            ),
            color = when {
                !snap.btHardware -> Bad
                snap.btOn -> Ok
                else -> Bad
            },
            note = if (snap.btOn) {
                stringResource(
                    R.string.ui_bt_counts,
                    snap.btDevices ?: "?",
                    snap.btBonded ?: "?"
                )
            } else {
                stringResource(R.string.ui_bt_auto_note)
            }
        )
        KeyLine(
            label = stringResource(R.string.ui_camaps_fx),
            value = stringResource(
                when (snap.presence) {
                    Presence.ALIVE -> R.string.ui_camaps_alive
                    Presence.NOT_ALIVE -> R.string.ui_camaps_mute
                    Presence.UNKNOWN -> R.string.ui_camaps_unknown
                    Presence.MISSING -> R.string.ui_camaps_missing
                    Presence.NO_TARGET -> R.string.ui_camaps_no_target
                }
            ),
            color = when (snap.presence) {
                Presence.ALIVE -> Ok
                Presence.NOT_ALIVE -> Bad
                Presence.UNKNOWN -> Warn
                Presence.MISSING -> Bad
                Presence.NO_TARGET -> Warn
            },
            note = snap.presenceEvidence.take(110)
        )
        if (snap.xdrip != XState.MISSING) {
            KeyLine(
                label = stringResource(R.string.ui_xdrip),
                value = if (snap.xdripWatch) ctx.xLabel(snap.xdrip) else stringResource(R.string.ui_xdrip_off),
                color = when {
                    !snap.xdripWatch -> Dim
                    snap.xdrip == XState.OK -> Ok
                    snap.xdrip == XState.UNKNOWN -> Warn
                    else -> Bad
                },
                note = stringResource(R.string.ui_xdrip_note, snap.xdripRepairs) +
                    if (snap.xdripEvidence.isNotBlank()) " · " + snap.xdripEvidence.take(90) else ""
            )
        }
        KeyLine(
            label = stringResource(R.string.ui_round),
            value = stringResource(
                if (snap.enabled) R.string.ui_round_active else R.string.ui_round_stopped
            ),
            color = if (snap.enabled) Ok else Warn,
            note = stringResource(
                R.string.ui_round_note,
                Fmt.ago(ctx, snap.lastCheck),
                Fmt.time(snap.nextCheck)
            )
        )
        KeyLine(
            label = stringResource(R.string.ui_battery),
            value = if (snap.battery >= 0) "${snap.battery} %" else "?",
            color = when {
                snap.battery < 0 -> Dim
                snap.charging -> Ok
                snap.battery <= 15 -> Bad
                else -> Ok
            },
            note = stringResource(
                if (snap.charging) R.string.ui_charging else R.string.ui_on_battery
            )
        )
        KeyLine(
            label = stringResource(R.string.ui_sleep),
            value = stringResource(
                if (snap.doze) R.string.ui_doze else R.string.ui_normal
            ),
            color = if (snap.doze) Warn else Ok,
            note = stringResource(
                R.string.ui_sleep_note,
                snap.checks,
                snap.btFixes,
                snap.relaunches
            )
        )
        if (snap.lastSummary.isNotBlank()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Text(
                snap.lastSummary,
                fontSize = 11.sp,
                color = Dim,
                fontFamily = FontFamily.Monospace,
                lineHeight = 15.sp
            )
        }
    }
}

// ------------------------------------------------------------------ fréquences

@Composable
private fun IntervalPanel(snap: Snap) {
    val ctx = LocalContext.current
    Panel(stringResource(R.string.ui_frequency)) {
        Text(
            stringResource(R.string.ui_check_round),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        Chips(P.INTERVALS, snap.checkMinutes) {
            Control.setInterval(ctx, it)
            Status.refresh(ctx)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.ui_recovery_interval),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        Chips(P.INTERVALS, snap.recoverMinutes) {
            Control.setRecover(ctx, it)
            Status.refresh(ctx)
        }
        Text(
            stringResource(R.string.ui_frequency_note),
            fontSize = 10.sp,
            color = Dim,
            lineHeight = 13.sp
        )
    }
}

@Composable
private fun Chips(options: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { minutes ->
            FilterChip(
                selected = minutes == selected,
                onClick = { onSelect(minutes) },
                label = {
                    Text(stringResource(R.string.ui_minutes, minutes), fontSize = 11.sp)
                }
            )
        }
    }
}

// ------------------------------------------------------------------ rétablissement

@Composable
private fun RecoveryPanel(ctx: Context, snap: Snap) {
    Panel(stringResource(R.string.ui_recovery)) {
        ToggleLine(
            title = stringResource(R.string.ui_toggle_bt),
            subtitle = stringResource(R.string.ui_toggle_bt_sub),
            checked = snap.btAuto,
            onChange = { value ->
                Control.setFlag(ctx) { p -> p.copy(btAuto = value) }
                Status.refresh(ctx)
            }
        )
        ToggleLine(
            title = stringResource(R.string.ui_toggle_app),
            subtitle = stringResource(R.string.ui_toggle_app_sub),
            checked = snap.appAuto,
            onChange = { value ->
                Control.setFlag(ctx) { p -> p.copy(appAuto = value) }
                Status.refresh(ctx)
            }
        )
        ToggleLine(
            title = stringResource(R.string.ui_toggle_btloss),
            subtitle = stringResource(R.string.ui_toggle_btloss_sub),
            checked = snap.relaunchOnBtLoss,
            onChange = { value ->
                Control.setFlag(ctx) { p -> p.copy(relaunchOnBtLoss = value) }
                Status.refresh(ctx)
            }
        )
        ToggleLine(
            title = stringResource(R.string.ui_toggle_xdrip),
            subtitle = stringResource(R.string.ui_toggle_xdrip_sub),
            checked = snap.xdripWatch,
            onChange = { value ->
                Control.setFlag(ctx) { p -> p.copy(xdripWatch = value) }
                Status.refresh(ctx)
            }
        )
        ToggleLine(
            title = stringResource(R.string.ui_toggle_cycle),
            subtitle = stringResource(R.string.ui_toggle_cycle_sub),
            checked = snap.aggressiveCycle,
            onChange = { value ->
                Control.setFlag(ctx) { p -> p.copy(aggressiveCycle = value) }
                Status.refresh(ctx)
            }
        )
    }
}

@Composable
private fun ToggleLine(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 10.sp, color = Dim, lineHeight = 13.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ------------------------------------------------------------------ autorisations

@Composable
private fun PermissionPanel(ctx: Context, snap: Snap) {
    Panel(stringResource(R.string.ui_permissions)) {
        PermLine(
            title = stringResource(R.string.ui_perm_battery),
            ok = snap.ignoringBattery,
            onOpen = { openBattery(ctx) }
        )
        PermLine(
            title = stringResource(R.string.ui_perm_overlay),
            ok = snap.overlay,
            onOpen = { openOverlay(ctx) }
        )
        PermLine(
            title = stringResource(R.string.ui_perm_notifications),
            ok = snap.notifAccess,
            onOpen = { openNotifAccess(ctx) }
        )
        PermLine(
            title = stringResource(R.string.ui_perm_usage),
            ok = snap.usageAccess,
            onOpen = { openUsage(ctx) }
        )
        PermLine(
            title = stringResource(R.string.ui_perm_alarms),
            ok = snap.exactAlarms,
            onOpen = { openExactAlarms(ctx) }
        )
        PermLine(
            title = stringResource(R.string.ui_perm_bt),
            ok = snap.btPerm,
            onOpen = { openAppDetails(ctx) }
        )
        PermLine(
            title = stringResource(R.string.ui_perm_a11y),
            ok = snap.a11y,
            onOpen = { launch(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    if (snap.root) R.string.ui_privileges_root
                    else if (snap.shizuku) R.string.ui_privileges_shizuku
                    else R.string.ui_privileges_none
                ),
                Modifier.weight(1f),
                fontSize = 12.sp,
                color = if (snap.root || snap.shizuku) Ok else Dim
            )
            if (!snap.root && !snap.shizuku) {
                TextButton(onClick = {
                    Shell.requestShizukuPermission()
                    Status.refresh(ctx)
                }) {
                    Text(
                        stringResource(
                            if (snap.shizukuRunning) R.string.ui_shizuku_allow else R.string.ui_shizuku_stopped
                        ),
                        fontSize = 11.sp
                    )
                }
            }
        }
        Text(
            stringResource(R.string.ui_privileges_note),
            fontSize = 10.sp,
            color = Dim,
            lineHeight = 13.sp
        )
    }
}

@Composable
private fun PermLine(title: String, ok: Boolean, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "●" else "○", color = if (ok) Ok else Bad, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            Modifier.weight(1f),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (ok) {
            Text(stringResource(R.string.ui_ok), fontSize = 11.sp, color = Ok)
        } else {
            TextButton(onClick = onOpen) {
                Text(stringResource(R.string.ui_open), fontSize = 11.sp)
            }
        }
    }
}

// ------------------------------------------------------------------ journal

@Composable
private fun LogPanel(logs: List<String>) {
    Panel(stringResource(R.string.ui_log)) {
        if (logs.isEmpty()) {
            Text(stringResource(R.string.ui_log_empty), fontSize = 11.sp, color = Dim)
        } else {
            Text(
                logs.takeLast(40).joinToString("\n"),
                fontSize = 9.sp,
                lineHeight = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = Dim
            )
        }
    }
}

@Composable
private fun Footnote() {
    Text(
        stringResource(R.string.ui_footnote),
        fontSize = 10.sp,
        color = Dim,
        lineHeight = 14.sp,
        textAlign = TextAlign.Start
    )
}

// ------------------------------------------------------------------ rapport

@Composable
private fun ReportPanel() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var period by remember { mutableStateOf(Period.WEEK) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var pdf by remember { mutableStateOf<Uri?>(null) }
    Panel(stringResource(R.string.ui_report)) {
        Text(
            stringResource(R.string.ui_report_note, History.KEEP_DAYS),
            fontSize = 10.sp,
            color = Dim,
            lineHeight = 13.sp
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                Period.DAY to R.string.ui_report_day,
                Period.WEEK to R.string.ui_report_week,
                Period.MONTH to R.string.ui_report_month,
                Period.QUARTER to R.string.ui_report_quarter
            ).forEach { (p, label) ->
                FilterChip(
                    selected = p == period,
                    onClick = { period = p },
                    label = { Text(stringResource(label), fontSize = 11.sp) }
                )
            }
        }
        Button(
            onClick = {
                if (!busy) {
                    busy = true
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { ReportPdf.export(ctx, period) }
                        }.fold(
                            {
                                result = ctx.tr(R.string.ui_report_done, it.label)
                                pdf = it.uri
                            },
                            {
                                result = ctx.tr(
                                    R.string.ui_report_failed,
                                    it.message ?: it.javaClass.simpleName
                                )
                                pdf = null
                            }
                        )
                        busy = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(if (busy) R.string.ui_report_making else R.string.ui_report_make),
                fontSize = 12.sp
            )
        }
        if (result.isNotBlank()) {
            Text(result, fontSize = 11.sp, color = Dim)
        }
        pdf?.let { uri ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val view = Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/pdf")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        if (!launch(ctx, view)) result = ctx.tr(R.string.ui_report_no_viewer)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.ui_report_open), fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("application/pdf")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        launch(ctx, Intent.createChooser(send, null))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.ui_report_share), fontSize = 12.sp)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ briques

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(
            title.uppercase(),
            fontSize = 10.sp,
            letterSpacing = 1.2.sp,
            color = Dim,
            fontWeight = FontWeight.Bold
        )
        content()
    }
}

@Composable
private fun KeyLine(label: String, value: String, color: Color, note: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            Modifier.weight(0.72f),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2
        )
        Column(Modifier.weight(1.28f), horizontalAlignment = Alignment.End) {
            Text(
                value,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = color,
                textAlign = TextAlign.End
            )
            if (!note.isNullOrBlank()) {
                Text(
                    note,
                    fontSize = 10.sp,
                    color = Dim,
                    textAlign = TextAlign.End,
                    lineHeight = 13.sp
                )
            }
        }
    }
}

// ------------------------------------------------------------------ intents

private fun launch(ctx: Context, intent: Intent): Boolean = try {
    ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (t: Throwable) {
    false
}

private fun openBattery(ctx: Context) {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${ctx.packageName}")
    )
    if (launch(ctx, direct)) return
    launch(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
}

private fun openOverlay(ctx: Context) {
    launch(
        ctx,
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${ctx.packageName}")
        )
    )
}

private fun openNotifAccess(ctx: Context) {
    launch(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
}

private fun openUsage(ctx: Context) {
    launch(ctx, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
}

private fun openExactAlarms(ctx: Context) {
    val intent = if (Build.VERSION.SDK_INT >= 31) {
        Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:${ctx.packageName}")
        )
    } else {
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${ctx.packageName}")
        )
    }
    launch(ctx, intent)
}

private fun openAppDetails(ctx: Context) {
    launch(
        ctx,
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${ctx.packageName}")
        )
    )
}
