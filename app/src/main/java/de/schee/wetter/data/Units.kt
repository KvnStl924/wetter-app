package de.schee.wetter.data

import android.content.Context
import java.util.Locale
import kotlin.math.roundToInt

/** Einheiten aus den Einstellungen (Standard °C, km/h, mm). */
class Units(val temp: String, val wind: String) {
    fun t(c: Double): String = "${tNum(c)}°"
    fun tNum(c: Double): Int = (if (temp == "°F") c * 9 / 5 + 32 else c).roundToInt()

    fun w(kmh: Double): String = when (wind) {
        "m/s" -> "${(kmh / 3.6).roundToInt()} m/s"
        "Bft" -> "${beaufort(kmh)} Bft"
        else -> "${kmh.roundToInt()} km/h"
    }
    fun wNum(kmh: Double): Int = when (wind) { "m/s" -> (kmh / 3.6).roundToInt(); "Bft" -> beaufort(kmh); else -> kmh.roundToInt() }
    val windLabel get() = wind

    fun mm(v: Double): String = if (v > 0 && v < 1) String.format(Locale.GERMANY, "%.1f mm", v) else "${v.roundToInt()} mm"

    companion object {
        fun from(context: Context): Units {
            val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            return Units(p.getString("unit_temp", "°C")!!, p.getString("unit_wind", "km/h")!!)
        }

        fun beaufort(kmh: Double): Int {
            val limits = doubleArrayOf(1.0, 6.0, 12.0, 20.0, 29.0, 39.0, 50.0, 62.0, 75.0, 89.0, 103.0, 118.0)
            return limits.indexOfFirst { kmh < it }.let { if (it < 0) 12 else it }
        }

        fun compassLong(deg: Int): String {
            val names = listOf("Norden", "Nordosten", "Osten", "Südosten", "Süden", "Südwesten", "Westen", "Nordwesten")
            return names[((deg % 360 + 360) % 360 + 22) / 45 % 8]
        }

        fun compass(deg: Int): String {
            val names = listOf("N", "NO", "O", "SO", "S", "SW", "W", "NW")
            return names[((deg % 360 + 360) % 360 + 22) / 45 % 8]
        }
    }
}
