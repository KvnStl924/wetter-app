package de.schee.wetter.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.schee.wetter.data.Confidence
import de.schee.wetter.data.Day
import de.schee.wetter.data.Forecast
import de.schee.wetter.data.Hour
import de.schee.wetter.data.Units
import de.schee.wetter.data.WeatherCodes
import de.schee.wetter.ui.icons.IconKind
import de.schee.wetter.ui.icons.WeatherIcon
import de.schee.wetter.ui.theme.Inter
import de.schee.wetter.ui.theme.WColors
import de.schee.wetter.ui.theme.WType
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Tages-Fenster (Tippen auf einen Tag der 10-Tage-Liste), neu gestaltet in 3.1.0 – Kevin am 10.11.2026:
 * „sieht billig aus, überhaupt nicht Apple-like“. Vorher: Fließtext und eine nackte Stundenliste.
 * Jetzt wie bei iOS in Gruppen: großer Kopf, Verlauf als Kurve, Wertekacheln, Sicherheit, Stunden.
 * Die Angaben selbst sind dieselben geblieben.
 */

/** Fläche einer Gruppe im Tages-Fenster (heller als der Fensterhintergrund, ohne Rand – iOS „grouped“). */
private val GroupBg = Color.White.copy(alpha = .06f)
private val Hairline = Color.White.copy(alpha = .08f)
private val GroupShape = RoundedCornerShape(18.dp)

private val caption = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.7.sp, color = WColors.Label)
private val statValue = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 22.sp, color = WColors.Text)
private val statSub = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, color = WColors.TextDim)
private val axis = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.sp, color = WColors.Label)
private val mark = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = WColors.Text)

@Composable
private fun Group(title: String?, modifier: Modifier = Modifier, trailing: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(GroupShape).background(GroupBg).padding(horizontal = 16.dp, vertical = 14.dp)) {
        if (title != null) {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title.uppercase(), style = caption, modifier = Modifier.weight(1f))
                if (trailing != null) Text(trailing, style = caption.copy(color = WColors.TextDim, letterSpacing = 0.sp, fontSize = 12.sp))
            }
        }
        content()
    }
}

private val longDate = DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN)
private val shortDate = DateTimeFormatter.ofPattern("d. MMMM", Locale.GERMAN)

/** Inhalt des Tages-Fensters. [now] = aktuelle Ortszeit (markiert bei „Heute“ die laufende Stunde). */
@Composable
fun DayDetails(f: Forecast, d: Day, today: LocalDate, units: Units, now: LocalDateTime? = null) {
    val hours = f.hours.filter { it.time.toLocalDate() == d.date }
    val nowIndex = if (now != null && d.date == today) hours.indexOfFirst { it.time.hour == now.hour } else -1
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ---- Kopf: Tag, große Temperatur, Wetterlage
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                val name = Timeline.dayLong(d.date, today)
                Text(name, style = WType.title)
                // „Heute“/„Morgen“ → Wochentag dazu; sonst steht der Wochentag schon in der Überschrift
                Text(if (name == "Heute" || name == "Morgen") longDate.format(d.date) else shortDate.format(d.date), style = WType.bodyDim.copy(fontSize = 15.sp))
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(units.t(d.tMax), style = WType.bigTemp.copy(fontSize = 64.sp, fontWeight = FontWeight.Light))
                    Text(units.t(d.tMin), style = WType.value.copy(fontSize = 30.sp, color = WColors.Label), modifier = Modifier.padding(start = 10.dp, bottom = 12.dp))
                }
                Text(WeatherCodes.text(d.code, true), style = WType.condition)
            }
            WeatherIcon(WeatherCodes.icon(d.code, true), Modifier.padding(top = 6.dp, end = 2.dp), size = 76.dp)
        }

        // ---- Verlauf: Temperatur als Kurve, darunter die Regenwahrscheinlichkeit je Stunde
        if (hours.size >= 3) Group("Verlauf") {
            // Wetter-Symbole alle 3 Stunden über der Kurve
            Row(Modifier.fillMaxWidth()) {
                hours.chunked(3).forEach { c ->
                    val h = c[c.size / 2]
                    Box(Modifier.weight(c.size.toFloat()), contentAlignment = Alignment.Center) { WeatherIcon(WeatherCodes.icon(h.code, h.isDay), size = 22.dp) }
                }
            }
            TempCurve(hours, units, nowIndex)
            Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp).height(1.dp).background(Hairline))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("REGENWAHRSCHEINLICHKEIT", style = caption, modifier = Modifier.weight(1f))
                Text("bis ${hours.maxOf { it.precipProb ?: 0 }} %", style = caption.copy(color = WColors.Rain, letterSpacing = 0.sp, fontSize = 12.sp))
            }
            RainBars(hours)
        }

        // ---- Werte des Tages
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("Niederschlag", units.mm(d.precipSum), d.precipProb?.let { "Wahrscheinlichkeit $it %" }, Modifier.weight(1f).fillMaxHeight()) { DropGlyph(WColors.Rain) }
            Stat("Wind", "bis ${units.w(d.windMax)}", d.gustMax?.let { "Böen bis ${units.w(it)}" }, Modifier.weight(1f).fillMaxHeight()) { WindGlyph(Modifier.size(13.dp)) }
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val rise = d.sunrise; val set = d.sunset
            if (rise != null && set != null) {
                val m = Duration.between(rise, set).toMinutes()
                Stat("Sonne", "${Timeline.hhmm(rise)} – ${Timeline.hhmm(set)}", "${m / 60} Std. ${m % 60} Min. Tageslicht", Modifier.weight(1f).fillMaxHeight()) { WeatherIcon(IconKind.SUNRISE, size = 15.dp) }
            }
            val icon = d.model == "ICON"
            Stat("Datenquelle", if (icon) "ICON-D2" else "ECMWF", if (icon) "Deutscher Wetterdienst" else "Europäisches Modell", Modifier.weight(1f).fillMaxHeight()) {}
        }

        // ---- Sicherheit der Vorhersage
        val conf = f.confidence[d.date]
        if (conf != null) ConfidenceBlock(conf, units)
        else if (d.uncertain) Group("Sicherheit der Vorhersage") {
            Text("Die Wettermodelle sind sich für diesen Tag uneinig – die Vorhersage ist unsicher.", style = statSub.copy(color = WColors.Warn))
        }

        // ---- Stunden
        if (hours.isNotEmpty()) Group("Stunden") {
            val lo = hours.minOf { it.temp }; val hi = hours.maxOf { it.temp }
            hours.forEachIndexed { i, h ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
                HourRow(h, units, lo, hi, isNow = i == nowIndex)
            }
        }
    }
}

/** Temperaturkurve des Tages: weiche Linie in Temperaturfarben, Höchst- und Tiefstwert markiert, Achse alle 6 Stunden. */
@Composable
private fun TempCurve(hours: List<Hour>, units: Units, nowIndex: Int) {
    val measurer = rememberTextMeasurer()
    val iMax = hours.indices.maxBy { hours[it].temp }
    val iMin = hours.indices.minBy { hours[it].temp }
    Canvas(Modifier.fillMaxWidth().padding(top = 4.dp).height(128.dp)) {
        val n = hours.size
        val slot = size.width / n
        val top = 26.dp.toPx(); val bottom = size.height - 24.dp.toPx()
        val tMin = hours[iMin].temp; val tMax = hours[iMax].temp
        // flacher Tag: nicht auf volle Höhe aufblasen (sonst sähe 1° Unterschied wie ein Berg aus)
        val range = (tMax - tMin).coerceAtLeast(4.0); val mid = (tMax + tMin) / 2
        fun x(i: Int) = slot * (i + .5f)
        fun y(t: Double) = ((top + bottom) / 2 - ((t - mid) / range).toFloat() * (bottom - top))
        // Achse: feine Linie und Uhrzeit alle 6 Stunden
        hours.forEachIndexed { i, h ->
            if (h.time.hour % 6 != 0) return@forEachIndexed
            drawLine(Hairline, Offset(x(i), top - 14.dp.toPx()), Offset(x(i), bottom + 6.dp.toPx()), 1.dp.toPx())
            val l = measurer.measure("%02d Uhr".format(h.time.hour), axis)
            drawText(l, topLeft = Offset((x(i) - l.size.width / 2f).coerceIn(0f, size.width - l.size.width), size.height - l.size.height))
        }
        val pts = hours.mapIndexed { i, h -> Offset(x(i), y(h.temp)) }
        val line = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 0 until pts.size - 1) {
                val p0 = pts[maxOf(i - 1, 0)]; val p1 = pts[i]; val p2 = pts[i + 1]; val p3 = pts[minOf(i + 2, pts.size - 1)]
                cubicTo(p1.x + (p2.x - p0.x) / 6, p1.y + (p2.y - p0.y) / 6, p2.x - (p3.x - p1.x) / 6, p2.y - (p3.y - p1.y) / 6, p2.x, p2.y)
            }
        }
        val floor = bottom + 6.dp.toPx()
        val area = Path().apply { addPath(line); lineTo(pts.last().x, floor); lineTo(pts[0].x, floor); close() }
        val colors = hours.map { Timeline.tempColor(it.temp) }
        val brush = if (colors.size > 1) Brush.horizontalGradient(colors, startX = pts.first().x, endX = pts.last().x) else Brush.horizontalGradient(listOf(colors[0], colors[0]))
        drawPath(area, Brush.verticalGradient(listOf(Timeline.tempColor(mid).copy(alpha = .26f), Color.Transparent), startY = top, endY = floor))
        drawPath(line, brush, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        // laufende Stunde (nur „Heute“)
        if (nowIndex in pts.indices) {
            val p = pts[nowIndex]
            drawLine(Color.White.copy(alpha = .35f), Offset(p.x, p.y), Offset(p.x, floor), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
            drawCircle(Color.White, 5.dp.toPx(), p)
            drawCircle(Color(0xFF141A2B), 2.5.dp.toPx(), p)
        }
        // Höchst- und Tiefstwert: Punkt mit Zahl darüber
        fun point(i: Int) {
            val p = pts[i]
            drawCircle(Color(0xFF141A2B), 5.5.dp.toPx(), p)
            drawCircle(colors[i], 4.dp.toPx(), p)
            val l = measurer.measure(units.t(hours[i].temp), mark)
            drawText(l, topLeft = Offset((p.x - l.size.width / 2f).coerceIn(0f, size.width - l.size.width), p.y - l.size.height - 7.dp.toPx()))
        }
        point(iMax); if (iMin != iMax) point(iMin)
    }
}

/** Regenwahrscheinlichkeit je Stunde als Balken (gleiche Spalten wie die Kurve darüber). */
@Composable
private fun RainBars(hours: List<Hour>) {
    Canvas(Modifier.fillMaxWidth().padding(top = 8.dp).height(34.dp)) {
        val slot = size.width / hours.size
        val w = (slot * .5f).coerceAtMost(8.dp.toPx())
        hours.forEachIndexed { i, h ->
            val p = (h.precipProb ?: 0).coerceIn(0, 100)
            val bar = (size.height * p / 100f).coerceAtLeast(2.dp.toPx())
            drawRoundRect(
                if (p >= 30) WColors.Rain else if (p > 0) WColors.Rain.copy(alpha = .4f) else Color.White.copy(alpha = .12f),
                topLeft = Offset(slot * (i + .5f) - w / 2, size.height - bar), size = Size(w, bar), cornerRadius = CornerRadius(w / 2),
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, sub: String?, modifier: Modifier, glyph: @Composable () -> Unit) {
    Column(modifier.clip(GroupShape).background(GroupBg).padding(horizontal = 14.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            glyph()
            Text(label.uppercase(), style = caption, maxLines = 1)
        }
        Text(value, style = statValue, maxLines = 1)
        if (sub != null) Text(sub, style = statSub, maxLines = 2)
    }
}

@Composable
private fun DropGlyph(color: Color, size: androidx.compose.ui.unit.Dp = 12.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        drawPath(Path().apply {
            moveTo(w / 2, 0f)
            cubicTo(w * 1.05f, h * .55f, w * .95f, h, w / 2, h)
            cubicTo(w * .05f, h, -w * .05f, h * .55f, w / 2, 0f)
        }, color)
    }
}

/** Eine Stunde: Uhrzeit, Symbol, Regen, Lage der Temperatur im Tagesverlauf, Temperatur, Wind. */
@Composable
private fun HourRow(h: Hour, units: Units, lo: Double, hi: Double, isNow: Boolean) {
    Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (isNow) "Jetzt" else "%02d Uhr".format(h.time.hour), style = if (isNow) WType.small.copy(fontSize = 16.sp) else WType.body.copy(color = WColors.TextDim), modifier = Modifier.width(60.dp), maxLines = 1)
        Box(Modifier.width(34.dp), contentAlignment = Alignment.Center) { WeatherIcon(WeatherCodes.icon(h.code, h.isDay), size = 24.dp) }
        val p = h.precipProb ?: 0
        Row(Modifier.width(58.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (p > 0) {
                DropGlyph(if (p >= 30) WColors.Rain else WColors.Label, 9.dp)
                Text("$p %", style = statSub.copy(fontWeight = FontWeight.Medium, color = if (p >= 30) WColors.Rain else WColors.Label), maxLines = 1)
            }
        }
        // Wo liegt diese Stunde zwischen Tiefst- und Höchstwert des Tages? (wie die Balken der 10-Tage-Liste)
        Canvas(Modifier.weight(1f).padding(horizontal = 6.dp).height(5.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(Color.White.copy(alpha = .10f), cornerRadius = r)
            val f = if (hi - lo < 0.05) .5f else ((h.temp - lo) / (hi - lo)).toFloat()
            val cx = size.height / 2 + f * (size.width - size.height)
            drawRoundRect(Timeline.tempColor(h.temp).copy(alpha = .45f), size = Size(cx + size.height / 2, size.height), cornerRadius = r)
            drawCircle(Timeline.tempColor(h.temp), size.height * .95f, Offset(cx, size.height / 2))
        }
        Text(units.t(h.temp), style = WType.small.copy(fontSize = 16.sp), modifier = Modifier.width(38.dp), textAlign = TextAlign.End, maxLines = 1)
        Text(units.w(h.wind), style = statSub, modifier = Modifier.width(68.dp), textAlign = TextAlign.End, maxLines = 1)
    }
}

/** „Wie sicher ist die Vorhersage?“ – Stufe mit drei Balken und was die Wettermodelle sagen. */
@Composable
fun ConfidenceBlock(c: Confidence, units: Units) {
    val color = when (c.level) { 3 -> WColors.Good; 2 -> Color(0xFFFFD60A); else -> WColors.Warn }
    Group("Sicherheit der Vorhersage", Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.label.replaceFirstChar { it.uppercase() }, style = statValue.copy(color = color), modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (i in 1..3) Box(Modifier.width(28.dp).height(7.dp).clip(RoundedCornerShape(4.dp)).background(if (i <= c.level) color else Color.White.copy(alpha = .14f)))
            }
        }
        val dry = c.models - c.wetModels
        val rain = when {
            c.wetModels == 0 -> "Alle ${c.models} Wettermodelle rechnen mit einem trockenen Tag."
            dry == 0 -> "Alle ${c.models} Wettermodelle rechnen mit Regen."
            else -> "${c.wetModels} von ${c.models} Wettermodellen rechnen mit Regen."
        }
        val lo = units.tNum(c.tMaxLow); val hi = units.tNum(c.tMaxHigh)
        Text(rain + " " + (if (lo == hi) "Höchstwert überall $lo°." else "Höchstwert je nach Modell $lo° bis $hi°."),
            style = statSub.copy(fontSize = 14.sp, lineHeight = 20.sp), modifier = Modifier.padding(top = 6.dp))
        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp).background(Hairline))
        Text("Verglichen: ICON (DWD), ECMWF, GFS (USA), UKMO (Großbritannien), Météo-France, GEM (Kanada)",
            style = statSub.copy(fontSize = 12.sp, lineHeight = 17.sp, color = WColors.Label))
    }
}
