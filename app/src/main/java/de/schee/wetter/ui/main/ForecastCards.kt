package de.schee.wetter.ui.main

import de.schee.wetter.ui.pressable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.schee.wetter.data.Day
import de.schee.wetter.data.Forecast
import de.schee.wetter.data.Units
import de.schee.wetter.data.WeatherCodes
import de.schee.wetter.log.AppLog
import de.schee.wetter.ui.Tile
import de.schee.wetter.ui.icons.IconKind
import de.schee.wetter.ui.icons.WeatherIcon
import de.schee.wetter.ui.theme.WColors
import de.schee.wetter.ui.theme.WType
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.max


enum class Mode(val title: String, val subtitle: String) {
    TEMP("Wetterlage", "Temperatur"),
    RAIN("Niederschlag", "Menge · Regenwahrscheinlichkeit"),
    WIND("Wind", "Geschwindigkeit · Böen"),
}

@Composable
fun HourlyCard(f: Forecast, now: LocalDateTime, mode: Mode, units: Units, onMode: (Mode) -> Unit) {
    val items = Timeline.hourlyItems(f.hours, f.days, now)
    Tile(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(mode.title, style = WType.small)
                val sub = when (mode) { Mode.TEMP -> "Temperatur (${if (units.temp == "°F") "°F" else "°C"})"; Mode.WIND -> "Geschwindigkeit (${units.windLabel}) · Böen"; else -> mode.subtitle }
                Text(sub, style = WType.bodyDim.copy(fontSize = WType.label.fontSize))
            }
            ModeSwitch(mode, onMode)
        }
        HorizontalDivider(color = WColors.CardBorder)
        val maxRain = max(1.0, items.filterIsInstance<HourItem.H>().maxOfOrNull { it.hour.precip } ?: 0.0)
        // Beim Wischen durch die Stunden: feines Ticken je Stunde (Premium-Haptik)
        val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
        val haptic = de.schee.wetter.ui.rememberHaptics()
        androidx.compose.runtime.LaunchedEffect(rowState) {
            var last = -1
            androidx.compose.runtime.snapshotFlow { rowState.firstVisibleItemIndex }.collect { i ->
                if (last >= 0 && i != last && rowState.isScrollInProgress) haptic(de.schee.wetter.ui.Haptics.Kind.TICK)
                last = i
            }
        }
        LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(items, key = { it.time.toString() + (it is HourItem.Sun) }) { item ->
                Column(
                    if (item is HourItem.Sun) Modifier.widthIn(min = 48.dp) else Modifier.width(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (item) {
                        is HourItem.Sun -> {
                            Text(Timeline.hhmm(item.time), style = WType.small)
                            WeatherIcon(if (item.rise) IconKind.SUNRISE else IconKind.SUNSET)
                            Text(if (item.rise) "Sonnenaufgang" else "Sonnenuntergang", style = WType.small, maxLines = 1)
                        }
                        is HourItem.H -> {
                            val h = item.hour
                            Text(if (item.isNow) "Jetzt" else "%02d Uhr".format(h.time.hour), style = WType.small, maxLines = 1)
                            when (mode) {
                                Mode.TEMP -> {
                                    WeatherIcon(WeatherCodes.icon(h.code, h.isDay))
                                    Text(units.t(h.temp), style = WType.small)
                                }
                                Mode.RAIN -> {
                                    Box(Modifier.size(28.dp, 34.dp), contentAlignment = Alignment.BottomCenter) {
                                        val frac = (h.precip / maxRain).toFloat().coerceIn(0f, 1f)
                                        Box(Modifier.width(10.dp).height((3 + 31 * frac).dp).clip(RoundedCornerShape(3.dp))
                                            .background(if (h.precip > 0.05) WColors.Rain else WColors.Glass))
                                    }
                                    Text("${h.precipProb ?: 0} %", style = WType.small.copy(color = if ((h.precipProb ?: 0) >= 30) WColors.Rain else WColors.TextDim))
                                }
                                Mode.WIND -> {
                                    WindArrow(h.windDir ?: 0, Modifier.size(22.dp))
                                    Text("${units.wNum(h.wind)}", style = WType.small)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ModeSwitch(mode: Mode, onMode: (Mode) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(WColors.Glass).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Mode.entries.forEach { m ->
            Box(
                Modifier.pressable(scale = .9f, haptic = de.schee.wetter.ui.Haptics.Kind.SNAP) { if (m != mode) { AppLog.tap("Wetterlage → Umschalter „${m.title}“"); onMode(m) } }
                    .size(38.dp, 28.dp).clip(RoundedCornerShape(50))
                    .background(if (m == mode) Color.White.copy(alpha = .22f) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                when (m) {
                    Mode.TEMP -> WeatherIcon(IconKind.SUN_CLOUD, size = 18.dp)
                    Mode.RAIN -> Canvas(Modifier.size(14.dp)) {
                        val p = Path().apply {
                            moveTo(size.width / 2, 0f)
                            cubicTo(size.width * 1.05f, size.height * .55f, size.width * .95f, size.height, size.width / 2, size.height)
                            cubicTo(size.width * .05f, size.height, -size.width * .05f, size.height * .55f, size.width / 2, 0f)
                        }
                        drawPath(p, WColors.Text)
                    }
                    Mode.WIND -> WindGlyph(Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun WindGlyph(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val s = androidx.compose.ui.graphics.drawscope.Stroke(width = w * .1f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawPath(Path().apply { moveTo(0f, h * .35f); lineTo(w * .7f, h * .35f); cubicTo(w * 1.05f, h * .35f, w * 1.0f, 0f, w * .72f, h * .05f) }, WColors.Text, style = s)
        drawPath(Path().apply { moveTo(0f, h * .65f); lineTo(w * .55f, h * .65f); cubicTo(w * .9f, h * .65f, w * .85f, h, w * .6f, h * .95f) }, WColors.Text, style = s)
    }
}

/** Pfeil zeigt, wohin der Wind weht (Richtung = woher er kommt + 180°). */
@Composable
fun WindArrow(fromDeg: Int, modifier: Modifier) {
    Canvas(modifier.rotate((fromDeg + 180).toFloat())) {
        val w = size.width; val h = size.height
        drawLine(WColors.Text, Offset(w / 2, h * .85f), Offset(w / 2, h * .2f), strokeWidth = w * .09f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawPath(Path().apply { moveTo(w / 2, h * .08f); lineTo(w * .28f, h * .38f); lineTo(w * .72f, h * .38f); close() }, WColors.Text)
    }
}

@Composable
fun DailyCard(f: Forecast, today: LocalDate, mode: Mode, units: Units, onDay: (Day) -> Unit) {
    val days = f.days.filter { !it.date.isBefore(today) }.take(10)
    if (days.isEmpty()) return
    val gMin = days.minOf { it.tMin }; val gMax = days.maxOf { it.tMax }
    val maxWind = days.maxOf { it.gustMax ?: it.windMax }
    val maxRain = max(1.0, days.maxOf { it.precipSum })
    Tile("10-Tage-Vorhersage") {
        days.forEachIndexed { i, d ->
            if (i > 0) HorizontalDivider(color = WColors.CardBorder)
            Row(
                Modifier.pressable(scale = 1f, dim = .55f) { AppLog.tap("10-Tage-Liste → ${Timeline.dayLong(d.date, today)} geöffnet"); onDay(d) }.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.width(58.dp)) {
                    Text(Timeline.dayLabel(d.date, today), style = WType.body)
                    if (d.uncertain) Text("unsicher", style = WType.label.copy(color = WColors.Warn, fontSize = WType.label.fontSize * .85f))
                }
                Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    WeatherIcon(WeatherCodes.icon(d.code, true), size = 24.dp)
                    val p = d.precipProb ?: 0
                    if (p >= 20 && mode != Mode.RAIN) Text("$p %", style = WType.label.copy(color = WColors.Rain))
                }
                Spacer(Modifier.width(8.dp))
                when (mode) {
                    Mode.TEMP -> {
                        Text(units.t(d.tMin), style = WType.body.copy(color = WColors.Label), modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
                        TempBar(d, gMin, gMax, if (d.date == today) f.current.temp else null, Modifier.weight(1f).padding(horizontal = 10.dp))
                        Text(units.t(d.tMax), style = WType.body, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
                    }
                    Mode.RAIN -> {
                        RainBar(d.precipSum / maxRain, Modifier.weight(1f).padding(end = 10.dp))
                        Text(units.mm(d.precipSum), style = WType.body, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
                        Text("${d.precipProb ?: 0} %", style = WType.body.copy(color = if ((d.precipProb ?: 0) >= 30) WColors.Rain else WColors.Label), modifier = Modifier.width(52.dp), textAlign = TextAlign.End)
                    }
                    Mode.WIND -> {
                        RainBar((d.windMax / max(1.0, maxWind)), Modifier.weight(1f).padding(end = 10.dp), color = Color(0xFF4FD1C5))
                        Text("${units.wNum(d.windMax)}–${units.wNum(d.gustMax ?: d.windMax)} ${units.windLabel}", style = WType.body, modifier = Modifier.width(118.dp), textAlign = TextAlign.End)
                    }
                }
            }
        }
    }
}

@Composable
private fun TempBar(d: Day, gMin: Double, gMax: Double, now: Double?, modifier: Modifier) {
    Canvas(modifier.height(6.dp)) {
        val range = (gMax - gMin).coerceAtLeast(1.0)
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Color.White.copy(alpha = .12f), cornerRadius = r)
        val x0 = ((d.tMin - gMin) / range * size.width).toFloat()
        val x1 = ((d.tMax - gMin) / range * size.width).toFloat().coerceAtLeast(x0 + size.height)
        drawRoundRect(
            Brush.horizontalGradient(listOf(Timeline.tempColor(d.tMin), Timeline.tempColor(d.tMax)), startX = x0, endX = x1),
            topLeft = Offset(x0, 0f), size = Size(x1 - x0, size.height), cornerRadius = r,
        )
        if (now != null) {
            val xn = ((now - gMin) / range * size.width).toFloat().coerceIn(x0, x1)
            drawCircle(Color.Black.copy(alpha = .5f), size.height * .95f, Offset(xn, size.height / 2))
            drawCircle(Color.White, size.height * .7f, Offset(xn, size.height / 2))
        }
    }
}

@Composable
private fun RainBar(frac: Double, modifier: Modifier, color: Color = WColors.Rain) {
    Canvas(modifier.height(6.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Color.White.copy(alpha = .12f), cornerRadius = r)
        val w = (frac.coerceIn(0.0, 1.0) * size.width).toFloat()
        if (w > 0.5f) drawRoundRect(color, size = Size(w.coerceAtLeast(size.height), size.height), cornerRadius = r)
    }
}

/** „Wie sicher ist die Vorhersage?“ – drei Balken (gering/mittel/hoch) und was die Modelle sagen. */
@Composable
fun ConfidenceBlock(c: de.schee.wetter.data.Confidence, units: Units) {
    val color = when (c.level) { 3 -> WColors.Good; 2 -> Color(0xFFFFD60A); else -> WColors.Warn }
    Column(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .06f)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("SICHERHEIT DER VORHERSAGE", style = WType.label.copy(color = WColors.Label))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(c.label.replaceFirstChar { it.uppercase() }, style = WType.condition.copy(color = color))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 1..3) Box(Modifier.width(22.dp).height(6.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                    .background(if (i <= c.level) color else Color.White.copy(alpha = .14f)))
            }
        }
        val dry = c.models - c.wetModels
        val rain = when {
            c.wetModels == 0 -> "Alle ${c.models} Wettermodelle rechnen mit einem trockenen Tag."
            dry == 0 -> "Alle ${c.models} Wettermodelle rechnen mit Regen."
            else -> "${c.wetModels} von ${c.models} Wettermodellen rechnen mit Regen."
        }
        val lo = units.tNum(c.tMaxLow); val hi = units.tNum(c.tMaxHigh)
        Text(rain + "\n" + (if (lo == hi) "Höchstwert überall $lo°." else "Höchstwert je nach Modell $lo° bis $hi°."), style = WType.bodyDim)
        Text("Verglichen: ICON (DWD), ECMWF, GFS (USA), UKMO (Großbritannien), Météo-France, GEM (Kanada)", style = WType.label.copy(color = WColors.Label))
    }
}

/** Inhalt des Tages-Fensters (Tippen auf einen Tag). */
@Composable
fun DayDetails(f: Forecast, d: Day, today: LocalDate, units: Units) {
    val hours = f.hours.filter { it.time.toLocalDate() == d.date }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(Timeline.dayLong(d.date, today) + ", " + "%02d.%02d.".format(d.date.dayOfMonth, d.date.monthValue), style = WType.title)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WeatherIcon(WeatherCodes.icon(d.code, true), size = 40.dp)
            Column {
                Text(WeatherCodes.text(d.code, true), style = WType.condition)
                Text("H: ${units.t(d.tMax)}  T: ${units.t(d.tMin)}", style = WType.body)
            }
        }
        Text(
            buildString {
                append("Niederschlag ${units.mm(d.precipSum)}")
                d.precipProb?.let { append(" · Wahrscheinlichkeit $it %") }
                append("\nWind bis ${units.w(d.windMax)}")
                d.gustMax?.let { append(", Böen bis ${units.w(it)}") }
                if (d.sunrise != null && d.sunset != null) append("\nSonne ${Timeline.hhmm(d.sunrise)} – ${Timeline.hhmm(d.sunset)}")
                append("\nQuelle: Modell ${if (d.model == "ICON") "ICON-D2 (DWD)" else "ECMWF"}")
                if (d.uncertain && f.confidence[d.date] == null) append("\nDie Wettermodelle sind sich für diesen Tag uneinig – die Vorhersage ist unsicher.")
            },
            style = WType.bodyDim,
        )
        f.confidence[d.date]?.let { ConfidenceBlock(it, units) }
        if (hours.isNotEmpty()) {
            HorizontalDivider(color = WColors.CardBorder)
            hours.forEach { h ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("%02d Uhr".format(h.time.hour), style = WType.body, modifier = Modifier.width(64.dp))
                    WeatherIcon(WeatherCodes.icon(h.code, h.isDay), size = 22.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(units.t(h.temp), style = WType.body, modifier = Modifier.width(48.dp))
                    Text("${h.precipProb ?: 0} %", style = WType.body.copy(color = if ((h.precipProb ?: 0) >= 30) WColors.Rain else WColors.Label), modifier = Modifier.width(56.dp))
                    Text(units.w(h.wind), style = WType.bodyDim)
                }
            }
        }
    }
}
