package de.schee.wetter.data

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.zip.Inflater
import kotlin.math.floor

/**
 * Weltweite Modellfelder des DWD (ICON global) als Rohwerte – einmal geladen, überall sofort verfügbar:
 *  - Temperatur in 2 m (°C), 0,5°-Raster
 *  - Wind in 10 m (u/v), 1°-Raster
 * Quelle: DWD GeoServer (WCS), GeoTIFF. Auf dem Handy zwischengespeichert, damit das Radar beim
 * nächsten Öffnen ohne Wartezeit bereit ist (seit 0.5.3; vorher Open-Meteo-Gitter je Ausschnitt).
 */
class WorldGrid(
    val width: Int, val height: Int, val bands: Int,
    /** [y][x][band], y = 0 oben (Norden). */
    private val data: FloatArray,
    private val lon0: Double, private val lat0: Double, private val dx: Double, private val dy: Double,
) {
    /** Wert an einem Ort, bilinear; Länge läuft rundherum (−180/180), Breite am Rand festgehalten. */
    fun value(lat: Double, lon: Double, band: Int = 0): Double {
        val fx = (lon - lon0) / dx - 0.5
        val fy = ((lat0 - lat) / dy - 0.5).coerceIn(0.0, height - 1.0001)
        val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
        val tx = fx - x0; val ty = fy - y0
        fun at(x: Int, y: Int): Double {
            val xx = ((x % width) + width) % width
            return data[(y * width + xx) * bands + band].toDouble()
        }
        val a = at(x0, y0) * (1 - tx) + at(x0 + 1, y0) * tx
        val b = at(x0, y0 + 1) * (1 - tx) + at(x0 + 1, y0 + 1) * tx
        return a * (1 - ty) + b * ty
    }

    companion object {
        /**
         * GeoTIFF lesen (so wie der DWD-GeoServer es liefert): Kacheln oder Streifen, unkomprimiert oder
         * Deflate, Gleitkomma 32/64 Bit, ein oder mehrere Kanäle nebeneinander.
         */
        fun fromGeoTiff(bytes: ByteArray): WorldGrid {
            val bb = ByteBuffer.wrap(bytes)
            bb.order(if (bytes[0] == 'I'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
            val ifd = bb.getInt(4)
            val n = bb.getShort(ifd).toInt() and 0xFFFF
            val tags = HashMap<Int, DoubleArray>()
            val size = mapOf(1 to 1, 2 to 1, 3 to 2, 4 to 4, 5 to 8, 11 to 4, 12 to 8, 16 to 8)
            for (i in 0 until n) {
                val e = ifd + 2 + i * 12
                val tag = bb.getShort(e).toInt() and 0xFFFF
                val type = bb.getShort(e + 2).toInt() and 0xFFFF
                val count = bb.getInt(e + 4)
                val s = size[type] ?: continue
                val off = if (s * count <= 4) e + 8 else bb.getInt(e + 8)
                tags[tag] = DoubleArray(count) { k ->
                    val o = off + k * s
                    when (type) {
                        3 -> (bb.getShort(o).toInt() and 0xFFFF).toDouble()
                        4 -> (bb.getInt(o).toLong() and 0xFFFFFFFFL).toDouble()
                        12 -> bb.getDouble(o)
                        11 -> bb.getFloat(o).toDouble()
                        else -> 0.0
                    }
                }
            }
            fun one(t: Int, def: Int = 0) = tags[t]?.get(0)?.toInt() ?: def
            val w = one(256); val h = one(257); val spp = one(277, 1)
            val bits = one(258, 32); val comp = one(259, 1)
            require(one(339, 3) == 3) { "GeoTIFF: keine Gleitkommazahlen" }
            require(one(317, 1) == 1) { "GeoTIFF: Predictor nicht unterstützt" }
            val tiled = tags.containsKey(322)
            val tw = if (tiled) one(322) else w
            val th = if (tiled) one(323) else one(278, h)
            val offs = tags[if (tiled) 324 else 273]!!; val counts = tags[if (tiled) 325 else 279]!!
            val across = (w + tw - 1) / tw
            val out = FloatArray(w * h * spp)
            val bps = bits / 8
            for (i in offs.indices) {
                val start = offs[i].toInt(); val len = counts[i].toInt()
                val raw: ByteArray = if (comp == 1) bytes.copyOfRange(start, start + len) else {
                    val inf = Inflater(); inf.setInput(bytes, start, len)
                    val buf = ByteArray(tw * th * spp * bps); var got = 0
                    while (got < buf.size && !inf.finished()) { val r = inf.inflate(buf, got, buf.size - got); if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break; got += r }
                    inf.end(); buf
                }
                val rb = ByteBuffer.wrap(raw).order(bb.order())
                val x0 = (i % across) * tw; val y0 = (i / across) * th
                for (y in 0 until th) {
                    val yy = y0 + y; if (yy >= h) break
                    for (x in 0 until tw) {
                        val xx = x0 + x; if (xx >= w) continue
                        for (s in 0 until spp) {
                            val o = ((y * tw + x) * spp + s) * bps
                            if (o + bps > raw.size) continue
                            out[(yy * w + xx) * spp + s] = if (bps == 8) rb.getDouble(o).toFloat() else rb.getFloat(o)
                        }
                    }
                }
            }
            // Lage: Transformationsmatrix (34264) oder Pixelgröße (33550) + Bezugspunkt (33922)
            val m = tags[34264]
            val (lon0, lat0, dx, dy) = if (m != null) listOf(m[3], m[7], m[0], -m[5]) else {
                val sc = tags[33550]!!; val tp = tags[33922]!!
                listOf(tp[3], tp[4], sc[0], sc[1])
            }
            return WorldGrid(w, h, spp, out, lon0, lat0, dx, dy)
        }
    }
}

/** Temperatur und Wind einer Stunde, weltweit. */
class WorldWeather(val time: Instant, val temp: WorldGrid?, val wind: WorldGrid?) {
    /** °C, NaN ohne Daten. */
    fun tempAt(lat: Double, lon: Double): Double = temp?.value(lat, lon) ?: Double.NaN

    /** Wind in km/h: out[0] = nach Osten, out[1] = nach Norden. */
    fun windAt(lat: Double, lon: Double, out: DoubleArray) {
        val g = wind
        if (g == null) { out[0] = 0.0; out[1] = 0.0; return }
        out[0] = g.value(lat, lon, 0) * 3.6; out[1] = g.value(lat, lon, 1) * 3.6
    }
}

/** Abruf der DWD-Weltfelder (WCS) mit Zwischenspeicher auf dem Handy. */
object DwdWorld {
    private const val WCS = "https://maps.dwd.de/geoserver/dwd/ows?service=WCS&version=2.0.1&request=GetCoverage&format=image/tiff&geotiff:compression=Deflate"

    enum class Field(val id: String, val scale: String, val label: String) {
        TEMP("dwd__Icon_reg025_fd_sl_T2M", "0.5", "DWD Temperatur weltweit"),
        WIND("dwd__Icon_reg025_fd_sl_UV10M", "0.25", "DWD Wind weltweit"),
    }

    /** Volle Stunde, zu der „jetzt“ gehört (ab der 31. Minute die nächste). */
    fun hourOf(now: Instant): Instant = now.plus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.HOURS)

    fun url(f: Field, time: Instant) =
        "$WCS&coverageId=${f.id}&scaleFactor=${f.scale}&subset=time(%22${time.truncatedTo(ChronoUnit.SECONDS).toString().removeSuffix("Z")}.000Z%22)"

    /** Feld laden: zuerst aus dem Speicher (höchstens [maxAgeH] Stunden alt), sonst vom DWD. */
    fun load(context: Context?, f: Field, time: Instant, maxAgeH: Int = 3): WorldGrid {
        val dir = context?.let { File(it.cacheDir, "world").apply { mkdirs() } }
        val file = dir?.let { File(it, "${f.name.lowercase()}_${time.epochSecond / 3600}.tif") }
        if (file != null && file.exists() && System.currentTimeMillis() - file.lastModified() < maxAgeH * 3600_000L) {
            runCatching { return WorldGrid.fromGeoTiff(file.readBytes()) }
        }
        val bytes = try {
            Http.get("${f.label} ${time.toString().substring(11, 16)} UTC", url(f, time), timeoutMs = 30_000)
        } catch (e: Exception) {
            // ohne Netz: gespeicherte Datei auch wenn älter (bis 12 Stunden, dann räumt die App sie weg)
            if (file != null && file.exists()) return WorldGrid.fromGeoTiff(file.readBytes())
            throw e
        }
        val g = WorldGrid.fromGeoTiff(bytes)
        if (file != null) runCatching {
            file.writeBytes(bytes)
            // alte Dateien aufräumen (älter als 12 Stunden)
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 12 * 3600_000L }?.forEach { it.delete() }
        }
        return g
    }

    /** Ohne Netz: jüngste gespeicherte Stunde, für die Temperatur und Wind vorliegen (oder null). */
    fun newestCached(context: Context): WorldWeather? = runCatching {
        val dir = File(context.cacheDir, "world")
        val hours = dir.listFiles()?.mapNotNull { f -> f.name.removePrefix("temp_").removeSuffix(".tif").takeIf { f.name.startsWith("temp_") }?.toLongOrNull() }?.sortedDescending() ?: return null
        val h = hours.firstOrNull { File(dir, "wind_$it.tif").exists() } ?: return null
        WorldWeather(Instant.ofEpochSecond(h * 3600), WorldGrid.fromGeoTiff(File(dir, "temp_$h.tif").readBytes()), WorldGrid.fromGeoTiff(File(dir, "wind_$h.tif").readBytes()))
    }.getOrNull()
}
