package de.schee.wetter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.schee.wetter.data.Place
import de.schee.wetter.data.PlaceStore
import de.schee.wetter.data.Units
import de.schee.wetter.data.WeatherCodes
import de.schee.wetter.log.AppLog
import de.schee.wetter.ui.sky.SkyWeather
import de.schee.wetter.ui.theme.WColors
import de.schee.wetter.ui.theme.WType
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Ortsliste (wie die iOS-Wetter-App): oben die Suche, darunter „Mein Standort“ und die gespeicherten Orte
 * als Karten mit Temperatur, Wetterlage und Höchst-/Tiefstwert. Tippen öffnet den Ort, langes Drücken
 * schaltet „Bearbeiten“ ein (am Griff ≡ verschieben, mit ⊖ löschen).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlacesScreen(onClose: () -> Unit, vm: WeatherViewModel = viewModel()) {
    val context = LocalContext.current
    val units = remember { Units.from(context) }
    val gps by vm.gpsAllowed.collectAsState()
    val states by vm.states.collectAsState()
    val saved by vm.saved.collectAsState()
    var edit by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadAllForList() }
    androidx.activity.compose.BackHandler(enabled = edit) { AppLog.tap("Zurück-Geste → Ortsliste bearbeiten beendet"); edit = false }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF070B14)).statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Wetter", style = WType.title, modifier = Modifier.weight(1f))
            Text(
                if (edit) "Fertig" else "Bearbeiten", style = WType.small.copy(color = WColors.Accent),
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                    edit = !edit; AppLog.tap("Ortsliste → ${if (edit) "Bearbeiten" else "Fertig"}")
                }.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        if (!edit) PlaceSearchField { p ->
            AppLog.tap("Ortsliste → ${p.name} hinzugefügt/geöffnet")
            vm.addPlace(p); onClose()
        }

        // „Mein Standort“ (falls erlaubt) – nicht verschiebbar, nicht löschbar
        if (gps) {
            PlaceCard(states[WeatherViewModel.GPS] ?: vm.uiFor(WeatherViewModel.GPS), gps = true, units = units, edit = edit,
                onClick = { AppLog.tap("Ortsliste → Mein Standort"); vm.select(0); onClose() },
                onLongClick = { edit = true; AppLog.tap("Ortsliste lange gedrückt → Bearbeiten") })
        }

        // Gespeicherte Orte, im Bearbeiten-Modus am Griff verschiebbar
        val offsetGps = if (gps) 1 else 0
        val density = LocalDensity.current
        val rowH = with(density) { 98.dp.toPx() }
        var dragging by remember { mutableStateOf<Int?>(null) }
        var dragY by remember { mutableFloatStateOf(0f) }
        saved.forEachIndexed { i, p ->
            val id = PlaceStore.id(p)
            val isDragged = dragging == i
            Box(
                Modifier.fillMaxWidth().let { if (isDragged) it.offset { IntOffset(0, dragY.roundToInt()) } else it },
            ) {
                PlaceCard(states[id] ?: vm.uiFor(id).copy(place = p), gps = false, units = units, edit = edit,
                    onClick = { AppLog.tap("Ortsliste → ${p.name}"); vm.select(i + offsetGps); onClose() },
                    onLongClick = { edit = true; AppLog.tap("Ortsliste lange gedrückt → Bearbeiten") },
                    onDelete = { AppLog.tap("Ortsliste → ${p.name} gelöscht"); vm.removePlace(p) },
                    handle = Modifier.pointerInput(saved.size) {
                        detectDragGestures(
                            onDragStart = { dragging = i; dragY = 0f },
                            onDragEnd = {
                                val to = (i + (dragY / rowH).roundToInt()).coerceIn(0, saved.size - 1)
                                if (to != i) { AppLog.tap("Ortsliste → ${p.name} von Platz ${i + 1} auf ${to + 1} verschoben"); vm.movePlace(i, to) }
                                dragging = null; dragY = 0f
                            },
                            onDragCancel = { dragging = null; dragY = 0f },
                        ) { change, amount -> change.consume(); dragY += amount.y }
                    })
            }
        }
        if (saved.isEmpty() && !edit) Text("Suche oben nach einem Ort, um ihn hinzuzufügen. Danach kannst du auf der Hauptseite zwischen den Orten wischen.", style = WType.bodyDim)
        Spacer(Modifier.height(24.dp).navigationBarsPadding())
    }
}

/** Karte eines Ortes in der Liste – Hintergrund nach Wetter und Tageszeit eingefärbt. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaceCard(
    ui: WeatherUi, gps: Boolean, units: Units, edit: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, onDelete: (() -> Unit)? = null, handle: Modifier? = null,
) {
    val f = ui.forecast
    val zone = runCatching { ZoneId.of(f?.timezone ?: "Europe/Berlin") }.getOrDefault(ZoneId.systemDefault())
    val now = LocalDateTime.now(zone)
    val day = f?.days?.firstOrNull { it.date == now.toLocalDate() }
    val sky = f?.let { WeatherCodes.sky(it.current.code) }
    val isDay = f?.current?.isDay ?: true
    // Gleicher Himmel wie auf der Seite des Ortes (Tag, Dämmerung, Nacht · klar, bewölkt, Regen …), etwas aufgehellt
    val colors = remember(sky, isDay, day?.sunrise, now.hour) {
        val t = de.schee.wetter.data.WeatherCodes.skyTime(now, day?.sunrise, day?.sunset).let { if (!isDay && it == de.schee.wetter.ui.sky.SkyTime.DAY) de.schee.wetter.ui.sky.SkyTime.NIGHT else it }
        val (top, bottom) = de.schee.wetter.ui.sky.SkyPalette.gradient(sky ?: SkyWeather.PARTLY, t)
        listOf(androidx.compose.ui.graphics.lerp(top, Color.White, .07f), androidx.compose.ui.graphics.lerp(bottom, Color.White, .12f))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (edit && onDelete != null) {
            Box(Modifier.size(34.dp).clip(CircleShape).clickable { onDelete() }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(WColors.Bad), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(10.dp).height(2.dp).background(Color.White))
                }
            }
            Spacer(Modifier.width(6.dp))
        }
        Row(
            Modifier.weight(1f).pressable(scale = .97f, onLongClick = onLongClick, onClick = onClick).height(88.dp).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(colors))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.SpaceBetween) {
                Text(if (gps) "Mein Standort" else ui.place?.name ?: "", style = WType.condition.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (gps) ui.place?.name ?: "wird ermittelt …" else "%02d:%02d".format(now.hour, now.minute), style = WType.bodyDim, maxLines = 1)
                Text(f?.let { WeatherCodes.text(it.current.code, it.current.isDay) } ?: if (ui.loading) "lädt …" else "", style = WType.bodyDim, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(f?.let { units.t(it.current.temp) } ?: "–", style = WType.value.copy(fontSize = 40.sp, fontWeight = FontWeight.Light))
                day?.let { Text("H: ${units.t(it.tMax)}  T: ${units.t(it.tMin)}", style = WType.bodyDim) }
            }
        }
        if (edit && handle != null) {
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(width = 34.dp, height = 60.dp).then(handle), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(20.dp)) {
                    listOf(.3f, .5f, .7f).forEach { y -> drawLine(WColors.Label, Offset(0f, size.height * y), Offset(size.width, size.height * y), 2.dp.toPx(), StrokeCap.Round) }
                }
            }
        }
    }
}
