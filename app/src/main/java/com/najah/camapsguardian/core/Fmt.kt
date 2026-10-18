package com.najah.camapsguardian.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Petits formats de date/heure pour l'interface et le journal. */
object Fmt {

    private val hm = SimpleDateFormat("HH:mm", Locale.FRANCE)
    private val dm = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)

    @Synchronized
    fun time(ms: Long): String = if (ms <= 0L) "—" else hm.format(Date(ms))

    @Synchronized
    fun dateTime(ms: Long): String = if (ms <= 0L) "—" else dm.format(Date(ms))

    fun ago(ms: Long): String {
        if (ms <= 0L) return "jamais"
        val s = ((System.currentTimeMillis() - ms) / 1000L).coerceAtLeast(0L)
        return when {
            s < 60 -> "il y a ${s} s"
            s < 3600 -> "il y a ${s / 60} min"
            s < 86_400 -> "il y a ${s / 3600} h ${(s % 3600) / 60} min"
            else -> "il y a ${s / 86_400} j"
        }
    }

    fun inFuture(ms: Long): String {
        if (ms <= 0L) return "—"
        val s = (ms - System.currentTimeMillis()) / 1000L
        return when {
            s <= 0 -> "maintenant"
            s < 60 -> "dans ${s} s"
            s < 3600 -> "dans ${s / 60} min"
            else -> "dans ${s / 3600} h ${(s % 3600) / 60} min"
        }
    }
}
