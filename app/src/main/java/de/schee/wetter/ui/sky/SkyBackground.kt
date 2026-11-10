package de.schee.wetter.ui.sky

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.withFrameNanos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

enum class SkyWeather(val label: String) {
    CLEAR("Klar"), PARTLY("Teils bewölkt"), OVERCAST("Bedeckt"), RAIN("Regen"),
    STORM("Gewitter"), SNOW("Schnee"), FOG("Nebel")
}

enum class SkyTime(val label: String) { DAWN("Sonnenaufgang"), DAY("Tag"), DUSK("Sonnenuntergang"), NIGHT("Nacht") }

/**
 * Echter Himmel am Ort (Kevin am 10.11.2026): Sonne und Mond stehen dort, wo sie wirklich stehen,
 * der Mond in seiner echten Phase. Gewichte 0…1 für Tag, Dämmerung und Nacht aus der Sonnenhöhe.
 */
data class SkyLight(
    val sunAlt: Double, val sunAz: Double,
    val moonAlt: Double, val moonAz: Double,
    /** beleuchteter Anteil 0…1 und ob zunehmend */
    val moonLit: Double, val waxing: Boolean,
) {
    private fun smooth(a: Double, b: Double, x: Double): Float { val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0); return (t * t * (3 - 2 * t)).toFloat() }
    val day: Float get() = smooth(-1.0, 9.0, sunAlt)
    val night: Float get() = 1f - smooth(-13.0, -5.0, sunAlt)
    /** Dämmerung: am stärksten kurz vor/nach Auf- und Untergang. */
    val twilight: Float get() = (smooth(-12.0, -2.0, sunAlt) * (1f - smooth(1.0, 10.0, sunAlt))).coerceIn(0f, 1f)
    val morning: Boolean get() = sunAz < 180

    companion object {
        fun at(epochMs: Long, lat: Double, lon: Double): SkyLight {
            val s = de.schee.wetter.data.Astro.sun(epochMs, lat, lon)
            val m = de.schee.wetter.data.Astro.moonPos(epochMs, lat, lon)
            val ph = de.schee.wetter.data.Astro.moon(epochMs)
            return SkyLight(s.altDeg, s.azDeg, m.altDeg, m.azDeg, ph.illumination, ph.waxing)
        }

        /** Richtung → waagrechte Lage im Bild: Osten links, Süden Mitte, Westen rechts (Blick nach Süden). */
        fun xOf(az: Double): Float = (((az - 90.0) / 180.0).coerceIn(-.05, 1.05)).toFloat()
    }
}

/** Farben und Stärken wie in der abgenommenen Vorschau. */
object SkyPalette {
    private val base = mapOf(
        SkyTime.DAWN to (Color(0xFF151A36) to Color(0xFF3A2A48)),
        SkyTime.DAY to (Color(0xFF0C2A4D) to Color(0xFF1A4A78)),
        SkyTime.DUSK to (Color(0xFF1A1636) to Color(0xFF41263D)),
        SkyTime.NIGHT to (Color(0xFF070B1D) to Color(0xFF18213F)),
    )
    private val tint = mapOf(
        SkyWeather.CLEAR to (Color.Black to 0f),
        SkyWeather.PARTLY to (Color(0xFF1C2533) to .25f),
        SkyWeather.OVERCAST to (Color(0xFF1B2129) to .6f),
        SkyWeather.RAIN to (Color(0xFF121921) to .7f),
        SkyWeather.STORM to (Color(0xFF120E1C) to .8f),
        SkyWeather.SNOW to (Color(0xFF1D2736) to .55f),
        SkyWeather.FOG to (Color(0xFF1E2226) to .7f),
    )
    val cloudAmount = mapOf(
        SkyWeather.CLEAR to 0f, SkyWeather.PARTLY to .42f, SkyWeather.OVERCAST to 1f, SkyWeather.RAIN to .92f,
        SkyWeather.STORM to 1f, SkyWeather.SNOW to .85f, SkyWeather.FOG to 0f,
    )

    /** Stufenloser Verlauf nach echtem Sonnenstand: Tag, Dämmerung (morgens/abends) und Nacht ineinander geblendet. */
    fun gradient(w: SkyWeather, light: SkyLight): Pair<Color, Color> {
        val day = gradient(w, SkyTime.DAY); val night = gradient(w, SkyTime.NIGHT)
        val twi = gradient(w, if (light.morning) SkyTime.DAWN else SkyTime.DUSK)
        fun mix(a: Color, b: Color, c: Color) = lerp(lerp(c, a, light.day / (light.day + light.night + 1e-3f).coerceAtLeast(1e-3f)), b, light.twilight)
        return mix(day.first, twi.first, night.first) to mix(day.second, twi.second, night.second)
    }

    fun gradient(w: SkyWeather, t: SkyTime): Pair<Color, Color> {
        val (top, bottom) = base.getValue(t)
        val (c, k) = tint.getValue(w)
        return lerp(top, c, k) to lerp(bottom, c, k * .85f)
    }

    /** Wolkenfarben je Tageszeit: hell / Schatten (gedämpft für den Dark Mode). */
    fun cloudTone(t: SkyTime): Pair<IntArray, IntArray> = when (t) {
        SkyTime.DAY -> intArrayOf(142, 158, 182) to intArrayOf(30, 42, 62)
        SkyTime.NIGHT -> intArrayOf(84, 94, 120) to intArrayOf(14, 18, 32)
        SkyTime.DAWN -> intArrayOf(168, 132, 146) to intArrayOf(36, 30, 52)
        SkyTime.DUSK -> intArrayOf(160, 120, 136) to intArrayOf(32, 26, 46)
    }

    fun fogTone(t: SkyTime): IntArray = when (t) {
        SkyTime.NIGHT -> intArrayOf(120, 126, 138)
        SkyTime.DAY -> intArrayOf(150, 158, 170)
        else -> intArrayOf(160, 148, 156)
    }
}

/** false = keine Endlos-Animationen (nur für Bildschirmfotos im Test). */
val LocalAnimate = androidx.compose.runtime.staticCompositionLocalOf { true }

private class Particle(var x: Float, var y: Float, val a: Float, val b: Float, val c: Float)

/**
 * Immer dunkler, animierter Himmel nach Wetter und Tageszeit.
 * Animiert nur, solange die App sichtbar ist (repeatOnLifecycle STARTED) – kein Akku im Hintergrund.
 */
@Composable
fun SkyBackground(weather: SkyWeather, time: SkyTime, modifier: Modifier = Modifier, light: SkyLight? = null) {
    val w by rememberUpdatedState(weather)
    val tm by rememberUpdatedState(time)
    val lt by rememberUpdatedState(light)
    var seconds by remember { mutableFloatStateOf(0f) }
    var volume by remember { mutableStateOf<ImageBitmap?>(null) }
    var size by remember { mutableStateOf(Size.Zero) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val stars = remember { List(90) { Particle(Random.nextFloat(), Random.nextFloat() * .7f, Random.nextFloat() * 1.1f + .2f, Random.nextFloat() * 6f, 0f) } }
    val drops = remember { List(140) { Particle(Random.nextFloat(), Random.nextFloat(), 10f + Random.nextFloat() * 14f, 7f + Random.nextFloat() * 5f, 0f) } }
    val flakes = remember { List(90) { Particle(Random.nextFloat(), Random.nextFloat(), 1f + Random.nextFloat() * 2.2f, .4f + Random.nextFloat() * .8f, Random.nextFloat() * 6f) } }
    var flash by remember { mutableFloatStateOf(0f) }

    val animate = LocalAnimate.current

    // Bildtakt für Partikel und Lichteffekte
    LaunchedEffect(lifecycle) {
        if (!animate) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val start = withFrameNanos { it }
            while (isActive) {
                withFrameNanos { now -> seconds = (now - start) / 1e9f }
            }
        }
    }

    // Wolken/Nebel: kleines Bild (110 px breit), ~15× pro Sekunde neu berechnet, weich hochskaliert
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val buffers = arrayOfNulls<Bitmap>(2)
            var which = 0
            val t0 = System.nanoTime()
            while (isActive) {
                val s = size
                val weatherNow = w
                val needs = (SkyPalette.cloudAmount.getValue(weatherNow) > 0f) || weatherNow == SkyWeather.FOG
                if (s.width > 0f && needs) {
                    val vw = 110
                    val vh = (vw * s.height / s.width).roundToInt().coerceAtLeast(1)
                    val t = (System.nanoTime() - t0) / 1e9f
                    val bmp = withContext(Dispatchers.Default) {
                        val b = buffers[which]?.takeIf { it.width == vw && it.height == vh }
                            ?: Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888).also { buffers[which] = it }
                        b.setPixels(renderVolume(weatherNow, tm, vw, vh, t), 0, vw, 0, 0, vw, vh)
                        b
                    }
                    volume = bmp.asImageBitmap()
                    which = 1 - which
                } else if (!needs) {
                    volume = null
                }
                if (!animate && volume != null) break
                delay(66)
            }
        }
    }

    Canvas(modifier) {
        size = this.size
        val wd = this.size.width; val ht = this.size.height
        val t = seconds
        val l = lt
        val (top, bottom) = if (l != null) SkyPalette.gradient(w, l) else SkyPalette.gradient(w, tm)
        drawRect(Brush.verticalGradient(listOf(top, bottom)))
        val clearish = w == SkyWeather.CLEAR || w == SkyWeather.PARTLY

        if (l != null) {
            // Horizont-Glühen dort, wo die Sonne wirklich auf- bzw. untergeht
            if (l.twilight > .02f) {
                val k = when (w) { SkyWeather.CLEAR -> .55f; SkyWeather.PARTLY -> .4f; else -> .18f } * l.twilight
                drawRect(Brush.radialGradient(
                    0f to Color(255, 138, 76).copy(alpha = k), .45f to Color(170, 70, 120).copy(alpha = k * .45f), 1f to Color.Transparent,
                    center = Offset(wd * SkyLight.xOf(l.sunAz), ht * 1.05f), radius = ht * .72f))
            }
            // Sonnenschein: Lichtfleck an der echten Sonnenposition, je höher die Sonne, desto weiter oben
            if (l.day > .02f && clearish) {
                val k = (if (w == SkyWeather.CLEAR) .38f else .22f) * l.day
                val y = (ht * (.34f - (l.sunAlt / 60.0).toFloat().coerceIn(0f, 1f) * .44f)).coerceAtLeast(-ht * .1f)
                val warm = (1f - (l.sunAlt / 25.0).toFloat().coerceIn(0f, 1f))   // tiefe Sonne: wärmer
                drawRect(Brush.radialGradient(
                    0f to lerp(Color(255, 214, 120), Color(255, 160, 90), warm).copy(alpha = k),
                    .35f to Color(255, 170, 80).copy(alpha = k * .35f), 1f to Color.Transparent,
                    center = Offset(wd * SkyLight.xOf(l.sunAz), y), radius = ht * .6f))
            }
        } else {
            // Horizont-Glühen bei Auf-/Untergang
            if (tm == SkyTime.DAWN || tm == SkyTime.DUSK) {
                val k = when (w) { SkyWeather.CLEAR -> .55f; SkyWeather.PARTLY -> .4f; else -> .18f }
                drawRect(Brush.radialGradient(
                    0f to Color(255, 138, 76).copy(alpha = k), .45f to Color(170, 70, 120).copy(alpha = k * .45f), 1f to Color.Transparent,
                    center = Offset(wd * .5f, ht * 1.05f), radius = ht * .7f))
            }
            // Sonnenschein am Tag
            if (tm == SkyTime.DAY && clearish) {
                val k = if (w == SkyWeather.CLEAR) .38f else .22f
                drawRect(Brush.radialGradient(
                    0f to Color(255, 214, 120).copy(alpha = k), .35f to Color(255, 170, 80).copy(alpha = k * .35f), 1f to Color.Transparent,
                    center = Offset(wd * .85f, -10f), radius = ht * .6f))
            }
        }
        // Mond in seiner echten Phase an seiner echten Stelle (nur wenn er über dem Horizont steht und es dunkel ist)
        if (l != null && clearish && l.moonAlt > 1 && l.night > .3f && l.moonLit > .03) {
            val r = 12f * density
            val c = Offset(wd * SkyLight.xOf(l.moonAz), (ht * (.30f - (l.moonAlt / 70.0).toFloat().coerceIn(0f, 1f) * .2f)).coerceAtLeast(70f * density))
            val vis = (if (w == SkyWeather.CLEAR) 1f else .6f) * l.night
            drawCircle(Brush.radialGradient(0f to Color(220, 228, 255).copy(alpha = .22f * vis * l.moonLit.toFloat()), 1f to Color.Transparent, center = c, radius = r * 6), r * 6, c)
            drawCircle(Color(150, 160, 190).copy(alpha = .16f * vis), r, c)
            drawPath(moonLitPath(c, r, l.moonLit.toFloat(), l.waxing), Color(236, 240, 250).copy(alpha = .9f * vis))
        }
        // Sterne
        val dark = l?.night ?: if (tm == SkyTime.NIGHT) 1f else 0f
        if (dark > .05f && clearish) {
            val base = (if (w == SkyWeather.CLEAR) .9f else .55f) * dark * (1f - .35f * ((l?.moonLit ?: 0.0).toFloat() * if ((l?.moonAlt ?: -1.0) > 0) 1f else 0f))
            stars.forEach { st ->
                val a = base * (.55f + .45f * sin(t * 1.6f + st.b))
                drawCircle(Color.White.copy(alpha = a.coerceIn(0f, 1f)), st.a * density, Offset(st.x * wd, st.y * ht))
            }
        }
        // Wolken / Nebel
        volume?.let {
            drawImage(it, srcOffset = IntOffset.Zero, srcSize = IntSize(it.width, it.height),
                dstOffset = IntOffset.Zero, dstSize = IntSize(wd.roundToInt(), ht.roundToInt()), filterQuality = FilterQuality.High)
        }
        // Regen
        if (w == SkyWeather.RAIN || w == SkyWeather.STORM) {
            val c = Color(170, 200, 235).copy(alpha = .28f)
            val scale = density / 2f
            drops.forEach { d ->
                d.y += d.b * scale / ht; d.x -= d.b * .18f * scale / wd
                if (d.y > 1f) { d.y = -d.a * scale / ht; d.x = Random.nextFloat() * 1.2f }
                val x = d.x * wd; val y = d.y * ht; val l = d.a * scale
                drawLine(c, Offset(x, y), Offset(x - l * .18f, y + l), strokeWidth = density * .5f)
            }
        }
        // Schnee
        if (w == SkyWeather.SNOW) {
            val scale = density / 2f
            flakes.forEach { f ->
                f.y += f.b * scale / ht; f.x += sin(t + f.c) * .35f * scale / wd
                if (f.y > 1f) { f.y = -.01f; f.x = Random.nextFloat() }
                drawCircle(Color(240, 244, 252).copy(alpha = .75f), f.a * scale, Offset(f.x * wd, f.y * ht))
            }
        }
        // Blitz
        if (w == SkyWeather.STORM) {
            if (flash <= 0f && Random.nextFloat() < .006f) flash = 1f
            if (flash > 0f) { drawRect(Color(200, 190, 255).copy(alpha = flash * .22f)); flash -= .06f }
        }
        // Lesbarkeit unten
        drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = .25f), startY = ht * .55f, endY = ht),
            topLeft = Offset(0f, ht * .55f), size = Size(wd, ht * .45f))
    }
}

/** Beleuchteter Teil der Mondscheibe: Außenrand auf der hellen Seite + elliptische Schattengrenze. */
internal fun moonLitPath(c: Offset, r: Float, lit: Float, waxing: Boolean): androidx.compose.ui.graphics.Path {
    val p = androidx.compose.ui.graphics.Path()
    val circle = androidx.compose.ui.geometry.Rect(c.x - r, c.y - r, c.x + r, c.y + r)
    val rx = r * kotlin.math.abs(1f - 2f * lit)
    val ell = androidx.compose.ui.geometry.Rect(c.x - rx, c.y - r, c.x + rx, c.y + r)
    // zunehmend: rechts hell (Nordhalbkugel)
    p.moveTo(c.x, c.y - r)
    p.arcTo(circle, -90f, if (waxing) 180f else -180f, false)
    // zurück nach oben über die Schattengrenze: Sichel (< halb) wölbt zur hellen Seite, Dreiviertel zur dunklen
    val toLitSide = lit < .5f
    val sweep = if (waxing == toLitSide) -180f else 180f
    p.arcTo(ell, 90f, sweep, false)
    p.close()
    return p
}

/** Berechnet Wolken bzw. Nebel als ARGB-Pixel (gleiche Formeln wie die Vorschau). */
internal fun renderVolume(w: SkyWeather, tm: SkyTime, vw: Int, vh: Int, t: Float): IntArray {
    val out = IntArray(vw * vh)
    if (w == SkyWeather.FOG) {
        val c = SkyPalette.fogTone(tm)
        for (y in 0 until vh) {
            val fy = y.toFloat() / vh
            val depth = .25f + .75f * SkyNoise.smooth(.05f, .95f, fy)
            for (x in 0 until vw) {
                val u = x.toFloat() / vw
                val a1 = SkyNoise.fbm(u * 1.6f + t * .035f, fy * 7.5f, 4)
                val a2 = SkyNoise.fbm(u * 2.4f - t * .022f + 40f, fy * 11f + 7f, 3)
                val wisps = SkyNoise.smooth(.38f, .78f, a1 * .62f + a2 * .38f)
                val veil = .18f + .22f * fy
                val a = min(.82f, (veil + wisps * .55f) * depth)
                val lift = (10 * wisps).toInt()
                out[y * vw + x] = argb(a, c[0] + lift, c[1] + lift, c[2] + lift)
            }
        }
        return out
    }
    val cov = SkyPalette.cloudAmount.getValue(w)
    val dark = when (w) { SkyWeather.STORM -> .55f; SkyWeather.RAIN -> .7f; else -> 1f }
    val (l, sh) = SkyPalette.cloudTone(tm)
    val maxA = if (w == SkyWeather.PARTLY) .78f else .9f
    val sc = 2.1f / vw
    val thr = 1f - cov
    val lo = if (w == SkyWeather.PARTLY) .44f else .28f + thr * .3f
    val span = if (w == SkyWeather.PARTLY) .16f else .3f
    for (y in 0 until vh) {
        val fy = y.toFloat() / vh
        val vfall = if (w == SkyWeather.PARTLY) 1f - SkyNoise.smooth(.25f, .85f, fy) * .85f else 1f - SkyNoise.smooth(.6f, 1f, fy) * .35f
        for (x in 0 until vw) {
            val u = x * sc; val v = y * sc * 1.35f
            val wx = SkyNoise.noise(u * .5f + t * .006f, v * .5f + 3.1f) - .5f
            val wy = SkyNoise.noise(u * .5f + 8.7f, v * .5f - t * .005f) - .5f
            val px = u + wx * .55f + t * .02f; val py = v + wy * .55f
            val n = SkyNoise.fbm(px, py, 5)
            val dens = SkyNoise.smooth(lo, lo + span, n) * vfall
            val nUp = SkyNoise.fbm(px, py - .07f, 3)
            val light = ((.3f + dens * .45f + (n - nUp) * 1.8f).coerceIn(0f, 1f)) * dark
            out[y * vw + x] = argb(
                min(maxA, dens * maxA),
                (sh[0] + (l[0] - sh[0]) * light).toInt(),
                (sh[1] + (l[1] - sh[1]) * light).toInt(),
                (sh[2] + (l[2] - sh[2]) * light).toInt(),
            )
        }
    }
    return out
}

private fun argb(a: Float, r: Int, g: Int, b: Int): Int {
    val ai = (a.coerceIn(0f, 1f) * 255).toInt()
    // Bitmap erwartet nicht vormultiplizierte Werte bei setPixels
    return (ai shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}
