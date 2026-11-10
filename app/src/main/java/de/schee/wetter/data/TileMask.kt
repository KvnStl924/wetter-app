package de.schee.wetter.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/**
 * Radar-Kacheln (RainViewer) für die Vorhersage: Dort, wo das DWD-Radar misst, werden sie geleert.
 *
 * Hintergrund (Kevin am 10.11.2026): Direkt nach „Jetzt“ sprang die Karte außerhalb Deutschlands vom scharfen
 * Radarbild auf das grobe Modellbild. Jetzt bleibt das letzte Radarbild in der Vorhersage stehen und geht über zwei
 * Stunden ins Modell über. Im Gebiet des DWD-Radars darf es aber nicht stehen bleiben – dort läuft die echte
 * DWD-Vorhersage weiter, ein stehendes Bild darunter ergäbe „Geisterregen“ an der alten Stelle.
 *
 * Die Karte (MapLibre) kann eine Kachel-Ebene nicht ausschneiden. Deshalb hängt sich diese Klasse in ihre
 * Abrufe: Kachel-Adressen mit dem Zusatz [MARK] werden ohne den Zusatz geladen und vor der Übergabe an die
 * Karte im DWD-Gebiet durchsichtig gemacht. Alle anderen Abrufe der Karte laufen unverändert durch.
 */
object TileMask : Interceptor {
    const val MARK = "dwdmask"
    private var installed = false

    /** Einmal je App-Start, bevor die Karte Kacheln lädt. */
    fun install() {
        if (installed) return
        installed = true
        runCatching {
            // wie der eigene Standard der Karte (20 Abrufe je Server gleichzeitig), nur mit unserem Zwischenschritt
            org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(
                OkHttpClient.Builder().dispatcher(Dispatcher().apply { maxRequestsPerHost = 20 }).addInterceptor(this).build())
        }
    }

    /** Kachel-Adresse (Vorlage mit {z}/{x}/{y}), deren Kacheln im DWD-Gebiet geleert werden. */
    fun marked(tiles: String) = "$tiles?$MARK=1"

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.url.queryParameter(MARK) == null) return chain.proceed(req)
        val clean = req.url.newBuilder().removeAllQueryParameters(MARK).build()
        val resp = chain.proceed(req.newBuilder().url(clean).build())
        val body = resp.body
        if (!resp.isSuccessful || body == null) return resp
        val type = body.contentType()
        val bytes = body.bytes()
        // geht beim Leeren etwas schief, bekommt die Karte die Kachel unverändert
        val out = runCatching { maskPng(bytes, clean.encodedPath, RadarData.coverage) }.getOrNull() ?: bytes
        return resp.newBuilder().removeHeader("Content-Length").body(out.toResponseBody(type)).build()
    }

    private val TILE = Regex("/512/(\\d+)/(\\d+)/(\\d+)/")

    /** Zoom, Spalte, Zeile aus dem Pfad einer RainViewer-Kachel (…/512/{z}/{x}/{y}/2/1_1.png). */
    fun tileOf(path: String): Triple<Int, Int, Int>? =
        TILE.find(path)?.let { m -> Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }

    fun lonOf(z: Int, x: Double) = x / (1 shl z) * 360.0 - 180.0
    fun latOf(z: Int, y: Double) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / (1 shl z)))))

    /** Liegt die Kachel ganz außerhalb des Deutschland-Ausschnitts? Dann ist nichts zu tun. */
    fun outside(z: Int, x: Int, y: Int) =
        lonOf(z, x + 1.0) < RadarData.WEST || lonOf(z, x.toDouble()) > RadarData.EAST ||
            latOf(z, y.toDouble()) < RadarData.SOUTH || latOf(z, y + 1.0) > RadarData.NORTH

    /**
     * Bildpunkte einer Kachel leeren, wo das DWD-Radar misst ([cov]; unbekannt = der ganze Deutschland-Ausschnitt).
     * @return true, wenn etwas geleert wurde
     */
    fun maskPixels(px: IntArray, size: Int, z: Int, x: Int, y: Int, cov: RadarData.Coverage?): Boolean {
        if (outside(z, x, y)) return false
        var changed = false
        for (py in 0 until size) {
            val lat = latOf(z, y + (py + 0.5) / size)
            if (lat < RadarData.SOUTH || lat > RadarData.NORTH) continue
            for (pxl in 0 until size) {
                val i = py * size + pxl
                if (px[i] ushr 24 == 0) continue
                val lon = lonOf(z, x + (pxl + 0.5) / size)
                if (lon < RadarData.WEST || lon > RadarData.EAST) continue
                if (cov == null || cov.at(lat, lon)) { px[i] = 0; changed = true }
            }
        }
        return changed
    }

    /** Kachel (PNG) leeren; null = unverändert lassen. */
    fun maskPng(bytes: ByteArray, path: String, cov: RadarData.Coverage?): ByteArray? {
        val (z, x, y) = tileOf(path) ?: return null
        if (outside(z, x, y)) return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (bmp.width != bmp.height) return null
        val n = bmp.width
        val px = IntArray(n * n); bmp.getPixels(px, 0, n, 0, 0, n, n)
        if (!maskPixels(px, n, z, x, y, cov)) return null
        val out = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream(bytes.size).also { out.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}
