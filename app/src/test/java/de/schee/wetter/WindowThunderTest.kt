package de.schee.wetter

import de.schee.wetter.data.BestTime
import de.schee.wetter.data.ForecastParser
import de.schee.wetter.data.Hour
import de.schee.wetter.data.Satellite
import de.schee.wetter.data.Thunder
import de.schee.wetter.ui.tiles.TileId
import de.schee.wetter.ui.tiles.TileLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

/** 2.0: bestes Zeitfenster und Gewitter-Abstand. */
class WindowThunderTest {
    private val day = LocalDateTime.of(2026, 11, 10, 0, 0)
    private fun h(hour: Int, temp: Double = 12.0, precip: Double = 0.0, prob: Int = 5, wind: Double = 10.0, code: Int = 1) =
        Hour(day.plusHours(hour.toLong()), temp, code, prob, precip, wind, wind * 1.5, 250, hour in 8..16, 70)

    @Test fun walkWindowEndsWithRain() {
        // trocken 8–13 Uhr, ab 14 Uhr Regen
        val hours = (0..23).map { if (it >= 14) h(it, precip = 1.2, prob = 80, code = 61) else h(it) }
        val f = ForecastParser.parse(res("icon.json"), res("ecmwf.json"), 0L).copy(hours = hours)
        val r = BestTime.compute(f, day.plusHours(9).plusMinutes(20), BestTime.Activity.WALK)
        val w = r.today!!
        assertEquals(9, w.start.hour); assertEquals(14, w.end.hour)
        assertEquals("danach Regen", w.after)
        assertEquals("Jetzt bis 14 Uhr", BestTime.span(w, day.plusHours(9).plusMinutes(20)))
        assertEquals(15, r.strip.size)   // 9–23 Uhr
    }

    @Test fun laundryNeedsThreeHoursAndGrillNeedsWarmth() {
        val hours = (0..23).map { if (it == 11) h(it, precip = 0.5, prob = 60, code = 61) else h(it, temp = 6.0) }
        val f = ForecastParser.parse(res("icon.json"), res("ecmwf.json"), 0L).copy(hours = hours)
        val r = BestTime.compute(f, day.plusHours(7), BestTime.Activity.LAUNDRY)
        // 8–10 Uhr sind nur 3 Stunden → reicht gerade; 12–16 Uhr ist länger und gewinnt
        assertEquals(12, r.today!!.start.hour)
        val g = BestTime.compute(f, day.plusHours(7), BestTime.Activity.GRILL)
        assertNull(g.today); assertEquals("zu kalt", g.todayReason)
        assertEquals("Zu kalt", BestTime.noneText(g.todayReason))
    }

    @Test fun newTileLandsAfterRain() {
        val r = TileLayout.restore("RADAR,RAIN_2H,WIND,MOON")
        assertEquals(TileId.BEST_TIME, r[r.indexOf(TileId.RAIN_2H) + 1])
        assertEquals(TileId.entries.size, r.size)
    }

    @Test fun thunderApproaching() {
        val lat = 50.59; val lon = 6.65; val now = Instant.parse("2026-11-10T15:00:00Z")
        // vor 25 Min. 30 km südwestlich, jetzt 12 km südwestlich
        fun at(km: Double, minAgo: Long): Satellite.Strike {
            val d = km / 111.0 / Math.sqrt(2.0)
            return Satellite.Strike(lat - d, lon - d / Math.cos(Math.toRadians(lat)), now.minusSeconds(minAgo * 60), .5f)
        }
        val n = Thunder.analyze(listOf(at(30.0, 25), at(29.0, 25), at(12.0, 0), at(13.0, 5)), lat, lon, now)
        assertNotNull(n)
        assertEquals(12.0, n!!.km, 1.0)
        assertEquals(-1, n.trend)
        assertEquals("Gewitter 12 km im Südwesten · kommt näher", Thunder.text(n, now))
        assertEquals(2, n.band)
        // nur alte Blitze: zieht ab
        val old = Thunder.analyze(listOf(at(20.0, 25)), lat, lon, now)!!
        assertTrue(!old.active)
        assertTrue(Thunder.text(old, now).startsWith("Gewitter zieht ab"))
        // weit weg: nichts
        assertNull(Thunder.analyze(listOf(at(120.0, 0)), lat, lon, now))
    }

    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
}
