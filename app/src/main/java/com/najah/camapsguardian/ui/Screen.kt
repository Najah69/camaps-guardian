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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.najah.camapsguardian.core.Control
import com.najah.camapsguardian.core.Fmt
import com.najah.camapsguardian.core.Logbook
import com.najah.camapsguardian.core.P
import com.najah.camapsguardian.core.Presence
import com.najah.camapsguardian.core.Shell
import com.najah.camapsguardian.core.Snap
import com.najah.camapsguardian.core.Status
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

            Panel("Actions") {
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
                        Text(if (busy) "Contrôle…" else "Vérifier maintenant", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                message = "CamAPS : " + Control.relaunchCamaps(ctx)
                                Status.refresh(ctx)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Relancer CamAPS", fontSize = 12.sp)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val found = Control.detectCamaps(ctx)
                                message = if (found.isEmpty()) {
                                    "Aucun paquet CamAPS trouvé"
                                } else {
                                    "Trouvé : " + found.joinToString(", ")
                                }
                                Status.refresh(ctx)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Détecter CamAPS FX", fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = {
                            Logbook.clear()
                            message = "Journal effacé"
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Effacer le journal", fontSize = 12.sp)
                    }
                }
                if (message.isNotBlank()) {
                    Text(message, fontSize = 11.sp, color = Dim)
                }
            }

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
            "CamAPS Guardian",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "Bluetooth + CamAPS FX · ronde automatique",
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
                    if (snap.enabled) "Surveillance active" else "Surveillance arrêtée",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (snap.enabled) Ok else Warn
                )
                Text(
                    if (snap.enabled) {
                        if (snap.running) "Service en cours · auto-relancé" else "Service relancé automatiquement"
                    } else {
                        "Aucune ronde programmée"
                    },
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
    Panel("État") {
        KeyLine(
            label = "Bluetooth",
            value = when {
                !snap.btHardware -> "Indisponible"
                snap.btOn -> "Activé"
                else -> "Éteint"
            },
            color = when {
                !snap.btHardware -> Bad
                snap.btOn -> Ok
                else -> Bad
            },
            note = if (snap.btOn) {
                "${snap.btDevices ?: "?"} connecté(s) · ${snap.btBonded ?: "?"} appairé(s)"
            } else {
                "rallumage automatique"
            }
        )
        KeyLine(
            label = "CamAPS FX",
            value = when (snap.presence) {
                Presence.ALIVE -> "Actif"
                Presence.NOT_ALIVE -> "Muet"
                Presence.UNKNOWN -> "Indéterminé"
                Presence.MISSING -> "Introuvable"
                Presence.NO_TARGET -> "Non configuré"
            },
            color = when (snap.presence) {
                Presence.ALIVE -> Ok
                Presence.NOT_ALIVE -> Bad
                Presence.UNKNOWN -> Warn
                Presence.MISSING -> Bad
                Presence.NO_TARGET -> Warn
            },
            note = snap.presenceEvidence.take(110)
        )
        KeyLine(
            label = "Ronde",
            value = if (snap.enabled) "Active" else "Arrêtée",
            color = if (snap.enabled) Ok else Warn,
            note = "dernière ${Fmt.ago(snap.lastCheck)} · prochaine ${Fmt.time(snap.nextCheck)}"
        )
        KeyLine(
            label = "Batterie",
            value = if (snap.battery >= 0) "${snap.battery} %" else "?",
            color = when {
                snap.battery < 0 -> Dim
                snap.charging -> Ok
                snap.battery <= 15 -> Bad
                else -> Ok
            },
            note = if (snap.charging) "en charge" else "sur batterie"
        )
        KeyLine(
            label = "Veille",
            value = if (snap.doze) "Doze" else "Normale",
            color = if (snap.doze) Warn else Ok,
            note = "${snap.checks} rondes · ${snap.btFixes} BT · ${snap.relaunches} relances"
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
    Panel("Fréquence") {
        Text("Ronde de contrôle", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        Chips(P.INTERVALS, snap.checkMinutes) {
            Control.setInterval(ctx, it)
            Status.refresh(ctx)
        }
        Spacer(Modifier.height(4.dp))
        Text("Rétablissement en UP (relance CamAPS / Bluetooth)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        Chips(P.INTERVALS, snap.recoverMinutes) {
            Control.setRecover(ctx, it)
            Status.refresh(ctx)
        }
        Text(
            "La ronde tourne au rythme choisi ; les relances sont limitées à l'intervalle de rétablissement.",
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
                label = { Text("$minutes min", fontSize = 11.sp) }
            )
        }
    }
}

// ------------------------------------------------------------------ rétablissement

@Composable
private fun RecoveryPanel(ctx: Context, snap: Snap) {
    Panel("Rétablissement") {
        ToggleLine(
            title = "Rallumer le Bluetooth",
            subtitle = "Direct, puis root/Shizuku, sinon notification d'action",
            checked = snap.btAuto,
            onChange = { value ->
                Control.setFlag(ctx) { p -> p.copy(btAuto = value) }
                Status.refresh(ctx)
            }
        )
        ToggleLine(
            title = "Relancer CamAPS FX",
            subtitle = "Après deux rondes muettes consécutives",
            checked = snap.appAuto,
            onChange = { value ->
                Control.setFlag(ctx) { p -> p.copy(appAuto = value) }
                Status.refresh(ctx)
            }
        )
        ToggleLine(
            title = "Relancer si connexion Bluetooth perdue",
            subtitle = "Ramène CamAPS au premier plan pour qu'il se reconnecte",
            checked = snap.relaunchOnBtLoss,
            onChange = { value ->
                Control.setFlag(ctx) { p -> p.copy(relaunchOnBtLoss = value) }
                Status.refresh(ctx)
            }
        )
        ToggleLine(
            title = "Cycle Bluetooth agressif",
            subtitle = "Coupe puis rallume le Bluetooth après 4 pertes",
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
    Panel("Autorisations") {
        PermLine(
            title = "Batterie sans restriction",
            ok = snap.ignoringBattery,
            onOpen = { openBattery(ctx) }
        )
        PermLine(
            title = "Afficher par-dessus les autres applis",
            ok = snap.overlay,
            onOpen = { openOverlay(ctx) }
        )
        PermLine(
            title = "Accès aux notifications (état de CamAPS)",
            ok = snap.notifAccess,
            onOpen = { openNotifAccess(ctx) }
        )
        PermLine(
            title = "Accès aux stats d'usage (facultatif)",
            ok = snap.usageAccess,
            onOpen = { openUsage(ctx) }
        )
        PermLine(
            title = "Alarmes exactes",
            ok = snap.exactAlarms,
            onOpen = { openExactAlarms(ctx) }
        )
        PermLine(
            title = "Permission Bluetooth",
            ok = snap.btPerm,
            onOpen = { openAppDetails(ctx) }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (snap.root || snap.shizuku) "Privilèges : ${if (snap.root) "root" else "Shizuku"}"
                else "Privilèges : aucun (facultatif)",
                Modifier.weight(1f),
                fontSize = 12.sp,
                color = if (snap.root || snap.shizuku) Ok else Dim
            )
            if (!snap.root && !snap.shizuku) {
                TextButton(onClick = {
                    Shell.requestShizukuPermission()
                    Status.refresh(ctx)
                }) { Text("Shizuku", fontSize = 11.sp) }
            }
        }
        Text(
            "Sans privilège, Android 13+ interdit de rallumer le Bluetooth par programme : " +
                "l'application affiche alors une alerte plein écran à toucher. " +
                "Avec root ou Shizuku, le rallumage est totalement automatique.",
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
            Text("OK", fontSize = 11.sp, color = Ok)
        } else {
            TextButton(onClick = onOpen) { Text("Ouvrir", fontSize = 11.sp) }
        }
    }
}

// ------------------------------------------------------------------ journal

@Composable
private fun LogPanel(logs: List<String>) {
    Panel("Journal") {
        if (logs.isEmpty()) {
            Text("Rien pour l'instant.", fontSize = 11.sp, color = Dim)
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
        "Cet outil complète CamAPS FX, il ne le remplace pas. Les alarmes de CamAPS FX " +
            "(perte de connexion, glycémie) restent la référence. Ne désinstallez jamais CamAPS FX.",
        fontSize = 10.sp,
        color = Dim,
        lineHeight = 14.sp,
        textAlign = TextAlign.Start
    )
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
