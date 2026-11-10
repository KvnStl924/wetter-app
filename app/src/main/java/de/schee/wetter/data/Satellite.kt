package de.schee.wetter.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp


/**
 * Satellitendaten von EUMETSAT (EUMETView, frei mit Quellenangabe „© EUMETSAT“).
 * Beides wird beim Öffnen der App einmal für die ganze Welt geladen – danach ist es beim Wischen sofort da.
 *  - Wolken: Infrarot-Bild. Europa/Afrika/Atlantik vom Satelliten MTG (alle 10 Minuten), sonst die
 *    weltweite Wolkenkarte (alle 3 Stunden), weich ineinander übergeblendet. Kalt = Wolke = weiß.
 *  - Blitze: „Accumulated Flash Area“ des Blitz-Sensors auf MTG (Europa, Afrika, Atlantik, Teile Südamerikas),
 *    alle 5 Minuten. Die App macht daraus einzelne Blitzpunkte mit Alter.
 */
object Satellite {
    private const val WMS = "https://view.eumetsat.int/geoserver/ows?service=WMS&version=1.3.0&request=GetMap&styles=&crs=EPSG:3857"
    const val ATTRIBUTION = "<a href=\"https://view.eumetsat.int/\">Satellit: © EUMETSAT</a>"

    /** Weltbild in Mercator-Aufteilung, ±85° Breite – passt genau auf die ganze Karte. */
    const val MAX_LAT = 85.0
    const val WORLD_PX = 1800

    fun mercX(lon: Double) = lon * 20037508.34 / 180.0
    fun mercY(lat: Double) = RadarData.mercY(lat)
    fun latOfRow(y: Double, h: Int): Double {
        val top = mercY(MAX_LAT)
        val my = (top - y / h * 2 * top) * 180.0 / 20037508.34
        return 360.0 / PI * atan(exp(my * PI / 180.0)) - 90.0
    }
    fun lonOfCol(x: Double, w: Int) = -180.0 + x / w * 360.0

    private fun worldUrl(layer: String, format: String, extra: String = "") =
        "$WMS&layers=$layer&format=$format&width=$WORLD_PX&height=$WORLD_PX&bbox=" +
            "%.0f,%.0f,%.0f,%.0f".format(Locale.US, mercX(-180.0), mercY(-MAX_LAT), mercX(180.0), mercY(MAX_LAT)) + extra

    // ------------------------------------------------------------------ Wolken

    /** Fertiges Wolkenbild der ganzen Welt (weiß mit Durchsichtigkeit). */
    class Clouds(val bitmap: Bitmap, val loadedAt: Long)

    /** Graustufen (0–255) eines Weltbilds; null = nicht erreichbar. Zwischenspeicher 30 Minuten. */
    private fun grays(context: Context?, layer: String, label: String): IntArray? = runCatching {
        val file = context?.let { File(File(it.cacheDir, "world").apply { mkdirs() }, "sat2_${layer.substringBefore(':')}.jpg") }
        val bytes = if (file != null && file.exists() && System.currentTimeMillis() - file.lastModified() < 30 * 60_000L) file.readBytes()
        else runCatching { Http.get(label, worldUrl(layer, "image/jpeg", "&bgcolor=0x000000"), timeoutMs = 40_000) }
            .onSuccess { b -> file?.let { runCatching { it.writeBytes(b) } } }
            // ohne Netz: das zuletzt gespeicherte Bild, auch wenn älter
            .getOrElse { e -> if (file != null && file.exists()) file.readBytes() else throw e }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
        val s = if (bmp.width == WORLD_PX && bmp.height == WORLD_PX) bmp else Bitmap.createScaledBitmap(bmp, WORLD_PX, WORLD_PX, true)
        val px = IntArray(WORLD_PX * WORLD_PX); s.getPixels(px, 0, WORLD_PX, 0, 0, WORLD_PX, WORLD_PX)
        IntArray(px.size) { (px[it] shr 8) and 0xFF }
    }.getOrNull()

    fun loadClouds(context: Context?, temp: WorldWeather?): Clouds {
        val world = grays(context, "mumi:worldcloudmap_ir108", "EUMETSAT Wolken weltweit")
        val mtg = grays(context, "mtg_fd:ir105_hrfi", "EUMETSAT Wolken MTG")
        if (world == null && mtg == null) error("Satellitenbild nicht erreichbar")
        val px = cloudPixels(world, mtg, WORLD_PX, WORLD_PX, temp)
        return Clouds(Bitmap.createBitmap(px, WORLD_PX, WORLD_PX, Bitmap.Config.ARGB_8888), System.currentTimeMillis())
    }

    /**
     * Graustufen → Wolken:
     *  - MTG (aktueller) und Weltkarte werden am Rand der MTG-Scheibe weich gemischt (keine Kante)
     *  - kalter Boden (Winter, Polargebiete) sieht im Infrarot hell aus wie Wolken: dort beginnen Wolken
     *    erst bei hellerem Grau – mit Hilfe der Bodentemperatur aus dem Modell
     *  - dunkel = durchsichtig, hellgrau = zarte Wolke, weiß = dicke, hohe Wolke; zum Schluss weichgezeichnet
     */
    fun cloudPixels(
        world: IntArray?, mtg: IntArray?, w: Int, h: Int, temp: WorldWeather?,
        latOf: (Double) -> Double = { latOfRow(it, h) }, lonOf: (Double) -> Double = { lonOfCol(it, w) },
        maskBlur: Int = 14,
    ): IntArray {
        val n = w * h
        // Gewicht von MTG: 1 innen, weich zum Rand der Scheibe (schwarz = keine Daten)
        val mw = FloatArray(n)
        if (mtg != null) {
            for (i in 0 until n) mw[i] = if (mtg[i] > 6) 1f else 0f
            blur(mw, w, h, maskBlur); blur(mw, w, h, maskBlur)
            // schwarze Ränder selbst nie verwenden
            for (i in 0 until n) if (mtg[i] <= 6) mw[i] = 0f else mw[i] = ((mw[i] - 0.5f) * 2f).coerceIn(0f, 1f)
        }
        val gray = FloatArray(n)
        val has = BooleanArray(n)
        for (i in 0 until n) {
            val wv = world?.get(i)?.takeIf { it > 6 }
            val m = mw[i]
            when {
                wv != null -> { gray[i] = wv * (1 - m) + (mtg?.get(i) ?: 0) * m; has[i] = true }
                m > 0f -> { gray[i] = mtg!![i].toFloat(); has[i] = true }
            }
        }
        // Bodentemperatur (grob, nur alle 4 Pixel ausrechnen)
        val alpha = FloatArray(n)
        val step = 4
        for (y in 0 until h) {
            val lat = latOf(y + 0.5)
            var low = LOW.toFloat()
            for (x in 0 until w) {
                val i = y * w + x
                if (temp != null && x % step == 0) {
                    val t = temp.tempAt(lat, lonOf(x + 0.5))
                    // je kälter der Boden, desto heller muss es sein, damit es als Wolke zählt
                    low = if (t.isNaN()) LOW.toFloat() else (LOW + ((4.0 - t) * 2.6).coerceIn(0.0, 75.0)).toFloat()
                }
                if (!has[i]) continue
                val tt = ((gray[i] - low) / (HIGH - low)).coerceIn(0f, 1f)
                alpha[i] = tt
            }
        }
        blur(alpha, w, h, 1)
        val out = IntArray(n)
        for (i in 0 until n) {
            val t = alpha[i]
            if (t <= 0.02f) continue
            val a = (Math.pow(t.toDouble(), 0.8) * 235).toInt().coerceIn(0, 255)
            val c = (200 + 55 * t).toInt().coerceAtMost(255)
            out[i] = (a shl 24) or (c shl 16) or (c shl 8) or minOf(255, c + 5)
        }
        return out
    }

    /** Scharfes Wolkenbild eines Ausschnitts (beim Hineinzoomen), Mercator-Rechteck. */
    class CloudDetail(val west: Double, val east: Double, val south: Double, val north: Double, val bitmap: Bitmap, val loadedAt: Long) {
        /** Deckt das Bild den Ausschnitt ganz ab? */
        fun covers(w: Double, e: Double, s: Double, n: Double) = w >= west && e <= east && s >= south && n <= north
    }

    /** Hier ist das MTG-Bild verlässlich (weit genug innerhalb der Satellitenscheibe). */
    fun detailCovers(w: Double, e: Double, s: Double, n: Double) = w >= -50 && e <= 50 && s >= -62 && n <= 68

    /**
     * Wolken eines Ausschnitts in voller Auflösung des Satelliten (≈ 1–2 km statt ≈ 20 km im Weltbild) –
     * Kevin am 10.11.2026: „die Wolken sehen noch billig aus, die müssen richtig scharf sein“.
     */
    fun loadCloudDetail(west: Double, east: Double, south: Double, north: Double, temp: WorldWeather?, maxPx: Int = 1400): CloudDetail {
        val mx0 = mercX(west); val mx1 = mercX(east); val my0 = mercY(south); val my1 = mercY(north)
        val aspect = (my1 - my0) / (mx1 - mx0)
        val w = if (aspect <= 1) maxPx else (maxPx / aspect).toInt().coerceAtLeast(64)
        val h = if (aspect <= 1) (maxPx * aspect).toInt().coerceAtLeast(64) else maxPx
        val url = "$WMS&layers=mtg_fd:ir105_hrfi&format=image/jpeg&bgcolor=0x000000&width=$w&height=$h&bbox=" +
            "%.0f,%.0f,%.0f,%.0f".format(Locale.US, mx0, my0, mx1, my1)
        val bytes = Http.get("EUMETSAT Wolken Ausschnitt", url, timeoutMs = 25_000)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Wolkenbild leer")
        val s = if (bmp.width == w && bmp.height == h) bmp else Bitmap.createScaledBitmap(bmp, w, h, true)
        val px = IntArray(w * h); s.getPixels(px, 0, w, 0, 0, w, h)
        val gray = IntArray(px.size) { (px[it] shr 8) and 0xFF }
        val latOf = { y: Double -> val my = (my1 - y / h * (my1 - my0)) * 180.0 / 20037508.34; 360.0 / PI * atan(exp(my * PI / 180.0)) - 90.0 }
        val lonOf = { x: Double -> west + x / w * (east - west) }
        val out = cloudPixels(null, gray, w, h, temp, latOf, lonOf, maskBlur = 6)
        return CloudDetail(west, east, south, north, Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888), System.currentTimeMillis())
    }

    /** Grauwert, ab dem Wolken beginnen bzw. ganz deckend sind (bei normal warmem Boden). */
    private const val LOW = 100
    private const val HIGH = 225

    /** Einfacher Weichzeichner (Kastenfilter waagerecht + senkrecht). */
    internal fun blur(a: FloatArray, w: Int, h: Int, r: Int) {
        if (r <= 0) return
        val tmp = FloatArray(a.size)
        for (y in 0 until h) {
            var s = 0f; val row = y * w
            for (x in -r..r) s += a[row + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = s / (2 * r + 1)
                s += a[row + (x + r + 1).coerceAtMost(w - 1)] - a[row + (x - r).coerceAtLeast(0)]
            }
        }
        for (x in 0 until w) {
            var s = 0f
            for (y in -r..r) s += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                a[y * w + x] = s / (2 * r + 1)
                s += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
            }
        }
    }

    // ------------------------------------------------------------------ Blitze

    /** Blitz: Ort, Zeitpunkt (Ende des 5-Minuten-Abschnitts) und Stärke 0..1 (viele Entladungen = stark). */
    data class Strike(val lat: Double, val lon: Double, val time: Instant, val strength: Float)

    /** Gebiet des Blitz-Sensors (MTG), in dem gesucht wird. */
    private const val LI_W = -80.0; private const val LI_E = 80.0; private const val LI_S = -75.0; private const val LI_N = 75.0
    private const val LI_PX_W = 1400
    private val LI_PX_H get() = (LI_PX_W * (mercY(LI_N) - mercY(LI_S)) / (mercX(LI_E) - mercX(LI_W))).toInt()

    fun lightningUrl(time: Instant) = "$WMS&layers=mtg_fd:li_afa&format=image/png&transparent=true&width=$LI_PX_W&height=$LI_PX_H&bbox=" +
        "%.0f,%.0f,%.0f,%.0f".format(Locale.US, mercX(LI_W), mercY(LI_S), mercX(LI_E), mercY(LI_N)) + "&time=$time"

    /** Jüngster Zeitpunkt, der wohl schon da ist (die Daten kommen etwa 15–20 Minuten verzögert an). */
    fun lightningTime(now: Instant): Instant {
        val m = now.minus(20, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES)
        return m.minusSeconds((m.epochSecond / 60 % 5) * 60)
    }

    /**
     * Abschnitte zusammenführen: Jedes Satellitenbild enthält auch noch ältere Blitzflächen. Damit ein Blitz nicht
     * in jedem Bild wieder als „neu“ zählt, gilt für jede Stelle (≈ 0,4°) der Zeitpunkt, an dem sie zuerst auftaucht.
     */
    fun firstSeen(frames: Map<Instant, List<Strike>>): List<Strike> {
        val best = HashMap<Long, Strike>()
        for ((_, list) in frames.toSortedMap()) for (s in list) {
            val key = Math.round(s.lat / 0.4) * 100_000L + Math.round(s.lon / 0.4)
            val old = best[key]
            best[key] = if (old == null) s else old.copy(strength = maxOf(old.strength, s.strength))
        }
        return best.values.sortedBy { it.time }
    }

    /**
     * Blitze rund um einen Ort in voller Auflösung des Satelliten (≈ 1 km je Bildpunkt statt ≈ 45 km je Punkt
     * in der Weltansicht) – für „Gewitter 12 km entfernt“. Ausschnitt ±[THUNDER_BOX_LAT]° Breite.
     */
    fun localStrikes(lat: Double, lon: Double, time: Instant): List<Strike> {
        val dLat = THUNDER_BOX_LAT; val dLon = THUNDER_BOX_LAT / kotlin.math.cos(Math.toRadians(lat))
        val s = lat - dLat; val n = lat + dLat; val wst = lon - dLon; val e = lon + dLon
        val px = 320
        val url = "$WMS&layers=mtg_fd:li_afa&format=image/png&transparent=true&width=$px&height=$px&bbox=" +
            "%.0f,%.0f,%.0f,%.0f".format(Locale.US, mercX(wst), mercY(s), mercX(e), mercY(n)) + "&time=$time"
        val bytes = Http.get("EUMETSAT Blitze am Ort", url, timeoutMs = 20_000, log = false)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return emptyList()
        val w = bmp.width; val h = bmp.height
        val p = IntArray(w * h); bmp.getPixels(p, 0, w, 0, 0, w, h)
        return strikesFrom(p, w, h, time, cell = 2, west = wst, east = e, south = s, north = n)
    }

    /** Halbe Höhe des Ausschnitts um den Ort (1,1° ≈ 120 km). */
    const val THUNDER_BOX_LAT = 1.1

    /** Liegt der Ort im Gebiet des Blitz-Sensors? */
    fun lightningCovers(lat: Double, lon: Double) = lat in (LI_S + 2)..(LI_N - 2) && lon in (LI_W + 2)..(LI_E - 2)

    /** Blitze eines 5-Minuten-Abschnitts als Punkte. Leere Bilder (noch keine Daten) ergeben eine leere Liste. */
    fun strikes(time: Instant, label: String = "EUMETSAT Blitze"): List<Strike> {
        val bytes = Http.get(label, lightningUrl(time), timeoutMs = 25_000, log = false)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return emptyList()
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        return strikesFrom(px, w, h, time)
    }

    /**
     * Bild → Punkte: Zellen von 4 × 4 Pixeln (≈ 0,45°); jede Zelle mit Blitzfläche wird ein Punkt in ihrem
     * Schwerpunkt. Stärke nach Farbe (gelb = wenig, rot = viele Entladungen).
     */
    internal fun strikesFrom(
        px: IntArray, w: Int, h: Int, time: Instant, cell: Int = 4,
        west: Double = LI_W, east: Double = LI_E, south: Double = LI_S, north: Double = LI_N,
    ): List<Strike> {
        val out = ArrayList<Strike>()
        val yTop = mercY(north); val yBot = mercY(south)
        for (cy in 0 until h step cell) for (cx in 0 until w step cell) {
            var sx = 0.0; var sy = 0.0; var cnt = 0; var red = 0f
            for (y in cy until minOf(h, cy + cell)) for (x in cx until minOf(w, cx + cell)) {
                val p = px[y * w + x]
                if ((p ushr 24) < 60) continue
                sx += x; sy += y; cnt++
                val g = (p shr 8) and 0xFF
                red = maxOf(red, 1f - g / 255f)
            }
            if (cnt == 0) continue
            val x = sx / cnt + 0.5; val y = sy / cnt + 0.5
            val my = (yTop + y / h * (yBot - yTop)) * 180.0 / 20037508.34
            val lat = 360.0 / PI * atan(exp(my * PI / 180.0)) - 90.0
            val lon = west + x / w * (east - west)
            out += Strike(lat, lon, time, (red * 0.7f + cnt / (cell * cell).toFloat() * 0.3f).coerceIn(0f, 1f))
        }
        return out
    }
}
