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

    /** Wo das DWD-Radar misst (Radarverbund) – grobes Raster über dem Ausschnitt [WEST]…[EAST], [SOUTH]…[NORTH]. */
    class Coverage(val w: Int, val h: Int, private val covered: BooleanArray) {
        /** Anteil des Ausschnitts mit Messung. */
        val share: Double get() = covered.count { it }.toDouble() / covered.size

        fun at(lat: Double, lon: Double): Boolean {
            if (lon < WEST || lon > EAST || lat < SOUTH || lat > NORTH) return false
            val x = ((lon - WEST) / (EAST - WEST) * w).toInt().coerceIn(0, w - 1)
            val y = ((mercY(NORTH) - mercY(lat)) / (mercY(NORTH) - mercY(SOUTH)) * h).toInt().coerceIn(0, h - 1)
            return covered[y * w + x]
        }

        companion object {
            /** Aus den Bildpunkten eines DWD-Radarbilds: grau und fast durchsichtig (126,126,126, Deckkraft 77) = keine Messung. */
            fun of(px: IntArray, w: Int, h: Int, step: Int = 4): Coverage {
                val cw = w / step; val ch = h / step
                return Coverage(cw, ch, BooleanArray(cw * ch) { i -> !noData(px[(i / cw * step + step / 2) * w + (i % cw) * step + step / 2]) })
            }

            private fun noData(p: Int): Boolean {
                val a = p ushr 24; val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
                return a in 40..120 && kotlin.math.abs(r - 126) < 12 && kotlin.math.abs(g - 126) < 12 && kotlin.math.abs(b - 126) < 12
            }
        }
    }

    /** Messgebiet des jüngsten Radarbilds (für [TileMask]; null = unbekannt → der ganze Ausschnitt zählt als Messgebiet). */
    @Volatile var coverage: Coverage? = null

    /** Messgebiet aus einem DWD-Radarbild (PNG); null, wenn das Bild nicht passt (z. B. gar kein Rand erkennbar). */
    fun coverageOf(bytes: ByteArray): Coverage? {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return Coverage.of(px, bmp.width, bmp.height).takeIf { it.share in 0.2..0.98 }
    }

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
        return crisp(raw, if (kind == Kind.MODEL) MODEL_SCALE else RADAR_SCALE)
    }

    /** Wie viel feiner als die Messung gezeichnet wird (2 = doppelte Auflösung). */
    const val UPSCALE = 2

    /** Farbe je Stufe der Regenmenge (log-Skala, 256 Stufen bis ~200 mm/h) – schneller als für jeden Punkt neu rechnen. */
    private val LUT: IntArray by lazy {
        IntArray(256) { i ->
            val mm = kotlin.math.expm1(i / 255.0 * LOG_MAX)
            val s = ((mm - 0.5) / 1.0).coerceIn(0.0, 1.0); val sm = s * s * (3 - 2 * s)
            val alpha = (0xB0 + (0xD8 - 0xB0) * sm).toInt()
            (alpha shl 24) or (colorFor(mm) and 0xFFFFFF)
        }
    }
    private val LOG_MAX = kotlin.math.ln1p(200.0)

    /**
     * Gestochen scharfer Regen (Kevin am 10.11.2026: „richtig crispy“): Statt Farben weichzuzeichnen, wird die
     * Regenmenge selbst geglättet und auf die doppelte Auflösung hochgerechnet; erst dann kommt die Farbe drauf.
     * So bleiben die Übergänge zwischen leicht/mäßig/stark weich, die Ränder des Regengebiets aber glatt und klar –
     * ohne Treppen der 1-km-Messpunkte und ohne grauen Schleier.
     */
    fun crisp(src: Bitmap, scale: Map<Int, Double>, up: Int = UPSCALE): Bitmap {
        val w = src.width; val h = src.height; val n = w * h
        val px = IntArray(n); src.getPixels(px, 0, w, 0, 0, w, h)
        // 1. Farbe → Regenmenge (log) und „hier regnet es“ (0/1)
        val cache = HashMap<Int, Double>()
        var v = FloatArray(n); var c = FloatArray(n)
        for (i in 0 until n) {
            val p = px[i]
            if (p ushr 24 == 0) continue
            val mm = cache.getOrPut(p) { mmOf(p, scale) }
            if (mm > 0) { v[i] = kotlin.math.ln1p(mm).toFloat(); c[i] = 1f }
        }
        // 2. einmal 1-2-1 glätten (Menge nach Abdeckung gewichtet → Ränder werden nicht „dünner“)
        for (i in 0 until n) v[i] *= c[i]
        var tv = FloatArray(n); var tc = FloatArray(n)
        for (horizontal in booleanArrayOf(true, false)) {
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                val p = if (horizontal) (if (x > 0) i - 1 else i) else (if (y > 0) i - w else i)
                val q = if (horizontal) (if (x < w - 1) i + 1 else i) else (if (y < h - 1) i + w else i)
                tv[i] = (v[p] + 2 * v[i] + v[q]) * .25f; tc[i] = (c[p] + 2 * c[i] + c[q]) * .25f
            }
            var s = v; v = tv; tv = s; s = c; c = tc; tc = s
        }
        // 3. hochrechnen (bilinear) und einfärben; Rand: weiche Kante genau um 50 % Abdeckung
        val ow = w * up; val oh = h * up
        val out = IntArray(ow * oh)
        val inv = 1f / up
        for (oy in 0 until oh) {
            val fy = ((oy + .5f) * inv - .5f).coerceIn(0f, h - 1.001f)
            val y0 = fy.toInt(); val ty = fy - y0; val r0 = y0 * w; val r1 = minOf(y0 + 1, h - 1) * w
            for (ox in 0 until ow) {
                val fx = ((ox + .5f) * inv - .5f).coerceIn(0f, w - 1.001f)
                val x0 = fx.toInt(); val tx = fx - x0; val x1 = minOf(x0 + 1, w - 1)
                val a00 = c[r0 + x0]; val a01 = c[r0 + x1]; val a10 = c[r1 + x0]; val a11 = c[r1 + x1]
                if (a00 + a01 + a10 + a11 == 0f) continue
                val cov = (a00 * (1 - tx) + a01 * tx) * (1 - ty) + (a10 * (1 - tx) + a11 * tx) * ty
                if (cov < .12f) continue
                val vv = (v[r0 + x0] * (1 - tx) + v[r0 + x1] * tx) * (1 - ty) + (v[r1 + x0] * (1 - tx) + v[r1 + x1] * tx) * ty
                val lv = vv / cov   // mittlere Menge dort, wo es regnet
                val k = ((cov - .12f) / .36f).coerceIn(0f, 1f); val edge = k * k * (3 - 2 * k)
                val col = LUT[(lv / LOG_MAX * 255).toInt().coerceIn(0, 255)]
                out[oy * ow + ox] = (((col ushr 24) * edge).toInt() shl 24) or (col and 0xFFFFFF)
            }
        }
        return Bitmap.createBitmap(out, ow, oh, Bitmap.Config.ARGB_8888)
    }

    /** Regenmenge einer DWD-Farbe (0 = kein Regen bzw. Rand/keine Daten). */
    private fun mmOf(argb: Int, scale: Map<Int, Double>): Double {
        if (argb ushr 24 < 140) return 0.0
        val r = (argb shr 16) and 255; val g = (argb shr 8) and 255; val b = argb and 255
        var best = 0.0; var bestD = Int.MAX_VALUE
        for ((col, mm) in scale) {
            val dr = r - ((col shr 16) and 255); val dg = g - ((col shr 8) and 255); val db = b - (col and 255)
            val d = dr * dr + dg * dg + db * db
            if (d < bestD) { bestD = d; best = mm }
        }
        return if (bestD > 40 * 40) 0.0 else best
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
        val mm = mmOf(argb, scale)
        if (mm <= 0) return 0
        return ((if (mm < 1.0) 0xB0 else 0xD8) shl 24) or (colorFor(mm) and 0xFFFFFF)
    }

    /** Gibt es im Bild sichtbaren Niederschlag (nach dem Umfärben)? */
    fun hasRain(b: Bitmap, step: Int = 6): Boolean {
        var y = 0
        while (y < b.height) { var x = 0; while (x < b.width) { if ((b.getPixel(x, y) ushr 24) > 100) return true; x += step }; y += step }
        return false
    }
}

/**
 * Weltweites Radar (letzte 2 Stunden bis jetzt, 10-Minuten-Schritte) von RainViewer – kostenlos,
 * Kacheln bis Zoomstufe 7 (darüber werden sie vergrößert). Seit 2026 ohne Vorhersage („nowcast“ leer).
 * Bedingungen (rainviewer.com/api): nur privat/Bildung, Quelle mit Link nennen, höchstens 100 Abrufe pro Minute
 * → große 512er-Kacheln und nur das aktuelle und das nächste Bild laden.
 * In Deutschland liegt das genauere DWD-Radar darüber.
 */
object RainViewer {
    data class Frame(val time: Instant, val tiles: String)

    fun load(): List<Frame> {
        val json = org.json.JSONObject(Http.text("RainViewer-Übersicht", "https://api.rainviewer.com/public/weather-maps.json"))
        val host = json.getString("host")
        val past = json.getJSONObject("radar").getJSONArray("past")
        return (0 until past.length()).map { i ->
            val f = past.getJSONObject(i)
            Frame(Instant.ofEpochSecond(f.getLong("time")), "$host${f.getString("path")}/512/{z}/{x}/{y}/2/1_1.png")
        }
    }

    /** Passendes Bild für einen Zeitpunkt der Zeitleiste: das letzte davor, höchstens 15 Minuten alt. */
    fun indexFor(frames: List<Frame>, time: Instant): Int? {
        var best: Int? = null
        for (i in frames.indices) if (!frames[i].time.isAfter(time)) best = i
        return best?.takeIf { java.time.Duration.between(frames[it].time, time).toMinutes() <= 15 }
    }
}
