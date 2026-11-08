package de.schee.wetter.ui

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
import de.schee.wetter.log.AppLog
import de.schee.wetter.ui.theme.WColors
import de.schee.wetter.ui.theme.WType

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
            .clip(RoundedCornerShape(50))
            .background(if (selected) WColors.Accent.copy(alpha = .28f) else WColors.Glass)
            .border(1.dp, if (selected) WColors.Accent else Color.Transparent, RoundedCornerShape(50))
            .clickable(remember { MutableInteractionSource() }, null) { AppLog.tap(logName); onClick() }
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
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = if (enabled) .26f else .1f))
            .clickable(enabled = enabled) { AppLog.tap(logName); onClick() }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = WType.small.copy(color = if (enabled) WColors.Text else WColors.Label)) }
}
