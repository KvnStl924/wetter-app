package de.schee.wetter.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.schee.wetter.ui.theme.WColors
import kotlin.math.cos
import kotlin.math.sin


/** Eigene Wetter-Symbole (bunt wie im iOS-Stil, aber selbst gezeichnet). Raster 32×32. */
enum class IconKind { SUN, MOON, CLOUD, SUN_CLOUD, MOON_CLOUD, RAIN, STORM, SNOW, FOG, SUNRISE, SUNSET }

@Composable
fun WeatherIcon(kind: IconKind, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 32f
        when (kind) {
            IconKind.SUN -> sun(s, 16f, 16f, 6f, rays = true)
            IconKind.MOON -> moon(s, 0f, 0f, 1f)
            IconKind.CLOUD -> cloud(s, 0f, 0f, 1f)
            IconKind.SUN_CLOUD -> { sun(s, 20f, 11f, 5f, rays = true, rayLen = 2f); cloud(s, -2f, 4f, .82f) }
            IconKind.MOON_CLOUD -> { moon(s, 6f, -3f, .62f); cloud(s, -3f, 4f, .82f) }
            IconKind.RAIN -> { cloud(s, 0f, -5f, 1f); drops(s) }
            IconKind.STORM -> { cloud(s, 0f, -6f, 1f); bolt(s) }
            IconKind.SNOW -> { cloud(s, 0f, -5f, 1f); flakes(s) }
            IconKind.FOG -> fog(s)
            IconKind.SUNRISE -> horizon(s, up = true)
            IconKind.SUNSET -> horizon(s, up = false)
        }
    }
}

private fun DrawScope.sun(s: Float, cx: Float, cy: Float, r: Float, rays: Boolean, rayLen: Float = 3f) {
    drawCircle(WColors.Sun, r * s, Offset(cx * s, cy * s))
    if (!rays) return
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        val r1 = r + 3f; val r2 = r1 + rayLen
        drawLine(
            WColors.Sun,
            Offset((cx + cos(a) * r1).toFloat() * s, (cy + sin(a) * r1).toFloat() * s),
            Offset((cx + cos(a) * r2).toFloat() * s, (cy + sin(a) * r2).toFloat() * s),
            strokeWidth = 2f * s, cap = StrokeCap.Round
        )
    }
}

/** Wolke: Grundlinie y=25, Breite 9..30 (bei Faktor 1). */
private fun DrawScope.cloud(s: Float, dx: Float, dy: Float, k: Float) {
    fun p(x: Float, y: Float) = Offset((16f + (x - 16f) * k + dx) * s, (16f + (y - 16f) * k + dy) * s)
    val path = Path().apply {
        val a = p(9f, 25f); moveTo(a.x, a.y)
        val b = p(24f, 25f); lineTo(b.x, b.y)
        val c1 = p(30f, 25f); val c2 = p(31f, 14f); val e = p(24.6f, 13f); cubicTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y)
        val d1 = p(23f, 6f); val d2 = p(11f, 6f); val f = p(9.3f, 14.3f); cubicTo(d1.x, d1.y, d2.x, d2.y, f.x, f.y)
        val g1 = p(3f, 15f); val g2 = p(3.5f, 25f); cubicTo(g1.x, g1.y, g2.x, g2.y, a.x, a.y)
        close()
    }
    drawPath(path, WColors.Cloud)
}

private fun DrawScope.moon(s: Float, dx: Float, dy: Float, k: Float) {
    fun p(x: Float, y: Float) = Offset((16f + (x - 16f) * k + dx) * s, (16f + (y - 16f) * k + dy) * s)
    val path = Path().apply {
        val a = p(20f, 5f); moveTo(a.x, a.y)
        val c1 = p(10f, 5f); val c2 = p(3f, 17f); val b = p(9f, 24f); cubicTo(c1.x, c1.y, c2.x, c2.y, b.x, b.y)
        val d1 = p(14f, 30f); val d2 = p(24f, 28f); val c = p(27f, 22f); cubicTo(d1.x, d1.y, d2.x, d2.y, c.x, c.y)
        val e1 = p(19f, 23f); val e2 = p(14f, 12f); cubicTo(e1.x, e1.y, e2.x, e2.y, a.x, a.y)
        close()
    }
    drawPath(path, WColors.Cloud)
    if (k >= 1f) {
        val st = p(25f, 8f)
        drawCircle(WColors.Cloud, 1.2f * s, st)
    }
}

private fun DrawScope.drops(s: Float) {
    for (x in listOf(11f, 17f, 23f)) {
        drawLine(WColors.Rain, Offset(x * s, 23f * s), Offset((x - 1.2f) * s, 27.5f * s), strokeWidth = 2.2f * s, cap = StrokeCap.Round)
    }
}

private fun DrawScope.flakes(s: Float) {
    listOf(11f to 24f, 17f to 27f, 23f to 24f).forEach { (x, y) -> drawCircle(WColors.Cloud, 1.6f * s, Offset(x * s, y * s)) }
}

private fun DrawScope.bolt(s: Float) {
    val path = Path().apply {
        moveTo(17f * s, 17f * s); lineTo(12.5f * s, 24.5f * s); lineTo(16.5f * s, 24.5f * s)
        lineTo(14.5f * s, 31f * s); lineTo(21f * s, 21.5f * s); lineTo(17f * s, 21.5f * s); lineTo(19.5f * s, 17f * s); close()
    }
    drawPath(path, WColors.Sun)
}

private fun DrawScope.fog(s: Float) {
    listOf(Triple(6f, 26f, 12f), Triple(4f, 26f, 17f), Triple(8f, 26f, 22f)).forEach { (a, b, y) ->
        drawLine(WColors.Cloud.copy(alpha = .9f), Offset(a * s, y * s), Offset(b * s, y * s), strokeWidth = 2.4f * s, cap = StrokeCap.Round)
    }
}

private fun DrawScope.horizon(s: Float, up: Boolean) {
    val half = Path().apply {
        moveTo(9f * s, 21f * s)
        arcTo(androidx.compose.ui.geometry.Rect(9f * s, 14f * s, 23f * s, 28f * s), 180f, 180f, false)
        close()
    }
    drawPath(half, WColors.Sun)
    drawLine(WColors.Sun, Offset(4f * s, 24f * s), Offset(28f * s, 24f * s), strokeWidth = 2f * s, cap = StrokeCap.Round)
    val arrow = Path().apply {
        if (up) { moveTo(16f * s, 12f * s); lineTo(16f * s, 4f * s); moveTo(12.5f * s, 7.5f * s); lineTo(16f * s, 4f * s); lineTo(19.5f * s, 7.5f * s) }
        else { moveTo(16f * s, 4f * s); lineTo(16f * s, 12f * s); moveTo(12.5f * s, 8.5f * s); lineTo(16f * s, 12f * s); lineTo(19.5f * s, 8.5f * s) }
    }
    drawPath(arrow, WColors.Sun, style = Stroke(width = 2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
