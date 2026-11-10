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
        val ids = org.json.JSONObject(out).getJSONArray("layers").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
        // schlicht: keine Straßen, Gebäude, Bahnen; Grenzen und Orte bleiben
        assertTrue(ids.none { it.startsWith("highway") || it.startsWith("railway") || it == "building" })
        assertTrue("boundary_country_z5-" in ids && "place_city" in ids && "water" in ids)
        assertTrue(out.contains("name:de"))
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
        // Wolken: dunkel → durchsichtig, weiß → fast deckend; außerhalb der MTG-Scheibe (schwarz) gilt die Weltkarte
        val n = 64 * 64
        val out = de.schee.wetter.data.Satellite.cloudPixels(IntArray(n) { if (it % 64 < 32) 60 else 240 }, IntArray(n) { 0 }, 64, 64, null)
        assertEquals(0, out[64 * 32 + 5] ushr 24)
        assertTrue((out[64 * 32 + 60] ushr 24) > 200)
        // Zeilen ↔ Breite: oben 85° N, Mitte Äquator
        assertEquals(85.0, de.schee.wetter.data.Satellite.latOfRow(0.0, 1000), 0.01)
        assertEquals(0.0, de.schee.wetter.data.Satellite.latOfRow(500.0, 1000), 0.01)
    }

    @Test fun mapTransformDateline() {
        // Ausschnitt über der Datumsgrenze (170° O … 190° = −170°): Punkt bei −175° liegt rechts von der Mitte
        val t = de.schee.wetter.ui.radar.MapTransform(170.0, 190.0, 10.0, -10.0, 1000f, 1000f)
        assertTrue(t.x(-175.0) > 500f && t.x(-175.0) < 1000f)
        assertTrue(t.x(175.0) > 0f && t.x(175.0) < 500f)
    }

    @Test fun worldCities() {
        assertTrue(de.schee.wetter.data.WorldCities.ALL.size > 150)
        assertTrue(de.schee.wetter.data.WorldCities.ALL.any { it.name == "New York" })
    }

    @Test fun worldField() {
        // GeoTIFF vom DWD (Deflate, Kacheln, 64-Bit-Gleitkomma): Temperatur in °C, Wind u/v in m/s
        val t = de.schee.wetter.data.WorldGrid.fromGeoTiff(javaClass.classLoader!!.getResource("world_t2m.tif")!!.readBytes())
        val uv = de.schee.wetter.data.WorldGrid.fromGeoTiff(javaClass.classLoader!!.getResource("world_uv10m.tif")!!.readBytes())
        assertEquals(2.5, t.value(52.5, 13.4), 1.5)    // Berlin
        assertEquals(28.8, t.value(6.5, 3.4), 1.5)     // Lagos
        assertTrue(t.value(10.0, 179.9).let { abs(it - t.value(10.0, -179.9)) < 2 }) // über die Datumsgrenze stetig
        val ww = de.schee.wetter.data.WorldWeather(Instant.EPOCH, t, uv)
        val o = DoubleArray(2); ww.windAt(52.5, 13.4, o)
        assertTrue(hypot(o[0], o[1]) in 0.5..40.0)
        assertTrue(de.schee.wetter.data.DwdWorld.url(de.schee.wetter.data.DwdWorld.Field.TEMP, Instant.parse("2026-11-09T18:00:00Z")).contains("2026-11-09T18:00:00.000Z"))
    }

    /** 3.0.2: Orte hatten keine Schriftgröße → die Karte zeichnete ihre Namen mit Größe 0 (unsichtbar). */
    @Test fun jedeBeschriftungHatEineSchriftgroesse() {
        val labels = listOf(
            de.schee.wetter.ui.radar.MapLabel(50.6, 6.6, "9 km/h", "Mechernich", 2, 0xFF4A6CC0.toInt()),
            de.schee.wetter.ui.radar.MapLabel(52.5, 13.4, null, "Berlin", 3),
            de.schee.wetter.ui.radar.MapLabel(48.9, 2.3, "12°", "Paris", 1, 0xFF4A6CC0.toInt()),
            de.schee.wetter.ui.radar.MapLabel(46.9, 2.3, "19°", "Frankreich", 4, 0xFF4A6CC0.toInt(), "3°", 0xFF4A6CC0.toInt()),
            de.schee.wetter.ui.radar.MapLabel(47.6, 14.1, "9°", "Österreich", 4, 0xFF4A6CC0.toInt(), "-2°", 0xFF4A6CC0.toInt(), small = true),
        )
        val sizes = de.schee.wetter.ui.radar.labelFeatures(labels).features()!!.map { it.getNumberProperty("ts").toFloat() }
        org.junit.Assert.assertEquals(listOf(12.5f, 12.5f, 12.5f, 11f, 9.5f), sizes)
    }
}
