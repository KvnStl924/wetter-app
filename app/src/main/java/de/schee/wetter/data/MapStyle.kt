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

    fun load(context: Context): String? {
        val cache = File(context.cacheDir, "map_style_dark.json")
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < 24 * 3600_000L) {
            return runCatching { cache.readText() }.getOrNull()
        }
        return runCatching { transform(Http.text("Kartenstil", URL)).also { cache.writeText(it) } }
            .getOrElse { if (cache.exists()) cache.readText() else null }
    }

    fun transform(json: String): String = german(brighten(json))

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
