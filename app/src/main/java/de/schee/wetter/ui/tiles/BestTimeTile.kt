package de.schee.wetter.ui.tiles

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.schee.wetter.data.BestTime
import de.schee.wetter.log.AppLog
import de.schee.wetter.ui.Haptics
import de.schee.wetter.ui.rememberHaptics
import de.schee.wetter.ui.theme.WColors
import de.schee.wetter.ui.theme.WType

private fun prefs(c: Context) = c.getSharedPreferences("tiles", Context.MODE_PRIVATE)

/** Bestes Zeitfenster: Auswahl der Tätigkeit (wie der iOS-Segmentschalter), Fenster heute und morgen, 24-Stunden-Leiste. */
@Composable
fun BestTimeContent(d: TileData, initial: BestTime.Activity? = null) {
    val context = LocalContext.current
    val haptic = rememberHaptics()
    var act by remember {
        mutableStateOf(initial ?: runCatching { BestTime.Activity.valueOf(prefs(context).getString("bestTime", null) ?: "WALK") }.getOrDefault(BestTime.Activity.WALK))
    }
    val r = remember(d.f, d.now.hour, act) { BestTime.compute(d.f, d.now, act) }

    Segmented(BestTime.Activity.entries.map { it.label }, act.ordinal) { i ->
        val a = BestTime.Activity.entries[i]
        if (a == act) return@Segmented
        haptic(Haptics.Kind.SNAP)
        AppLog.tap("Kachel Bestes Zeitfenster → ${a.label}")
        act = a
        prefs(context).edit().putString("bestTime", a.name).apply()
    }

    val w = r.today ?: r.tomorrow
    val isToday = r.today != null
    if (w != null) {
        val span = BestTime.span(w, d.now)
        Text(if (!isToday) "Morgen $span" else if (span.startsWith("Jetzt")) span else "Heute $span",
            style = WType.value.copy(fontSize = WType.value.fontSize * .78f))
        Text(BestTime.details(act, w, d.units), style = WType.bodyDim)
    } else {
        Text(BestTime.noneText(r.todayReason ?: r.tomorrowReason), style = WType.value.copy(fontSize = WType.value.fontSize * .78f))
        Text("Heute und morgen passt es leider nicht.", style = WType.bodyDim)
    }

    Strip(r.strip, w, Modifier.fillMaxWidth().height(22.dp))
    // Beschriftung genau unter Stunde 0, 6, 12, 18 der Leiste
    Row(Modifier.fillMaxWidth()) {
        r.strip.filterIndexed { i, _ -> i % 6 == 0 }.forEachIndexed { i, (t, _) ->
            Text(if (i == 0) "Jetzt" else "${t.hour} Uhr", style = WType.label.copy(color = WColors.Label), modifier = Modifier.weight(1f))
        }
    }
    if (isToday) {
        val m = r.tomorrow
        Text(if (m != null) "Morgen: ${BestTime.span(m, d.now)}" else "Morgen: ${BestTime.noneText(r.tomorrowReason).replaceFirstChar { it.lowercase() }}",
            style = WType.bodyDim)
    }
}

/** iOS-Segmentschalter: graue Fläche, ausgewähltes Feld als hellere Kapsel. */
@Composable
private fun Segmented(items: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = .08f)).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEachIndexed { i, label ->
            val bg by animateColorAsState(if (i == selected) Color.White.copy(alpha = .22f) else Color.Transparent, label = "seg")
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(7.dp)).background(bg)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onPick(i) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = WType.label.copy(color = if (i == selected) WColors.Text else WColors.TextDim, letterSpacing = WType.label.letterSpacing * 0),
                    textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    }
}

/** 24 Stunden als Kästchen: grün = gut, blasser = geht, dunkel = geht nicht; das empfohlene Fenster hell umrandet. */
@Composable
private fun Strip(strip: List<Pair<java.time.LocalDateTime, Double>>, w: BestTime.Window?, modifier: Modifier) {
    Canvas(modifier) {
        if (strip.isEmpty()) return@Canvas
        val n = strip.size
        val gap = 2 * density
        val cw = (size.width - gap * (n - 1)) / n
        val r = CornerRadius(3 * density)
        strip.forEachIndexed { i, (t, s) ->
            val x = i * (cw + gap)
            val c = if (s < 0) Color.White.copy(alpha = .07f) else lerp(WColors.Good.copy(alpha = .28f), WColors.Good, s.toFloat().coerceIn(0f, 1f))
            val inWin = w != null && !t.isBefore(w.start) && t.isBefore(w.end)
            val h = if (inWin) size.height else size.height * .72f
            drawRoundRect(c, Offset(x, (size.height - h) / 2), Size(cw, h), r)
        }
    }
}
