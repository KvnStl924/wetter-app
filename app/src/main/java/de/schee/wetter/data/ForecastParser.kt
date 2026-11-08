package de.schee.wetter.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * Baut aus den beiden Open-Meteo-Antworten eine Vorhersage:
 * Tag 0–1 und alle Stundenwerte der ersten Tage von ICON (ICON-D2 über icon_seamless),
 * Tag 2–9 von ECMWF. Weichen beide Modelle stark ab, wird der Tag als „unsicher“ markiert.
 */
object ForecastParser {
    const val ICON_DAYS = 2

    fun parse(iconJson: String, ecmwfJson: String, fetchedAt: Long): Forecast {
        val icon = JSONObject(iconJson)
        val ec = JSONObject(ecmwfJson)
        val c = icon.getJSONObject("current")
        val current = Current(
            time = LocalDateTime.parse(c.getString("time")),
            temp = c.optDouble("temperature_2m"),
            feels = c.optDouble("apparent_temperature"),
            humidity = c.optInt("relative_humidity_2m"),
            dewPoint = c.optDouble("dew_point_2m"),
            code = c.optInt("weather_code"),
            wind = c.optDouble("wind_speed_10m", 0.0),
            gusts = c.optDouble("wind_gusts_10m", 0.0),
            windDir = c.optInt("wind_direction_10m"),
            visibilityM = c.optDouble("visibility").takeUnless { it.isNaN() },
            isDay = c.optInt("is_day", 1) == 1,
            precip = c.optDouble("precipitation", 0.0),
        )

        val iconHours = hours(icon.getJSONObject("hourly"))
        val ecHours = hours(ec.getJSONObject("hourly"))
        val lastIcon = iconHours.lastOrNull()?.time
        val mergedHours = iconHours + ecHours.filter { lastIcon == null || it.time.isAfter(lastIcon) }

        val iconDays = days(icon.getJSONObject("daily"), "ICON")
        val ecDays = days(ec.getJSONObject("daily"), "ECMWF")
        val ecByDate = ecDays.associateBy { it.date }
        val iconByDate = iconDays.associateBy { it.date }
        val merged = mutableListOf<Day>()
        for ((i, d) in iconDays.withIndex()) if (i < ICON_DAYS) merged += d.copy(uncertain = disagree(d, ecByDate[d.date]))
        for (d in ecDays) if (merged.none { it.date == d.date }) merged += d.copy(uncertain = disagree(iconByDate[d.date], d))

        val minutely = icon.optJSONObject("minutely_15")?.let { m ->
            val t = m.getJSONArray("time"); val p = m.getJSONArray("precipitation")
            (0 until t.length()).map { LocalDateTime.parse(t.getString(it)) to p.optDouble(it, 0.0).nanTo0() }
        }.orEmpty()

        return Forecast(
            lat = icon.optDouble("latitude"), lon = icon.optDouble("longitude"),
            timezone = icon.optString("timezone", "Europe/Berlin"),
            fetchedAt = fetchedAt, current = current,
            hours = mergedHours, days = merged.sortedBy { it.date }.take(10), minutely = minutely,
        )
    }

    /** Deutlich unterschiedliche Höchst-/Tiefstwerte oder Regen nur in einem Modell. */
    fun disagree(a: Day?, b: Day?): Boolean {
        if (a == null || b == null) return false
        if (abs(a.tMax - b.tMax) >= 3.0 || abs(a.tMin - b.tMin) >= 3.0) return true
        val wetA = a.precipSum >= 2.0; val wetB = b.precipSum >= 2.0
        val dryA = a.precipSum < 0.3; val dryB = b.precipSum < 0.3
        return (wetA && dryB) || (wetB && dryA)
    }

    private fun hours(h: JSONObject): List<Hour> {
        val t = h.getJSONArray("time")
        val temp = h.arr("temperature_2m"); val code = h.arr("weather_code"); val pp = h.arr("precipitation_probability")
        val pr = h.arr("precipitation"); val ws = h.arr("wind_speed_10m"); val wg = h.arr("wind_gusts_10m")
        val wd = h.arr("wind_direction_10m"); val day = h.arr("is_day")
        val out = ArrayList<Hour>(t.length())
        for (i in 0 until t.length()) {
            val tv = temp.d(i) ?: continue
            out += Hour(
                time = LocalDateTime.parse(t.getString(i)), temp = tv, code = code.d(i)?.toInt() ?: 3,
                precipProb = pp.d(i)?.toInt(), precip = pr.d(i) ?: 0.0, wind = ws.d(i) ?: 0.0,
                gusts = wg.d(i), windDir = wd.d(i)?.toInt(),
                isDay = day.d(i)?.let { it >= 1.0 } ?: (LocalDateTime.parse(t.getString(i)).hour in 7..17),
            )
        }
        return out
    }

    private fun days(d: JSONObject, model: String): List<Day> {
        val t = d.getJSONArray("time")
        val code = d.arr("weather_code"); val mx = d.arr("temperature_2m_max"); val mn = d.arr("temperature_2m_min")
        val ps = d.arr("precipitation_sum"); val pp = d.arr("precipitation_probability_max")
        val wm = d.arr("wind_speed_10m_max"); val gm = d.arr("wind_gusts_10m_max")
        val sr = d.optJSONArray("sunrise"); val ss = d.optJSONArray("sunset")
        val out = ArrayList<Day>()
        for (i in 0 until t.length()) {
            val tMax = mx.d(i) ?: continue
            val tMin = mn.d(i) ?: continue
            out += Day(
                date = LocalDate.parse(t.getString(i)), code = code.d(i)?.toInt() ?: 3, tMax = tMax, tMin = tMin,
                precipSum = ps.d(i) ?: 0.0, precipProb = pp.d(i)?.toInt(), windMax = wm.d(i) ?: 0.0, gustMax = gm.d(i),
                sunrise = sr?.optString(i)?.takeIf { it.length >= 16 }?.let(LocalDateTime::parse),
                sunset = ss?.optString(i)?.takeIf { it.length >= 16 }?.let(LocalDateTime::parse),
                uncertain = false, model = model,
            )
        }
        return out
    }

    private fun JSONObject.arr(name: String): JSONArray? = optJSONArray(name)
    private fun JSONArray?.d(i: Int): Double? = this?.takeIf { !it.isNull(i) }?.optDouble(i)?.takeUnless { it.isNaN() }
    private fun Double.nanTo0() = if (isNaN()) 0.0 else this
}
