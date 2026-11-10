package de.schee.wetter.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer


/**
 * Orte weltweit für die Werte auf der Radarkarte: alle Orte ab etwa 1000 Einwohnern (GeoNames, CC BY 4.0),
 * mit deutschen Namen, nach Größe sortiert. Die Karte füllt den freien Platz damit immer von groß nach klein –
 * weit draußen die Großstädte, beim Hineinzoomen nach und nach Kleinstädte und Dörfer (seit 0.6.6).
 * Liegt als `assets/places.bin` in der App (erzeugt mit `tools/places-build.mjs`).
 */
object Places {
    class Index(val lat: FloatArray, val lon: FloatArray, val pop: IntArray, private val names: ByteArray, private val off: IntArray) {
        val size get() = lat.size
        fun name(i: Int) = String(names, off[i], off[i + 1] - off[i], Charsets.UTF_8)
        /** Land je Ort (für den Max/Min-Schalter der Karte), null wenn die Länderliste fehlt. */
        var countries: Countries.Index? = null
    }

    private val _index = MutableStateFlow<Index?>(null)
    val index: StateFlow<Index?> = _index

    /** Einmal laden (≈ 0,2 s), danach im Speicher. */
    fun load(context: Context): Index {
        _index.value?.let { return it }
        val bytes = context.assets.open("places.bin").use { it.readBytes() }
        return parse(bytes).also { idx ->
            idx.countries = runCatching {
                val mask = runCatching { context.assets.open("country-mask.bin").use { it.readBytes() } }.getOrNull()
                Countries.parse(context.assets.open("places-cc.bin").use { it.readBytes() }, mask, idx)
            }.getOrNull()
            _index.value = idx
        }
    }

    fun parse(bytes: ByteArray): Index {
        val bb = ByteBuffer.wrap(bytes)
        require(String(bytes, 0, 4) == "WCT1") { "Ortsliste: falsches Format" }
        val n = bb.getInt(4)
        val lat = FloatArray(n); val lon = FloatArray(n); val pop = IntArray(n); val off = IntArray(n + 1)
        val names = ByteArray(bytes.size)
        var p = 8; var q = 0
        for (i in 0 until n) {
            lat[i] = bb.getFloat(p); lon[i] = bb.getFloat(p + 4); pop[i] = bb.getInt(p + 8)
            val len = bytes[p + 12].toInt() and 0xFF
            off[i] = q; System.arraycopy(bytes, p + 13, names, q, len); q += len
            p += 13 + len
        }
        off[n] = q
        return Index(lat, lon, pop, names.copyOf(q), off)
    }

    /**
     * Orte im Ausschnitt, größte zuerst (höchstens [limit]). Länge darf über ±180° hinausgehen
     * (Ausschnitt über der Datumsgrenze).
     */
    fun inView(idx: Index, south: Double, north: Double, west: Double, east: Double, limit: Int = 3000): List<Int> {
        val out = ArrayList<Int>()
        val wide = east - west >= 360
        for (i in 0 until idx.size) {
            val la = idx.lat[i]; if (la < south || la > north) continue
            if (!wide) {
                var lo = idx.lon[i].toDouble()
                while (lo < west) lo += 360; while (lo > east) lo -= 360
                if (lo < west) continue
            }
            out += i
            if (out.size >= limit) break
        }
        return out
    }
}
