package de.schee.wetter.data

import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Länder für den Max/Min-Schalter der Radarkarte (Kevin am 10.11.2026): Höchst- und Tiefstwert über die
 * ganze Landesfläche – Weltgitter 0,2° aus Natural Earth (`assets/country-mask.bin`, `tools/country-mask.mjs`) –
 * plus alle Orte des Landes (`assets/places-cc.bin`, `tools/places-country.mjs`; für Kleinstaaten ohne Gitterzelle).
 * Die Beschriftung steht immer fest in der Landesmitte (im Werkzeug berechnet: am weitesten weg von Küste und
 * Grenzen, im Hauptland, nahe der Flächenmitte).
 */
object Countries {
    /** Kürzere, übliche Namen (lange Namen verdrängten sonst Nachbarländer von der Karte). */
    private val SHORT = mapOf(
        "GB" to "Großbritannien", "US" to "USA", "AE" to "Emirate", "CF" to "Zentralafrika",
        "CD" to "Kongo (Dem. Rep.)", "CG" to "Kongo", "BA" to "Bosnien", "DO" to "Dominikan. Rep.",
        "VA" to "Vatikan", "KR" to "Südkorea", "KP" to "Nordkorea", "MK" to "Nordmazedonien", "SJ" to "Spitzbergen",
    )

    /** Unter dieser Fläche (km²) gilt ein Land als klein → kleinere Beschriftung. */
    private const val SMALL_KM2 = 100_000.0

    class Index(
        val codes: Array<String>,
        /** Land je Ort (Nummer in [codes], -1 = unbekannt). */
        val of: IntArray,
        /** Orte je Land. */
        val members: Array<IntArray>,
        /** Gitterzellen je Land (Zeile × [maskW] + Spalte). */
        val cells: Array<IntArray>,
        val maskW: Int, val maskH: Int,
        /** Landesmitte je Land (NaN = unbekannt). */
        val anchorLat: DoubleArray, val anchorLon: DoubleArray,
        /** Einwohner (Summe der Orte) und Fläche (km²) je Land. */
        val pop: LongArray, val area: DoubleArray,
    ) {
        val size get() = codes.size
        /** Deutscher Name („DE“ → „Deutschland“). */
        fun name(k: Int): String = SHORT[codes[k]] ?: Locale("", codes[k]).getDisplayCountry(Locale.GERMAN).ifEmpty { codes[k] }
        fun small(k: Int) = area[k] < SMALL_KM2
        fun cellLat(c: Int) = 90.0 - (c / maskW + 0.5) * 180.0 / maskH
        fun cellLon(c: Int) = -180.0 + (c % maskW + 0.5) * 360.0 / maskW
        /** Reihenfolge fürs Beschriften: wichtig = groß und viele Einwohner (√(Fläche × Einwohner)). */
        val rankOrder: IntArray by lazy {
            (0 until size).sortedByDescending { sqrt(maxOf(area[it], 1.0) * (pop[it] + 1).toDouble()) }.toIntArray()
        }
    }

    fun parse(placeCodes: ByteArray, mask: ByteArray?, places: Places.Index): Index {
        require(String(placeCodes, 0, 4) == "WCC1") { "Länderliste: falsches Format" }
        val bb = ByteBuffer.wrap(placeCodes)
        val codes = ArrayList<String>()
        val k0 = placeCodes[4].toInt() and 0xFF
        repeat(k0) { codes += String(placeCodes, 5 + 2 * it, 2, Charsets.US_ASCII) }
        var p = 5 + 2 * k0
        val n = bb.getInt(p); p += 4
        require(n == places.size) { "Länderliste passt nicht zur Ortsliste" }
        val of = IntArray(n) { val v = placeCodes[p + it].toInt() and 0xFF; if (v == 255) -1 else v }

        // Gitter: Länder-Nummern der Maske auf dieselben Nummern wie bei den Orten abbilden
        var maskW = 0; var maskH = 0
        var cellCountry = IntArray(0)
        val maskAnchors = HashMap<Int, Pair<Double, Double>>()
        if (mask != null && String(mask, 0, 4) == "WCM2") {
            val mb = ByteBuffer.wrap(mask)
            maskW = mb.getInt(4); maskH = mb.getInt(8)
            val km = mask[12].toInt() and 0xFF
            val map = IntArray(km) { i ->
                val cc = String(mask, 13 + 2 * i, 2, Charsets.US_ASCII)
                codes.indexOf(cc).takeIf { it >= 0 } ?: codes.size.also { codes += cc }
            }
            val off = 13 + 2 * km
            cellCountry = IntArray(maskW * maskH) { val v = mask[off + it].toInt() and 0xFF; if (v == 255) -1 else map[v] }
            val tail = off + maskW * maskH
            for (i in 0 until km) maskAnchors[map[i]] = mb.getFloat(tail + 8 * i).toDouble() to mb.getFloat(tail + 8 * i + 4).toDouble()
        }
        val k = codes.size
        val members = group(k, of)
        val cells = group(k, cellCountry)
        val pop = LongArray(k)
        for (i in 0 until n) { val c = of[i]; if (c >= 0) pop[c] += places.pop[i].toLong() }
        // Fläche: Zelle 0,2° × 0,2° ≈ 22,2 km × 22,2 km · cos(Breite)
        val area = DoubleArray(k)
        val cellKm = 180.0 / maskH.coerceAtLeast(1) * 111.2
        for (c in 0 until k) for (cell in cells[c]) area[c] += cellKm * cellKm * cos(Math.toRadians(90.0 - (cell / maskW + 0.5) * 180.0 / maskH))
        val idx = Index(codes.toTypedArray(), of, members, cells, maskW, maskH, DoubleArray(k) { Double.NaN }, DoubleArray(k) { Double.NaN }, pop, area)
        for (c in 0 until k) {
            val a = maskAnchors[c]
            if (a != null && cells[c].isNotEmpty()) { idx.anchorLat[c] = a.first; idx.anchorLon[c] = a.second }
            else placeAnchor(idx, c, places)
        }
        return idx
    }

    private fun group(k: Int, of: IntArray): Array<IntArray> {
        val count = IntArray(k); for (c in of) if (c >= 0) count[c]++
        val out = Array(k) { IntArray(count[it]) }
        val fill = IntArray(k)
        for (i in of.indices) { val c = of[i]; if (c >= 0) out[c][fill[c]++] = i }
        return out
    }

    /** Kleinstaaten ohne Gitterzelle (Malta, Andorra …): der Ort nahe der Mitte (Median) ihrer Orte. */
    private fun placeAnchor(idx: Index, c: Int, places: Places.Index) {
        val m = idx.members[c]; if (m.isEmpty()) return
        val raw = DoubleArray(m.size) { places.lon[m[it]].toDouble() }
        val wrap = raw.max() - raw.min() > 180
        val lons = DoubleArray(m.size) { if (wrap && raw[it] < 0) raw[it] + 360 else raw[it] }
        val lats = DoubleArray(m.size) { places.lat[m[it]].toDouble() }
        val mLat = lats.copyOf().also { it.sort() }[m.size / 2]
        val mLon = lons.copyOf().also { it.sort() }[m.size / 2]
        val cs = cos(Math.toRadians(mLat))
        var best = 0; var bd = Double.MAX_VALUE
        for (j in m.indices) {
            val dy = lats[j] - mLat; val dx = (lons[j] - mLon) * cs
            val d = dy * dy + dx * dx
            if (d < bd) { bd = d; best = j }
        }
        idx.anchorLat[c] = lats[best]; idx.anchorLon[c] = raw[best]
        // Fläche fehlt im Gitter → klein
    }

    /** Höchste und tiefste Temperatur über Fläche und Orte des Landes (zur gezeigten Stunde), null ohne Werte. */
    fun extremes(c: Index, k: Int, places: Places.Index, ww: WorldWeather): Pair<Double, Double>? {
        var max = Double.NEGATIVE_INFINITY; var min = Double.POSITIVE_INFINITY
        fun take(t: Double) { if (t.isNaN()) return; if (t > max) max = t; if (t < min) min = t }
        for (cell in c.cells[k]) take(ww.tempAt(c.cellLat(cell), c.cellLon(cell)))
        for (i in c.members[k]) take(ww.tempAt(places.lat[i].toDouble(), places.lon[i].toDouble()))
        return if (max == Double.NEGATIVE_INFINITY) null else max to min
    }
}
