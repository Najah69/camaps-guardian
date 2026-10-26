package com.najah.camapsguardian.core

import java.util.Calendar

/** Période d'un rapport. Les bornes sont alignées sur l'heure ou sur minuit. */
enum class Period(val buckets: Int) {
    DAY(24), WEEK(7), MONTH(30), QUARTER(13)
}

/**
 * État de la liaison pendant un intervalle, du plus grave au plus anodin.
 * « Aucun appareil » veut dire qu'aucun appareil Bluetooth n'était connecté : l'appli
 * ne sait pas distinguer le capteur d'une montre ou d'écouteurs.
 */
enum class Cat { OK, NO_DEVICE, CAMAPS_DOWN, BT_OFF, UNCERTAIN, UNMONITORED }

/** Une coupure : intervalles problématiques consécutifs. */
data class Episode(val start: Long, val end: Long, val cause: Cat) {
    val length: Long get() = end - start
}

class Bucket(val start: Long, val end: Long) {
    val time = LongArray(Cat.entries.size)
    val observed: Long get() = time.sum() - time[Cat.UNMONITORED.ordinal]
}

class Analysis(
    val period: Period,
    val from: Long,
    val to: Long,
    /** Début de l'historique s'il commence après [from]. */
    val dataFrom: Long,
    val time: LongArray,
    val buckets: List<Bucket>,
    /** Par heure de la journée : temps observé et temps en coupure. */
    val hourObserved: LongArray,
    val hourProblem: LongArray,
    val episodes: List<Episode>,
    val rounds: Int,
    val btFixes: Int,
    val needsUser: Int,
    val relaunches: Int,
    val cycles: Int,
    val boots: Int,
    val batteryMin: Int,
    val batteryAvg: Int,
    val lowBattery: Long,
    val battery: List<Pair<Long, Int>>,
    val gapLimit: Long,
    val previousOk: Double?,
    /** xDrip+ : temps surveillé, temps sans collecte, pannes et réparations (rondes de la 1.5 et après). */
    val xdripObserved: Long = 0L,
    val xdripDown: Long = 0L,
    val xdripEpisodes: List<Episode> = emptyList(),
    val xdripFixes: Int = 0
) {
    /** Part du temps surveillé où xDrip+ collectait. */
    val xdripRate: Double get() = if (xdripObserved <= 0) 0.0 else 1.0 - xdripDown.toDouble() / xdripObserved
    val span: Long get() = time.sum()
    val observed: Long get() = span - time[Cat.UNMONITORED.ordinal]
    fun share(c: Cat): Double = if (span <= 0) 0.0 else time[c.ordinal].toDouble() / span
    /** Part de liaison correcte sur le temps réellement surveillé. */
    val okRate: Double get() = if (observed <= 0) 0.0 else time[Cat.OK.ordinal].toDouble() / observed
    val coverage: Double get() = if (span <= 0) 0.0 else observed.toDouble() / span
    val longEpisodes: List<Episode> get() = episodes.filter { it.length >= Report.LONG_EPISODE }
}

object Report {

    const val LONG_EPISODE = 30 * 60_000L
    val PROBLEMS = setOf(Cat.NO_DEVICE, Cat.CAMAPS_DOWN, Cat.BT_OFF)

    fun cat(r: History.Round): Cat = when {
        r.bt == 0 -> Cat.BT_OFF
        r.presence == Presence.NOT_ALIVE || r.presence == Presence.MISSING -> Cat.CAMAPS_DOWN
        r.bt == 1 && r.devices == 0 -> Cat.NO_DEVICE
        r.bt == 1 && r.devices > 0 && r.presence == Presence.ALIVE -> Cat.OK
        else -> Cat.UNCERTAIN
    }

    /** Bornes des cases du graphique, de la plus ancienne à la plus récente. */
    fun bounds(period: Period, now: Long): List<Long> {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        val field: Int
        val step: Int
        if (period == Period.DAY) {
            c.add(Calendar.HOUR_OF_DAY, 1)
            field = Calendar.HOUR_OF_DAY
            step = 1
        } else {
            c.set(Calendar.HOUR_OF_DAY, 0)
            c.add(Calendar.DAY_OF_MONTH, 1)
            field = Calendar.DAY_OF_MONTH
            step = if (period == Period.QUARTER) 7 else 1
        }
        val out = ArrayList<Long>()
        out += c.timeInMillis
        repeat(period.buckets) {
            c.add(field, -step)
            out += c.timeInMillis
        }
        return out.reversed()
    }

    /**
     * @param checkMinutes intervalle actuel des rondes : au-delà de 2,5 rondes sans nouvelle,
     * le temps compte comme « non surveillé » (téléphone éteint, appli arrêtée…).
     */
    fun analyze(data: History.Data, period: Period, now: Long, checkMinutes: Int): Analysis {
        val b = bounds(period, now)
        val from = b.first()
        val to = minOf(now, b.last())
        val gap = (checkMinutes.coerceAtLeast(5) * 150_000L).coerceAtLeast(15 * 60_000L)
        val prevFrom = from - (b.last() - from)
        val prev = timeline(data, prevFrom, from, gap)
        val prevObserved = prev.sum() - prev[Cat.UNMONITORED.ordinal]
        val previousOk =
            if (prevObserved > (from - prevFrom) / 4) prev[Cat.OK.ordinal].toDouble() / prevObserved
            else null

        val buckets = (0 until b.size - 1).map { Bucket(b[it], b[it + 1]) }
        val time = LongArray(Cat.entries.size)
        val hourObserved = LongArray(24)
        val hourProblem = LongArray(24)
        val episodes = ArrayList<Episode>()
        var open: Episode? = null
        val openCause = LongArray(Cat.entries.size)
        val cal = Calendar.getInstance()

        val first = data.rounds.firstOrNull { it.at >= from - gap }?.at ?: to
        val dataFrom = maxOf(from, minOf(first, to))

        fun closeEpisode() {
            val e = open ?: return
            val cause = PROBLEMS.maxBy { openCause[it.ordinal] }
            episodes += e.copy(cause = cause)
            open = null
            openCause.fill(0)
        }

        segments(data, dataFrom, to, gap) { s, e, c ->
            time[c.ordinal] += e - s
            for (bk in buckets) {
                val o = minOf(e, bk.end) - maxOf(s, bk.start)
                if (o > 0) bk.time[c.ordinal] += o
            }
            if (c != Cat.UNMONITORED) {
                var t = s
                while (t < e) {
                    cal.timeInMillis = t
                    val h = cal.get(Calendar.HOUR_OF_DAY)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    cal.add(Calendar.HOUR_OF_DAY, 1)
                    val stop = minOf(e, cal.timeInMillis)
                    hourObserved[h] += stop - t
                    if (c in PROBLEMS) hourProblem[h] += stop - t
                    t = stop
                }
            }
            if (c in PROBLEMS) {
                val cur = open
                open = if (cur == null) Episode(s, e, c) else cur.copy(end = e)
                openCause[c.ordinal] += e - s
            } else if (c != Cat.UNCERTAIN) {
                closeEpisode()
            }
        }
        closeEpisode()

        val inRange = data.rounds.filter { it.at in from..to }
        val bat = inRange.filter { it.battery >= 0 }
        var low = 0L
        segmentsOf(inRange, from, to, gap) { r, s, e ->
            if (r.battery in 0..14 && r.charging != 1) low += e - s
        }

        // xDrip+ : à part des catégories ci-dessus, sa panne ne coupe pas la boucle d'insuline.
        val boots = data.events.filter { it.type == History.EV_BOOT }.map { it.at }
        var xObserved = 0L
        var xDown = 0L
        val xEpisodes = ArrayList<Episode>()
        var xOpen: Episode? = null
        segmentsOf(inRange, from, to, gap, boots) { r, s, e ->
            val bad = History.F_XDRIP in r.flags
            val ok = History.F_XDRIP_OK in r.flags
            if (bad || ok) xObserved += e - s
            if (bad) {
                xDown += e - s
                xOpen = xOpen?.copy(end = e) ?: Episode(s, e, Cat.UNCERTAIN)
            } else if (ok) {
                xOpen?.let { xEpisodes += it }
                xOpen = null
            }
        }
        xOpen?.let { xEpisodes += it }
        return Analysis(
            period = period,
            from = from,
            to = to,
            dataFrom = dataFrom,
            time = time,
            buckets = buckets,
            hourObserved = hourObserved,
            hourProblem = hourProblem,
            episodes = episodes,
            rounds = inRange.size,
            btFixes = inRange.count { History.F_BT_FIX in it.flags },
            needsUser = inRange.count { History.F_NEEDS_USER in it.flags },
            relaunches = inRange.count { History.F_RELAUNCH in it.flags },
            cycles = inRange.count { History.F_CYCLE in it.flags },
            boots = data.events.count { it.type == History.EV_BOOT && it.at in from..to },
            batteryMin = bat.minOfOrNull { it.battery } ?: -1,
            batteryAvg = if (bat.isEmpty()) -1 else bat.sumOf { it.battery } / bat.size,
            lowBattery = low,
            battery = bat.map { it.at to it.battery },
            gapLimit = gap,
            previousOk = previousOk,
            xdripObserved = xObserved,
            xdripDown = xDown,
            xdripEpisodes = xEpisodes,
            xdripFixes = inRange.count { History.F_XDRIP_FIX in it.flags }
        )
    }

    private fun timeline(data: History.Data, from: Long, to: Long, gap: Long): LongArray {
        val t = LongArray(Cat.entries.size)
        val first = data.rounds.firstOrNull { it.at >= from - gap }?.at ?: to
        segments(data, maxOf(from, minOf(first, to)), to, gap) { s, e, c -> t[c.ordinal] += e - s }
        return t
    }

    /**
     * Découpe [from, to) en intervalles : chaque ronde vaut jusqu'à la suivante, au plus
     * [gap], et jamais au-delà d'un redémarrage du téléphone ; le reste est non surveillé.
     */
    private inline fun segments(
        data: History.Data,
        from: Long,
        to: Long,
        gap: Long,
        emit: (Long, Long, Cat) -> Unit
    ) {
        if (to <= from) return
        val boots = data.events.filter { it.type == History.EV_BOOT }.map { it.at }
        var cursor = from
        segmentsOf(data.rounds, from, to, gap, boots) { r, s, e ->
            if (s > cursor) emit(cursor, s, Cat.UNMONITORED)
            emit(s, e, cat(r))
            cursor = e
        }
        if (cursor < to) emit(cursor, to, Cat.UNMONITORED)
    }

    private inline fun segmentsOf(
        rounds: List<History.Round>,
        from: Long,
        to: Long,
        gap: Long,
        boots: List<Long> = emptyList(),
        emit: (History.Round, Long, Long) -> Unit
    ) {
        var bi = 0
        for (i in rounds.indices) {
            val r = rounds[i]
            var end = r.at + gap
            if (i + 1 < rounds.size) end = minOf(end, rounds[i + 1].at)
            while (bi < boots.size && boots[bi] <= r.at) bi++
            if (bi < boots.size) end = minOf(end, boots[bi])
            val s = maxOf(r.at, from)
            val e = minOf(end, to)
            if (e > s) emit(r, s, e)
        }
    }
}
