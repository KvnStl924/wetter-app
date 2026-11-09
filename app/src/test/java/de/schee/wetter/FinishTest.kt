package de.schee.wetter

import de.schee.wetter.data.DwdWorld
import de.schee.wetter.data.Place
import de.schee.wetter.data.PlaceStore
import de.schee.wetter.data.Satellite
import de.schee.wetter.data.WeatherCodes
import de.schee.wetter.data.WorldCities
import de.schee.wetter.ui.sky.SkyWeather
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Prüfungen aus Phase 6 (Feinschliff): Ortsliste, Blitze, Weltdaten, Wettertexte. */
class FinishTest {
    @Test fun placesRoundTrip() {
        val list = listOf(Place("Köln", 50.94, 6.96, false), Place("Palma de Mallorca", 39.57, 2.65, false))
        assertEquals(list, PlaceStore.parse(PlaceStore.serialize(list)))
        assertEquals(emptyList<Place>(), PlaceStore.parse("kein json"))
        // Kennung: gleich für fast gleiche Koordinaten, unabhängig von der Sprache des Handys
        assertEquals("50.940,6.960", PlaceStore.id(Place("x", 50.94, 6.96, false)))
        assertEquals(PlaceStore.id(Place("a", 50.9401, 6.9599, false)), PlaceStore.id(Place("b", 50.94, 6.96, false)))
    }

    @Test fun lightningFirstSeenKeepsEarliest() {
        val t0 = Instant.parse("2026-11-09T18:00:00Z"); val t1 = t0.plusSeconds(300)
        val a = Satellite.Strike(6.5, 3.4, t0, .3f)
        val b = Satellite.Strike(6.51, 3.41, t1, .9f)   // gleiche Stelle, später, stärker
        val c = Satellite.Strike(10.0, 10.0, t1, .5f)
        val out = Satellite.firstSeen(mapOf(t1 to listOf(b, c), t0 to listOf(a)))
        assertEquals(2, out.size)
        val same = out.first { it.lat < 7 }
        assertEquals(t0, same.time)            // zählt ab dem ersten Auftauchen
        assertEquals(.9f, same.strength, 0f)   // stärkster Wert bleibt
    }

    @Test fun lightningFromImage() {
        // 8 × 8 Bild, ein roter Fleck oben links → genau ein Punkt
        val px = IntArray(64)
        px[0] = 0xFFFF0000.toInt(); px[1] = 0xFFFF0000.toInt(); px[8] = 0xFFFFE000.toInt()
        val s = Satellite.strikesFrom(px, 8, 8, Instant.EPOCH)
        assertEquals(1, s.size)
        assertTrue(s[0].strength > .5f)
        assertTrue(s[0].lon < -60 && s[0].lat > 50)   // oben links im Gebiet −80…80° / −75…75°
    }

    @Test fun worldHourRounding() {
        assertEquals(Instant.parse("2026-11-09T18:00:00Z"), DwdWorld.hourOf(Instant.parse("2026-11-09T18:29:00Z")))
        assertEquals(Instant.parse("2026-11-09T19:00:00Z"), DwdWorld.hourOf(Instant.parse("2026-11-09T18:31:00Z")))
    }

    @Test fun worldCitiesValid() {
        val all = WorldCities.ALL
        assertEquals(all.size, all.map { it.name }.toSet().size)
        assertTrue(all.all { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 && it.rank in 1..3 })
        // jede Weltgegend ist vertreten
        assertTrue(all.any { it.lon < -100 } && all.any { it.lon > 140 } && all.any { it.lat < -30 } && all.any { it.lat > 60 })
    }

    @Test fun weatherCodes() {
        assertEquals("Gewitter", WeatherCodes.text(95, true))
        assertEquals(SkyWeather.STORM, WeatherCodes.sky(95))
        for (c in listOf(0, 1, 2, 3, 45, 51, 61, 63, 65, 71, 80, 95)) assertTrue(WeatherCodes.text(c, true).isNotBlank())
    }

    @Test fun placesFile() {
        val idx = de.schee.wetter.data.Places.parse(java.io.File("src/main/assets/places.bin").readBytes())
        assertTrue(idx.size > 100_000)
        // größte zuerst, deutsche Namen
        assertTrue(idx.pop[0] >= idx.pop[1000])
        val names = (0 until idx.size).map { idx.name(it) }.toSet()
        assertTrue("Köln" in names && "München" in names && "Mechernich" in names && "Kall" in names && "Peking" in names)
        // Ausschnitt um Mechernich: viele Orte, auch kleine
        val near = de.schee.wetter.data.Places.inView(idx, 50.3, 50.9, 6.3, 7.1)
        assertTrue(near.size > 35)
        // über die Datumsgrenze: Fidschi (178° O) im Ausschnitt 170 … 190
        assertTrue(de.schee.wetter.data.Places.inView(idx, -20.0, -15.0, 170.0, 190.0).isNotEmpty())
    }
}
