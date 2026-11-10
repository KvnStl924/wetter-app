package de.schee.wetter.data

import android.graphics.BitmapFactory
import java.time.Instant
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt


/** „Gefühlte Wahrheit“: ein Wort, wie sich das Wetter draußen anfühlt (aus Temperatur, Taupunkt, Wind, Regen). */
object Feel {
    fun word(c: Current): String {
        val t = c.temp; val f = c.feels; val wet = c.precip >= 0.1 || c.code in 51..67 || c.code in 80..82
        return when {
            t >= 20 && c.dewPoint >= 16 -> "schwül"
            t >= 30 -> "heiß"
            f <= -8 -> "eisig"
            c.wind >= 20 && t - f >= 4 -> "beißender Wind"
            wet && t <= 8 -> "nasskalt"
            t in 0.0..8.0 && c.humidity >= 92 && c.wind < 12 -> "feuchtkalt"
            f < 0 -> "frostig"
            f < 6 -> "kalt"
            f < 12 -> "frisch"
            f < 18 -> "mild"
            f <= 25 && c.dewPoint < 15 -> "angenehm"
            else -> "warm"
        }
    }
}

/** Vergleich mit gestern zur gleichen Uhrzeit: „3° kälter als gestern“. */
object Yesterday {
    fun diff(f: Forecast, now: LocalDateTime): Double? {
        val h = f.yesterday.firstOrNull { it.time.hour == now.hour } ?: return null
        return f.current.temp - h.temp
    }

    fun text(f: Forecast, now: LocalDateTime): String? {
        val d = diff(f, now) ?: return null
        val n = abs(d).roundToInt()
        return when {
            n == 0 -> "So warm wie gestern"
            d > 0 -> "$n° wärmer als gestern"
            else -> "$n° kälter als gestern"
        }
    }
}

/**
 * Regen auf die Minute: aus den DWD-Radarbildern (Messung + Vorhersage in 5-Minuten-Schritten) am Ort.
 * Dazu die Zugrichtung des Regens aus zwei Messungen 30 Minuten auseinander.
 */
object Nowcast {
    data class Series(val steps: List<Pair<Instant, Double>>) {
        val rainNow get() = steps.firstOrNull()?.second?.let { it >= WET } ?: false
        val any get() = steps.any { it.second >= WET }
    }

    data class Motion(
        /** Richtung, in die der Regen zieht (Grad, 0 = Nord, 90 = Ost). */
        val towardDeg: Double,
        val kmh: Double,
        val fromLat: Double, val fromLon: Double, val toLat: Double, val toLon: Double,
    ) {
        val towardName get() = Units.compassLong(towardDeg.roundToInt())
    }

    const val WET = 0.1

    fun inGermany(lat: Double, lon: Double) =
        lat in RadarData.SOUTH + .2..RadarData.NORTH - .2 && lon in RadarData.WEST + .2..RadarData.EAST - .2

    /** mm/h am Ort je Bild ab jetzt (bis +2 h). [frames] und [bytes] wie im Radar geladen. */
    fun series(frames: List<RadarData.Frame>, bytes: Map<Int, ByteArray?>, lat: Double, lon: Double, now: Instant): Series? {
        if (!inGermany(lat, lon)) return null
        val out = ArrayList<Pair<Instant, Double>>()
        frames.forEachIndexed { i, fr ->
            if (fr.kind == RadarData.Kind.MODEL) return@forEachIndexed
            if (fr.time.isBefore(now.minusSeconds(300))) return@forEachIndexed
            val b = bytes[i] ?: return@forEachIndexed
            out += fr.time to valueAt(b, lat, lon)
        }
        return if (out.size >= 6) Series(out) else null
    }

    /** Kurzer Satz für den Kopf, null wenn kein Regen in Sicht. */
    fun headline(s: Series, now: Instant): String? {
        val wet = s.steps.map { it.second >= WET }
        fun mins(i: Int) = ((s.steps[i].first.epochSecond - now.epochSecond) / 60).coerceAtLeast(1).let { ((it + 2) / 5 * 5).coerceAtLeast(5) }
        return when {
            wet.none { it } -> null
            wet.first() && wet.all { it } -> "Regen hält an"
            wet.first() -> "Regen hört in ${mins(wet.indexOfFirst { !it })} Min. auf"
            else -> "Regen in ${mins(wet.indexOfFirst { it })} Min."
        }
    }

    /** Ausführlicher Satz für die Kachel. */
    fun summary(s: Series, now: Instant): String = headline(s, now)?.let { h ->
        val peak = s.steps.maxOf { it.second }
        "$h · ${if (peak >= 10) "stark" else if (peak >= 2.5) "mäßig" else "leicht"}"
    } ?: "Kein Regen in den nächsten 2 Stunden"

    private fun px(bmpW: Int, bmpH: Int, lat: Double, lon: Double): Pair<Int, Int> {
        val x = (RadarData.mercX(lon) - RadarData.mercX(RadarData.WEST)) / (RadarData.mercX(RadarData.EAST) - RadarData.mercX(RadarData.WEST)) * bmpW
        val y = (RadarData.mercY(RadarData.NORTH) - RadarData.mercY(lat)) / (RadarData.mercY(RadarData.NORTH) - RadarData.mercY(RadarData.SOUTH)) * bmpH
        return x.toInt() to y.toInt()
    }

    private fun latOf(y: Double, bmpH: Int): Double {
        val my = RadarData.mercY(RadarData.NORTH) - y / bmpH * (RadarData.mercY(RadarData.NORTH) - RadarData.mercY(RadarData.SOUTH))
        return Math.toDegrees(2 * kotlin.math.atan(exp(my / 20037508.34 * Math.PI)) - Math.PI / 2)
    }

    private fun lonOf(x: Double, bmpW: Int) = RadarData.WEST + x / bmpW * (RadarData.EAST - RadarData.WEST)

    /** Mittel der Regenmenge in 3 × 3 Bildpunkten (≈ 3 km) um den Ort. */
    fun valueAt(bytes: ByteArray, lat: Double, lon: Double): Double {
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return 0.0
        val (x, y) = px(b.width, b.height, lat, lon)
        var sum = 0.0
        for (dy in -1..1) for (dx in -1..1) {
            val xx = (x + dx).coerceIn(0, b.width - 1); val yy = (y + dy).coerceIn(0, b.height - 1)
            sum += mm(b.getPixel(xx, yy))
        }
        b.recycle()
        return sum / 9
    }

    /** Farbe des DWD-Radarbilds → mm/h. */
    fun mm(argb: Int): Double {
        if ((argb ushr 24) < 40) return 0.0
        val r = (argb shr 16) and 255; val g = (argb shr 8) and 255; val bl = argb and 255
        var best = 0.0; var bestD = Int.MAX_VALUE
        for ((c, v) in RadarData.RADAR_SCALE) {
            val dr = r - ((c shr 16) and 255); val dg = g - ((c shr 8) and 255); val db = bl - (c and 255)
            val d = dr * dr + dg * dg + db * db
            if (d < bestD) { bestD = d; best = v }
        }
        return if (bestD > 9000) 0.0 else best
    }

    /** Raster der Regenstärke (log) in Blöcken von [block] Bildpunkten. */
    internal fun grid(px: IntArray, w: Int, h: Int, block: Int): Array<FloatArray> {
        val gw = w / block; val gh = h / block
        val cache = HashMap<Int, Float>()
        return Array(gh) { gy ->
            FloatArray(gw) { gx ->
                var s = 0f
                for (yy in 0 until block) for (xx in 0 until block) {
                    val c = px[(gy * block + yy) * w + gx * block + xx]
                    s += cache.getOrPut(c) { ln(1 + mm(c)).toFloat() }
                }
                s / (block * block)
            }
        }
    }

    /**
     * Verschiebung zwischen zwei Bildern (vorher [a], jetzt [b]) im Fenster um den Ort:
     * die Verschiebung mit der größten Übereinstimmung. Ergebnis in Blöcken (dx nach Osten, dy nach Süden).
     */
    internal fun shift(a: Array<FloatArray>, b: Array<FloatArray>, cx: Int, cy: Int, r: Int, maxShift: Int): Triple<Int, Int, Double>? {
        val gh = a.size; val gw = a[0].size
        val x0 = (cx - r).coerceAtLeast(maxShift); val x1 = (cx + r).coerceAtMost(gw - 1 - maxShift)
        val y0 = (cy - r).coerceAtLeast(maxShift); val y1 = (cy + r).coerceAtMost(gh - 1 - maxShift)
        if (x1 <= x0 || y1 <= y0) return null
        var wetCells = 0; var aa = 0.0
        for (y in y0..y1) for (x in x0..x1) { val v = a[y][x]; if (v > .05f) wetCells++; aa += v * v }
        if (wetCells < 30 || aa == 0.0) return null
        var best = -1.0; var bx = 0; var by = 0
        for (dy in -maxShift..maxShift) for (dx in -maxShift..maxShift) {
            var ab = 0.0; var bb = 0.0
            for (y in y0..y1) { val ra = a[y]; val rb = b[y + dy]; for (x in x0..x1) { val v = rb[x + dx]; ab += ra[x] * v; bb += v * v } }
            if (bb == 0.0) continue
            val score = ab / sqrt(aa * bb)
            if (score > best) { best = score; bx = dx; by = dy }
        }
        return if (best > .35) Triple(bx, by, best) else null
    }

    /** Zugrichtung aus den Messungen vor 30 Minuten und jetzt, im Umkreis von ≈ 250 km um den Ort. */
    fun motion(frames: List<RadarData.Frame>, bytes: Map<Int, ByteArray?>, lat: Double, lon: Double): Motion? {
        if (!inGermany(lat, lon)) return null
        val iNow = frames.indexOfFirst { it.minutes == 0 }; val iOld = frames.indexOfFirst { it.minutes == -30 }
        if (iNow < 0 || iOld < 0) return null
        val bNow = bytes[iNow]?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: return null
        val bOld = bytes[iOld]?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: return null
        val w = bNow.width; val h = bNow.height
        if (bOld.width != w || bOld.height != h) return null
        val pa = IntArray(w * h).also { bOld.getPixels(it, 0, w, 0, 0, w, h) }
        val pb = IntArray(w * h).also { bNow.getPixels(it, 0, w, 0, 0, w, h) }
        bNow.recycle(); bOld.recycle()
        val block = 4
        val ga = grid(pa, w, h, block); val gb = grid(pb, w, h, block)
        val (x, y) = px(w, h, lat, lon)
        val kmPerPx = (RadarData.EAST - RadarData.WEST) * 111.32 * cos(Math.toRadians(lat)) / w
        val r = (250 / (kmPerPx * block)).toInt(); val maxShift = (60 / (kmPerPx * block)).toInt().coerceAtLeast(4)
        val (dx, dy, _) = shift(ga, gb, x / block, y / block, r, maxShift) ?: return null
        val km = hypot(dx.toDouble(), dy.toDouble()) * block * kmPerPx
        if (km < 3) return null   // steht fast still
        // Schwerpunkt des Regens in der Nähe → Start des Pfeils; Länge = Weg in einer Stunde
        var sx = 0.0; var sy = 0.0; var sw = 0.0
        val gx0 = x / block; val gy0 = y / block; val rr = (120 / (kmPerPx * block)).toInt()
        for (gy in (gy0 - rr).coerceAtLeast(0)..(gy0 + rr).coerceAtMost(gb.size - 1)) for (gx in (gx0 - rr).coerceAtLeast(0)..(gx0 + rr).coerceAtMost(gb[0].size - 1)) {
            val v = gb[gy][gx].toDouble(); if (v > .05) { sx += gx * v; sy += gy * v; sw += v }
        }
        if (sw == 0.0) return null
        val cxPx = (sx / sw + .5) * block; val cyPx = (sy / sw + .5) * block
        val ex = cxPx + dx * block * 2.0; val ey = cyPx + dy * block * 2.0
        val toward = (Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())) + 360) % 360
        return Motion(toward, km * 2, latOf(cyPx, h), lonOf(cxPx, w), latOf(ey, h), lonOf(ex, w))
    }
}
