package de.schee.wetter.data

import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.cos

/**
 * Länder der Orte aus [Places] (für den Max/Min-Schalter der Radarkarte, Kevin am 10.11.2026):
 * je Land alle Orte, ein Ort für die Beschriftung (möglichst mittig im Land) und die Einwohnerzahl (Reihenfolge).
 * Liegt als `assets/places-cc.bin` in der App (erzeugt mit `tools/places-country.mjs`).
 */
object Countries {
    class Index(
        val codes: Array<String>,
        /** Land je Ort (Nummer in [codes], -1 = unbekannt). */
        val of: IntArray,
        /** Orte je Land. */
        val members: Array<IntArray>,
        /** Ort für die Beschriftung je Land. */
        val anchor: IntArray,
        val pop: LongArray,
    ) {
        val size get() = codes.size
        /** Deutscher Name („DE“ → „Deutschland“). */
        fun name(k: Int): String = Locale("", codes[k]).getDisplayCountry(Locale.GERMAN).ifEmpty { codes[k] }
    }

    fun parse(bytes: ByteArray, places: Places.Index): Index {
        require(String(bytes, 0, 4) == "WCC1") { "Länderliste: falsches Format" }
        val bb = ByteBuffer.wrap(bytes)
        val k = bytes[4].toInt() and 0xFF
        val codes = Array(k) { String(bytes, 5 + 2 * it, 2, Charsets.US_ASCII) }
        var p = 5 + 2 * k
        val n = bb.getInt(p); p += 4
        require(n == places.size) { "Länderliste passt nicht zur Ortsliste" }
        val of = IntArray(n) { val v = bytes[p + it].toInt() and 0xFF; if (v == 255) -1 else v }
        val count = IntArray(k); for (c in of) if (c >= 0) count[c]++
        val members = Array(k) { IntArray(count[it]) }
        val fill = IntArray(k)
        val pop = LongArray(k)
        for (i in 0 until n) { val c = of[i]; if (c < 0) continue; members[c][fill[c]++] = i; pop[c] += places.pop[i].toLong() }
        val anchor = IntArray(k) { c -> anchorOf(members[c], places) }
        return Index(codes, of, members, anchor, pop)
    }

    /** Ort am nächsten zur Mitte (Median) des Landes – liegt sicher an Land, nicht im Meer. */
    private fun anchorOf(m: IntArray, places: Places.Index): Int {
        if (m.isEmpty()) return -1
        val lats = DoubleArray(m.size) { places.lat[m[it]].toDouble() }.also { it.sort() }
        // Länder über der Datumsgrenze (Russland, Fidschi): westliche Längen um 360° verschieben
        val raw = DoubleArray(m.size) { places.lon[m[it]].toDouble() }
        val wrap = raw.maxOrNull()!! - raw.minOrNull()!! > 180
        val lons = DoubleArray(m.size) { if (wrap && raw[it] < 0) raw[it] + 360 else raw[it] }
        val sorted = lons.copyOf().also { it.sort() }
        val mLat = lats[lats.size / 2]; val mLon = sorted[sorted.size / 2]
        val c = cos(Math.toRadians(mLat))
        var best = m[0]; var bd = Double.MAX_VALUE
        for (j in m.indices) {
            val dy = places.lat[m[j]] - mLat; val dx = (lons[j] - mLon) * c
            val d = dy * dy + dx * dx
            if (d < bd) { bd = d; best = m[j] }
        }
        return best
    }

    /** Höchste und tiefste Temperatur über alle Orte des Landes (zur gezeigten Stunde), null ohne Werte. */
    fun extremes(c: Index, k: Int, places: Places.Index, ww: WorldWeather): Pair<Double, Double>? {
        var max = Double.NEGATIVE_INFINITY; var min = Double.POSITIVE_INFINITY
        for (i in c.members[k]) {
            val t = ww.tempAt(places.lat[i].toDouble(), places.lon[i].toDouble())
            if (t.isNaN()) continue
            if (t > max) max = t; if (t < min) min = t
        }
        return if (max == Double.NEGATIVE_INFINITY) null else max to min
    }
}
