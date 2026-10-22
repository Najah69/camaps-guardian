package com.najah.camapsguardian.core

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.najah.camapsguardian.R
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/**
 * Rapport PDF (A4, en points) dessiné avec le Canvas d'Android : aucune bibliothèque.
 * Page 1 : chiffres clés, camembert, analyse. Page 2 : graphiques dans le temps.
 * Page 3 : interventions, plus longues coupures, méthode.
 */
object ReportPdf {

    private const val W = 595f
    private const val H = 842f
    private const val M = 40f

    private val INK = Color.parseColor("#1F2328")
    private val INK2 = Color.parseColor("#57606A")
    private val MUTED = Color.parseColor("#8C959F")
    private val GRID = Color.parseColor("#E4E6EA")
    private val TILE = Color.parseColor("#F4F5F7")
    private val LINE = Color.parseColor("#2F6FD0")

    /** Couleurs d'état (palette « statut » : bon, attention, grave, critique, neutres). */
    fun color(c: Cat): Int = Color.parseColor(
        when (c) {
            Cat.OK -> "#0CA30C"
            Cat.NO_DEVICE -> "#FAB219"
            Cat.CAMAPS_DOWN -> "#EC835A"
            Cat.BT_OFF -> "#D03B3B"
            Cat.UNCERTAIN -> "#9A9A96"
            Cat.UNMONITORED -> "#D9DADC"
        }
    )

    fun label(ctx: Context, c: Cat): String = ctx.tr(
        when (c) {
            Cat.OK -> R.string.rep_cat_ok
            Cat.NO_DEVICE -> R.string.rep_cat_no_device
            Cat.CAMAPS_DOWN -> R.string.rep_cat_camaps
            Cat.BT_OFF -> R.string.rep_cat_bt_off
            Cat.UNCERTAIN -> R.string.rep_cat_uncertain
            Cat.UNMONITORED -> R.string.rep_cat_unmonitored
        }
    )

    fun periodLabel(ctx: Context, p: Period): String = ctx.tr(
        when (p) {
            Period.DAY -> R.string.rep_period_day
            Period.WEEK -> R.string.rep_period_week
            Period.MONTH -> R.string.rep_period_month
            Period.QUARTER -> R.string.rep_period_quarter
        }
    )

    fun duration(ctx: Context, ms: Long): String {
        val min = (ms / 60_000L).coerceAtLeast(0)
        return when {
            min < 60 -> ctx.tr(R.string.dur_min, min)
            min < 24 * 60 -> ctx.tr(R.string.dur_h, min / 60, "%02d".format(min % 60))
            else -> ctx.tr(R.string.dur_d, min / 1440, (min % 1440) / 60)
        }
    }

    fun pct(x: Double, decimals: Int = 1): String =
        String.format(Locale.getDefault(), "%.${decimals}f %%", x * 100)

    private fun dateTime(ms: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

    // ================================================================ rendu

    fun render(ctx: Context, a: Analysis): ByteArray {
        val d = Doc(ctx)
        pageSummary(ctx, d, a)
        d.newPage()
        pageCharts(ctx, d, a)
        d.newPage()
        pageDetails(ctx, d, a)
        return d.finish()
    }

    // ---------------------------------------------------------------- page 1

    private fun pageSummary(ctx: Context, d: Doc, a: Analysis) {
        val version = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: ""
        d.text(ctx.tr(R.string.rep_title), M, d.y + 20, 20f, INK, bold = true)
        d.y += 30
        d.text(periodLabel(ctx, a.period), M, d.y + 13, 12f, INK, bold = true)
        d.y += 18
        d.paragraph(
            ctx.tr(R.string.rep_range, dateTime(a.from), dateTime(a.to)) + "\n" +
                ctx.tr(R.string.rep_generated, dateTime(System.currentTimeMillis()), Sys.deviceLabel(), version),
            9f, INK2
        )
        if (a.dataFrom > a.from) {
            d.paragraph(ctx.tr(R.string.rep_history_since, dateTime(a.dataFrom)), 9f, INK2)
        }
        d.paragraph(ctx.tr(R.string.rep_disclaimer), 8f, MUTED)
        d.y += 8

        // Chiffres clés
        val longest = a.episodes.maxByOrNull { it.length }
        val tiles = listOf(
            Triple(ctx.tr(R.string.rep_kpi_ok), if (a.observed > 0) pct(a.okRate) else "—",
                ctx.tr(R.string.rep_kpi_ok_sub)),
            Triple(ctx.tr(R.string.rep_kpi_cuts), a.longEpisodes.size.toString(),
                ctx.tr(R.string.rep_kpi_cuts_sub, a.episodes.size)),
            Triple(ctx.tr(R.string.rep_kpi_longest), longest?.let { duration(ctx, it.length) } ?: "—",
                longest?.let { ctx.tr(R.string.rep_kpi_longest_sub, dateTime(it.start)) } ?: ""),
            Triple(ctx.tr(R.string.rep_kpi_coverage), if (a.span > 0) pct(a.coverage, 0) else "—",
                ctx.tr(R.string.rep_kpi_coverage_sub, a.rounds))
        )
        val gap = 8f
        val tw = (W - 2 * M - gap * 3) / 4
        tiles.forEachIndexed { i, (title, value, sub) ->
            val x = M + i * (tw + gap)
            d.round(RectF(x, d.y, x + tw, d.y + 64), 6f, TILE)
            d.text(title, x + 8, d.y + 15, 8f, INK2)
            d.text(value, x + 8, d.y + 40, 18f, INK, bold = true)
            d.text(d.fit(sub, tw - 16, 7.5f), x + 8, d.y + 55, 7.5f, MUTED)
        }
        d.y += 64 + 22

        // Camembert (anneau) + légende
        d.text(ctx.tr(R.string.rep_pie_title), M, d.y, 12f, INK, bold = true)
        d.y += 12
        val r = 78f
        val cx = M + r + 4
        val cy = d.y + r + 4
        val cats = Cat.entries.filter { a.time[it.ordinal] >= 60_000L }
        if (a.span > 0) {
            val ring = 30f
            val oval = RectF(cx - r + ring / 2, cy - r + ring / 2, cx + r - ring / 2, cy + r - ring / 2)
            var angle = -90f
            val p = d.paint(0, stroke = ring)
            for (c in cats) {
                val sweep = (a.time[c.ordinal].toDouble() / a.span * 360).toFloat()
                p.color = color(c)
                // 1,2° d'écart entre parts (le blanc du fond sépare les couleurs)
                val gapDeg = if (cats.size > 1) minOf(1.2f, sweep / 3) else 0f
                d.canvas.drawArc(oval, angle + gapDeg / 2, sweep - gapDeg, false, p)
                angle += sweep
            }
            d.text(if (a.observed > 0) pct(a.okRate, 0) else "—", cx, cy + 6, 17f, INK, bold = true, center = true)
            d.text(ctx.tr(R.string.rep_pie_center), cx, cy + 19, 7.5f, INK2, center = true)
        } else {
            d.circle(cx, cy, r - 15, GRID, stroke = 30f)
            d.text(ctx.tr(R.string.rep_a_no_data), cx, cy + 3, 8f, INK2, center = true)
        }
        var ly = d.y + 18
        val lx = cx + r + 30
        val right = W - M
        for (c in Cat.entries) {
            val t = a.time[c.ordinal].takeIf { it >= 60_000L } ?: 0L
            d.round(RectF(lx, ly - 8, lx + 10, ly + 2), 2f, color(c))
            d.text(label(ctx, c), lx + 16, ly, 9f, if (t > 0) INK else MUTED)
            val share = if (a.span > 0) pct(a.share(c)) else "—"
            d.text(share, right - 70, ly, 9f, if (t > 0) INK else MUTED, bold = t > 0, alignRight = true)
            d.text(duration(ctx, t), right, ly, 9f, INK2, alignRight = true)
            ly += 23
        }
        d.y = maxOf(cy + r, ly) + 18

        // Analyse
        d.text(ctx.tr(R.string.rep_analysis), M, d.y, 12f, INK, bold = true)
        d.y += 8
        for (line in findings(ctx, a)) d.bullet(line)
    }

    /** Phrases d'analyse, de la plus importante à la plus anodine. */
    fun findings(ctx: Context, a: Analysis): List<String> {
        if (a.rounds == 0 && a.observed <= 0) return listOf(ctx.tr(R.string.rep_a_no_data))
        val out = ArrayList<String>()
        out += ctx.tr(R.string.rep_a_ok, pct(a.okRate))
        a.previousOk?.let { prev ->
            val delta = (a.okRate - prev) * 100
            out += when {
                delta >= 0.5 -> ctx.tr(R.string.rep_a_trend_up, "%.1f".format(Locale.getDefault(), delta), pct(prev))
                delta <= -0.5 -> ctx.tr(R.string.rep_a_trend_down, "%.1f".format(Locale.getDefault(), -delta), pct(prev))
                else -> ctx.tr(R.string.rep_a_trend_same, pct(prev))
            }
        }
        val main = Report.PROBLEMS.maxBy { a.time[it.ordinal] }
        if (a.time[main.ordinal] > 0) {
            out += ctx.tr(R.string.rep_a_main, label(ctx, main), duration(ctx, a.time[main.ordinal]))
            a.episodes.maxByOrNull { it.length }?.let {
                out += ctx.tr(R.string.rep_a_longest, duration(ctx, it.length), dateTime(it.start), label(ctx, it.cause))
            }
            if (main == Cat.NO_DEVICE) out += ctx.tr(R.string.rep_a_no_device_hint)
        } else {
            out += ctx.tr(R.string.rep_a_none)
        }
        worstHours(a)?.let { (h0, h1, rate) ->
            out += ctx.tr(R.string.rep_a_hours, h0, h1, pct(rate, 0))
        }
        if (a.span > 0 && a.coverage < 0.95) {
            out += ctx.tr(R.string.rep_a_coverage_low, pct(1 - a.coverage, 0))
        }
        if (a.boots > 0) out += ctx.tr(R.string.rep_a_boots, a.boots)
        out += ctx.tr(R.string.rep_a_actions, a.btFixes, a.relaunches, a.cycles, a.needsUser)
        if (a.batteryAvg >= 0) {
            out += ctx.tr(R.string.rep_a_battery, a.batteryAvg, a.batteryMin, duration(ctx, a.lowBattery))
        }
        return out
    }

    /**
     * Fenêtre de 3 heures consécutives où la part de coupure est la plus forte, si elle
     * pèse nettement plus que la moyenne et repose sur assez de données.
     */
    private fun worstHours(a: Analysis): Triple<Int, Int, Double>? {
        if (a.period == Period.DAY) return null
        val totalObs = a.hourObserved.sum()
        val totalProb = a.hourProblem.sum()
        if (totalObs <= 0 || totalProb <= 0) return null
        val mean = totalProb.toDouble() / totalObs
        var best = -1
        var bestRate = 0.0
        for (h in 0 until 24) {
            var obs = 0L
            var prob = 0L
            for (k in 0 until 3) {
                obs += a.hourObserved[(h + k) % 24]
                prob += a.hourProblem[(h + k) % 24]
            }
            if (obs < 3 * 3_600_000L) continue
            val rate = prob.toDouble() / obs
            if (rate > bestRate) {
                bestRate = rate
                best = h
            }
        }
        if (best < 0 || bestRate < 0.05 || bestRate < mean * 1.5) return null
        return Triple(best, (best + 3) % 24, bestRate)
    }

    // ---------------------------------------------------------------- page 2

    private fun pageCharts(ctx: Context, d: Doc, a: Analysis) {
        d.text(
            ctx.tr(
                when (a.period) {
                    Period.DAY -> R.string.rep_bars_title_day
                    Period.QUARTER -> R.string.rep_bars_title_quarter
                    else -> R.string.rep_bars_title_days
                }
            ),
            M, d.y + 12, 12f, INK, bold = true
        )
        d.y += 18
        d.paragraph(ctx.tr(R.string.rep_bars_note), 8f, INK2)
        legendRow(ctx, d, Cat.entries)
        stackedBars(d, a, 170f)
        d.y += 26

        if (a.period != Period.DAY) {
            d.text(ctx.tr(R.string.rep_hours_title), M, d.y + 12, 12f, INK, bold = true)
            d.y += 18
            d.paragraph(ctx.tr(R.string.rep_hours_note), 8f, INK2)
            hourBars(d, a, 120f)
            d.y += 26
        }

        d.text(ctx.tr(R.string.rep_battery_title), M, d.y + 12, 12f, INK, bold = true)
        d.y += 18
        d.paragraph(ctx.tr(R.string.rep_battery_note), 8f, INK2)
        batteryLine(d, a, 120f)
    }

    private fun legendRow(ctx: Context, d: Doc, cats: List<Cat>) {
        var x = M
        d.y += 12
        for (c in cats) {
            val s = label(ctx, c)
            val w = 14 + d.width(s, 8f) + 14
            if (x + w > W - M) {
                x = M
                d.y += 13
            }
            d.round(RectF(x, d.y - 7, x + 9, d.y + 2), 2f, color(c))
            d.text(s, x + 13, d.y, 8f, INK2)
            x += w
        }
        d.y += 12
    }

    private fun axisY(d: Doc, left: Float, top: Float, right: Float, h: Float, max: Double, steps: Int) {
        for (i in 0..steps) {
            val y = top + h - h * i / steps
            d.line(left, y, right, y, GRID, 0.6f)
            d.text(pct(max * i / steps, 0), left - 4, y + 2.5f, 6.5f, MUTED, alignRight = true)
        }
    }

    private fun stackedBars(d: Doc, a: Analysis, h: Float) {
        val left = M + 26
        val right = W - M
        val top = d.y + 4
        axisY(d, left, top, right, h, 1.0, 4)
        val n = a.buckets.size
        val slot = (right - left) / n
        val bw = slot * 0.72f
        val order = listOf(Cat.OK, Cat.NO_DEVICE, Cat.CAMAPS_DOWN, Cat.BT_OFF, Cat.UNCERTAIN, Cat.UNMONITORED)
        val fmt = bucketFormat(a.period)
        val every = when (a.period) {
            Period.DAY -> 2
            Period.MONTH -> 3
            else -> 1
        }
        a.buckets.forEachIndexed { i, b ->
            val x = left + i * slot + (slot - bw) / 2
            val total = b.time.sum()
            if (total > 0) {
                var base = top + h
                val shown = order.filter { b.time[it.ordinal] > 0 }
                shown.forEachIndexed { k, c ->
                    val sh = (b.time[c.ordinal].toDouble() / total * h).toFloat()
                    // 1 pt de fond entre deux segments ; coins arrondis seulement au sommet.
                    val gapPt = if (k < shown.size - 1) 1f else 0f
                    val rect = RectF(x, base - sh + gapPt, x + bw, base)
                    if (rect.height() > 0.3f) {
                        if (k == shown.size - 1) d.topRounded(rect, minOf(2f, bw / 3), color(c))
                        else d.rect(rect, color(c))
                    }
                    base -= sh
                }
            } else {
                d.line(x, top + h - 0.5f, x + bw, top + h - 0.5f, MUTED, 0.8f)
            }
            if (i % every == 0) {
                d.text(fmt.format(Date(b.start)), x + bw / 2, top + h + 10, 6.5f, INK2, center = true)
            }
        }
        d.line(left, top + h, right, top + h, MUTED, 0.8f)
        d.y = top + h + 12
    }

    private fun hourBars(d: Doc, a: Analysis, h: Float) {
        val left = M + 26
        val right = W - M
        val top = d.y + 8
        val rates = (0 until 24).map {
            if (a.hourObserved[it] > 0) a.hourProblem[it].toDouble() / a.hourObserved[it] else 0.0
        }
        val peak = rates.maxOrNull() ?: 0.0
        val max = (ceil(peak * 10) / 10).coerceIn(0.1, 1.0)
        axisY(d, left, top, right, h, max, 2)
        val slot = (right - left) / 24
        val bw = slot * 0.7f
        val color = color(Cat.CAMAPS_DOWN)
        val peakIdx = rates.indexOf(peak)
        for (hr in 0 until 24) {
            val x = left + hr * slot + (slot - bw) / 2
            val bh = (rates[hr] / max * h).toFloat()
            if (bh > 0.3f) d.topRounded(RectF(x, top + h - bh, x + bw, top + h), 2f, color)
            if (hr == peakIdx && peak > 0) {
                d.text(pct(peak, 0), x + bw / 2, top + h - bh - 3, 6.5f, INK, bold = true, center = true)
            }
            if (hr % 3 == 0) d.text("${hr} h", x + bw / 2, top + h + 10, 6.5f, INK2, center = true)
        }
        d.line(left, top + h, right, top + h, MUTED, 0.8f)
        d.y = top + h + 12
    }

    private fun batteryLine(d: Doc, a: Analysis, h: Float) {
        val left = M + 26
        val right = W - M
        val top = d.y + 8
        // Zone sous 15 % (seuil d'alerte de Guardian)
        d.rect(RectF(left, top + h * 0.85f, right, top + h), Color.parseColor("#FBEAEA"))
        for (i in 0..4) {
            val y = top + h - h * i / 4
            d.line(left, y, right, y, GRID, 0.6f)
            d.text("${i * 25} %", left - 4, y + 2.5f, 6.5f, MUTED, alignRight = true)
        }
        val t0 = maxOf(a.buckets.first().start, a.dataFrom - a.dataFrom % 3_600_000L)
        val t1 = a.buckets.last().end
        val span = (t1 - t0).toFloat()
        fun xOf(t: Long) = left + (t - t0) / span * (right - left)
        val step = (a.battery.size / 2000).coerceAtLeast(1)
        val path = Path()
        var prev = Long.MIN_VALUE
        var started = false
        a.battery.forEachIndexed { i, (t, v) ->
            if (i % step != 0) return@forEachIndexed
            val x = xOf(t)
            val y = top + h - h * v / 100f
            if (!started || t - prev > a.gapLimit * step) path.moveTo(x, y) else path.lineTo(x, y)
            started = true
            prev = t
        }
        val p = d.paint(LINE, stroke = 1.4f)
        p.strokeJoin = Paint.Join.ROUND
        p.strokeCap = Paint.Cap.ROUND
        d.canvas.drawPath(path, p)
        // Repères de temps : début, milieu, fin
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        d.text(fmt.format(Date(t0)), left, top + h + 10, 6.5f, INK2)
        d.text(fmt.format(Date((t0 + t1) / 2)), (left + right) / 2, top + h + 10, 6.5f, INK2, center = true)
        d.text(fmt.format(Date(t1)), right, top + h + 10, 6.5f, INK2, alignRight = true)
        d.line(left, top + h, right, top + h, MUTED, 0.8f)
        d.y = top + h + 12
    }

    private fun bucketFormat(p: Period): SimpleDateFormat {
        val loc = Locale.getDefault()
        val skeleton = when (p) {
            Period.DAY -> "HH"
            Period.WEEK -> "EEEd"
            Period.MONTH -> "d"
            Period.QUARTER -> "dM"
        }
        return SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(loc, skeleton), loc)
    }

    // ---------------------------------------------------------------- page 3

    private fun pageDetails(ctx: Context, d: Doc, a: Analysis) {
        d.text(ctx.tr(R.string.rep_actions_title), M, d.y + 12, 12f, INK, bold = true)
        d.y += 32
        val rows = listOf(
            ctx.tr(R.string.rep_row_rounds) to a.rounds.toString(),
            ctx.tr(R.string.rep_row_bt_fix) to a.btFixes.toString(),
            ctx.tr(R.string.rep_row_needs_user) to a.needsUser.toString(),
            ctx.tr(R.string.rep_row_relaunch) to a.relaunches.toString(),
            ctx.tr(R.string.rep_row_cycle) to a.cycles.toString(),
            ctx.tr(R.string.rep_row_boots) to a.boots.toString(),
            ctx.tr(R.string.rep_row_low) to duration(ctx, a.lowBattery)
        )
        rows.forEachIndexed { i, (k, v) ->
            if (i % 2 == 0) d.rect(RectF(M, d.y - 10, W - M, d.y + 4), TILE)
            d.text(k, M + 6, d.y, 9f, INK)
            d.text(v, W - M - 6, d.y, 9f, INK, bold = true, alignRight = true)
            d.y += 14
        }
        d.y += 18

        d.text(ctx.tr(R.string.rep_episodes_title), M, d.y, 12f, INK, bold = true)
        d.y += 16
        val top = a.episodes.sortedByDescending { it.length }.take(15)
        if (top.isEmpty()) {
            d.paragraph(ctx.tr(R.string.rep_a_none), 9f, INK2)
        } else {
            val cols = floatArrayOf(M + 6, M + 140, M + 274, M + 350)
            val heads = listOf(R.string.rep_col_start, R.string.rep_col_end, R.string.rep_col_length, R.string.rep_col_cause)
            heads.forEachIndexed { i, id -> d.text(ctx.tr(id), cols[i], d.y, 8f, INK2, bold = true) }
            d.y += 4
            d.line(M, d.y, W - M, d.y, MUTED, 0.6f)
            d.y += 11
            for (e in top) {
                d.text(dateTime(e.start), cols[0], d.y, 8.5f, INK)
                d.text(dateTime(e.end), cols[1], d.y, 8.5f, INK)
                d.text(duration(ctx, e.length), cols[2], d.y, 8.5f, INK, bold = true)
                d.round(RectF(cols[3], d.y - 7, cols[3] + 8, d.y + 1), 2f, color(e.cause))
                d.text(label(ctx, e.cause), cols[3] + 12, d.y, 8.5f, INK)
                d.y += 13
            }
            val more = a.episodes.size - top.size
            if (more > 0) {
                d.y += 2
                d.paragraph(ctx.tr(R.string.rep_episodes_more, more), 8f, INK2)
            }
        }
        d.y += 16

        d.ensure(120f)
        d.text(ctx.tr(R.string.rep_method_title), M, d.y, 12f, INK, bold = true)
        d.y += 6
        d.paragraph(
            ctx.tr(R.string.rep_method_text, duration(ctx, a.gapLimit), duration(ctx, Report.LONG_EPISODE), History.KEEP_DAYS),
            8f, INK2
        )
    }

    // ================================================================ mise en page

    private class Doc(private val ctx: Context) {
        private val pdf = PdfDocument()
        private var page: PdfDocument.Page? = null
        lateinit var canvas: Canvas
        var y = M
        private var number = 0
        private val regular = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        private val boldFace = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

        init {
            newPage()
        }

        fun newPage() {
            closePage()
            number++
            val p = pdf.startPage(PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), number).create())
            page = p
            canvas = p.canvas
            canvas.drawColor(Color.WHITE)
            y = M
        }

        private fun closePage() {
            val p = page ?: return
            line(M, H - 30, W - M, H - 30, GRID, 0.6f)
            text("CamAPS Guardian", M, H - 18, 7f, MUTED)
            text(ctx.tr(R.string.rep_page, number), W - M, H - 18, 7f, MUTED, alignRight = true)
            pdf.finishPage(p)
            page = null
        }

        fun ensure(h: Float) {
            if (y + h > H - 46) newPage()
        }

        fun finish(): ByteArray {
            closePage()
            val out = ByteArrayOutputStream()
            pdf.writeTo(out)
            pdf.close()
            return out.toByteArray()
        }

        fun paint(color: Int, size: Float = 10f, bold: Boolean = false, stroke: Float = 0f) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = size
                typeface = if (bold) boldFace else regular
                if (stroke > 0f) {
                    style = Paint.Style.STROKE
                    strokeWidth = stroke
                }
            }

        fun width(s: String, size: Float, bold: Boolean = false) = paint(0, size, bold).measureText(s)

        fun text(
            s: String, x: Float, y: Float, size: Float, color: Int,
            bold: Boolean = false, center: Boolean = false, alignRight: Boolean = false
        ) {
            val p = paint(color, size, bold)
            p.textAlign = when {
                center -> Paint.Align.CENTER
                alignRight -> Paint.Align.RIGHT
                else -> Paint.Align.LEFT
            }
            canvas.drawText(s, x, y, p)
        }

        /** Coupe [s] avec une ellipse s'il dépasse [w]. */
        fun fit(s: String, w: Float, size: Float): String {
            if (width(s, size) <= w) return s
            var t = s
            while (t.isNotEmpty() && width("$t…", size) > w) t = t.dropLast(1)
            return "$t…"
        }

        fun wrap(s: String, w: Float, size: Float, bold: Boolean = false): List<String> {
            val p = paint(0, size, bold)
            val out = ArrayList<String>()
            for (para in s.split('\n')) {
                var cur = ""
                for (word in para.split(' ')) {
                    val next = if (cur.isEmpty()) word else "$cur $word"
                    if (p.measureText(next) <= w || cur.isEmpty()) cur = next
                    else {
                        out += cur
                        cur = word
                    }
                }
                out += cur
            }
            return out
        }

        fun paragraph(s: String, size: Float, color: Int, x: Float = M, w: Float = W - 2 * M) {
            val lh = size * 1.4f
            for (l in wrap(s, w, size)) {
                ensure(lh)
                y += lh
                text(l, x, y, size, color)
            }
            y += size * 0.5f
        }

        fun bullet(s: String) {
            val size = 9.5f
            val lh = size * 1.45f
            val lines = wrap(s, W - 2 * M - 14, size)
            ensure(lh * lines.size)
            y += 4
            lines.forEachIndexed { i, l ->
                y += lh
                if (i == 0) circle(M + 4, y - 3.2f, 1.8f, INK2)
                text(l, M + 14, y, size, INK)
            }
        }

        fun rect(r: RectF, color: Int) = canvas.drawRect(r, paint(color))

        fun round(r: RectF, radius: Float, color: Int) = canvas.drawRoundRect(r, radius, radius, paint(color))

        /** Rectangle arrondi en haut seulement (bout de barre), ancré à la ligne de base. */
        fun topRounded(r: RectF, radius: Float, color: Int) {
            val rad = minOf(radius, r.height() / 2, r.width() / 2)
            val path = Path()
            path.addRoundRect(r, floatArrayOf(rad, rad, rad, rad, 0f, 0f, 0f, 0f), Path.Direction.CW)
            canvas.drawPath(path, paint(color))
        }

        fun circle(x: Float, y: Float, r: Float, color: Int, stroke: Float = 0f) =
            canvas.drawCircle(x, y, r, paint(color, stroke = stroke))

        fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, w: Float) =
            canvas.drawLine(x0, y0, x1, y1, paint(color, stroke = w))
    }

    // ================================================================ export

    /** Analyse la période, dessine le PDF et l'enregistre dans Téléchargements. */
    fun export(ctx: Context, period: Period): Downloads.Saved {
        val now = System.currentTimeMillis()
        val b = Report.bounds(period, now)
        // La période précédente sert à la comparaison ; une heure de marge pour la ronde à cheval.
        val since = b.first() - (b.last() - b.first()) - 3_600_000L
        val data = History.read(since)
        val a = Report.analyze(data, period, now, Prefs.get(ctx).value.checkMinutes)
        val bytes = render(ctx, a)
        val name = "camaps-guardian-rapport-" + period.name.lowercase() + "-" +
            SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now)) + ".pdf"
        val saved = Downloads.save(ctx, name, "application/pdf", bytes)
        Logbook.add(
            ctx.tr(R.string.cat_action),
            ctx.tr(R.string.log_report_saved, periodLabel(ctx, period), saved.label, pct(a.okRate))
        )
        return saved
    }
}
