package de.schee.wetter.ui.tiles

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import de.schee.wetter.log.AppLog
import de.schee.wetter.ui.sky.LocalAnimate
import de.schee.wetter.ui.theme.WColors
import de.schee.wetter.ui.theme.WType
import kotlin.math.roundToInt

private val Draggable = TileId.entries.filter { it != TileId.WARNING }

/** Gemeinsamer Zustand für Warnung oben und Kachelbereich unten. */
class TileState(private val store: TileStore) {
    var order by mutableStateOf(store.order().filter { it in Draggable })
    var hidden by mutableStateOf(store.hidden())
    fun save() = store.save(listOf(TileId.WARNING) + order, hidden)
}

/** Warnung immer ganz oben, nur wenn aktiv und nicht ausgeblendet. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WarningTile(d: TileData, state: TileState, onEdit: () -> Unit) {
    if (d.warnings.isEmpty() || TileId.WARNING in state.hidden) return
    var expanded by remember { mutableStateOf(false) }
    TileFrame(TileId.WARNING.title, Modifier.fillMaxWidth(), border = WColors.Warn.copy(alpha = .6f),
        onClick = { AppLog.tap("Kachel Unwetterwarnung → ${if (expanded) "zugeklappt" else "Details"}"); expanded = !expanded }, onLongClick = onEdit) {
        WarningContent(d, expanded)
    }
}

/**
 * Die Kacheln unter der Vorhersage. Langes Drücken auf eine Kachel öffnet den Bearbeitungsmodus:
 * Kacheln wackeln, lassen sich am Griff ziehen, mit ⊖ ausblenden und mit ⊕ wieder hinzufügen.
 */
@Composable
fun TilesSection(d: TileData, state: TileState, edit: Boolean, onEdit: (Boolean) -> Unit, onHint: (String) -> Unit) {
    val order = state.order
    val hidden = state.hidden
    val haptic = LocalHapticFeedback.current

    fun save() = state.save()

    if (edit) {
        EditList(order, hidden,
            onMove = { from, to ->
                AppLog.tap("Kacheln bearbeiten → „${order[from].title}“ von Platz ${from + 1} auf ${to + 1} verschoben")
                state.order = TileLayout.move(order, from, to); save()
            },
            onHide = { id -> AppLog.tap("Kacheln bearbeiten → „${id.title}“ ausgeblendet"); state.hidden = hidden + id; save() },
            onShow = { id -> AppLog.tap("Kacheln bearbeiten → „${id.title}“ hinzugefügt"); state.hidden = hidden - id; save() },
            onDone = { AppLog.tap("Kacheln bearbeiten → Fertig"); onEdit(false) },
        )
        return
    }

    val startEdit = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); AppLog.tap("Kachel lange gedrückt → Bearbeiten"); onEdit(true) }
    val visible = order.filter { it !in hidden }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TileLayout.rows(visible).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { id ->
                    val m = if (row.size == 2) Modifier.weight(1f).fillMaxHeight() else Modifier.fillMaxWidth()
                    TileFrame(when (id) { TileId.MOON -> moonTitle(d); TileId.SUN -> sunTitle(d); TileId.AVERAGE -> "Durchschnitt"; else -> id.title }, m,
                        onClick = {
                            AppLog.tap("Kachel ${id.title} angetippt")
                            when (id) {
                                TileId.RADAR -> onHint("Das Vollbild-Radar mit Zeitleiste kommt mit Version 0.4.0.")
                                TileId.WIND_MAP -> onHint("Die große Windkarte kommt mit Version 0.4.0.")
                                else -> Unit
                            }
                        },
                        onLongClick = startEdit,
                    ) { TileBody(id, d) }
                }
            }
        }
        Text(
            "Kacheln bearbeiten", style = WType.small.copy(color = WColors.Accent), textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { AppLog.tap("Text „Kacheln bearbeiten“"); onEdit(true) }.padding(10.dp),
        )
    }
}

@Composable
private fun TileBody(id: TileId, d: TileData) {
    when (id) {
        TileId.WARNING -> WarningContent(d, false)
        TileId.RADAR -> RadarContent(d)
        TileId.RAIN_2H -> Rain2hContent(d)
        TileId.WIND -> WindContent(d)
        TileId.WIND_MAP -> WindMapContent(d)
        TileId.FEELS -> FeelsContent(d)
        TileId.HUMIDITY -> HumidityContent(d)
        TileId.PRECIP_TODAY -> PrecipTodayContent(d)
        TileId.VISIBILITY -> VisibilityContent(d)
        TileId.AVERAGE -> AverageContent(d)
        TileId.SUN -> SunContent(d)
        TileId.MOON -> MoonContent(d)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TileFrame(
    title: String, modifier: Modifier, border: Color = WColors.CardBorder,
    onClick: () -> Unit, onLongClick: () -> Unit, content: @Composable () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(WColors.Card)
            .border(1.dp, border, RoundedCornerShape(18.dp))
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title.uppercase(), style = WType.label)
        content()
    }
}

@Composable
private fun EditList(
    order: List<TileId>, hidden: Set<TileId>,
    onMove: (Int, Int) -> Unit, onHide: (TileId) -> Unit, onShow: (TileId) -> Unit, onDone: () -> Unit,
) {
    val density = LocalDensity.current
    val rowH = with(density) { 64.dp.toPx() }
    var dragging by remember { mutableStateOf<TileId?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val visible = order.filter { it !in hidden }
    val fromIdx = dragging?.let { order.indexOf(it) } ?: -1
    val target = if (fromIdx >= 0) (fromIdx + (offset / rowH).roundToInt()).coerceIn(0, order.size - 1) else -1

    val animate = LocalAnimate.current
    val wobble = if (animate) {
        val inf = rememberInfiniteTransition(label = "wobble")
        inf.animateFloat(-0.6f, 0.6f, infiniteRepeatable(tween(140), RepeatMode.Reverse), label = "w").value
    } else 0f

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Kacheln bearbeiten", style = WType.condition, modifier = Modifier.weight(1f))
            Text("Fertig", style = WType.small.copy(color = WColors.Accent), modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onDone() }.padding(8.dp))
        }
        Text("Am Griff ≡ ziehen zum Verschieben. ⊖ blendet aus, ⊕ holt zurück.", style = WType.bodyDim)

        EditRow(TileId.WARNING.title + " (immer oben)", visible = TileId.WARNING !in hidden, wobble = wobble, handle = false,
            onToggle = { if (TileId.WARNING in hidden) onShow(TileId.WARNING) else onHide(TileId.WARNING) })

        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            order.forEachIndexed { i, id ->
                val shift = when {
                    dragging == id -> offset
                    fromIdx in 0 until i && i <= target -> -rowH
                    target in 0..i && i < fromIdx -> rowH
                    else -> 0f
                }
                Box(Modifier.height(64.dp).zIndex(if (dragging == id) 1f else 0f).graphicsLayer { translationY = shift }) {
                    EditRow(id.title, visible = id !in hidden, wobble = if (dragging == id) 0f else wobble * (if (i % 2 == 0) 1 else -1), handle = true,
                        onToggle = { if (id in hidden) onShow(id) else onHide(id) },
                        handleModifier = Modifier.pointerInput(id, order) {
                            detectDragGestures(
                                onDragStart = { dragging = id; offset = 0f },
                                onDragEnd = { val f = order.indexOf(id); val t = (f + (offset / rowH).roundToInt()).coerceIn(0, order.size - 1); dragging = null; offset = 0f; if (t != f) onMove(f, t) },
                                onDragCancel = { dragging = null; offset = 0f },
                                onDrag = { change, amount -> change.consume(); offset += amount.y },
                            )
                        })
                }
            }
        }
        if (visible.isEmpty()) Text("Alle Kacheln sind ausgeblendet.", style = WType.bodyDim)
    }
}

@Composable
private fun EditRow(title: String, visible: Boolean, wobble: Float, handle: Boolean, onToggle: () -> Unit, handleModifier: Modifier = Modifier) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).graphicsLayer { rotationZ = if (visible) wobble else 0f }
            .clip(RoundedCornerShape(14.dp)).background(WColors.Card).border(1.dp, WColors.CardBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp).height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(if (visible) WColors.Bad else WColors.Good).clickable { onToggle() },
            contentAlignment = Alignment.Center,
        ) { Text(if (visible) "−" else "+", style = WType.small) }
        Text(title, style = WType.body.copy(color = if (visible) WColors.Text else WColors.Label), modifier = Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1)
        if (handle) Box(handleModifier.size(40.dp), contentAlignment = Alignment.Center) { Text("≡", style = WType.condition.copy(color = WColors.TextDim)) }
    }
}
