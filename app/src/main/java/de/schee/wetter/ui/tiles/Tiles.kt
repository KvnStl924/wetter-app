package de.schee.wetter.ui.tiles

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import de.schee.wetter.data.Astro
import de.schee.wetter.data.Forecast
import de.schee.wetter.data.MapImages
import de.schee.wetter.data.Units
import de.schee.wetter.data.Warning
import de.schee.wetter.ui.main.Timeline
import de.schee.wetter.ui.theme.WColors
import de.schee.wetter.ui.theme.WType
import kotlinx.coroutines.isActive
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Alles, was die Kacheln brauchen. */
class TileData(
    val f: Forecast,
    val now: LocalDateTime,
    val units: Units,
    val warnings: List<Warning>,
    val normalMax: Double?,
    val maps: MapImages.Layers?,
    val lat: Double,
    val lon: Double,
    /** Regen auf die Minute aus dem DWD-Radar (null = außerhalb Deutschlands oder noch nicht geladen). */
    val nowcast: de.schee.wetter.data.Nowcast.Series? = null,
)

// ------------------------------------------------------------ Unwetterwarnung

@Composable
fun WarningContent(d: TileData, expanded: Boolean, onMap: () -> Unit = {}) {
    d.warnings.forEachIndexed { i, w ->
        if (i > 0) HorizontalDivider(color = WColors.CardBorder)
        val color = when (w.level) { 4 -> Color(0xFFB83CFF); 3 -> WColors.Bad; 2 -> WColors.Warn; else -> Color(0xFFFFD60A) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(color))
            Text(w.headline, style = WType.small)
        }
        val until = w.expires?.let { runCatching { ZonedDateTime.parse(it).withZoneSameInstant(ZoneId.of(d.f.timezone)) }.getOrNull() }
        if (until != null) Text("bis ${Timeline.dayLong(until.toLocalDate(), d.now.toLocalDate())}, ${"%02d:%02d".format(until.hour, until.minute)} Uhr", style = WType.bodyDim)
        if (expanded && w.description.isNotBlank()) Text(w.description, style = WType.bodyDim)
    }
    if (expanded) Text("Warngebiet auf der Karte zeigen ›", style = WType.small.copy(color = WColors.Accent),
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { de.schee.wetter.log.AppLog.tap("Kachel Unwetterwarnung → Warngebiet auf der Karte"); onMap() }.padding(vertical = 4.dp))
    Text(if (expanded) "Quelle: Deutscher Wetterdienst" else "Tippen für Details · Quelle: DWD", style = WType.label.copy(color = WColors.Label))
}

// ------------------------------------------------------------ Regenradar

@Composable
fun RadarContent(d: TileData) {
    if (LiveMaps.enabled) {
        // Live-Karte mit aktuellem Radar (DWD, weltweit RainViewer) um den Ort
        de.schee.wetter.ui.radar.MiniRadar(de.schee.wetter.ui.radar.RadarPlace("", d.lat, d.lon, d.units.t(d.f.current.temp)), wind = false,
            Modifier.fillMaxWidth().aspectRatio(1.35f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0B1220)))
        val rainNow = (d.maps?.radar?.let { de.schee.wetter.data.RadarData.hasRain(it) } ?: false)
        Text(if (rainNow) "Aktuell Niederschlag in der Umgebung · Tippen für Vollbild" else "Kein Niederschlag in der Umgebung · Tippen für Vollbild", style = WType.bodyDim)
        return
    }
    Box(Modifier.fillMaxWidth().aspectRatio(1.35f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0B1220))) {
        MapBase(d.maps)
        d.maps?.radar?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        LocationDot(Modifier.align(Alignment.Center), d.units.t(d.f.current.temp))
        if (d.maps == null) Text("Radar wird geladen …", style = WType.bodyDim, modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp))
    }
    val rainNow = (d.maps?.radar?.let { de.schee.wetter.data.RadarData.hasRain(it) } ?: false)
    Text(if (rainNow) "Aktuell Niederschlag in der Umgebung · DWD-Radar" else "Kein Niederschlag in der Umgebung · DWD-Radar", style = WType.bodyDim)
}

@Composable
private fun MapBase(maps: MapImages.Layers?) {
    maps?.borders?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
}

@Composable
private fun LocationDot(modifier: Modifier, label: String) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(50)).background(Color(0xE61C2233)), contentAlignment = Alignment.Center) {
            Text(label, style = WType.small)
        }
    }
}

// ------------------------------------------------------------ Regen nächste 2 Std.

object RainSoon {
    /** Text wie „Regen ab 15:20“ / „Regen hört um 16:05 auf“. */
    fun summary(slots: List<Pair<LocalDateTime, Double>>): String {
        if (slots.isEmpty()) return "Keine Daten"
        val wet = slots.map { it.second >= 0.1 }
        return when {
            wet.none { it } -> "Kein Regen in den nächsten 2 Stunden"
            wet.first() && wet.all { it } -> "Regen hält mindestens 2 Stunden an"
            wet.first() -> "Regen hört gegen ${Timeline.hhmm(slots[wet.indexOfFirst { !it }].first)} Uhr auf"
            else -> "Regen ab etwa ${Timeline.hhmm(slots[wet.indexOfFirst { it }].first)} Uhr"
        }
    }

    fun slots(d: TileData): List<Pair<LocalDateTime, Double>> {
        val start = d.now.withSecond(0).withNano(0).minusMinutes((d.now.minute % 15).toLong())
        return d.f.minutely.filter { !it.first.isBefore(start) }.take(8)
    }
}

@Composable
fun Rain2hContent(d: TileData) {
    // Radar (5 Minuten, Deutschland) – sonst Modell (15 Minuten)
    val nc = d.nowcast
    val nowI = java.time.Instant.now()
    val pts: List<Pair<Float, Double>>
    val text: String
    if (nc != null) {
        pts = nc.steps.map { (t, mm) -> ((t.epochSecond - nowI.epochSecond) / 60f) to mm }
        text = de.schee.wetter.data.Nowcast.summary(nc, nowI)
    } else {
        val slots = RainSoon.slots(d)
        pts = slots.mapIndexed { i, (_, mm) -> (i * 15f) to mm * 4 }   // mm je 15 min → mm/h
        text = RainSoon.summary(slots)
    }
    Text(text, style = WType.body)
    RainCurve(pts, Modifier.fillMaxWidth().height(78.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("Jetzt", "30 Min.", "60 Min.", "90 Min.", "2 Std.").forEach { Text(it, style = WType.label.copy(color = WColors.Label)) }
    }
    Text(if (nc != null) "Quelle: DWD-Radar, alle 5 Minuten" else "Quelle: Modell ICON-D2, alle 15 Minuten", style = WType.label.copy(color = WColors.Label))
}

/** Weiche Regenkurve wie bei iOS: drei gestrichelte Linien (leicht · mäßig · stark), Fläche mit Verlauf. */
@Composable
private fun RainCurve(pts: List<Pair<Float, Double>>, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        // Stärke auf 0…1: 0,1 mm/h ≈ 0,15 · 2,5 mm/h ≈ 0,55 · 10 mm/h ≈ 0,9
        fun level(mm: Double) = if (mm < 0.05) 0f else (kotlin.math.ln(1 + mm * 4) / kotlin.math.ln(41.0)).toFloat().coerceIn(.06f, 1f)
        val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3 * density, 4 * density))
        listOf(.33f, .66f, 1f).forEach { k -> drawLine(Color.White.copy(alpha = .14f), Offset(0f, h * (1 - k * .92f)), Offset(w, h * (1 - k * .92f)), 1 * density, pathEffect = dash) }
        if (pts.size < 2) return@Canvas
        fun x(m: Float) = (m / 120f).coerceIn(0f, 1f) * w
        fun y(mm: Double) = h - level(mm) * h * .92f
        val line = Path(); val area = Path()
        pts.forEachIndexed { i, (m, mm) ->
            val px = x(m); val py = y(mm)
            if (i == 0) { line.moveTo(px, py); area.moveTo(px, h); area.lineTo(px, py) }
            else {
                val (pm, pmm) = pts[i - 1]; val qx = x(pm); val qy = y(pmm); val cx = (qx + px) / 2
                line.cubicTo(cx, qy, cx, py, px, py); area.cubicTo(cx, qy, cx, py, px, py)
            }
        }
        area.lineTo(x(pts.last().first), h); area.close()
        drawPath(area, Brush.verticalGradient(listOf(WColors.Rain.copy(alpha = .75f), WColors.Rain.copy(alpha = .12f))))
        drawPath(line, WColors.Rain, style = Stroke(2 * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ------------------------------------------------------------ Wind

@Composable
fun WindContent(d: TileData) {
    val c = d.f.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoRow("Wind", d.units.w(c.wind))
            HorizontalDivider(color = WColors.CardBorder)
            InfoRow("Böen", d.units.w(c.gusts))
            HorizontalDivider(color = WColors.CardBorder)
            InfoRow("Richtung", "${c.windDir}° ${Units.compass(c.windDir)}")
        }
        Spacer(Modifier.width(14.dp))
        Compass(c.windDir, d.units.wNum(c.wind), d.units.windLabel, Modifier.size(110.dp))
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = WType.body, modifier = Modifier.weight(1f))
        Text(value, style = WType.body.copy(color = WColors.TextDim))
    }
}

@Composable
private fun Compass(fromDeg: Int, value: Int, unit: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            val c = Offset(size.width / 2, size.height / 2)
            for (i in 0 until 72) {
                val a = i * 5.0 * PI / 180
                val long = i % 18 == 0
                val r1 = r * (if (long) .78f else .86f); val r2 = r * .95f
                drawLine(Color.White.copy(alpha = if (long) .7f else .3f),
                    Offset(c.x + (sin(a) * r1).toFloat(), c.y - (cos(a) * r1).toFloat()),
                    Offset(c.x + (sin(a) * r2).toFloat(), c.y - (cos(a) * r2).toFloat()), strokeWidth = 1.5f)
            }
            // Pfeil: zeigt, wohin der Wind weht
            val to = (fromDeg + 180) * PI / 180
            val tip = Offset(c.x + (sin(to) * r * .72f).toFloat(), c.y - (cos(to) * r * .72f).toFloat())
            val tail = Offset(c.x - (sin(to) * r * .72f).toFloat(), c.y + (cos(to) * r * .72f).toFloat())
            drawLine(Color.White, tail, tip, strokeWidth = 3f, cap = StrokeCap.Round)
            drawCircle(Color.White, 5f, tail)
            val left = to + 2.6; val right = to - 2.6
            val p = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(tip.x + (sin(left) * 14).toFloat(), tip.y - (cos(left) * 14).toFloat())
                lineTo(tip.x + (sin(right) * 14).toFloat(), tip.y - (cos(right) * 14).toFloat()); close()
            }
            drawPath(p, Color.White)
            drawCircle(Color(0xFF151B2C), r * .36f, c)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$value", style = WType.small)
            Text(unit, style = WType.label.copy(color = WColors.TextDim))
        }
        listOf("N" to Alignment.TopCenter, "O" to Alignment.CenterEnd, "S" to Alignment.BottomCenter, "W" to Alignment.CenterStart).forEach { (t, a) ->
            Text(t, style = WType.label.copy(color = WColors.TextDim), modifier = Modifier.align(a).padding(1.dp))
        }
    }
}

// ------------------------------------------------------------ Windkarte (einfache Strömung, volle Karte in 0.4.0)

@Composable
fun WindMapContent(d: TileData) {
    if (LiveMaps.enabled) {
        val c0 = d.f.current
        de.schee.wetter.ui.radar.MiniRadar(de.schee.wetter.ui.radar.RadarPlace("", d.lat, d.lon, d.units.t(c0.temp)), wind = true,
            Modifier.fillMaxWidth().aspectRatio(1.35f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0A2340)))
        Text("Wind aus ${Units.compass(c0.windDir)} mit ${d.units.w(c0.wind)}, Böen bis ${d.units.w(c0.gusts)} · Tippen für Vollbild", style = WType.bodyDim)
        return
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var t by remember { mutableFloatStateOf(0f) }
    val animate = de.schee.wetter.ui.sky.LocalAnimate.current
    LaunchedEffect(lifecycle) {
        if (!animate) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val s = withFrameNanos { it }
            while (isActive) withFrameNanos { now -> t = (now - s) / 1e9f }
        }
    }
    val parts = remember { List(160) { floatArrayOf(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) } }
    val c = d.f.current
    Box(Modifier.fillMaxWidth().aspectRatio(1.35f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0A2340))) {
        MapBase(d.maps)
        Canvas(Modifier.fillMaxSize()) {
            val to = (c.windDir + 180) * PI / 180
            val dx = sin(to).toFloat(); val dy = -cos(to).toFloat()
            val speed = (0.02f + c.wind.toFloat() / 400f)
            parts.forEach { p ->
                val life = (p[2] + t * .25f) % 1f
                val x = ((p[0] + dx * speed * t * 6f) % 1f + 1f) % 1f
                val y = ((p[1] + dy * speed * t * 6f) % 1f + 1f) % 1f
                val len = 10f + c.wind.toFloat() * .8f
                val a = (sin(life * PI).toFloat() * .55f).coerceAtLeast(0f)
                drawLine(Color(0xFFBFE6FF).copy(alpha = a), Offset(x * size.width, y * size.height),
                    Offset(x * size.width - dx * len, y * size.height - dy * len), strokeWidth = 2f, cap = StrokeCap.Round)
            }
        }
        LocationDot(Modifier.align(Alignment.Center), d.units.wNum(c.wind).toString())
    }
    Text("Wind aus ${Units.compass(c.windDir)} mit ${d.units.w(c.wind)}, Böen bis ${d.units.w(c.gusts)} · Tippen für die große Karte", style = WType.bodyDim)
}

// ------------------------------------------------------------ Halbe Kacheln

@Composable
fun BigValue(value: String, sub: String? = null) {
    Text(value, style = WType.value)
    sub?.let { Text(it, style = WType.small) }
}

@Composable
fun FeelsContent(d: TileData) {
    val c = d.f.current
    BigValue(d.units.t(c.feels))
    Spacer(Modifier.height(18.dp))
    val diff = c.feels - c.temp
    Text(when {
        abs(diff) < 1.5 -> "Ähnelt der aktuellen Temperatur."
        diff < 0 && c.wind > 10 -> "Der Wind lässt es kühler wirken."
        diff < 0 -> "Fühlt sich kühler an."
        c.humidity > 70 -> "Die Feuchtigkeit lässt es wärmer wirken."
        else -> "Fühlt sich wärmer an."
    }, style = WType.bodyDim)
}

@Composable
fun HumidityContent(d: TileData) {
    val c = d.f.current
    BigValue("${c.humidity} %")
    Spacer(Modifier.height(18.dp))
    Text("Der Taupunkt liegt derzeit bei ${d.units.t(c.dewPoint)}.", style = WType.bodyDim)
}

@Composable
fun PrecipTodayContent(d: TileData) {
    val today = d.now.toLocalDate()
    val day = d.f.days.firstOrNull { it.date == today }
    BigValue(d.units.mm(day?.precipSum ?: 0.0), "Heute")
    Spacer(Modifier.height(10.dp))
    val next = d.f.days.firstOrNull { it.date.isAfter(today) && it.precipSum >= 1.0 }
    Text(if (next == null) "In den nächsten Tagen kein nennenswerter Regen." else "${d.units.mm(next.precipSum)} am ${Timeline.dayLong(next.date, today).let { if (it == "Morgen") "morgigen Tag" else it }} erwartet.", style = WType.bodyDim)
}

@Composable
fun VisibilityContent(d: TileData) {
    val v = d.f.current.visibilityM
    if (v == null) { BigValue("–"); return }
    val km = v / 1000
    BigValue(if (km < 1) String.format(java.util.Locale.GERMANY, "%.1f km", km) else "${km.roundToInt()} km")
    Spacer(Modifier.height(18.dp))
    Text(when {
        km < 0.2 -> "Sehr dichter Nebel."
        km < 1 -> "Nebel – eingeschränkte Sicht."
        km < 5 -> "Dunstig, mäßige Sicht."
        km < 15 -> "Gute Sicht."
        else -> "Absolut klare Sicht."
    }, style = WType.bodyDim)
}

@Composable
fun AverageContent(d: TileData) {
    val today = d.f.days.firstOrNull { it.date == d.now.toLocalDate() }
    val normal = d.normalMax
    if (today == null || normal == null) { BigValue("–"); Text("Langjähriges Mittel wird geladen …", style = WType.bodyDim); return }
    val diff = d.units.tNum(today.tMax) - d.units.tNum(normal)
    BigValue((if (diff > 0) "+" else "") + "$diff°")
    Text(when {
        diff > 0 -> "über dem durchschnittlichen Tageshöchstwert"
        diff < 0 -> "unter dem durchschnittlichen Tageshöchstwert"
        else -> "genau im Durchschnitt"
    }, style = WType.small)
    Spacer(Modifier.height(6.dp))
    InfoRow("Heute", "H: ${d.units.t(today.tMax)}")
    InfoRow("Durchschnitt", "H: ${d.units.t(normal)}")
}

@Composable
fun SunContent(d: TileData) {
    val today = d.f.days.firstOrNull { it.date == d.now.toLocalDate() } ?: return
    val rise = today.sunrise; val set = today.sunset
    if (rise == null || set == null) return
    val tomorrowRise = d.f.days.firstOrNull { it.date == d.now.toLocalDate().plusDays(1) }?.sunrise
    val (big, other) = when {
        d.now.isBefore(rise) -> Timeline.hhmm(rise) to "Sonnenuntergang: ${Timeline.hhmm(set)}"
        d.now.isBefore(set) -> Timeline.hhmm(set) to "Sonnenaufgang: ${Timeline.hhmm(rise)}"
        else -> (tomorrowRise?.let(Timeline::hhmm) ?: "–") to "Sonnenuntergang: ${Timeline.hhmm(set)}"
    }
    BigValue(big)
    // Sonnenbogen über 24 h: über dem Horizont zwischen Auf- und Untergang, darunter nachts
    val riseMin = rise.hour * 60 + rise.minute
    val setMin = set.hour * 60 + set.minute
    val nowMin = d.now.hour * 60 + d.now.minute
    Canvas(Modifier.fillMaxWidth().height(48.dp)) {
        val w = size.width; val h = size.height
        val horizon = h * .66f
        fun alt(min: Int): Float = SunArc.altitude(min, riseMin, setMin)
        fun y(min: Int) = horizon - alt(min) * (if (alt(min) >= 0) horizon * .9f else (h - horizon) * .9f)
        val path = Path()
        for (m in 0..1440 step 12) { val x = w * m / 1440f; if (m == 0) path.moveTo(x, y(m)) else path.lineTo(x, y(m)) }
        drawPath(path, Brush.verticalGradient(listOf(Color(0xFFFFD27A), Color(0xFF6E7EA6)), startY = 0f, endY = h), style = Stroke(width = 3f))
        drawLine(Color.White.copy(alpha = .35f), Offset(0f, horizon), Offset(w, horizon), strokeWidth = 1.5f)
        drawCircle(Color.White, 6f, Offset(w * nowMin / 1440f, y(nowMin)))
    }
    Text(other, style = WType.bodyDim)
}

@Composable
fun MoonContent(d: TileData) {
    val zone = ZoneId.of(d.f.timezone)
    val nowZ = d.now.atZone(zone)
    val m = Astro.moon(nowZ.toInstant().toEpochMilli())
    val rise = remember(d.lat, d.lon, d.now.toLocalDate()) { Astro.nextMoonrise(nowZ, d.lat, d.lon) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoRow("Beleuchtung", "${(m.illumination * 100).roundToInt()} %")
            HorizontalDivider(color = WColors.CardBorder)
            InfoRow("Nächster Mondaufgang", rise?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "–")
            HorizontalDivider(color = WColors.CardBorder)
            InfoRow("Nächster Vollmond", if (m.daysToFull == 0) "heute" else "in ${m.daysToFull} Tg.")
        }
        Spacer(Modifier.width(16.dp))
        MoonDisc(m, Modifier.size(78.dp))
    }
}

@Composable
private fun MoonDisc(m: Astro.MoonInfo, modifier: Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2; val c = Offset(size.width / 2, size.height / 2)
        drawCircle(Color(0xFF2A3044), r, c)
        // Beleuchteter Teil: Halbkreis + Ellipse (Terminator)
        val k = (cos(2 * PI * m.ageDays / Astro.SYNODIC)).toFloat() // 1 = neu, -1 = voll
        val lit = Path()
        val rightSide = m.waxing
        val steps = 48
        for (i in 0..steps) {
            val a = -PI / 2 + PI * i / steps
            val x = (cos(a) * r).toFloat() * (if (rightSide) 1 else -1)
            val y = (sin(a) * r).toFloat()
            if (i == 0) lit.moveTo(c.x + x, c.y + y) else lit.lineTo(c.x + x, c.y + y)
        }
        for (i in steps downTo 0) {
            val a = -PI / 2 + PI * i / steps
            val x = (cos(a) * r * k).toFloat() * (if (rightSide) 1 else -1)
            val y = (sin(a) * r).toFloat()
            lit.lineTo(c.x + x, c.y + y)
        }
        lit.close()
        drawPath(lit, Color(0xFFE9ECF5))
    }
}

/** Sonnenhöhe als -1…1 über den Tag (Minuten seit Mitternacht), 0 = Horizont bei Auf-/Untergang. */
object SunArc {
    fun altitude(min: Int, rise: Int, set: Int): Float {
        val dayLen = (set - rise).coerceAtLeast(1)
        return if (min in rise..set) sin(PI * (min - rise) / dayLen).toFloat()
        else {
            val nightLen = 1440 - dayLen
            val since = if (min > set) min - set else min + 1440 - set
            -sin(PI * since / nightLen).toFloat() * .6f
        }
    }
}

/** Überschrift der Sonnen-Kachel nach dem nächsten Ereignis, wie bei Apple. */
fun sunTitle(d: TileData): String {
    val today = d.f.days.firstOrNull { it.date == d.now.toLocalDate() }
    val rise = today?.sunrise; val set = today?.sunset
    return when {
        rise == null || set == null -> "Sonne"
        d.now.isBefore(rise) || !d.now.isBefore(set) -> "Sonnenaufgang"
        else -> "Sonnenuntergang"
    }
}

/** Überschrift der Mond-Kachel = Phasenname, wie bei Apple. */
fun moonTitle(d: TileData): String = Astro.moon(d.now.atZone(ZoneId.of(d.f.timezone)).toInstant().toEpochMilli()).phaseName

/** Live-Karten (MapLibre) in den Kacheln – nicht in den Testbildern am PC (Robolectric kann keine Karte zeichnen). */
object LiveMaps {
    val enabled: Boolean get() = android.os.Build.FINGERPRINT != "robolectric"
}
