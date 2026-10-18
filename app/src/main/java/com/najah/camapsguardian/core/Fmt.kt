package com.najah.camapsguardian.core

import android.content.Context
import com.najah.camapsguardian.R
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Petits formats de date/heure pour l'interface et le journal, selon la langue du téléphone. */
object Fmt {

    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())

    @Synchronized
    fun time(ms: Long): String = if (ms <= 0L) "—" else hm.format(Date(ms))

    @Synchronized
    fun dateTime(ms: Long): String =
        if (ms <= 0L) "—"
        else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(ms))

    fun ago(ctx: Context, ms: Long): String {
        if (ms <= 0L) return ctx.tr(R.string.time_never)
        val s = ((System.currentTimeMillis() - ms) / 1000L).coerceAtLeast(0L)
        return when {
            s < 60 -> ctx.tr(R.string.time_ago_s, s)
            s < 3600 -> ctx.tr(R.string.time_ago_min, s / 60)
            s < 86_400 -> ctx.tr(R.string.time_ago_h, s / 3600, (s % 3600) / 60)
            else -> ctx.tr(R.string.time_ago_d, s / 86_400)
        }
    }

    fun inFuture(ctx: Context, ms: Long): String {
        if (ms <= 0L) return ctx.tr(R.string.time_dash)
        val s = (ms - System.currentTimeMillis()) / 1000L
        return when {
            s <= 0 -> ctx.tr(R.string.time_now)
            s < 60 -> ctx.tr(R.string.time_in_s, s)
            s < 3600 -> ctx.tr(R.string.time_in_min, s / 60)
            else -> ctx.tr(R.string.time_in_h, s / 3600, (s % 3600) / 60)
        }
    }
}
