package de.schee.wetter.ui.tiles

import android.content.Context


/** Die 12 Kacheln. HALF = halbe Breite (zwei nebeneinander), FULL = ganze Breite. */
enum class TileId(val title: String, val half: Boolean) {
    WARNING("Unwetterwarnung", false),
    RADAR("Regenradar", false),
    RAIN_2H("Regen nächste 2 Std.", false),
    BEST_TIME("Bestes Zeitfenster", false),
    WIND("Wind", false),
    WIND_MAP("Windkarte", false),
    FEELS("Gefühlt", true),
    HUMIDITY("Feuchtigkeit", true),
    PRECIP_TODAY("Niederschlag heute", true),
    VISIBILITY("Sichtweite", true),
    AVERAGE("Durchschnittswerte", true),
    SUN("Sonnenauf-/untergang", true),
    MOON("Mondphase", false),
}

/** Reihenfolge und ausgeblendete Kacheln, gespeichert auf dem Handy. */
class TileStore(context: Context) {
    private val prefs = context.getSharedPreferences("tiles", Context.MODE_PRIVATE)

    fun order(): List<TileId> = TileLayout.restore(prefs.getString("order", null))
    fun hidden(): Set<TileId> = prefs.getString("hidden", null).orEmpty().split(',').mapNotNull { n -> TileId.entries.firstOrNull { it.name == n } }.toSet()

    fun save(order: List<TileId>, hidden: Set<TileId>) {
        prefs.edit().putString("order", order.joinToString(",") { it.name }).putString("hidden", hidden.joinToString(",") { it.name }).apply()
    }
}

object TileLayout {
    /** Gespeicherte Reihenfolge lesen; neue Kacheln hinten anhängen, unbekannte ignorieren. */
    fun restore(saved: String?): List<TileId> {
        val known = saved.orEmpty().split(',').mapNotNull { n -> TileId.entries.firstOrNull { it.name == n } }.distinct()
        // Neue Kacheln an ihren vorgesehenen Platz (hinter ihren Vorgänger), nicht ans Ende
        val out = known.toMutableList()
        TileId.entries.forEachIndexed { i, t ->
            if (t in out) return@forEachIndexed
            val prev = TileId.entries.take(i).lastOrNull { it in out }
            if (prev == null) out += t else out.add(out.indexOf(prev) + 1, t)
        }
        return out
    }

    /** Reihen bilden: zwei aufeinanderfolgende halbe Kacheln teilen sich eine Reihe. */
    fun rows(tiles: List<TileId>): List<List<TileId>> {
        val out = mutableListOf<List<TileId>>()
        var i = 0
        while (i < tiles.size) {
            val t = tiles[i]
            if (t.half && i + 1 < tiles.size && tiles[i + 1].half) { out += listOf(t, tiles[i + 1]); i += 2 } else { out += listOf(t); i++ }
        }
        return out
    }

    fun move(list: List<TileId>, from: Int, to: Int): List<TileId> {
        if (from == to || from !in list.indices) return list
        val m = list.toMutableList()
        val item = m.removeAt(from)
        m.add(to.coerceIn(0, m.size), item)
        return m
    }
}
