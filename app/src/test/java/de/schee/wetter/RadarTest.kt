package de.schee.wetter

import de.schee.wetter.data.RadarData
import de.schee.wetter.data.WindGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.abs
import kotlin.math.hypot

class RadarTest {
    @Test fun timeline() {
        val latest = Instant.parse("2026-11-09T07:35:00Z")
        val f = RadarData.frames(latest)
        // −2 h … +2 h in 5-Minuten-Schritten = 49 Bilder, dann Modell 10:00 … 19:00 (volle Stunden bis +12 h)
        assertEquals(49, f.count { it.kind != RadarData.Kind.MODEL })
        assertEquals(-120, f.first().minutes)
        assertEquals(RadarData.Kind.RADAR, f.first { it.minutes == 0 }.kind)
        assertEquals(RadarData.Kind.FORECAST, f.first { it.minutes == 5 }.kind)
        val model = f.filter { it.kind == RadarData.Kind.MODEL }
        assertEquals(Instant.parse("2026-11-09T10:00:00Z"), model.first().time)
        assertEquals(Instant.parse("2026-11-09T19:00:00Z"), model.last().time)
        assertTrue(RadarData.url(model.first()).contains("TOTPREC01H") && RadarData.url(model.first()).contains("time=2026-11-09T11:00:00Z"))
    }

    @Test fun latestGuessRoundsToFiveMinutes() {
        assertEquals(Instant.parse("2026-11-09T07:30:00Z"), RadarData.latestGuess(Instant.parse("2026-11-09T07:41:59Z")))
        assertEquals(Instant.parse("2026-11-09T07:35:00Z"), RadarData.latestGuess(Instant.parse("2026-11-09T07:43:00Z")))
    }

    @Test fun recolorKeepsRainDropsNoData() {
        // halbtransparentes Grau = „keine Daten“ → durchsichtig
        assertEquals(0, RadarData.mapColor(0x4d7e7e7e, RadarData.RADAR_SCALE))
        // Magenta-Rand des Radarverbunds → durchsichtig
        assertEquals(0, RadarData.mapColor(0xfffb00ff.toInt(), RadarData.RADAR_SCALE))
        // leichter Regen (0,1–0,2 mm/h) → sichtbar, Farbe der Skala „Leicht“
        val light = RadarData.mapColor(0xff33ffff.toInt(), RadarData.RADAR_SCALE)
        assertTrue(light ushr 24 > 100)
        assertEquals(RadarData.colorFor(0.15) and 0xFFFFFF, light and 0xFFFFFF)
        // Modell: Grau bedeutet dort 0,1–2 mm → sichtbar
        assertTrue(RadarData.mapColor(0xffc0c0c0.toInt(), RadarData.MODEL_SCALE) ushr 24 > 100)
    }

    @Test fun mercatorRoundTrip() {
        val t = de.schee.wetter.ui.radar.MapTransform(5.0, 15.0, 55.0, 47.0, 1000f, 1200f)
        assertEquals(0f, t.x(5.0), 0.01f); assertEquals(1200f, t.y(47.0), 0.01f)
        assertTrue(abs(t.lat(t.y(50.59)) - 50.59) < 1e-6 && abs(t.lon(t.x(6.65)) - 6.65) < 1e-6)
    }

    @Test fun mapStyleBrighterAndGerman() {
        val src = javaClass.classLoader!!.getResource("style_dark.json")!!.readText()
        val out = de.schee.wetter.data.MapStyle.transform(src)
        assertTrue(!out.contains("rgb(12,12,12)") && out.contains("rgba(30,30,30,1)"))
        assertTrue(out.contains("[\"get\",\"name:de\"],[\"get\",\"name_en\"]"))
        org.json.JSONObject(out) // gültiges JSON
        assertEquals(listOf(59.0, 59.0, 59.0, 1.0), de.schee.wetter.data.MapStyle.parse("hsl(0,0%,23%)")!!.map { Math.round(it).toDouble() })
    }

    @Test fun windGrid() {
        val g = WindGrid.parse(javaClass.classLoader!!.getResource("windgrid.json")!!.readText())
        val view = WindGrid.parse(javaClass.classLoader!!.getResource("viewgrid.json")!!.readText(), WindGrid.Spec(22.5, -16.0, 3.5, 16, 16))
        assertTrue((0 until view.hours.size).any { h -> (0 until 16).any { r -> (0 until 16).any { c -> view.precip(h, r, c) > 0 } } })
        val field = de.schee.wetter.data.WindField(g, view)
        val o = DoubleArray(2); field.at(0, 40.0, -5.0, o); assertTrue(hypot(o[0], o[1]) in 0.0..200.0)
        assertEquals(154, WindGrid.GERMANY.rows * WindGrid.GERMANY.cols)
        // weit herausgezoomt (größer als die Erde) darf nicht abstürzen
        val s = WindGrid.viewSpec(-89.0, 89.0, -400.0, 400.0); assertTrue(s.lat0 >= -80.0 && s.lat0 + s.step * (s.rows - 1) <= 80.0001)
        assertTrue(s.step * (s.cols - 1) >= 359.0) // ganze Erde in der Länge
        assertTrue(g.hours.size >= 12)
        val out = DoubleArray(2)
        g.at(0, 50.5, 7.0, out)
        assertTrue(hypot(out[0], out[1]) in 0.0..150.0)
        assertEquals(0, g.hourIndex(Instant.parse("2000-01-01T00:00:00Z")))
    }

    @Test fun satellite() {
        // Blitze: 20 Minuten zurück, auf 5 Minuten abgerundet
        assertEquals(Instant.parse("2026-11-09T16:40:00Z"), de.schee.wetter.data.Satellite.lightningTime(Instant.parse("2026-11-09T17:03:41Z")))
        // Wolken: dunkel → durchsichtig, weiß → fast deckend; MTG hat Vorrang vor der Weltkarte
        val dark = 0xFF3C3C3C.toInt(); val white = 0xFFF0F0F0.toInt()
        val out = de.schee.wetter.data.Satellite.cloudPixels(intArrayOf(dark, white, white), intArrayOf(0, 0, dark), 3)
        assertEquals(0, out[0] ushr 24)
        assertTrue((out[1] ushr 24) > 220)
        assertEquals(0, out[2] ushr 24)
        // Ausschnitt mit Rand, auf die Weltkarte begrenzt
        val a = de.schee.wetter.data.Satellite.area(-80.0, 80.0, -170.0, 170.0)
        assertEquals(-85.0, a[0], 0.0); assertEquals(180.0, a[3], 0.0)
    }
}
