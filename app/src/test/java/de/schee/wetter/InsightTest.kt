package de.schee.wetter

import de.schee.wetter.data.Astro
import de.schee.wetter.data.Current
import de.schee.wetter.data.Feel
import de.schee.wetter.data.ForecastParser
import de.schee.wetter.data.Hour
import de.schee.wetter.data.Nowcast
import de.schee.wetter.data.Yesterday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

/** Neue Funktionen in 1.0: Modellvergleich, gefühlte Wahrheit, Gestern, Regen auf die Minute, Zugrichtung, Sonnenstand. */
class InsightTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun confidence() {
        val high = ForecastParser.rate(listOf(10.0, 10.5, 11.0, 10.2, 9.8, 10.4), listOf(0.0, 0.0, 0.1, 0.0, 0.0, 0.0))
        assertEquals(3, high.level); assertEquals(0, high.wetModels)
        val low = ForecastParser.rate(listOf(10.0, 11.0, 12.0, 10.0, 11.0, 12.0), listOf(5.0, 4.0, 0.0, 0.0, 6.0, 0.0))
        assertEquals(1, low.level)
        val spread = ForecastParser.rate(listOf(4.0, 12.0, 8.0, 7.0), listOf(0.0, 0.0, 0.0, 0.0))
        assertEquals(1, spread.level)
        // echte Antwort mit 6 Modellen
        val c = ForecastParser.confidence(org.json.JSONObject(res("multi.json")))
        assertTrue(c.size >= 8)
        assertTrue(c.values.all { it.models in 3..6 && it.level in 1..3 }); assertEquals(6, c.values.first().models)
    }

    private fun cur(t: Double, f: Double, dew: Double, hum: Int, wind: Double, code: Int = 3, precip: Double = 0.0) =
        Current(LocalDateTime.of(2026, 11, 10, 14, 0), t, f, hum, dew, code, wind, wind * 1.5, 270, 10000.0, true, precip)

    @Test fun feelWords() {
        assertEquals("schwül", Feel.word(cur(27.0, 30.0, 19.0, 70, 5.0)))
        assertEquals("beißender Wind", Feel.word(cur(3.0, -2.0, -1.0, 70, 30.0)))
        assertEquals("nasskalt", Feel.word(cur(5.0, 3.0, 4.0, 95, 10.0, code = 61, precip = .4)))
        assertEquals("angenehm", Feel.word(cur(21.0, 21.0, 10.0, 50, 5.0)))
        assertEquals("frisch", Feel.word(cur(9.0, 8.0, 3.0, 70, 8.0)))
    }

    @Test fun yesterday() {
        val f = ForecastParser.parse(res("icon.json"), res("ecmwf.json"), 0L)
        val now = f.current.time
        val h = Hour(now.minusDays(1), f.current.temp + 3.4, 3, 0, 0.0, 5.0, 8.0, 200, true)
        assertEquals("3° kälter als gestern", Yesterday.text(f.copy(yesterday = listOf(h)), now))
        assertNull(Yesterday.text(f.copy(yesterday = emptyList()), now))
        // Gestern gehört nicht in die Tagesliste
        assertTrue(f.days.none { it.date.isBefore(now.toLocalDate()) })
    }

    @Test fun nowcastHeadline() {
        val t0 = Instant.parse("2026-11-10T12:00:00Z")
        fun s(vararg mm: Double) = Nowcast.Series(mm.mapIndexed { i, v -> t0.plusSeconds(i * 300L) to v })
        assertNull(Nowcast.headline(s(0.0, 0.0, 0.0, 0.0, 0.0, 0.0), t0))
        assertEquals("Regen in 15 Min.", Nowcast.headline(s(0.0, 0.0, 0.0, 0.5, 1.0, 1.0), t0))
        assertEquals("Regen hört in 10 Min. auf", Nowcast.headline(s(1.0, 1.0, 0.0, 0.0, 0.0, 0.0), t0))
        assertEquals("Regen hält an", Nowcast.headline(s(1.0, 1.0, 1.0, 1.0, 1.0, 1.0), t0))
        // Farben der DWD-Legende → mm/h
        assertEquals(0.0, Nowcast.mm(0), 0.0)
        assertEquals(6.0, Nowcast.mm(0xFFFFFF01.toInt()), 0.0)
    }

    @Test fun motionFromShift() {
        // Regenfeld um 3 Blöcke nach Osten und 2 nach Süden verschoben
        val w = 60; val h = 60
        val a = Array(h) { y -> FloatArray(w) { x -> if (x in 15..30 && y in 20..34) 1f + (x % 3) * .3f else 0f } }
        val b = Array(h) { y -> FloatArray(w) { x -> val sx = x - 3; val sy = y - 2; if (sx in 15..30 && sy in 20..34) 1f + (sx % 3) * .3f else 0f } }
        val r = Nowcast.shift(a, b, 30, 30, 25, 6)
        assertNotNull(r)
        assertEquals(3, r!!.first); assertEquals(2, r.second)
    }

    @Test fun sunAndMoon() {
        // Mechernich, 10.11.2026 Mittag (12:20 MEZ): Sonne im Süden, gut 20° hoch
        val noon = Instant.parse("2026-11-10T11:20:00Z").toEpochMilli()
        val s = Astro.sun(noon, 50.59, 6.65)
        assertTrue("Höhe ${s.altDeg}", s.altDeg in 18.0..26.0)
        assertTrue("Richtung ${s.azDeg}", s.azDeg in 165.0..195.0)
        // Abends unter dem Horizont, im Westen
        val eve = Astro.sun(Instant.parse("2026-11-10T17:00:00Z").toEpochMilli(), 50.59, 6.65)
        assertTrue(eve.altDeg < 0 && eve.azDeg in 220.0..300.0)
        val m = Astro.moonPos(noon, 50.59, 6.65)
        assertTrue(m.altDeg in -90.0..90.0 && m.azDeg in 0.0..360.0)
    }
}
