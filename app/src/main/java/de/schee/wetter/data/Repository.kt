package de.schee.wetter.data

import android.content.Context
import de.schee.wetter.BuildConfig
import de.schee.wetter.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.util.Locale

/** HTTP mit Protokoll: jede Abfrage landet mit Dauer und Größe im Log. */
object Http {
    /** [log] = false für viele kleine Abrufe (Radarbilder); der Aufrufer protokolliert dann eine Zusammenfassung. */
    fun get(label: String, url: String, timeoutMs: Int = 12_000, log: Boolean = true): ByteArray {
        val started = System.currentTimeMillis()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs; readTimeout = timeoutMs
            setRequestProperty("User-Agent", "Wetter-App/${BuildConfig.VERSION_NAME} (Android)")
        }
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("$label: HTTP $code")
            val bytes = conn.inputStream.use { it.readBytes() }
            if (log) AppLog.data("$label geladen (${System.currentTimeMillis() - started} ms, ${bytes.size / 1024} kB)")
            return bytes
        } catch (e: Exception) {
            if (log) AppLog.error("$label fehlgeschlagen nach ${System.currentTimeMillis() - started} ms", e)
            throw e
        } finally {
            conn.disconnect()
        }
    }

    fun text(label: String, url: String, timeoutMs: Int = 12_000) = String(get(label, url, timeoutMs), Charsets.UTF_8)
}

/** Lädt die Vorhersage (ICON + ECMWF parallel) und hält eine Kopie für die Offline-Anzeige. */
class WeatherRepository(private val context: Context) {
    private fun cacheFile(lat: Double, lon: Double) =
        File(context.filesDir, "cache/forecast_${"%.2f".format(Locale.US, lat)}_${"%.2f".format(Locale.US, lon)}.json")

    fun cached(lat: Double, lon: Double): Forecast? = runCatching {
        val f = cacheFile(lat, lon)
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        ForecastParser.parse(o.getString("icon"), o.getString("ecmwf"), o.getLong("fetchedAt"))
    }.onFailure { AppLog.error("Gespeicherte Vorhersage nicht lesbar", it) }.getOrNull()

    suspend fun load(lat: Double, lon: Double): Forecast = withContext(Dispatchers.IO) {
        val ll = "latitude=${"%.4f".format(Locale.US, lat)}&longitude=${"%.4f".format(Locale.US, lon)}"
        coroutineScope {
            val icon = async {
                Http.text(
                    "Open-Meteo ICON",
                    "https://api.open-meteo.com/v1/forecast?$ll&models=icon_seamless&timezone=auto&forecast_days=7" +
                        "&current=temperature_2m,apparent_temperature,relative_humidity_2m,dew_point_2m,weather_code,wind_speed_10m,wind_gusts_10m,wind_direction_10m,visibility,is_day,precipitation" +
                        "&hourly=temperature_2m,weather_code,precipitation_probability,precipitation,wind_speed_10m,wind_gusts_10m,wind_direction_10m,is_day" +
                        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,wind_speed_10m_max,wind_gusts_10m_max,sunrise,sunset" +
                        "&minutely_15=precipitation"
                )
            }
            val ecmwf = async {
                Http.text(
                    "Open-Meteo ECMWF",
                    "https://api.open-meteo.com/v1/forecast?$ll&models=ecmwf_ifs025&timezone=auto&forecast_days=10" +
                        "&hourly=temperature_2m,weather_code,precipitation_probability,precipitation,wind_speed_10m,wind_direction_10m" +
                        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,wind_speed_10m_max,wind_gusts_10m_max,sunrise,sunset"
                )
            }
            val now = System.currentTimeMillis()
            val i = icon.await(); val e = ecmwf.await()
            val forecast = ForecastParser.parse(i, e, now)
            runCatching {
                val f = cacheFile(lat, lon); f.parentFile?.mkdirs()
                f.writeText(JSONObject().put("icon", i).put("ecmwf", e).put("fetchedAt", now).toString())
            }
            forecast
        }
    }
}

/** Langjähriges Mittel (1991–2020) des Tageshöchstwerts, einmal je Ort geladen und dauerhaft gespeichert. */
class ClimateRepository(private val context: Context) {
    private fun file(lat: Double, lon: Double) =
        File(context.filesDir, "cache/climate_${"%.1f".format(Locale.US, lat)}_${"%.1f".format(Locale.US, lon)}.json")

    /** Mittel des Tageshöchstwerts für [date] (±3 Tage, 30 Jahre). */
    suspend fun normalMax(lat: Double, lon: Double, date: LocalDate): Double? = withContext(Dispatchers.IO) {
        val f = file(lat, lon)
        val json = if (f.exists()) f.readText() else runCatching {
            Http.text(
                "Open-Meteo Klimamittel 1991–2020",
                "https://archive-api.open-meteo.com/v1/archive?latitude=${"%.2f".format(Locale.US, lat)}&longitude=${"%.2f".format(Locale.US, lon)}" +
                    "&start_date=1991-01-01&end_date=2020-12-31&daily=temperature_2m_max&timezone=Europe%2FBerlin"
            ).also { f.parentFile?.mkdirs(); f.writeText(it) }
        }.getOrNull() ?: return@withContext null
        val d = JSONObject(json).getJSONObject("daily")
        Climate.normalMax(d.getJSONArray("time").let { a -> List(a.length()) { a.getString(it) } },
            d.getJSONArray("temperature_2m_max").let { a -> List(a.length()) { if (a.isNull(it)) null else a.getDouble(it) } }, date)
    }
}

object Climate {
    fun normalMax(times: List<String>, values: List<Double?>, date: LocalDate, window: Int = 3): Double? {
        val target = date.dayOfYear
        var sum = 0.0; var n = 0
        for (i in times.indices) {
            val v = values[i] ?: continue
            val doy = LocalDate.parse(times[i]).dayOfYear
            var diff = kotlin.math.abs(doy - target)
            if (diff > 183) diff = 366 - diff
            if (diff <= window) { sum += v; n++ }
        }
        return if (n == 0) null else sum / n
    }
}

/** Amtliche Warnungen des DWD für einen Punkt (WFS, Gemeindeebene). */
object WarningsRepository {
    suspend fun load(lat: Double, lon: Double): List<Warning> = withContext(Dispatchers.IO) {
        val point = URLEncoder.encode("INTERSECTS(THE_GEOM,POINT(${"%.4f".format(Locale.US, lat)} ${"%.4f".format(Locale.US, lon)}))", "UTF-8")
        val json = Http.text(
            "DWD-Warnungen",
            "https://maps.dwd.de/geoserver/dwd/ows?service=WFS&version=2.0.0&request=GetFeature&typeName=dwd:Warnungen_Gemeinden" +
                "&outputFormat=application/json&propertyName=EVENT,SEVERITY,HEADLINE,DESCRIPTION,ONSET,EXPIRES,EC_AREA_COLOR&CQL_FILTER=$point"
        )
        parse(json)
    }

    fun parse(json: String): List<Warning> {
        val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).map { features.getJSONObject(it).getJSONObject("properties") }.map { p ->
            Warning(
                event = p.optString("EVENT"), severity = p.optString("SEVERITY"), headline = p.optString("HEADLINE"),
                description = p.optString("DESCRIPTION"), onset = p.optString("ONSET").ifEmpty { null },
                expires = p.optString("EXPIRES").ifEmpty { null }.takeUnless { it == "null" }, colorRgb = p.optString("EC_AREA_COLOR").ifEmpty { null },
            )
        }.distinctBy { it.headline + it.onset }.sortedByDescending { it.level }
    }
}

/** Ortssuche über Open-Meteo Geocoding (Deutsch). */
object PlaceSearch {
    suspend fun search(query: String): List<Place> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val json = Http.text("Ortssuche „$query“",
            "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(query, "UTF-8")}&count=8&language=de")
        val r = JSONObject(json).optJSONArray("results") ?: return@withContext emptyList()
        (0 until r.length()).map { r.getJSONObject(it) }.map {
            val region = listOfNotNull(it.optString("admin1").ifEmpty { null }, it.optString("country").ifEmpty { null }).joinToString(", ")
            Place("${it.getString("name")}${if (region.isNotEmpty()) " · $region" else ""}", it.getDouble("latitude"), it.getDouble("longitude"), false)
        }
    }
}
