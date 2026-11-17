package de.schee.wetter.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
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
    @Volatile private var client: OkHttpClient? = null

    /**
     * Abruf-Programm der Karte – mit Zwischenspeicher auf dem Handy (150 MB, ältestes fliegt zuerst raus). Dieselben
     * Kacheln lädt [prefetch] schon beim App-Start vor; öffnet man dann die Karte, kommen sie aus dem Speicher.
     */
    fun client(context: android.content.Context): OkHttpClient = client ?: synchronized(this) {
        client ?: OkHttpClient.Builder()
            // wie der eigene Standard der Karte (20 Abrufe je Server gleichzeitig), nur mit unserem Zwischenschritt
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 20 })
            .cache(okhttp3.Cache(java.io.File(context.cacheDir, "map_tiles"), 150L * 1024 * 1024))
            .addInterceptor(this)
            .addNetworkInterceptor(KeepTiles)
            .build().also { client = it }
    }

    /** Einmal je App-Start, bevor die Karte Kacheln lädt. */
    fun install(context: android.content.Context) {
        if (installed) return
        installed = true
        runCatching { org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(client(context.applicationContext)) }
    }

    /**
     * Kacheln ändern sich unter ihrer Adresse nie (Grundkarte mit Versionsstand, Radar mit Zeitstempel im Pfad) –
     * deshalb dürfen sie im Zwischenspeicher bleiben, egal was der Server dazu sagt. Radar-Kacheln gelten nach
     * 2 Stunden als veraltet; ist der Speicher voll, fliegt das Älteste zuerst raus.
     */
    private object KeepTiles : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val resp = chain.proceed(chain.request())
            if (!resp.isSuccessful) return resp
            val url = chain.request().url
            val age = when {
                url.host.endsWith("rainviewer.com") && url.encodedPath.endsWith(".png") -> 2 * 3600
                url.host == "tiles.openfreemap.org" && url.encodedPath.endsWith(".pbf") -> 14 * 24 * 3600
                else -> return resp
            }
            return resp.newBuilder().removeHeader("Pragma").removeHeader("Expires")
                .header("Cache-Control", "public, max-age=$age").build()
        }
    }

    /** Kachel (Spalte, Zeile) an einer Position. */
    fun tileAt(z: Int, lat: Double, lon: Double): Pair<Int, Int> {
        val n = 1 shl z
        val x = ((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val y = ((1 - kotlin.math.ln(kotlin.math.tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * n).toInt().coerceIn(0, n - 1)
        return x to y
    }

    /** Kacheln rund um eine Position (3 × 3), als Adressen aus einer Vorlage mit {z}/{x}/{y}. */
    fun around(template: String, z: Int, lat: Double, lon: Double): List<String> {
        val (cx, cy) = tileAt(z, lat, lon)
        val n = 1 shl z
        return (-1..1).flatMap { dy -> (-1..1).map { dx -> (cx + dx).mod(n) to (cy + dy) } }
            .filter { it.second in 0 until n }
            .map { (x, y) -> template.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y") }
    }

    /** Beschreibung der Grundkarte (OpenFreeMap) mit der Kachel-Adresse – wie im Kartenstil eingetragen. */
    const val BASE_TILEJSON = "https://tiles.openfreemap.org/planet"

    /**
     * Kartenkacheln um den Ort schon beim App-Start laden (Kevin am 17.11.2026: „wenn ich auf das Radar drücke,
     * muss das Bild da sein“): Grundkarte (Zoom 5–8) und die drei neuesten weltweiten Radarbilder (Zoom 6–7).
     * Was schon im Speicher liegt, wird nicht erneut geladen – RainViewer bekommt so nur wenige Abrufe je Minute.
     */
    suspend fun prefetch(context: android.content.Context, lat: Double, lon: Double, rv: List<RainViewer.Frame>) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val c = client(context.applicationContext)
            val started = System.currentTimeMillis()
            val base = runCatching {
                val json = c.newCall(okhttp3.Request.Builder().url(BASE_TILEJSON).build()).execute().use { it.body?.string() }
                org.json.JSONObject(json ?: "{}").getJSONArray("tiles").getString(0)
            }.getOrNull()
            val urls = (base?.let { t -> (5..8).flatMap { z -> around(t, z, lat, lon) } } ?: emptyList()) +
                rv.takeLast(3).flatMap { f -> (6..7).flatMap { z -> around(f.tiles, z, lat, lon) } }
            val fresh = java.util.concurrent.atomic.AtomicInteger()
            val gate = kotlinx.coroutines.sync.Semaphore(4)
            kotlinx.coroutines.coroutineScope {
                urls.map { u ->
                    async {
                        gate.withPermit {
                            runCatching {
                                c.newCall(okhttp3.Request.Builder().url(u).build()).execute().use { r ->
                                    r.body?.bytes()
                                    if (r.networkResponse != null) fresh.incrementAndGet()
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
            if (fresh.get() > 0) de.schee.wetter.log.AppLog.data("Kartenkacheln vorgeladen: ${fresh.get()} neu, ${urls.size - fresh.get()} schon im Speicher (${System.currentTimeMillis() - started} ms)")
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
