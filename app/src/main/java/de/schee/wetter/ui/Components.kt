package de.schee.wetter.ui

import androidx.compose.ui.composed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import de.schee.wetter.log.AppLog
import de.schee.wetter.ui.theme.WColors
import de.schee.wetter.ui.theme.WType

import androidx.compose.runtime.getValue

/** Halbtransparente dunkle Kachel mit kleiner Überschrift (iOS-Stil). */
@Composable
fun Tile(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(WColors.Card)
            .border(1.dp, WColors.CardBorder, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null) Text(title.uppercase(), style = WType.label)
        content()
    }
}

/** Kapsel-Knopf. Jeder Tipp wird mit [logName] protokolliert. */
@Composable
fun Pill(text: String, selected: Boolean, logName: String, onClick: () -> Unit) {
    Box(
        Modifier
            .pressable(scale = .92f) { AppLog.tap(logName); onClick() }
            .clip(RoundedCornerShape(50))
            .background(if (selected) WColors.Accent.copy(alpha = .28f) else WColors.Glass)
            .border(1.dp, if (selected) WColors.Accent else Color.Transparent, RoundedCornerShape(50))
            .padding(horizontal = 13.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = WType.small) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> PillRow(options: List<T>, selected: T, label: (T) -> String, logPrefix: String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> Pill(label(o), o == selected, "$logPrefix → „${label(o)}“") { onSelect(o) } }
    }
}

/** Großer Aktionsknopf (z. B. Update). */
@Composable
fun ActionButton(text: String, logName: String, enabled: Boolean = true, color: Color = WColors.Accent, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .pressable(enabled = enabled, scale = .97f) { AppLog.tap(logName); onClick() }
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = if (enabled) .26f else .1f))
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = WType.small.copy(color = if (enabled) WColors.Text else WColors.Label)) }
}

/**
 * iOS-Segmentschalter: graue Fläche, gewähltes Feld als hellere Kapsel, die weich hinübergleitet.
 * Jeder Wechsel vibriert fein und wird mit [logPrefix] protokolliert.
 */
@Composable
fun Segmented(items: List<String>, selected: Int, logPrefix: String, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    val haptic = rememberHaptics()
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = .09f)).padding(2.dp),
    ) {
        val segW = maxWidth / items.size.coerceAtLeast(1)
        val x by androidx.compose.animation.core.animateDpAsState(segW * selected,
            androidx.compose.animation.core.spring(dampingRatio = .82f, stiffness = 520f), label = "seg")
        Box(Modifier.offset(x = x).width(segW).height(30.dp).clip(RoundedCornerShape(7.dp)).background(Color.White.copy(alpha = .24f)))
        Row(Modifier.fillMaxWidth().height(30.dp)) {
            items.forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (i != selected) { haptic(Haptics.Kind.SNAP); AppLog.tap("$logPrefix → „$label“"); onPick(i) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = WType.small.copy(fontSize = 13.sp, color = if (i == selected) WColors.Text else WColors.TextDim), maxLines = 1)
                }
            }
        }
    }
}

/** iOS-Suchfeld: gefüllte graue Fläche, Lupe, Platzhalter; ✕ zum Leeren. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, onSearch: () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(11.dp)).background(Color(0x3D767680)).padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(17.dp)) {
            val s = size.minDimension; val sw = s * .11f
            drawCircle(WColors.TextDim, radius = s * .3f, center = androidx.compose.ui.geometry.Offset(s * .42f, s * .42f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
            drawLine(WColors.TextDim, androidx.compose.ui.geometry.Offset(s * .64f, s * .64f), androidx.compose.ui.geometry.Offset(s * .9f, s * .9f), sw, androidx.compose.ui.graphics.StrokeCap.Round)
        }
        Spacer(Modifier.width(7.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = WType.body.copy(color = WColors.Label), maxLines = 1)
            androidx.compose.foundation.text.BasicTextField(
                value, onChange, singleLine = true, textStyle = WType.body,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(WColors.Accent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Box(Modifier.size(28.dp).clip(CircleShape).clickable { AppLog.tap("Suchfeld → leeren"); onChange("") }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(16.dp).clip(CircleShape).background(WColors.Label), contentAlignment = Alignment.Center) {
                    Text("✕", style = WType.label.copy(fontSize = 9.sp, color = Color(0xFF1C1C1E)))
                }
            }
        }
    }
}

/**
 * Tippen wie bei iOS: kein Android-Wellenkreis, sondern das Element gibt beim Drücken leicht nach (kleiner bzw.
 * abgedunkelt) und federt zurück; dazu ein feiner Klick. Gehört an den Anfang der Modifier-Kette, damit das
 * ganze Element mitgeht.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.pressable(
    enabled: Boolean = true,
    scale: Float = .94f,
    dim: Float = 1f,
    haptic: Haptics.Kind? = Haptics.Kind.TAP,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val k by androidx.compose.animation.core.animateFloatAsState(
        if (pressed) 1f else 0f,
        if (pressed) androidx.compose.animation.core.spring(stiffness = 1500f) else androidx.compose.animation.core.spring(dampingRatio = .55f, stiffness = 420f),
        label = "press")
    val h = rememberHaptics()
    Modifier.graphicsLayer {
        val s = 1f - (1f - scale) * k
        scaleX = s; scaleY = s
        alpha = 1f - (1f - dim) * k.coerceIn(0f, 1f)
    }.combinedClickable(
        interactionSource = src, indication = null, enabled = enabled,
        onLongClick = onLongClick?.let { l -> { h(Haptics.Kind.PRESS); l() } },
        onClick = { haptic?.let(h); onClick() },
    )
}
