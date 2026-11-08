package de.schee.wetter.ui.main

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import de.schee.wetter.data.Day
import de.schee.wetter.data.Hour
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Einträge der stündlichen Leiste: Stunden plus Sonnenauf-/untergang als eigene Einträge. */
sealed interface HourItem {
    val time: LocalDateTime
    data class H(val hour: Hour, val isNow: Boolean) : HourItem { override val time get() = hour.time }
    data class Sun(override val time: LocalDateTime, val rise: Boolean) : HourItem
}

object Timeline {
    fun hourlyItems(hours: List<Hour>, days: List<Day>, now: LocalDateTime, count: Int = 25): List<HourItem> {
        val start = now.truncatedTo(ChronoUnit.HOURS)
        val hs = hours.filter { !it.time.isBefore(start) }.take(count)
        if (hs.isEmpty()) return emptyList()
        val end = hs.last().time
        val suns = days.flatMap { d -> listOfNotNull(d.sunrise?.let { HourItem.Sun(it, true) }, d.sunset?.let { HourItem.Sun(it, false) }) }
            .filter { it.time.isAfter(now) && it.time.isBefore(end) }
        return (hs.mapIndexed { i, h -> HourItem.H(h, i == 0) } + suns).sortedBy { it.time }
    }

    fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Heute"
        else -> when (date.dayOfWeek) {
            DayOfWeek.MONDAY -> "Mo"; DayOfWeek.TUESDAY -> "Di"; DayOfWeek.WEDNESDAY -> "Mi"; DayOfWeek.THURSDAY -> "Do"
            DayOfWeek.FRIDAY -> "Fr"; DayOfWeek.SATURDAY -> "Sa"; else -> "So"
        }
    }

    fun dayLong(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Heute"
        today.plusDays(1) -> "Morgen"
        else -> when (date.dayOfWeek) {
            DayOfWeek.MONDAY -> "Montag"; DayOfWeek.TUESDAY -> "Dienstag"; DayOfWeek.WEDNESDAY -> "Mittwoch"; DayOfWeek.THURSDAY -> "Donnerstag"
            DayOfWeek.FRIDAY -> "Freitag"; DayOfWeek.SATURDAY -> "Samstag"; else -> "Sonntag"
        }
    }

    fun hhmm(t: LocalDateTime) = "%02d:%02d".format(t.hour, t.minute)

    private val stops = listOf(
        -10.0 to Color(0xFF3E6BFF), 0.0 to Color(0xFF45C3F5), 10.0 to Color(0xFFA3E05C),
        18.0 to Color(0xFFFFD23F), 25.0 to Color(0xFFFF9A2E), 32.0 to Color(0xFFFF4A3D),
    )

    /** Farbe für eine Temperatur (°C) – kalt blau bis heiß rot. */
    fun tempColor(c: Double): Color {
        if (c <= stops.first().first) return stops.first().second
        if (c >= stops.last().first) return stops.last().second
        val i = stops.indexOfLast { it.first <= c }
        val (a, ca) = stops[i]; val (b, cb) = stops[i + 1]
        return lerp(ca, cb, ((c - a) / (b - a)).toFloat())
    }
}
