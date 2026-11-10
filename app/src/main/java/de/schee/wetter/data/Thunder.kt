package de.schee.wetter.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Gewitter-Abstand (Kevin am 10.11.2026): „Gewitter 12 km im Südwesten · kommt näher“.
 * Blitze vom Satelliten (MTG) in voller Auflösung um den Ort; Vergleich der Blitze von vor 15–30 Minuten
 * mit den neuesten zeigt, ob das Gewitter näher kommt oder abzieht. Die Daten kommen etwa 20 Minuten verzögert.
 */
object Thunder {
    /** Bis zu diesem Abstand gibt es einen Hinweis. */
    const val RADIUS_KM = 60.0

    data class Near(
        val km: Double,
        /** Richtung vom Ort zum Blitz (0 = Nord, 90 = Ost) */
        val bearing: Double,
        /** Zeitpunkt des nächsten Blitzes */
        val time: Instant,
        /** -1 kommt näher, 0 unklar, +1 zieht ab */
        val trend: Int,
        /** true = Blitze in den neuesten 10 Minuten der Daten */
        val active: Boolean,
    ) {
        /** Stufen für die Vibration: 0 = < 5 km … 4 = bis 60 km */
        val band: Int get() = when { km < 5 -> 0; km < 10 -> 1; km < 20 -> 2; km < 35 -> 3; else -> 4 }
    }

    fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 6371.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2); val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Jede Stelle (≈ 2 km) zählt zu dem Abschnitt, in dem sie zuerst auftaucht. */
    internal fun firstSeen(frames: Map<Instant, List<Satellite.Strike>>): List<Satellite.Strike> {
        val best = HashMap<Long, Satellite.Strike>()
        for ((_, list) in frames.toSortedMap()) for (s in list) {
            val key = Math.round(s.lat / 0.02) * 1_000_000L + Math.round(s.lon / 0.03)
            if (key !in best) best[key] = s
        }
        return best.values.sortedBy { it.time }
    }

    fun analyze(strikes: List<Satellite.Strike>, lat: Double, lon: Double, newest: Instant): Near? {
        val near = strikes.map { it to km(lat, lon, it.lat, it.lon) }
            .filter { it.second <= RADIUS_KM && !it.first.time.isBefore(newest.minusSeconds(35 * 60)) }
        if (near.isEmpty()) return null
        val recent = near.filter { !it.first.time.isBefore(newest.minusSeconds(10 * 60)) }
        val old = near.filter { it.first.time.isBefore(newest.minusSeconds(14 * 60)) }
        val pick = (recent.ifEmpty { near }).minBy { it.second }
        val trend = when {
            recent.isEmpty() -> 1
            old.isEmpty() -> 0
            else -> {
                // mittlerer Abstand der drei nächsten Blitze: robuster als nur der eine nächste
                fun core(l: List<Pair<Satellite.Strike, Double>>) = l.map { it.second }.sorted().take(3).average()
                val d = core(recent) - core(old)
                when { d < -4 -> -1; d > 4 -> 1; else -> 0 }
            }
        }
        return Near(pick.second, bearing(lat, lon, pick.first.lat, pick.first.lon), pick.first.time, trend, recent.isNotEmpty())
    }

    fun text(n: Near, now: Instant = Instant.now()): String {
        val dir = Units.compassLong(n.bearing.roundToInt())
        val km = "${n.km.roundToInt()} km im $dir"
        if (!n.active) {
            val min = ((now.epochSecond - n.time.epochSecond) / 60).coerceAtLeast(0)
            return "Gewitter zieht ab · letzter Blitz vor $min Min."
        }
        val head = if (n.km < 3) "Gewitter direkt hier" else "Gewitter $km"
        return when (n.trend) { -1 -> "$head · kommt näher"; 1 -> "$head · zieht ab"; else -> head }
    }

    /**
     * Laden: erst nur der neueste Abschnitt (1 kleines Bild). Nur wenn darin Blitze in der Nähe sind,
     * kommen die letzten 30 Minuten dazu (für „kommt näher“).
     */
    suspend fun load(lat: Double, lon: Double, now: Instant = Instant.now()): Near? = withContext(Dispatchers.IO) {
        if (!Satellite.lightningCovers(lat, lon)) return@withContext null
        val newest = Satellite.lightningTime(now)
        val first = runCatching { Satellite.localStrikes(lat, lon, newest) }.getOrNull() ?: return@withContext null
        if (first.none { km(lat, lon, it.lat, it.lon) <= RADIUS_KM }) {
            de.schee.wetter.log.AppLog.data("Blitze am Ort: keine im Umkreis von ${RADIUS_KM.toInt()} km")
            return@withContext null
        }
        val frames = coroutineScope {
            (1..6).map { i -> newest.minusSeconds(i * 300L) }.map { t ->
                async { t to (runCatching { Satellite.localStrikes(lat, lon, t) }.getOrNull() ?: emptyList()) }
            }.awaitAll()
        }.toMap() + (newest to first)
        val n = analyze(firstSeen(frames), lat, lon, newest)
        de.schee.wetter.log.AppLog.data("Blitze am Ort: ${n?.let { text(it, now) } ?: "keine"}")
        n
    }
}
