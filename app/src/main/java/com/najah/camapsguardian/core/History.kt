package com.najah.camapsguardian.core

import android.content.Context
import java.io.File
import java.util.Calendar

/**
 * Historique structuré des rondes, pour les rapports.
 *
 * Le journal texte ne garde qu'un jour ou deux (il tourne à 150 Ko) et sa langue change
 * avec celle du téléphone : impossible d'en tirer trois mois de statistiques. On garde donc
 * à part une ligne compacte par ronde, conservée [KEEP_DAYS] jours (≈ 1 Mo pour 90 jours
 * de rondes toutes les 10 minutes).
 *
 * Format, une ligne par enregistrement :
 * - `R;horodatage;bt;appareils;présence;batterie;secteur;drapeaux` — une ronde.
 *   `bt` : 1 actif, 0 éteint, -1 pas de Bluetooth ; `appareils` -1 si inconnu ;
 *   `secteur` 1/0, -1 inconnu ; drapeaux : [F_BT_FIX] [F_NEEDS_USER] [F_RELAUNCH]
 *   [F_CYCLE] [F_MANUAL].
 * - `E;horodatage;type` — un événement ([EV_BOOT], [EV_ON], [EV_OFF]).
 */
object History {

    private const val FILE_NAME = "history.csv"
    const val KEEP_DAYS = 100

    const val F_BT_FIX = 'B'
    const val F_NEEDS_USER = 'U'
    const val F_RELAUNCH = 'L'
    const val F_CYCLE = 'C'
    const val F_MANUAL = 'M'

    const val EV_BOOT = "BOOT"
    const val EV_ON = "ON"
    const val EV_OFF = "OFF"

    data class Round(
        val at: Long,
        val bt: Int,
        val devices: Int,
        val presence: Presence,
        val battery: Int,
        val charging: Int,
        val flags: String
    )

    data class Event(val at: Long, val type: String)

    class Data(val rounds: List<Round>, val events: List<Event>)

    @Volatile
    private var file: File? = null
    private var appended = 0

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, FILE_NAME)
        file = f
        runCatching {
            if (!f.exists()) {
                // Première fois : on récupère ce que le journal texte contient encore.
                val seeded = JournalImport.parse(Logbook.fileLines(), System.currentTimeMillis())
                f.writeText(seeded.joinToString("") { it + "\n" })
            }
            prune(f)
        }
    }

    fun round(
        at: Long,
        bt: Int,
        devices: Int,
        presence: Presence,
        battery: Int,
        charging: Int,
        flags: String
    ) = append(encode(Round(at, bt, devices, presence, battery, charging, flags)))

    fun event(at: Long, type: String) = append("E;$at;$type")

    @Synchronized
    private fun append(line: String) {
        val f = file ?: return
        runCatching {
            f.appendText(line + "\n")
            if (++appended % 500 == 0) prune(f)
        }
    }

    /** Lit les enregistrements postérieurs à [since]. */
    @Synchronized
    fun read(since: Long): Data {
        val rounds = ArrayList<Round>()
        val events = ArrayList<Event>()
        val f = file ?: return Data(rounds, events)
        runCatching {
            f.forEachLine { line ->
                decode(line, since, rounds, events)
            }
        }
        rounds.sortBy { it.at }
        events.sortBy { it.at }
        return Data(rounds, events)
    }

    internal fun encode(r: Round): String =
        "R;${r.at};${r.bt};${r.devices};${r.presence.name};${r.battery};${r.charging};${r.flags}"

    internal fun decode(
        line: String,
        since: Long,
        rounds: MutableList<Round>,
        events: MutableList<Event>
    ) {
        val p = line.split(';')
        val at = p.getOrNull(1)?.toLongOrNull() ?: return
        if (at < since) return
        when (p[0]) {
            "R" -> if (p.size >= 7) {
                val presence = runCatching { Presence.valueOf(p[4]) }.getOrDefault(Presence.UNKNOWN)
                rounds += Round(
                    at,
                    p[2].toIntOrNull() ?: -1,
                    p[3].toIntOrNull() ?: -1,
                    presence,
                    p[5].toIntOrNull() ?: -1,
                    p[6].toIntOrNull() ?: -1,
                    p.getOrElse(7) { "" }
                )
            }
            "E" -> if (p.size >= 3) events += Event(at, p[2])
        }
    }

    private fun prune(f: File) {
        val limit = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -KEEP_DAYS) }.timeInMillis
        val first = f.bufferedReader().use { it.readLine() } ?: return
        val firstAt = first.split(';').getOrNull(1)?.toLongOrNull() ?: 0L
        if (firstAt >= limit) return
        val kept = f.readLines().filter { line ->
            (line.split(';').getOrNull(1)?.toLongOrNull() ?: 0L) >= limit
        }
        f.writeText(kept.joinToString("") { it + "\n" })
    }
}

/**
 * Import best effort du journal texte existant (lignes de bilan de ronde, en français
 * ou en anglais), pour que le premier rapport ne parte pas de zéro.
 * L'année n'est pas notée dans le journal : on prend l'année courante, ou la précédente
 * si la date tomberait dans le futur.
 */
internal object JournalImport {

    private val round = Regex(
        """^(\d{2})/(\d{2}) (\d{2}):(\d{2}):(\d{2}) \[(?:ronde|round)] BT (actif|on|éteint|off)""" +
            """(?: · (\d+) connect\S*)? · (CamAPS actif|CamAPS active|CamAPS muet|CamAPS silent|""" +
            """CamAPS \?|CamAPS absent|cible \?|target \?)(?: · (\d+) %)?(.*)$"""
    )
    private val boot = Regex(
        """^(\d{2})/(\d{2}) (\d{2}):(\d{2}):(\d{2}) \[boot] \S*BOOT_COMPLETED"""
    )

    fun parse(lines: List<String>, now: Long): List<String> {
        val out = ArrayList<String>()
        for (line in lines) {
            round.find(line)?.let { m ->
                val at = time(m.groupValues, now) ?: return@let
                val g = m.groupValues
                val bt = if (g[6] == "actif" || g[6] == "on") 1 else 0
                val presence = when (g[8]) {
                    "CamAPS actif", "CamAPS active" -> Presence.ALIVE
                    "CamAPS muet", "CamAPS silent" -> Presence.NOT_ALIVE
                    "CamAPS absent" -> Presence.MISSING
                    "cible ?", "target ?" -> Presence.NO_TARGET
                    else -> Presence.UNKNOWN
                }
                val rest = g[10].lowercase()
                val flags = buildString {
                    if ("rallumé" in rest || "turned back on" in rest) append(History.F_BT_FIX)
                    if ("accord utilisateur" in rest || "user approval" in rest) append(History.F_NEEDS_USER)
                    if ("relancé" in rest || "restarted" in rest) append(History.F_RELAUNCH)
                    if ("cycle" in rest) append(History.F_CYCLE)
                }
                out += History.encode(
                    History.Round(
                        at, bt, g[7].toIntOrNull() ?: -1, presence,
                        g[9].toIntOrNull() ?: -1, -1, flags
                    )
                )
            }
            boot.find(line)?.let { m ->
                time(m.groupValues, now)?.let { out += "E;$it;${History.EV_BOOT}" }
            }
        }
        return out
    }

    private fun time(g: List<String>, now: Long): Long? {
        val day = g[1].toIntOrNull() ?: return null
        val month = g[2].toIntOrNull() ?: return null
        val c = Calendar.getInstance()
        c.timeInMillis = now
        val year = c.get(Calendar.YEAR)
        c.clear()
        c.set(year, month - 1, day, g[3].toInt(), g[4].toInt(), g[5].toInt())
        if (c.timeInMillis > now + 86_400_000L) c.add(Calendar.YEAR, -1)
        return c.timeInMillis
    }
}
