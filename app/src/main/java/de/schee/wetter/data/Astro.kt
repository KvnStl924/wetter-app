package de.schee.wetter.data

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan


/**
 * Mondphase und Mondaufgang, lokal berechnet (Formeln nach SunCalc, V. Agafonkin, BSD-Lizenz).
 * Keine Abfrage im Netz nötig.
 */
object Astro {
    private const val RAD = PI / 180
    private const val DAY_MS = 86_400_000.0
    private const val J1970 = 2440588.0
    private const val J2000 = 2451545.0
    private const val E = RAD * 23.4397
    const val SYNODIC = 29.530588853

    data class MoonInfo(val phaseName: String, val illumination: Double, val ageDays: Double, val daysToFull: Int, val waxing: Boolean)

    /** Bekannter Neumond: 6. Jan. 2000, 18:14 UTC. */
    private const val NEW_MOON_MS = 947182440000.0

    fun moon(epochMs: Long): MoonInfo {
        val age = ((epochMs - NEW_MOON_MS) / DAY_MS % SYNODIC).let { if (it < 0) it + SYNODIC else it }
        val illum = (1 - cos(2 * PI * age / SYNODIC)) / 2
        val name = when {
            age < 1.0 || age > SYNODIC - 1.0 -> "Neumond"
            age < 6.4 -> "Zunehmende Sichel"
            age < 8.4 -> "Erstes Viertel"
            age < 13.8 -> "Zunehmender Mond"
            age < 15.8 -> "Vollmond"
            age < 21.1 -> "Abnehmender Mond"
            age < 23.1 -> "Letztes Viertel"
            else -> "Abnehmende Sichel"
        }
        val toFull = (SYNODIC / 2 - age).let { if (it < 0) it + SYNODIC else it }
        return MoonInfo(name, illum, age, floor(toFull + 0.5).toInt(), age < SYNODIC / 2)
    }

    private fun toDays(ms: Double) = ms / DAY_MS - 0.5 + J1970 - J2000
    private fun rightAscension(l: Double, b: Double) = atan2(sin(l) * cos(E) - tan(b) * sin(E), cos(l))
    private fun declination(l: Double, b: Double) = asin(sin(b) * cos(E) + cos(b) * sin(E) * sin(l))
    private fun altitude(h: Double, phi: Double, dec: Double) = asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(h))
    private fun siderealTime(d: Double, lw: Double) = RAD * (280.16 + 360.9856235 * d) - lw
    private fun refraction(h0: Double): Double { val h = if (h0 < 0) 0.0 else h0; return 0.0002967 / tan(h + 0.00312536 / (h + 0.08901179)) }

    /** Höhe (Grad über dem Horizont) und Richtung (Grad, 0 = Nord, 90 = Ost) am Himmel. */
    data class SkyPos(val altDeg: Double, val azDeg: Double)

    private fun azimuth(h: Double, phi: Double, dec: Double) = atan2(sin(h), cos(h) * sin(phi) - tan(dec) * cos(phi))

    /** Stand der Sonne. */
    fun sun(epochMs: Long, lat: Double, lon: Double): SkyPos {
        val lw = RAD * -lon; val phi = RAD * lat; val d = toDays(epochMs.toDouble())
        val m = RAD * (357.5291 + 0.98560028 * d)
        val c = RAD * (1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m))
        val l = m + c + RAD * 102.9372 + PI
        val dec = declination(l, 0.0); val ra = rightAscension(l, 0.0)
        val h = siderealTime(d, lw) - ra
        return SkyPos(altitude(h, phi, dec) / RAD, (azimuth(h, phi, dec) / RAD + 180 + 360) % 360)
    }

    /** Stand des Mondes. */
    fun moonPos(epochMs: Long, lat: Double, lon: Double): SkyPos {
        val ms = epochMs.toDouble()
        val lw = RAD * -lon; val phi = RAD * lat; val d = toDays(ms)
        val l0 = RAD * (218.316 + 13.176396 * d); val m = RAD * (134.963 + 13.064993 * d); val f = RAD * (93.272 + 13.229350 * d)
        val l = l0 + RAD * 6.289 * sin(m); val b = RAD * 5.128 * sin(f)
        val ra = rightAscension(l, b); val dec = declination(l, b)
        val h = siderealTime(d, lw) - ra
        return SkyPos(moonAltitude(ms, lat, lon) / RAD, (azimuth(h, phi, dec) / RAD + 180 + 360) % 360)
    }

    private fun moonAltitude(ms: Double, lat: Double, lon: Double): Double {
        val lw = RAD * -lon; val phi = RAD * lat; val d = toDays(ms)
        val l0 = RAD * (218.316 + 13.176396 * d); val m = RAD * (134.963 + 13.064993 * d); val f = RAD * (93.272 + 13.229350 * d)
        val l = l0 + RAD * 6.289 * sin(m); val b = RAD * 5.128 * sin(f)
        val ra = rightAscension(l, b); val dec = declination(l, b)
        val h = altitude(siderealTime(d, lw) - ra, phi, dec)
        return h + refraction(h)
    }

    /** Mondaufgang an [date] (Ortszeit) oder null, wenn der Mond an dem Tag nicht aufgeht. */
    fun moonrise(date: LocalDate, zone: ZoneId, lat: Double, lon: Double): ZonedDateTime? {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli().toDouble()
        val hc = 0.133 * RAD
        fun alt(hours: Double) = moonAltitude(start + hours * 3_600_000, lat, lon) - hc
        var h0 = alt(0.0)
        var i = 1
        while (i <= 24) {
            val h1 = alt(i.toDouble()); val h2 = alt(i + 1.0)
            val a = (h0 + h2) / 2 - h1; val b = (h2 - h0) / 2
            val xe = -b / (2 * a); val ye = (a * xe + b) * xe + h1
            val disc = b * b - 4 * a * h1
            var rise: Double? = null
            if (disc >= 0 && a != 0.0) {
                val dx = sqrt(disc) / (abs(a) * 2)
                var x1 = xe - dx; val x2 = xe + dx
                var roots = 0
                if (abs(x1) <= 1) roots++
                if (abs(x2) <= 1) roots++
                if (x1 < -1) x1 = x2
                if (roots == 1 && h0 < 0) rise = i + x1
                else if (roots == 2) rise = i + if (ye < 0) x2 else x1
            }
            if (rise != null) return java.time.Instant.ofEpochMilli((start + rise * 3_600_000).toLong()).atZone(zone)
            h0 = h2
            i += 2
        }
        return null
    }

    /** Nächster Mondaufgang ab jetzt (heute oder in den nächsten Tagen). */
    fun nextMoonrise(now: ZonedDateTime, lat: Double, lon: Double): ZonedDateTime? {
        for (k in 0..2) {
            val r = moonrise(now.toLocalDate().plusDays(k.toLong()), now.zone, lat, lon) ?: continue
            if (r.isAfter(now)) return r
        }
        return null
    }
}
