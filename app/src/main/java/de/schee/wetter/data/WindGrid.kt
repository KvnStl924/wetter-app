package de.schee.wetter.data

import org.json.JSONArray
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Windfeld über Deutschland und Nachbarländer: Gitter im Abstand von 0,5° (567 Punkte),
 * Open-Meteo (ICON), stündlich für die nächsten 13 Stunden. Wird nur beim Öffnen der Windebene geladen.
 */
class WindGrid(
    val hours: List<Instant>,
    /** [hour][row][col] → Wind (u = nach Osten, v = nach Norden) in km/h. */
    private val u: Array<Array<DoubleArray>>,
    private val v: Array<Array<DoubleArray>>,
) {
    companion object {
        const val LAT0 = 46.0; const val LAT1 = 56.0
        const val LON0 = 4.0; const val LON1 = 17.0
        const val STEP = 0.5
        val ROWS = ((LAT1 - LAT0) / STEP).toInt() + 1
        val COLS = ((LON1 - LON0) / STEP).toInt() + 1

        fun url(): String {
            val lats = StringBuilder(); val lons = StringBuilder()
            for (r in 0 until ROWS) for (c in 0 until COLS) {
                if (lats.isNotEmpty()) { lats.append(','); lons.append(',') }
                lats.append("%.1f".format(Locale.US, LAT0 + r * STEP)); lons.append("%.1f".format(Locale.US, LON0 + c * STEP))
            }
            return "https://api.open-meteo.com/v1/forecast?latitude=$lats&longitude=$lons" +
                "&hourly=wind_speed_10m,wind_direction_10m&forecast_hours=13&models=icon_seamless&timezone=UTC"
        }

        fun load(): WindGrid = parse(Http.text("Open-Meteo Windgitter", url(), timeoutMs = 20_000))

        fun parse(json: String): WindGrid {
            val arr = JSONArray(json)
            val first = arr.getJSONObject(0).getJSONObject("hourly").getJSONArray("time")
            val hours = (0 until first.length()).map { LocalDateTime.parse(first.getString(it)).toInstant(ZoneOffset.UTC) }
            val u = Array(hours.size) { Array(ROWS) { DoubleArray(COLS) } }
            val v = Array(hours.size) { Array(ROWS) { DoubleArray(COLS) } }
            for (i in 0 until minOf(arr.length(), ROWS * COLS)) {
                val h = arr.getJSONObject(i).getJSONObject("hourly")
                val sp = h.getJSONArray("wind_speed_10m"); val dir = h.getJSONArray("wind_direction_10m")
                val r = i / COLS; val c = i % COLS
                for (t in hours.indices) {
                    val s = sp.optDouble(t, 0.0); val d = dir.optDouble(t, 0.0)
                    // Richtung = woher der Wind kommt → Bewegung in Gegenrichtung.
                    val rad = Math.toRadians(d)
                    u[t][r][c] = -s * sin(rad); v[t][r][c] = -s * cos(rad)
                }
            }
            return WindGrid(hours, u, v)
        }
    }

    /** Stunde, die zu [time] passt (vor der ersten Stunde → erste). */
    fun hourIndex(time: Instant): Int {
        var best = 0
        for (i in hours.indices) if (!hours[i].isAfter(time)) best = i
        return best
    }

    /** Wind am Ort, bilinear zwischen den Gitterpunkten; außerhalb des Gitters am Rand festgehalten. */
    fun at(hour: Int, lat: Double, lon: Double, out: DoubleArray) {
        val fr = ((lat - LAT0) / STEP).coerceIn(0.0, ROWS - 1.0001)
        val fc = ((lon - LON0) / STEP).coerceIn(0.0, COLS - 1.0001)
        val r = floor(fr).toInt(); val c = floor(fc).toInt()
        val tr = fr - r; val tc = fc - c
        val uu = u[hour]; val vv = v[hour]
        out[0] = (uu[r][c] * (1 - tc) + uu[r][c + 1] * tc) * (1 - tr) + (uu[r + 1][c] * (1 - tc) + uu[r + 1][c + 1] * tc) * tr
        out[1] = (vv[r][c] * (1 - tc) + vv[r][c + 1] * tc) * (1 - tr) + (vv[r + 1][c] * (1 - tc) + vv[r + 1][c + 1] * tc) * tr
    }
}
