package de.schee.wetter.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Locale


/**
 * Kartenbilder vom DWD (WMS): Kreisgrenzen als dunkle Grundkarte und das aktuelle Niederschlagsradar.
 * Ausschnitt etwa 120 × 120 km um den Ort. Das Vollbild-Radar steht in ui/radar/.
 */
object MapImages {
    data class Layers(val borders: Bitmap?, val radar: Bitmap?, val loadedAt: Long)

    const val HALF_LAT = 0.55
    const val HALF_LON = 0.85
    private var cache: Pair<String, Layers>? = null

    suspend fun load(lat: Double, lon: Double, maxAgeMs: Long = 5 * 60_000): Layers = withContext(Dispatchers.IO) {
        val key = "%.2f,%.2f".format(Locale.US, lat, lon)
        cache?.let { (k, l) -> if (k == key && System.currentTimeMillis() - l.loadedAt < maxAgeMs) return@withContext l }
        val bbox = "%.4f,%.4f,%.4f,%.4f".format(Locale.US, lat - HALF_LAT, lon - HALF_LON, lat + HALF_LAT, lon + HALF_LON)
        val base = "https://maps.dwd.de/geoserver/dwd/wms?service=WMS&version=1.3.0&request=GetMap&crs=EPSG:4326&bbox=$bbox&width=480&height=480&format=image/png&transparent=true&styles="
        coroutineScope {
            val b = async { runCatching { decode(Http.get("DWD-Grundkarte", base + "&layers=dwd:Warngebiete_Kreise"))?.let(::bordersOnly) }.getOrNull() }
            // Gleiche Farben wie im Vollbild-Radar (Leicht · Mäßig · Stark · Extrem).
            val r = async { runCatching { decode(Http.get("DWD-Radarbild", base + "&layers=dwd:Niederschlagsradar"))?.let { RadarData.crisp(it, RadarData.RADAR_SCALE) } }.getOrNull() }
            Layers(b.await(), r.await(), System.currentTimeMillis()).also { cache = key to it }
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    /** Aus der grün gefüllten DWD-Karte nur die dunklen Grenzlinien behalten, hell und halbtransparent. */
    internal fun bordersOnly(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24; val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            val lum = (r * 3 + g * 6 + b) / 10
            px[i] = if (a > 100 && lum < 140) (110 shl 24) or (190 shl 16) or (200 shl 8) or 225 else 0
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
