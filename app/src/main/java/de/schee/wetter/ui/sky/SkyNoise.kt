package de.schee.wetter.ui.sky

import kotlin.math.floor

/** Value-Noise + fBm, identisch zur abgenommenen Vorschau (docs/hintergrund-vorschau.html). */
object SkyNoise {
    private val p = IntArray(512).also { arr ->
        val base = IntArray(256) { it }
        var s = 1337L
        for (i in 255 downTo 1) {
            s = (s * 16807) % 2147483647
            val j = (s % (i + 1)).toInt()
            val t = base[i]; base[i] = base[j]; base[j] = t
        }
        for (i in 0 until 512) arr[i] = base[i and 255]
    }

    private fun fade(x: Float) = x * x * x * (x * (x * 6f - 15f) + 10f)

    fun noise(x: Float, y: Float): Float {
        val xf0 = floor(x); val yf0 = floor(y)
        val xi = xf0.toInt() and 255; val yi = yf0.toInt() and 255
        val xf = x - xf0; val yf = y - yf0
        val a = p[p[xi] + yi]; val b = p[p[xi + 1] + yi]
        val c = p[p[xi] + yi + 1]; val d = p[p[xi + 1] + yi + 1]
        val u = fade(xf); val v = fade(yf)
        val top = a + (b - a) * u
        val bot = c + (d - c) * u
        return (top + (bot - top) * v) / 255f
    }

    fun fbm(x: Float, y: Float, octaves: Int): Float {
        var sum = 0f; var amp = .5f; var f = 1f; var norm = 0f
        for (i in 0 until octaves) {
            sum += amp * noise(x * f, y * f); norm += amp; amp *= .5f; f *= 2.03f
        }
        return sum / norm
    }

    fun smooth(a: Float, b: Float, x: Float): Float {
        val k = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return k * k * (3f - 2f * k)
    }
}
