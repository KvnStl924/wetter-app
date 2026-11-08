package de.schee.wetter.data

import java.time.LocalDate
import java.time.LocalDateTime

data class Current(
    val time: LocalDateTime,
    val temp: Double,
    val feels: Double,
    val humidity: Int,
    val dewPoint: Double,
    val code: Int,
    val wind: Double,
    val gusts: Double,
    val windDir: Int,
    val visibilityM: Double?,
    val isDay: Boolean,
    val precip: Double,
)

data class Hour(
    val time: LocalDateTime,
    val temp: Double,
    val code: Int,
    val precipProb: Int?,
    val precip: Double,
    val wind: Double,
    val gusts: Double?,
    val windDir: Int?,
    val isDay: Boolean,
)

data class Day(
    val date: LocalDate,
    val code: Int,
    val tMax: Double,
    val tMin: Double,
    val precipSum: Double,
    val precipProb: Int?,
    val windMax: Double,
    val gustMax: Double?,
    val sunrise: LocalDateTime?,
    val sunset: LocalDateTime?,
    /** ICON und ECMWF weichen deutlich voneinander ab. */
    val uncertain: Boolean,
    /** "ICON" oder "ECMWF" – welches Modell diesen Tag liefert. */
    val model: String,
)

data class Forecast(
    val lat: Double,
    val lon: Double,
    val timezone: String,
    val fetchedAt: Long,
    val current: Current,
    val hours: List<Hour>,
    val days: List<Day>,
    /** 15-Minuten-Niederschlag (ICON-D2), mm je 15 min. */
    val minutely: List<Pair<LocalDateTime, Double>>,
)

data class Place(val name: String, val lat: Double, val lon: Double, val fromGps: Boolean)

data class Warning(
    val event: String,
    val severity: String,
    val headline: String,
    val description: String,
    val onset: String?,
    val expires: String?,
    val colorRgb: String?,
) {
    /** 1 = Wetterwarnung … 4 = extremes Unwetter */
    val level: Int get() = when (severity) { "Extreme" -> 4; "Severe" -> 3; "Moderate" -> 2; else -> 1 }
}
