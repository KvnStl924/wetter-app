package de.schee.wetter.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ln
import kotlin.math.tan

/**
 * Regenradar für das Vollbild (Phase 4).
 *
 * Quelle: DWD GeoServer (WMS, Zeitdimension), immer ganz Deutschland in einem Bild (EPSG:3857):
 *  - `dwd:Niederschlagsradar`: 5-Minuten-Schritte, enthält auch die 2-Stunden-Vorhersage des DWD
 *  - `dwd:Icon-eu_reg00625_fd_sl_TOTPREC01H`: Modell-Niederschlag (ICON-EU, stündlich) für +2 … +12 h
 *
 * Die DWD-Farben werden in eine eigene Skala umgefärbt (Leicht · Mäßig · Stark · Extrem),
 * Flächen ohne Messung und der Rand des Radarverbunds werden durchsichtig.
 */
object RadarData {
    // Ausschnitt: Deutschland mit Grenzgebieten.
    const val WEST = 4.6
    const val EAST = 16.4
    const val SOUTH = 46.6
    const val NORTH = 55.4
    const val WIDTH = 800

    /** Höhe so, dass ein Pixel in beide Richtungen gleich viel Mercator-Strecke abdeckt. */
    val HEIGHT: Int = (WIDTH * (mercY(NORTH) - mercY(SOUTH)) / (mercX(EAST) - mercX(WEST))).toInt()

    enum class Kind { RADAR, FORECAST, MODEL }

    /** Ein Bild der Zeitleiste. [minutes] relativ zu „Jetzt“ (letzte Radarmessung). */
    data class Frame(val time: Instant, val minutes: Int, val kind: Kind)

    fun mercX(lon: Double) = lon * 20037508.34 / 180.0
    fun mercY(lat: Double) = ln(tan((90.0 + lat) * Math.PI / 360.0)) / (Math.PI / 180.0) * 20037508.34 / 180.0

    /**
     * Zeitleiste: −2 h … +2 h in 5-Minuten-Schritten (Radar und DWD-Vorhersage),
     * danach volle Stunden aus dem Modell bis +12 h.
     * [latest] = jüngste Radarmessung (auf 5 Minuten gerundet).
     */
    fun frames(latest: Instant): List<Frame> {
        val out = mutableListOf<Frame>()
        for (m in -120..120 step 5) out += Frame(latest.plus(m.toLong(), ChronoUnit.MINUTES), m, if (m <= 0) Kind.RADAR else Kind.FORECAST)
        val end = latest.plus(120, ChronoUnit.MINUTES)
        var h = end.truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS)
        while (h <= latest.plus(12, ChronoUnit.HOURS)) {
            out += Frame(h, ChronoUnit.MINUTES.between(latest, h).toInt(), Kind.MODEL)
            h = h.plus(1, ChronoUnit.HOURS)
        }
        return out
    }

    /**
     * Jüngste Radarmessung schätzen: Der DWD stellt ein Bild etwa 5–10 Minuten nach der Messung bereit.
     * Liefert der Server für diese Zeit nichts, versucht der Aufrufer 5 Minuten früher.
     */
    fun latestGuess(now: Instant): Instant {
        val t = now.minus(8, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES)
        val epochMin = t.epochSecond / 60
        return Instant.ofEpochSecond((epochMin - epochMin % 5) * 60)
    }

    fun url(frame: Frame): String {
        val layer = if (frame.kind == Kind.MODEL) "dwd:Icon-eu_reg00625_fd_sl_TOTPREC01H" else "dwd:Niederschlagsradar"
        // Modell: Summe der folgenden Stunde liegt beim Zeitpunkt des Stundenendes.
        val t = if (frame.kind == Kind.MODEL) frame.time.plus(1, ChronoUnit.HOURS) else frame.time
        val bbox = "%.1f,%.1f,%.1f,%.1f".format(Locale.US, mercX(WEST), mercY(SOUTH), mercX(EAST), mercY(NORTH))
        return "https://maps.dwd.de/geoserver/dwd/wms?service=WMS&version=1.3.0&request=GetMap&crs=EPSG:3857" +
            "&bbox=$bbox&width=$WIDTH&height=$HEIGHT&format=image/png&transparent=true&styles=&layers=$layer&time=$t"
    }

    /**
     * Lädt ein Bild als PNG-Bytes (klein, ca. 20–60 kB). null = keine Daten für diese Zeit.
     * Gespeichert werden nur die Bytes; erst beim Anzeigen wird dekodiert und umgefärbt ([render]),
     * sonst bräuchten 59 Bilder über 150 MB Speicher.
     */
    fun fetch(frame: Frame): ByteArray? {
        val bytes = Http.get("DWD-Radar ${frame.minutes} min", url(frame), timeoutMs = 20_000, log = false)
        return if (isPng(bytes)) bytes else null // sonst XML-Fehlermeldung („Zeit nicht verfügbar“)
    }

    fun isPng(bytes: ByteArray) = bytes.size > 8 && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte()

    fun render(bytes: ByteArray, kind: Kind): Bitmap? {
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return recolor(raw, if (kind == Kind.MODEL) MODEL_SCALE else RADAR_SCALE)
    }

    // ------------------------------------------------------------------ Farben

    /** DWD-Legende: Farbe → typische Regenmenge in mm/h (Mitte der Klasse). */
    internal val RADAR_SCALE: Map<Int, Double> = mapOf(
        0x33ffff to 0.15, 0x1acc9a to 0.3, 0x019934 to 0.7, 0x4db31b to 1.5, 0x99cc01 to 2.5, 0xcce601 to 4.0,
        0xffff01 to 6.0, 0xffc401 to 9.0, 0xff8901 to 12.0, 0xff4501 to 20.0, 0xfe0000 to 38.0, 0xe5004c to 60.0,
        0xcc0098 to 85.0, 0x6600cb to 120.0, 0x0000fe to 160.0,
    )
    internal val MODEL_SCALE: Map<Int, Double> = mapOf(
        0xc0c0c0 to 0.6, 0xfefec0 to 3.0, 0xfefe5b to 7.0, 0xcff200 to 12.0, 0xa0d625 to 17.0, 0x36c969 to 22.0,
        0x00d7d7 to 27.0, 0x00a6dd to 35.0, 0x0000fe to 50.0, 0x9730c2 to 70.0, 0xd926c7 to 90.0, 0xfe0000 to 115.0,
        0xa00000 to 145.0, 0x606060 to 170.0,
    )

    /** Eigene Skala, angelehnt an iOS: Blau (leicht) → Grün → Gelb (stark) → Rot/Violett (extrem). */
    data class Step(val mm: Double, val color: Int)
    val PALETTE = listOf(
        Step(0.1, 0xFF2F6BFF.toInt()), Step(1.0, 0xFF2FC7FF.toInt()), Step(2.0, 0xFF31D17A.toInt()),
        Step(5.0, 0xFFFFD60A.toInt()), Step(15.0, 0xFFFF8A00.toInt()), Step(30.0, 0xFFFF3B30.toInt()), Step(80.0, 0xFFC64BFF.toInt()),
    )

    /** Legende unten im Vollbild. */
    val LEGEND = listOf("Leicht" to 0.5, "Mäßig" to 3.0, "Stark" to 10.0, "Extrem" to 40.0)

    fun colorFor(mm: Double): Int {
        if (mm <= PALETTE.first().mm) return PALETTE.first().color
        for (i in 1 until PALETTE.size) {
            val a = PALETTE[i - 1]; val b = PALETTE[i]
            if (mm <= b.mm) return lerp(a.color, b.color, ((mm - a.mm) / (b.mm - a.mm)).toFloat())
        }
        return PALETTE.last().color
    }

    private fun lerp(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 255) + (((b shr s) and 255) - ((a shr s) and 255)) * t).toInt() and 255
        return (255 shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Jede Farbe der nächsten Legendenfarbe zuordnen; unbekannte (Rand, „keine Daten“) werden durchsichtig. */
    internal fun mapColor(argb: Int, scale: Map<Int, Double>): Int {
        val a = argb ushr 24
        if (a < 140) return 0 // „keine Daten“ (halbtransparentes Grau) und Kantenglättung
        val r = (argb shr 16) and 255; val g = (argb shr 8) and 255; val b = argb and 255
        var best = Double.NaN; var bestD = Int.MAX_VALUE
        for ((c, mm) in scale) {
            val dr = r - ((c shr 16) and 255); val dg = g - ((c shr 8) and 255); val db = b - (c and 255)
            val d = dr * dr + dg * dg + db * db
            if (d < bestD) { bestD = d; best = mm }
        }
        if (bestD > 40 * 40) return 0
        val rgb = colorFor(best) and 0xFFFFFF
        val alpha = if (best < 1.0) 0xB0 else 0xD8
        return (alpha shl 24) or rgb
    }

    fun recolor(src: Bitmap, scale: Map<Int, Double>): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        val cache = HashMap<Int, Int>()
        for (i in px.indices) {
            val c = px[i]
            px[i] = if (c ushr 24 == 0) 0 else cache.getOrPut(c) { mapColor(c, scale) }
        }
        return Bitmap.createBitmap(soften(px, w, h), w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Weichzeichnen (2 × 3er-Kernel 1-2-1 in beide Richtungen), damit die 1-km-Rasterzellen des Radars
     * nicht als harte Vierecke erscheinen. Farben werden nach Deckkraft gewichtet, damit die Ränder nicht grau werden.
     */
    internal fun soften(src: IntArray, w: Int, h: Int, passes: Int = 2): IntArray {
        val n = w * h
        var a = FloatArray(n); var r = FloatArray(n); var g = FloatArray(n); var b = FloatArray(n)
        for (i in 0 until n) {
            val c = src[i]; val al = (c ushr 24) / 255f
            a[i] = al; r[i] = ((c shr 16) and 255) * al; g[i] = ((c shr 8) and 255) * al; b[i] = (c and 255) * al
        }
        var ta = FloatArray(n); var tr = FloatArray(n); var tg = FloatArray(n); var tb = FloatArray(n)
        fun blur(horizontal: Boolean) {
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                val p = if (horizontal) (if (x > 0) i - 1 else i) else (if (y > 0) i - w else i)
                val q = if (horizontal) (if (x < w - 1) i + 1 else i) else (if (y < h - 1) i + w else i)
                ta[i] = (a[p] + 2 * a[i] + a[q]) / 4; tr[i] = (r[p] + 2 * r[i] + r[q]) / 4
                tg[i] = (g[p] + 2 * g[i] + g[q]) / 4; tb[i] = (b[p] + 2 * b[i] + b[q]) / 4
            }
            var s = a; a = ta; ta = s; s = r; r = tr; tr = s; s = g; g = tg; tg = s; s = b; b = tb; tb = s
        }
        repeat(passes) { blur(true); blur(false) }
        val out = IntArray(n)
        for (i in 0 until n) {
            val al = a[i]
            if (al < 0.03f) continue
            out[i] = ((al * 255).toInt().coerceIn(0, 255) shl 24) or
                ((r[i] / al).toInt().coerceIn(0, 255) shl 16) or ((g[i] / al).toInt().coerceIn(0, 255) shl 8) or (b[i] / al).toInt().coerceIn(0, 255)
        }
        return out
    }

    /** Gibt es im Bild sichtbaren Niederschlag (nach dem Umfärben)? */
    fun hasRain(b: Bitmap, step: Int = 6): Boolean {
        var y = 0
        while (y < b.height) { var x = 0; while (x < b.width) { if ((b.getPixel(x, y) ushr 24) > 100) return true; x += step }; y += step }
        return false
    }
}
