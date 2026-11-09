package de.schee.wetter.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Satellitendaten von EUMETSAT (EUMETView, frei mit Quellenangabe „© EUMETSAT“):
 *  - Wolken: Infrarot-Bild. Über Europa, Afrika und dem Atlantik vom Satelliten MTG (alle 10 Minuten),
 *    sonst die weltweite Wolkenkarte (alle 3 Stunden). Kalt = hoch = weiß. Die App macht daraus
 *    weiße, halb durchsichtige Wolken – wolkenfreie Gebiete werden durchsichtig.
 *  - Blitze: „Accumulated Flash Area“ des Blitz-Sensors auf MTG (Europa, Afrika, Atlantik),
 *    alle 5 Minuten; schon passend eingefärbt und durchsichtig, daher direkt als Karten-Kacheln.
 */
object Satellite {
    private const val WMS = "https://view.eumetsat.int/geoserver/ows?service=WMS&version=1.3.0&request=GetMap&styles=&crs=EPSG:3857&format=image/png&transparent=true"
    const val ATTRIBUTION = "<a href=\"https://view.eumetsat.int/\">Satellit: © EUMETSAT</a>"
    private const val WORLD = "mumi:worldcloudmap_ir108"
    private const val MTG = "mtg_fd:ir105_hrfi"

    /** Wolkenbild für einen Ausschnitt (Ecken in Grad), Mercator wie die Karte. */
    class View(val west: Double, val east: Double, val south: Double, val north: Double, val bitmap: Bitmap, val fetchedAt: Long) {
        fun covers(s: Double, n: Double, w: Double, e: Double) = s >= south && n <= north && w >= west && e <= east
    }

    /** Ausschnitt mit Rand (halbe Breite je Seite), auf die Weltkarte begrenzt. */
    fun area(south: Double, north: Double, west: Double, east: Double): DoubleArray {
        val dLat = (north - south) / 2; val dLon = (east - west) / 2
        return doubleArrayOf((south - dLat).coerceAtLeast(-85.0), (north + dLat).coerceAtMost(85.0), (west - dLon).coerceAtLeast(-180.0), (east + dLon).coerceAtMost(180.0))
    }

    fun url(layer: String, south: Double, north: Double, west: Double, east: Double, width: Int, height: Int) =
        "$WMS&layers=$layer&width=$width&height=$height&bbox=" +
            "%.0f,%.0f,%.0f,%.0f".format(Locale.US, RadarData.mercX(west), RadarData.mercY(south), RadarData.mercX(east), RadarData.mercY(north))

    /** Bildgröße: 900 px auf der längeren Seite, Seitenverhältnis wie auf der Karte (Mercator). */
    fun size(south: Double, north: Double, west: Double, east: Double): Pair<Int, Int> {
        val w = RadarData.mercX(east) - RadarData.mercX(west); val h = RadarData.mercY(north) - RadarData.mercY(south)
        return if (w >= h) 900 to (900 * h / w).toInt().coerceAtLeast(16) else (900 * w / h).toInt().coerceAtLeast(16) to 900
    }

    /** Wolken laden (beide Quellen nacheinander, MTG darf fehlen). */
    fun clouds(south: Double, north: Double, west: Double, east: Double): View {
        val (w, h) = size(south, north, west, east)
        fun pixels(layer: String, label: String): IntArray? = runCatching {
            val b = Http.get(label, url(layer, south, north, west, east, w, h), timeoutMs = 25_000)
            val bmp = BitmapFactory.decodeByteArray(b, 0, b.size) ?: return@runCatching null
            val scaled = if (bmp.width == w && bmp.height == h) bmp else Bitmap.createScaledBitmap(bmp, w, h, true)
            IntArray(w * h).also { scaled.getPixels(it, 0, w, 0, 0, w, h) }
        }.getOrNull()
        val world = pixels(WORLD, "EUMETSAT Wolken weltweit")
        val mtg = pixels(MTG, "EUMETSAT Wolken MTG")
        if (world == null && mtg == null) error("Satellitenbild nicht erreichbar")
        val out = cloudPixels(world, mtg, w * h)
        return View(west, east, south, north, Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888), System.currentTimeMillis())
    }

    /**
     * Infrarot-Grau → Wolken: MTG hat Vorrang (aktueller), sonst Weltkarte.
     * Dunkel (warmer Boden) = durchsichtig, hellgrau (tiefe Wolken) = leicht, weiß (hohe, dicke Wolken) = deckend.
     */
    fun cloudPixels(world: IntArray?, mtg: IntArray?, n: Int): IntArray {
        val out = IntArray(n)
        for (i in 0 until n) {
            val m = mtg?.get(i) ?: 0
            val p = if ((m ushr 24) > 200) m else world?.get(i) ?: 0
            if ((p ushr 24) < 128) continue
            val v = (p shr 16) and 0xFF
            val t = ((v - LOW) / (HIGH - LOW).toFloat()).coerceIn(0f, 1f)
            if (t <= 0f) continue
            val a = (Math.pow(t.toDouble(), 0.75) * 240).toInt()
            // tiefe Wolken etwas grauer, hohe reinweiß
            val c = (205 + 50 * t).toInt()
            val b = minOf(255, c + 6)
            out[i] = (a shl 24) or (c shl 16) or (c shl 8) or b
        }
        return out
    }

    /** Grauwert, ab dem Wolken beginnen bzw. ganz deckend sind. */
    private const val LOW = 105
    private const val HIGH = 215

    /**
     * Blitz-Kacheln für MapLibre (WMS mit {bbox-epsg-3857}). Die Zeit steht in der Adresse, damit
     * nach 5 Minuten neue Kacheln geladen werden (die Daten kommen etwa 15 Minuten verzögert an).
     */
    fun lightningTiles(now: Instant = Instant.now()): String {
        val t = lightningTime(now)
        return "$WMS&layers=mtg_fd:li_afa&width=256&height=256&time=$t&bbox={bbox-epsg-3857}"
    }

    fun lightningTime(now: Instant): Instant {
        val m = now.minus(20, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES)
        return m.minusSeconds((m.epochSecond / 60 % 5) * 60)
    }
}
