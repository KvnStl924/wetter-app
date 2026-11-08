package de.schee.wetter

import de.schee.wetter.data.Day
import de.schee.wetter.data.ForecastParser
import de.schee.wetter.data.WarningsRepository
import de.schee.wetter.data.WeatherCodes
import de.schee.wetter.data.Climate
import de.schee.wetter.data.Units
import de.schee.wetter.ui.main.HourItem
import de.schee.wetter.ui.main.Timeline
import de.schee.wetter.ui.sky.SkyTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ForecastTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun parsesAndMergesModels() {
        val f = ForecastParser.parse(res("icon.json"), res("ecmwf.json"), 0L)
        assertEquals(10, f.days.size)
        assertEquals("ICON", f.days[0].model)
        assertEquals("ICON", f.days[1].model)
        assertEquals("ECMWF", f.days[2].model)
        assertTrue(f.days.zipWithNext().all { (a, b) -> b.date == a.date.plusDays(1) })
        assertTrue(f.hours.size >= 7 * 24)
        assertTrue(f.hours.zipWithNext().all { (a, b) -> b.time.isAfter(a.time) })
        assertTrue(f.minutely.isNotEmpty())
        assertEquals("Europe/Berlin", f.timezone)
        assertTrue(f.days[0].sunrise != null && f.days[0].sunset != null)
    }

    @Test fun flagsDisagreement() {
        val base = Day(LocalDate.of(2026, 11, 10), 3, 10.0, 4.0, 0.0, 10, 10.0, 20.0, null, null, false, "ICON")
        assertFalse(ForecastParser.disagree(base, base.copy(tMax = 11.5)))
        assertTrue(ForecastParser.disagree(base, base.copy(tMax = 13.5)))
        assertTrue(ForecastParser.disagree(base, base.copy(precipSum = 4.0)))
        assertFalse(ForecastParser.disagree(base.copy(precipSum = 1.0), base.copy(precipSum = 2.5)))
    }

    @Test fun hourlyContainsSunEvents() {
        val f = ForecastParser.parse(res("icon.json"), res("ecmwf.json"), 0L)
        val now = f.days[0].sunrise!!.minusHours(3)
        val items = Timeline.hourlyItems(f.hours, f.days, now)
        assertTrue(items.first() is HourItem.H && (items.first() as HourItem.H).isNow)
        assertTrue(items.any { it is HourItem.Sun && it.rise })
        assertTrue(items.any { it is HourItem.Sun && !it.rise })
        assertTrue(items.zipWithNext().all { (a, b) -> !b.time.isBefore(a.time) })
    }

    @Test fun skyTimeFromSun() {
        val rise = LocalDateTime.of(2026, 11, 9, 7, 30); val set = LocalDateTime.of(2026, 11, 9, 16, 55)
        assertEquals(SkyTime.DAWN, WeatherCodes.skyTime(rise.plusMinutes(10), rise, set))
        assertEquals(SkyTime.DAY, WeatherCodes.skyTime(rise.plusHours(4), rise, set))
        assertEquals(SkyTime.DUSK, WeatherCodes.skyTime(set.minusMinutes(20), rise, set))
        assertEquals(SkyTime.NIGHT, WeatherCodes.skyTime(set.plusHours(2), rise, set))
    }

    @Test fun parsesDwdWarnings() {
        val w = WarningsRepository.parse(res("warnings.json"))
        assertTrue(w.isNotEmpty())
        assertTrue(w.first().headline.startsWith("Amtliche WARNUNG"))
    }

    @Test fun climateAverageUsesWindowAcrossYears() {
        val times = listOf("2001-01-01", "2001-01-03", "2001-12-31", "2002-01-02", "2002-06-01")
        val vals = listOf(1.0, 3.0, 5.0, 3.0, 25.0)
        assertEquals(3.0, Climate.normalMax(times, vals, LocalDate.of(2026, 1, 1))!!, 0.01)
    }

    @Test fun unitsConvert() {
        assertEquals("50°", Units("°F", "km/h").t(10.0))
        assertEquals("3 m/s", Units("°C", "m/s").w(10.8))
        assertEquals("4 Bft", Units("°C", "Bft").w(25.0))
        assertEquals("SO", Units.compass(133))
        assertEquals("N", Units.compass(355))
    }
}
