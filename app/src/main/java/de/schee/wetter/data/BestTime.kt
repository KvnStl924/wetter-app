package de.schee.wetter.data

import java.time.LocalDateTime
import kotlin.math.abs


/**
 * Bestes Zeitfenster (Kevin am 10.11.2026): „Trocken von 14 bis 16:30 Uhr, dann Regen.“
 * Für eine Tätigkeit wird jede Stunde bewertet (nicht machbar / 0…1), dann das beste zusammenhängende Fenster
 * für heute (ab jetzt) und morgen gesucht.
 */
object BestTime {
    enum class Activity(val label: String, val minHours: Int) {
        WALK("Spazieren", 1),
        BIKE("Fahrrad", 1),
        LAUNDRY("Wäsche", 3),
        GRILL("Grillen", 2),
    }

    /** Bewertung einer Stunde: Punkte 0…1 oder warum es nicht geht. */
    data class Rating(val score: Double?, val reason: String?)

    data class Window(
        val start: LocalDateTime,
        /** erste Stunde, die nicht mehr dazugehört */
        val end: LocalDateTime,
        val score: Double,
        /** Stunde mit den meisten Punkten */
        val peak: LocalDateTime,
        val tMax: Double,
        val sunny: Boolean,
        /** Warum das Fenster endet („danach Regen“), null = bis zum Ende des Tages */
        val after: String?,
    )

    data class Result(
        val today: Window?,
        val tomorrow: Window?,
        /** Häufigster Grund, wenn heute nichts passt („zu kalt“) */
        val todayReason: String?,
        val tomorrowReason: String?,
        /** Nächste 24 Stunden ab jetzt: Punkte 0…1, -1 = geht nicht */
        val strip: List<Pair<LocalDateTime, Double>>,
    )

    private fun wet(h: Hour) = h.precip >= 0.1 || (h.precipProb ?: 0) >= 45
    private fun thunder(h: Hour) = h.code >= 95
    private fun sunBonus(h: Hour) = when (h.code) { 0, 1 -> 1.0; 2 -> .6; 3 -> .2; else -> 0.0 }
    private fun comfort(t: Double, best: Double, span: Double) = (1 - abs(t - best) / span).coerceIn(0.0, 1.0)

    fun rate(a: Activity, h: Hour): Rating {
        if (thunder(h)) return Rating(null, "Gewitter")
        val gust = h.gusts ?: (h.wind * 1.5)
        val dry = !wet(h)
        val prob = (h.precipProb ?: 0) / 100.0
        return when (a) {
            Activity.WALK -> when {
                !dry -> Rating(null, "Regen")
                !h.isDay -> Rating(null, "dunkel")
                gust >= 65 -> Rating(null, "Sturm")
                else -> Rating(.35 + .25 * sunBonus(h) + .25 * comfort(h.temp, 18.0, 25.0) + .15 * (1 - h.wind / 40).coerceIn(0.0, 1.0) - .3 * prob, null)
            }
            Activity.BIKE -> when {
                !dry -> Rating(null, "Regen")
                !h.isDay -> Rating(null, "dunkel")
                h.temp < 1 -> Rating(null, "Glätte")
                h.wind >= 32 || gust >= 50 -> Rating(null, "zu windig")
                else -> Rating(.3 + .2 * sunBonus(h) + .25 * comfort(h.temp, 19.0, 20.0) + .25 * (1 - h.wind / 32) - .3 * prob, null)
            }
            Activity.LAUNDRY -> when {
                !dry || prob >= .3 -> Rating(null, "Regen")
                !h.isDay -> Rating(null, "dunkel")
                h.temp < 4 -> Rating(null, "zu kalt")
                gust >= 60 -> Rating(null, "Sturm")
                (h.humidity ?: 70) >= 92 -> Rating(null, "zu feucht")
                else -> Rating(.2 + .25 * sunBonus(h) + .2 * (h.wind.coerceAtMost(25.0) / 25) + .2 * ((h.temp - 4) / 20).coerceIn(0.0, 1.0) +
                    .15 * ((95 - (h.humidity ?: 70)) / 55.0).coerceIn(0.0, 1.0), null)
            }
            Activity.GRILL -> when {
                h.time.hour !in 11..21 -> Rating(null, "Uhrzeit")
                !dry -> Rating(null, "Regen")
                h.temp < 8 -> Rating(null, "zu kalt")
                h.wind >= 35 || gust >= 55 -> Rating(null, "zu windig")
                else -> Rating(.3 + .2 * sunBonus(h) + .35 * comfort(h.temp, 24.0, 16.0) + .15 * (1 - h.wind / 35) - .3 * prob +
                    (if (h.time.hour in 17..20) .1 else 0.0), null)
            }
        }
    }

    /** Bestes Fenster in den Stunden (müssen aufeinander folgen); bevorzugt längere Fenster bis etwa 4 Stunden. */
    internal fun best(a: Activity, hours: List<Hour>, followUp: Hour? = null, next: (Hour) -> Hour? = { null }): Window? {
        var best: Window? = null
        var i = 0
        while (i < hours.size) {
            if (rate(a, hours[i]).score == null) { i++; continue }
            var j = i
            while (j + 1 < hours.size && rate(a, hours[j + 1]).score != null) j++
            val run = hours.subList(i, j + 1)
            if (run.size >= a.minHours) {
                val scores = run.map { rate(a, it).score!!.coerceIn(0.0, 1.0) }
                val value = scores.average() * (0.55 + 0.45 * (run.size.coerceAtMost(4) / 4.0))
                if (best == null || value > best.score) {
                    val stop = hours.getOrNull(j + 1) ?: next(run.last())
                    best = Window(
                        start = run.first().time, end = run.last().time.plusHours(1), score = value,
                        peak = run[scores.indices.maxBy { scores[it] }].time,
                        tMax = run.maxOf { it.temp }, sunny = run.count { sunBonus(it) >= .6 } * 2 >= run.size,
                        after = stop?.let { s -> rate(a, s).reason?.let { afterText(it) } },
                    )
                }
            }
            i = j + 1
        }
        return best
    }

    private fun afterText(reason: String): String? = when (reason) {
        "Regen" -> "danach Regen"
        "dunkel" -> "dann wird es dunkel"
        "Gewitter" -> "danach Gewitter"
        "Sturm", "zu windig" -> "danach zu windig"
        "zu kalt", "Glätte" -> "danach zu kalt"
        "zu feucht" -> "danach zu feucht"
        else -> null
    }

    /** Häufigster Grund unter den hellen Stunden (z. B. „zu kalt“). */
    private fun reason(a: Activity, hours: List<Hour>): String? =
        hours.mapNotNull { rate(a, it).reason }.filter { it != "dunkel" && it != "Uhrzeit" }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: if (hours.isNotEmpty()) "dunkel" else null

    fun compute(f: Forecast, now: LocalDateTime, a: Activity): Result {
        val startHour = now.withMinute(0).withSecond(0).withNano(0)
        val today = now.toLocalDate()
        val all = f.hours.filter { !it.time.isBefore(startHour) }.sortedBy { it.time }
        // Ab halb zählt die laufende Stunde nicht mehr mit („jetzt bis 17 Uhr“ um 16:54 wäre sinnlos)
        val from = if (now.minute >= 30) startHour.plusHours(1) else startHour
        val todayH = all.filter { it.time.toLocalDate() == today && !it.time.isBefore(from) }
        val tomorrowH = all.filter { it.time.toLocalDate() == today.plusDays(1) }
        val byTime = all.associateBy { it.time }
        val next = { h: Hour -> byTime[h.time.plusHours(1)] }
        val t = best(a, todayH, next = next)
        val m = best(a, tomorrowH, next = next)
        return Result(
            today = t, tomorrow = m,
            todayReason = if (t == null) reason(a, todayH) else null,
            tomorrowReason = if (m == null) reason(a, tomorrowH) else null,
            strip = all.take(24).map { it.time to (rate(a, it).score?.coerceIn(0.0, 1.0) ?: -1.0) },
        )
    }

    // ------------------------------------------------------------ Texte

    private fun hh(t: LocalDateTime) = "${t.hour}"

    /** „14–17 Uhr“, „jetzt bis 17 Uhr“, „ab 20 Uhr“ */
    fun span(w: Window, now: LocalDateTime): String {
        val endH = if (w.end.toLocalDate() != w.start.toLocalDate() && w.end.hour == 0) "24" else hh(w.end)
        return if (!w.start.isAfter(now)) "Jetzt bis $endH Uhr" else "${hh(w.start)}–$endH Uhr"
    }

    /** „Trocken, bis 9°, sonnig · danach Regen“ */
    fun details(a: Activity, w: Window, units: Units?): String {
        val t = units?.t(w.tMax) ?: "${Math.round(w.tMax)}°"
        val parts = mutableListOf("Trocken", "bis $t")
        if (w.sunny) parts += "sonnig"
        if (a == Activity.LAUNDRY) parts += "trocknet gut"
        val head = parts.joinToString(", ")
        return listOfNotNull(head, w.after).joinToString(" · ")
    }

    fun noneText(reason: String?) = when (reason) {
        "Regen" -> "Kein trockenes Fenster"
        "zu kalt", "Glätte" -> "Zu kalt"
        "zu windig", "Sturm" -> "Zu windig"
        "Gewitter" -> "Gewitter"
        "zu feucht" -> "Zu feucht, trocknet nicht"
        else -> "Kein passendes Fenster"
    }
}
