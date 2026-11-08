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
fun SkyBackground(weather: SkyWeather, time: SkyTime, modifier: Modifier = Modifier) {
    val w by rememberUpdatedState(weather)
    val tm by rememberUpdatedState(time)
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
        val (top, bottom) = SkyPalette.gradient(w, tm)
        drawRect(Brush.verticalGradient(listOf(top, bottom)))

        // Horizont-Glühen bei Auf-/Untergang
        if (tm == SkyTime.DAWN || tm == SkyTime.DUSK) {
            val k = when (w) { SkyWeather.CLEAR -> .55f; SkyWeather.PARTLY -> .4f; else -> .18f }
            drawRect(Brush.radialGradient(
                0f to Color(255, 138, 76).copy(alpha = k), .45f to Color(170, 70, 120).copy(alpha = k * .45f), 1f to Color.Transparent,
                center = Offset(wd * .5f, ht * 1.05f), radius = ht * .7f))
        }
        // Sonnenschein am Tag
        if (tm == SkyTime.DAY && (w == SkyWeather.CLEAR || w == SkyWeather.PARTLY)) {
            val k = if (w == SkyWeather.CLEAR) .38f else .22f
            drawRect(Brush.radialGradient(
                0f to Color(255, 214, 120).copy(alpha = k), .35f to Color(255, 170, 80).copy(alpha = k * .35f), 1f to Color.Transparent,
                center = Offset(wd * .85f, -10f), radius = ht * .6f))
        }
        // Sterne
        if (tm == SkyTime.NIGHT && (w == SkyWeather.CLEAR || w == SkyWeather.PARTLY)) {
            val base = if (w == SkyWeather.CLEAR) .9f else .55f
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
