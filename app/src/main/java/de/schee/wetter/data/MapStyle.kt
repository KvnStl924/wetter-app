package de.schee.wetter.data

import android.content.Context
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt


/**
 * Kartenstil für das Vollbild-Radar: OpenFreeMap „dark“, aber
 *  - etwas heller (Kevin am 09.11.2026: „einen Ticken zu dunkel … soll lesbarer sein“), bleibt dunkel,
 *  - Ortsnamen auf Deutsch („München“ statt „MUNICH“).
 * Der Stil wird beim Öffnen geladen und einen Tag lang im Cache gehalten.
 */
object MapStyle {
    const val URL = "https://tiles.openfreemap.org/styles/dark"

    /** Höhendaten für das Relief (AWS Open Data „Terrain Tiles“, Terrarium-Kodierung, frei nutzbar). */
    const val TERRAIN_TILES = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"

    fun load(context: Context): String? {
        val cache = File(context.cacheDir, "map_style_dark_v5.json")
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < 24 * 3600_000L) {
            return runCatching { cache.readText() }.getOrNull()
        }
        return runCatching { transform(Http.text("Kartenstil", URL)).also { cache.writeText(it) } }
            .getOrElse { if (cache.exists()) cache.readText() else null }
    }

    fun transform(json: String): String = simplify(german(brighten(json)))

    /** Ebenen, die bleiben: Land, Wasser, Eis, Flüsse (nah), Grenzen, Orts- und Ländernamen. */
    private val KEEP = setOf(
        "background", "water", "landcover_ice_shelf", "landcover_glacier", "waterway",
        "boundary_state", "boundary_country_z0-4", "boundary_country_z5-",
        "place_village", "place_town", "place_city", "place_city_large",
        "place_country_other", "place_country_minor", "place_country_major",
    )

    /**
     * Schlichte Karte wie bei WetterOnline, aber dunkel und ruhig (Kevin am 09.11.2026: keine Straßen,
     * „nicht so farbenfroh“): ohne Straßen, Bahnen, Gebäude, Flughäfen, Parks; Land, Wasser und Grenzen
     * klar unterscheidbar; Namen nur in einer Schrift (kein zweiter Name in Kyrillisch/Arabisch …).
     */
    fun simplify(json: String): String {
        val style = org.json.JSONObject(json)
        val layers = style.getJSONArray("layers")
        val kept = org.json.JSONArray()
        val name = org.json.JSONArray("[\"coalesce\",[\"get\",\"name:de\"],[\"get\",\"name_en\"],[\"get\",\"name\"]]")
        for (i in 0 until layers.length()) {
            val l = layers.getJSONObject(i)
            val id = l.getString("id")
            if (id !in KEEP) continue
            val paint = l.optJSONObject("paint") ?: org.json.JSONObject().also { l.put("paint", it) }
            when (id) {
                "background" -> paint.put("background-color", "rgb(40,43,48)")
                "landcover_ice_shelf", "landcover_glacier" -> { paint.put("fill-color", "rgb(52,56,62)"); paint.put("fill-opacity", 0.6) }
                // Flüsse (Kevin am 17.11.2026: Orientierung auf der Temperaturkarte): große Flüsse schon weit
                // draußen als dünne dunkle Linie, Bäche und Kanäle erst beim Hineinzoomen
                "waterway" -> {
                    l.remove("minzoom"); l.put("minzoom", 4)
                    paint.put("line-color", "rgb(14,22,34)")
                    paint.put("line-opacity", org.json.JSONArray("[\"step\",[\"zoom\"],[\"match\",[\"get\",\"class\"],\"river\",0.85,0],8,0.85]"))
                    paint.put("line-width", org.json.JSONArray("[\"interpolate\",[\"linear\"],[\"zoom\"],4,0.5,7,1.0,10,1.8,13,3]"))
                }
                // Grenzen deutlich (Kevin am 17.11.2026: „man sieht nur sehr schwierig die Länder“): Länder hell mit
                // dunklem Schatten daneben – lesbar auf jeder Farbe; Bundesländer zart gestrichelt
                "boundary_state" -> {
                    l.put("minzoom", 5)
                    paint.put("line-color", "rgba(232,236,242,0.4)")
                    paint.put("line-dasharray", org.json.JSONArray("[3,2.5]"))
                    paint.put("line-blur", 0)
                    paint.put("line-width", org.json.JSONArray("[\"interpolate\",[\"linear\"],[\"zoom\"],5,0.6,9,1.1,12,1.5]"))
                }
                "boundary_country_z0-4", "boundary_country_z5-" -> {
                    val casing = org.json.JSONObject(l.toString())
                    casing.put("id", "$id-casing")
                    casing.put("paint", org.json.JSONObject().apply {
                        put("line-color", "rgba(0,0,0,0.5)"); put("line-blur", 1.2)
                        put("line-width", org.json.JSONArray("[\"interpolate\",[\"linear\"],[\"zoom\"],2,2.2,5,3.4,8,4.8,12,6.5]"))
                    })
                    kept.put(casing)
                    paint.put("line-color", "rgba(242,244,248,0.92)")
                    paint.put("line-blur", 0)
                    paint.put("line-width", org.json.JSONArray("[\"interpolate\",[\"linear\"],[\"zoom\"],2,0.8,5,1.3,8,1.9,12,2.6]"))
                }
                // Gelände-Relief direkt unter dem Wasser (Kevin am 17.11.2026): Gebirge und Täler als leichter
                // Schatten über der Temperaturfarbe; Höhendaten frei von AWS (Terrain Tiles, Mapzen)
                "water" -> {
                    paint.put("fill-color", "rgb(22,28,36)")
                    style.getJSONObject("sources").put("terrain", org.json.JSONObject().apply {
                        put("type", "raster-dem"); put("encoding", "terrarium"); put("tileSize", 256); put("maxzoom", 11)
                        put("tiles", org.json.JSONArray().put(TERRAIN_TILES))
                    })
                    kept.put(org.json.JSONObject().apply {
                        put("id", "hillshade"); put("type", "hillshade"); put("source", "terrain")
                        put("paint", org.json.JSONObject().apply {
                            put("hillshade-exaggeration", 0.45)
                            put("hillshade-shadow-color", "rgba(0,0,0,0.55)")
                            put("hillshade-highlight-color", "rgba(255,255,255,0.20)")
                            put("hillshade-accent-color", "rgba(0,0,0,0)")
                            put("hillshade-illumination-direction", 315)
                        })
                    })
                }
            }
            if (id.startsWith("place_")) l.optJSONObject("layout")?.put("text-field", name)
            // Ländernamen in derselben Schrift wie die Werte der App, dezent (keine zweite Schriftart)
            if (id.startsWith("place_country")) {
                l.optJSONObject("layout")?.put("text-font", org.json.JSONArray("[\"Noto Sans Bold\"]"))
                paint.put("text-color", "rgba(214,218,224,0.55)"); paint.put("text-halo-color", "rgba(0,0,0,0.6)")
            }
            kept.put(l)
        }
        style.put("layers", kept)
        return style.toString()
    }

    /** Namen: zuerst Deutsch, dann Englisch, dann Originalname. */
    fun german(json: String): String = json.replace("[\"get\",\"name_en\"]", "[\"get\",\"name:de\"],[\"get\",\"name_en\"]")

    private val COLOR = Regex("""(rgba?|hsla?)\(\s*([^)]*)\)|#([0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3})(?=")""")

    /** Alle Farben im Stil aufhellen; Transparenz bleibt. */
    fun brighten(json: String): String = COLOR.replace(json) { m -> parse(m.value)?.let { (r, g, b, a) -> rgba(lift(r), lift(g), lift(b), a) } ?: m.value }

    /** Dunkle Töne stärker anheben als helle: 12 → 30, 27 → 50, 101 → 150. */
    internal fun lift(c: Double): Double = (c * 1.35 + 14).coerceAtMost(255.0)

    private fun rgba(r: Double, g: Double, b: Double, a: Double) =
        "rgba(${r.roundToInt()},${g.roundToInt()},${b.roundToInt()},${String.format(Locale.US, "%.3f", a).trimEnd('0').trimEnd('.')})"

    internal fun parse(s: String): List<Double>? {
        if (s.startsWith("#")) {
            val h = s.substring(1).let { if (it.length == 3) it.map { c -> "$c$c" }.joinToString("") else it }
            val v = h.chunked(2).map { it.toInt(16).toDouble() }
            return listOf(v[0], v[1], v[2], if (v.size > 3) v[3] / 255 else 1.0)
        }
        val fn = s.substringBefore('(').trim()
        val parts = s.substringAfter('(').substringBefore(')').split(',').map { it.trim() }
        return runCatching {
            if (fn.startsWith("rgb")) {
                listOf(parts[0].toDouble(), parts[1].toDouble(), parts[2].toDouble(), parts.getOrNull(3)?.toDouble() ?: 1.0)
            } else {
                val h = parts[0].toDouble(); val sat = parts[1].removeSuffix("%").toDouble() / 100; val l = parts[2].removeSuffix("%").toDouble() / 100
                val c = (1 - abs(2 * l - 1)) * sat
                val x = c * (1 - abs((h / 60) % 2 - 1)); val m = l - c / 2
                val (r, g, b) = when ((h / 60).toInt() % 6) { 0 -> Triple(c, x, 0.0); 1 -> Triple(x, c, 0.0); 2 -> Triple(0.0, c, x); 3 -> Triple(0.0, x, c); 4 -> Triple(x, 0.0, c); else -> Triple(c, 0.0, x) }
                listOf((r + m) * 255, (g + m) * 255, (b + m) * 255, parts.getOrNull(3)?.toDouble() ?: 1.0)
            }
        }.getOrNull()
    }
}
