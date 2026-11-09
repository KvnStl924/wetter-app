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
 * Windfeld als Gitter (Open-Meteo, ICON/global, stündlich für 13 Stunden).
 *  - [GERMANY]: 1° über Deutschland und Nachbarländer (154 Punkte), beim Öffnen der Windebene
 *  - [viewSpec]: grob, 10 × 10 Punkte über dem sichtbaren Kartenausschnitt – wird nachgeladen, wenn man
 *    die Karte über das Deutschland-Gitter hinaus verschiebt oder herauszoomt (echte Werte, keine Simulation)
 */
class WindGrid(
    val lat0: Double, val lon0: Double, val step: Double, val rows: Int, val cols: Int,
    val hours: List<Instant>,
    /** [hour][row][col] → Wind (u = nach Osten, v = nach Norden) in km/h. */
    private val u: Array<Array<DoubleArray>>,
    private val v: Array<Array<DoubleArray>>,
    /** [hour][row][col] → Niederschlag der vorangegangenen Stunde in mm (Modell, für das Radar außerhalb Deutschlands). */
    private val p: Array<Array<DoubleArray>>? = null,
) {
    val lat1 get() = lat0 + (rows - 1) * step
    val lon1 get() = lon0 + (cols - 1) * step

    data class Spec(val lat0: Double, val lon0: Double, val step: Double, val rows: Int, val cols: Int)

    companion object {
        // 1°-Raster (154 Punkte): Open-Meteo zählt jeden Punkt als Abruf (Grenze 600/Minute) – feiner wurde es am 09.11. zu viel.
        val GERMANY = Spec(46.0, 4.0, 1.0, 11, 14)
        const val VIEW_POINTS = 10

        /** Grobes Gitter über einem Ausschnitt (mit Rand), Breite auf ±80° begrenzt. */
        fun viewSpec(south: Double, north: Double, west: Double, east: Double): Spec {
            val span = maxOf(north - south, east - west) * 1.3
            // höchstens die ganze Erde (160° Breite, 360° Länge) – sonst Absturz bei weit herausgezoomter Karte (09.11.2026)
            val step = (span / (VIEW_POINTS - 1)).coerceIn(0.25, 160.0 / (VIEW_POINTS - 1))
            val cLat = (south + north) / 2; val cLon = (west + east) / 2
            val lat0 = (cLat - step * (VIEW_POINTS - 1) / 2).coerceIn(-80.0, 80.0 - step * (VIEW_POINTS - 1))
            val lon0 = cLon - step * (VIEW_POINTS - 1) / 2
            // auf das Raster runden, damit nahe Ausschnitte dieselbe Anfrage ergeben
            fun r(x: Double) = Math.round(x / step * 2) * step / 2
            return Spec(r(lat0), r(lon0), step, VIEW_POINTS, VIEW_POINTS)
        }

        fun url(s: Spec): String {
            val lats = StringBuilder(); val lons = StringBuilder()
            for (r in 0 until s.rows) for (c in 0 until s.cols) {
                if (lats.isNotEmpty()) { lats.append(','); lons.append(',') }
                lats.append("%.3f".format(Locale.US, s.lat0 + r * s.step))
                // Länge in −180 … 180 halten
                var lon = s.lon0 + c * s.step; while (lon > 180) lon -= 360; while (lon < -180) lon += 360
                lons.append("%.3f".format(Locale.US, lon))
            }
            return "https://api.open-meteo.com/v1/forecast?latitude=$lats&longitude=$lons" +
                "&hourly=wind_speed_10m,wind_direction_10m,precipitation&forecast_hours=13&timezone=UTC"
        }

        fun load(s: Spec, label: String): WindGrid = parse(Http.text(label, url(s), timeoutMs = 20_000), s)

        fun parse(json: String, s: Spec = GERMANY): WindGrid {
            val arr = JSONArray(json)
            val first = arr.getJSONObject(0).getJSONObject("hourly").getJSONArray("time")
            val hours = (0 until first.length()).map { LocalDateTime.parse(first.getString(it)).toInstant(ZoneOffset.UTC) }
            val u = Array(hours.size) { Array(s.rows) { DoubleArray(s.cols) } }
            val v = Array(hours.size) { Array(s.rows) { DoubleArray(s.cols) } }
            val p = Array(hours.size) { Array(s.rows) { DoubleArray(s.cols) } }
            for (i in 0 until minOf(arr.length(), s.rows * s.cols)) {
                val h = arr.getJSONObject(i).getJSONObject("hourly")
                val sp = h.getJSONArray("wind_speed_10m"); val dir = h.getJSONArray("wind_direction_10m"); val pr = h.optJSONArray("precipitation")
                val r = i / s.cols; val c = i % s.cols
                for (t in hours.indices) {
                    val speed = sp.optDouble(t, 0.0).takeIf { !it.isNaN() } ?: 0.0
                    val d = dir.optDouble(t, 0.0).takeIf { !it.isNaN() } ?: 0.0
                    // Richtung = woher der Wind kommt → Bewegung in Gegenrichtung.
                    val rad = Math.toRadians(d)
                    u[t][r][c] = -speed * sin(rad); v[t][r][c] = -speed * cos(rad)
                    p[t][r][c] = pr?.optDouble(t, 0.0)?.takeIf { !it.isNaN() } ?: 0.0
                }
            }
            return WindGrid(s.lat0, s.lon0, s.step, s.rows, s.cols, hours, u, v, p)
        }
    }

    /** Stunde, die zu [time] passt (vor der ersten Stunde → erste). */
    fun hourIndex(time: Instant): Int {
        var best = 0
        for (i in hours.indices) if (!hours[i].isAfter(time)) best = i
        return best
    }

    /** Abstand (in Grad) vom Rand des Gitters; negativ = außerhalb. */
    fun inside(lat: Double, lon: Double) = minOf(lat - lat0, lat1 - lat, lon - lon0, lon1 - lon)

    fun covers(south: Double, north: Double, west: Double, east: Double) = south >= lat0 && north <= lat1 && west >= lon0 && east <= lon1

    /** Niederschlag (mm/h) am Ort, bilinear. */
    fun precipAt(hour: Int, lat: Double, lon: Double): Double {
        val fr = ((lat - lat0) / step).coerceIn(0.0, rows - 1.0001); val fc = ((lon - lon0) / step).coerceIn(0.0, cols - 1.0001)
        val r = floor(fr).toInt(); val c = floor(fc).toInt(); val tr = fr - r; val tc = fc - c
        return (precip(hour, r, c) * (1 - tc) + precip(hour, r, c + 1) * tc) * (1 - tr) + (precip(hour, r + 1, c) * (1 - tc) + precip(hour, r + 1, c + 1) * tc) * tr
    }

    /** Niederschlag (mm/h) am Gitterpunkt. */
    fun precip(hour: Int, r: Int, c: Int): Double = p?.get(hour.coerceIn(0, hours.size - 1))?.get(r)?.get(c) ?: 0.0

    /** Wind am Ort, bilinear zwischen den Gitterpunkten; außerhalb des Gitters am Rand festgehalten. */
    fun at(hour: Int, lat: Double, lon: Double, out: DoubleArray) {
        val fr = ((lat - lat0) / step).coerceIn(0.0, rows - 1.0001)
        val fc = ((lon - lon0) / step).coerceIn(0.0, cols - 1.0001)
        val r = floor(fr).toInt(); val c = floor(fc).toInt()
        val tr = fr - r; val tc = fc - c
        val uu = u[hour.coerceIn(0, hours.size - 1)]; val vv = v[hour.coerceIn(0, hours.size - 1)]
        out[0] = (uu[r][c] * (1 - tc) + uu[r][c + 1] * tc) * (1 - tr) + (uu[r + 1][c] * (1 - tc) + uu[r + 1][c + 1] * tc) * tr
        out[1] = (vv[r][c] * (1 - tc) + vv[r][c + 1] * tc) * (1 - tr) + (vv[r + 1][c] * (1 - tc) + vv[r + 1][c + 1] * tc) * tr
    }
}

/**
 * Mehrere Gitter zusammen: das feine Deutschland-Gitter hat Vorrang, außerhalb gilt das grobe
 * Gitter des sichtbaren Ausschnitts. Am Übergang wird weich gemischt.
 */
class WindField(val base: WindGrid, val view: WindGrid?) {
    val hours get() = base.hours
    private val tmp = DoubleArray(2)

    fun at(hour: Int, lat: Double, lon: Double, out: DoubleArray) {
        val v = view
        val inBase = base.inside(lat, lon)
        if (v == null || inBase >= base.step * 2) { base.at(hour, lat, lon, out); return }
        val vh = v.hourIndex(base.hours[hour.coerceIn(0, base.hours.size - 1)])
        if (inBase <= base.step) { v.at(vh, lat, lon, out); return }
        // Übergangszone: weich mischen
        val f = (inBase - base.step) / base.step
        base.at(hour, lat, lon, out); v.at(vh, lat, lon, tmp)
        out[0] = out[0] * f + tmp[0] * (1 - f); out[1] = out[1] * f + tmp[1] * (1 - f)
    }

    /** Wie weit (Grad) liegt der Punkt innerhalb der Daten? Für das Ausblenden am Rand. */
    fun inside(lat: Double, lon: Double) = maxOf(base.inside(lat, lon), view?.inside(lat, lon) ?: Double.NEGATIVE_INFINITY)
}
