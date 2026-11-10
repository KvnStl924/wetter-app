package de.schee.wetter.ui.radar

import androidx.compose.animation.animateColorAsState
import de.schee.wetter.ui.pressable
import android.graphics.Bitmap
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.roundToInt
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.schee.wetter.data.LocationSource
import de.schee.wetter.data.Places
import de.schee.wetter.data.RadarData
import de.schee.wetter.data.Satellite
import de.schee.wetter.data.WindGrid
import de.schee.wetter.data.WorldCities
import de.schee.wetter.data.WorldWeather
import de.schee.wetter.log.AppLog
import de.schee.wetter.ui.sky.LocalAnimate
import de.schee.wetter.ui.theme.WColors
import de.schee.wetter.ui.theme.WType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.random.Random

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Ebenen des Radars. Es ist immer nur eine an – Ausnahme: Blitze lassen sich zum Regen dazuschalten. */
enum class RadarLayer(val label: String) { RAIN("Niederschlag"), WIND("Wind"), TEMP("Temperatur"), CLOUDS("Wolken") }

/** Ort in der Mitte der Karte mit aktueller Temperatur. */
data class RadarPlace(val name: String, val lat: Double, val lon: Double, val tempLabel: String)

/**
 * Lineare Abbildung Mercator → Bildschirm (die Karte lässt sich nicht drehen oder kippen).
 * Länge darf über ±180° hinausgehen (Ausschnitt über der Datumsgrenze); Punkte werden auf die
 * Welt-Kopie gelegt, die der Bildmitte am nächsten ist.
 */
data class MapTransform(val west: Double, val east: Double, val north: Double, val south: Double, val w: Float, val h: Float) {
    private val x0 = RadarData.mercX(west); private val x1 = RadarData.mercX(east)
    private val y0 = RadarData.mercY(north); private val y1 = RadarData.mercY(south)
    private val center = (west + east) / 2
    fun norm(lon: Double): Double { var l = lon; while (l - center > 180) l -= 360; while (l - center < -180) l += 360; return l }
    fun x(lon: Double) = xRaw(norm(lon))
    fun xRaw(lon: Double) = ((RadarData.mercX(lon) - x0) / (x1 - x0) * w).toFloat()
    fun y(lat: Double) = ((RadarData.mercY(lat) - y0) / (y1 - y0) * h).toFloat()
    fun lon(px: Float) = (x0 + px / w * (x1 - x0)) * 180.0 / 20037508.34
    fun lat(py: Float): Double {
        val my = (y0 + py / h * (y1 - y0)) * 180.0 / 20037508.34
        return 360.0 / PI * atan(exp(my * PI / 180.0)) - 90.0
    }
    fun visible(x: Float, y: Float, margin: Float = 0f) = x >= -margin && y >= -margin && x <= w + margin && y <= h + margin
}

private val hhmm = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/**
 * Vollbild-Radar: schlichte dunkle Karte, darauf genau eine Ebene (Regen, Wind, Temperatur oder Wolken),
 * Blitze zusätzlich zum Regen. Alle Weltdaten sind beim Öffnen der App vorgeladen → kein Warten beim Wischen.
 * [preview] = Testbild am PC: statt der Karte ein fester Ausschnitt (MapLibre läuft dort nicht).
 */
@Composable
fun RadarScreen(
    place: RadarPlace?,
    initialLayer: RadarLayer?,
    onClose: () -> Unit,
    vm: RadarViewModel = viewModel(),
    preview: Bitmap? = null,
    previewFix: LocationSource.Fix? = null,
    previewCities: List<MapCity> = emptyList(),
    previewLightning: Boolean = false,
    /** Testbild: anderer Ausschnitt (West, Ost, Süd, Nord) statt Deutschland. */
    previewArea: DoubleArray? = null,
    /** Warngebiete gleich eingeschaltet (z. B. aus der Warn-Kachel geöffnet). */
    initialWarnings: Boolean = false,
    /** Blitze gleich eingeschaltet (aus dem Gewitter-Hinweis geöffnet). */
    initialLightning: Boolean = false,
    previewPick: Picked? = null,
    previewMotion: de.schee.wetter.data.Nowcast.Motion? = null,
) {
    val ui by vm.ui.collectAsState()
    var layer by remember { mutableStateOf(initialLayer) }
    var lightning by remember { mutableStateOf(previewLightning || initialLightning) }
    var warnings by remember { mutableStateOf(initialWarnings) }
    var moving by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(previewPick) }
    val haptic = de.schee.wetter.ui.rememberHaptics()
    val weatherVm = if (preview == null) viewModel<de.schee.wetter.ui.WeatherViewModel>() else null
    var toast by remember { mutableStateOf<String?>(null) }
    var index by remember { mutableIntStateOf(if (ui.frames.isNotEmpty()) ui.nowIndex else -1) }
    var hour by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var transform by remember { mutableStateOf<MapTransform?>(null) }
    var idle by remember { mutableStateOf<MapTransform?>(null) }
    var shown by remember { mutableStateOf<Bitmap?>(null) }
    var fix by remember { mutableStateOf(previewFix) }
    var cities by remember { mutableStateOf(previewCities) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var centerTick by remember { mutableIntStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val units = remember { de.schee.wetter.data.Units.from(context) }
    val title = listOfNotNull(layer?.label, "Blitze".takeIf { lightning }, "Warnungen".takeIf { warnings }).joinToString(" · ").ifEmpty { "Karte" }

    // Schalter: eine Ebene; Blitze nur allein oder mit Regen (Kevin am 09.11.2026)
    fun pick(l: RadarLayer) {
        haptic(de.schee.wetter.ui.Haptics.Kind.SNAP)
        layer = if (layer == l) null else l
        if (layer != RadarLayer.RAIN && layer != null) lightning = false
        playing = false
        AppLog.tap("Vollbild-Radar → ${l.label} ${if (layer == l) "an" else "aus"}")
    }
    fun toggleLightning() {
        haptic(de.schee.wetter.ui.Haptics.Kind.SNAP)
        lightning = !lightning
        if (lightning && layer != RadarLayer.RAIN) layer = null
        AppLog.tap("Vollbild-Radar → Blitze ${if (lightning) "an" else "aus"}")
    }

    // Blauer Punkt: Live-Standort, nur solange das Radar sichtbar ist (kein Hintergrund).
    LaunchedEffect(Unit) {
        if (preview != null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { LocationSource.updates(context).collect { fix = it } }
    }
    // Alles ist schon beim Öffnen der App vorgeladen; hier nur auffrischen. Temperatur/Wind: weitere Stunden
    // für die Zeitleiste; Blitze: alle 5 Minuten neu, solange sie eingeschaltet sind.
    LaunchedEffect(Unit) {
        if (preview != null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.preload()
            androidx.compose.runtime.snapshotFlow { layer }.collect { if (it == RadarLayer.WIND || it == RadarLayer.TEMP) vm.loadWorldHours() }
        }
    }
    LaunchedEffect(lightning) {
        if (preview != null || !lightning) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (isActive) { vm.loadLightning(); delay(60_000) } }
    }
    LaunchedEffect(ui.frames.size) { if (index < 0 && ui.frames.isNotEmpty()) index = ui.nowIndex }

    // Zugrichtung des Regens (aus zwei Messungen 30 Minuten auseinander) um den Ort bzw. Standort
    val motionAt = fix?.let { it.lat to it.lon } ?: place?.let { it.lat to it.lon }
    val nowBytes = ui.bytes[ui.nowIndex]
    val oldBytes = ui.frames.indexOfFirst { it.minutes == -30 }.let { ui.bytes[it] }
    val motion by androidx.compose.runtime.produceState(previewMotion, nowBytes, oldBytes, motionAt?.first?.let { Math.round(it * 10) }, motionAt?.second?.let { Math.round(it * 10) }) {
        if (preview != null || motionAt == null || nowBytes == null || oldBytes == null) return@produceState
        value = withContext(Dispatchers.Default) { runCatching { de.schee.wetter.data.Nowcast.motion(ui.frames, ui.bytes, motionAt.first, motionAt.second) }.getOrNull() }
    }

    // Angetippter Punkt: Name, Temperatur, Wind und – in Deutschland – Regen in den nächsten 2 Stunden
    fun tapped(lat: Double, lon: Double, long: Boolean) {
        val ww0 = ui.worldNow
        val idx = de.schee.wetter.data.Places.index.value
        val name = idx?.let { ix ->
            de.schee.wetter.data.Places.inView(ix, lat - .12, lat + .12, lon - .18, lon + .18, limit = 400)
                .minByOrNull { i -> kotlin.math.hypot(ix.lat[i] - lat, (ix.lon[i] - lon) * kotlin.math.cos(Math.toRadians(lat))) - kotlin.math.ln(ix.pop[i].toDouble().coerceAtLeast(1.0)) * .004 }
                ?.let { ix.name(it) }
        }
        val temp = ww0?.tempAt(lat, lon)?.takeUnless { it.isNaN() }
        val wind = ww0?.let { w -> DoubleArray(2).also { w.windAt(lat, lon, it) } }
        val rain = runCatching { de.schee.wetter.data.Nowcast.series(ui.frames, ui.bytes, lat, lon, Instant.now()) }.getOrNull()
        val p = Picked(lat, lon, name, temp, wind?.let { kotlin.math.hypot(it[0], it[1]) }, rain?.let { de.schee.wetter.data.Nowcast.headline(it, Instant.now()) ?: "Kein Regen in den nächsten 2 Std." })
        picked = p
        AppLog.tap("Vollbild-Radar → ${if (long) "lange gedrückt" else "angetippt"}: ${name ?: "%.2f, %.2f".format(lat, lon)}")
        if (long) savePlace(p, weatherVm, haptic) { toast = it } else haptic(de.schee.wetter.ui.Haptics.Kind.SNAP)
    }

    // Regenbild zum gewählten Zeitpunkt dekodieren und umfärben (im Hintergrund-Thread, ca. 30 ms).
    val showRain = layer == RadarLayer.RAIN
    val bytes = ui.bytes[index]
    LaunchedEffect(index, bytes, showRain) {
        if (!showRain) return@LaunchedEffect
        val b = bytes ?: return@LaunchedEffect
        val kind = ui.frames.getOrNull(index)?.kind ?: return@LaunchedEffect
        shown = withContext(Dispatchers.Default) { RadarData.render(b, kind) }
    }

    // Abspielen: nur solange der Bildschirm sichtbar ist.
    LaunchedEffect(playing, layer) {
        if (!playing) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                if (layer == RadarLayer.RAIN) {
                    val avail = ui.frames.indices.filter { ui.bytes[it] != null }
                    if (avail.isEmpty()) { delay(300); continue }
                    val next = avail.firstOrNull { it > index } ?: avail.first()
                    delay(if (next == avail.first() && index == avail.last()) 1200 else if (ui.frames[next].kind == RadarData.Kind.MODEL) 650 else 280)
                    index = next
                } else {
                    delay(900)
                    val loaded = ui.worldHours.indices.filter { ui.world[ui.worldHours[it]] != null }
                    if (loaded.isNotEmpty()) hour = loaded.firstOrNull { it > hour } ?: loaded.first()
                }
            }
        }
    }

    // Temperatur/Wind der gewählten Stunde (fehlt sie noch, die nächstliegende geladene)
    val hours = ui.worldHours
    val ww: WorldWeather? = hours.getOrNull(hour)?.let { ui.world[it] } ?: ui.worldNow
    val fieldKind = if (layer == RadarLayer.TEMP || layer == RadarLayer.WIND) layer else null
    var field by remember { mutableStateOf<Bitmap?>(null) }
    if (preview != null) field = remember(fieldKind, ww) { if (fieldKind != null && ww != null) worldFieldBitmap(ww, fieldKind == RadarLayer.TEMP) else null }
    else LaunchedEffect(fieldKind, ww) {
        if (fieldKind == null || ww == null) { field = null; return@LaunchedEffect }
        field = withContext(Dispatchers.Default) { worldFieldBitmap(ww, fieldKind == RadarLayer.TEMP) }
    }

    // Regen-Vorhersage außerhalb Deutschlands: grobes Modell-Gitter (nur für Zeitpunkte in der Zukunft)
    val frame = ui.frames.getOrNull(index)
    val rvIndex = if (showRain && frame != null && frame.minutes <= 0) de.schee.wetter.data.RainViewer.indexFor(ui.rainViewer, frame.time) else null
    val future = showRain && frame != null && frame.minutes > 0
    val precipView = remember(future, ui.windView, index) {
        val g = ui.windView ?: return@remember null
        if (!future) return@remember null
        val h = frame!!.time.truncatedTo(java.time.temporal.ChronoUnit.HOURS).let { if (it == frame.time) it else it.plusSeconds(3600) }
        g to precipViewBitmap(g, g.hourIndex(h))
    }
    val tNow = transform
    LaunchedEffect(future, tNow?.let { listOf(Math.round(it.south * 2), Math.round(it.north * 2), Math.round(it.west * 2), Math.round(it.east * 2)) }) {
        if (preview != null || tNow == null || !future) return@LaunchedEffect
        delay(500)
        vm.ensureView(tNow.south, tNow.north, tNow.west, tNow.east)
    }

    // Wolken: beim Hineinzoomen den Ausschnitt scharf nachladen (nach dem Loslassen)
    LaunchedEffect(layer == RadarLayer.CLOUDS, idle) {
        val t = idle
        if (preview != null || layer != RadarLayer.CLOUDS || t == null) return@LaunchedEffect
        vm.ensureCloudDetail(t.west, t.east, t.south, t.north)
    }

    // Werte und Namen: Auswahl nach dem Verschieben/Zoomen, gezeichnet in der Karte (gehen beim Wischen exakt mit)
    val labelArea = idle ?: transform
    // Jede Stadt mit Wert (Kevin am 09.11.2026): bei Wind km/h, sonst die Temperatur – auch bei Regen, Wolken, Blitzen
    val labelWw = if (fieldKind != null) ww else ui.worldNow
    val labels = if (preview == null) rememberLabelsAsync(labelArea, cities, labelWw, layer != RadarLayer.WIND, units, place, fix, hotspots = fieldKind != null) else emptyList()

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF282B30))) {
        if (preview != null) {
            // Testbild am PC: alles ohne Hintergrund-Effekte berechnen, damit es sofort gezeichnet wird.
            transform = previewTransform(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), previewArea)
            idle = transform
            val kind = ui.frames.getOrNull(index)?.kind
            val overlay = if (showRain && bytes != null && kind != null) remember(index) { RadarData.render(bytes, kind) } else null
            PreviewMap(preview, if (previewArea == null) overlay else null, field, if (layer == RadarLayer.CLOUDS) ui.clouds?.bitmap else null, transform!!)
        } else {
            RadarMap(place, if (showRain) shown else null, if (showRain) ui.rainViewer else emptyList(), rvIndex,
                precipView, field, if (layer == RadarLayer.CLOUDS) ui.clouds?.bitmap else null,
                cloudDetail = if (layer == RadarLayer.CLOUDS) ui.cloudDetail else null, fieldLandOnly = fieldKind == RadarLayer.TEMP,
                center = fix?.let { it.lat to it.lon }, centerTick = centerTick,
                onCities = { c, t -> cities = c; idle = t },
                labels = labels, strikes = if (lightning) ui.strikes else emptyList(), strikesNewest = ui.strikesNewest,
                fix = fix, motion = if (showRain && (frame?.minutes ?: 0) <= 120) motion else null, warnings = warnings,
                pick = picked?.let { it.lat to it.lon },
                onTap = { la, lo, long -> tapped(la, lo, long) },
                onMoving = { moving = it; if (it && picked != null) picked = null }) { transform = it }
        }
        val t = transform
        // Windteilchen blenden beim Verschieben kurz aus und erscheinen danach an der richtigen Stelle
        // (sie werden über der Karte gezeichnet und hingen beim Wischen sonst sichtbar hinterher).
        val windAlpha by androidx.compose.animation.core.animateFloatAsState(if (moving) 0f else 1f,
            androidx.compose.animation.core.tween(if (moving) 90 else 380), label = "windAlpha")
        if (layer == RadarLayer.WIND && t != null && ww?.wind != null && windAlpha > 0.01f)
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = windAlpha }) { WindParticles(ww, t) }
        if (preview != null && lightning && t != null) LightningDots(ui.strikes, ui.strikesNewest, t)
        val f = fix
        if (preview != null && f != null && t != null) MyLocationDot(f, t)
        if (preview != null && t != null) motion?.let { MotionArrowPreview(it, t) }
        if (preview != null && t != null) ComposeLabels(t, rememberLabels(t, cities, labelWw, layer != RadarLayer.WIND, units, place, fix, hotspots = fieldKind != null))

        // Oben nur der Titel (Schließen mit der Zurück-Geste)
        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val sh = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = .9f), Offset(0f, 1.5f), 6f)
            Text(title, style = WType.small.copy(shadow = sh))
            if (place != null) Text(place.name, style = WType.bodyDim.copy(shadow = sh))
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
            // Rechts über dem Bedienfeld: Standort und die Ebenen (senkrecht übereinander)
            Column(Modifier.align(Alignment.End).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fix != null) RoundButton({ AppLog.tap("Vollbild-Radar → Auf meinen Standort zentrieren"); centerTick++ }) { LocateGlyph() }
                ToggleButton(layer == RadarLayer.RAIN, { pick(RadarLayer.RAIN) }) { RainGlyph() }
                ToggleButton(lightning, { toggleLightning() }) { BoltGlyph() }
                ToggleButton(layer == RadarLayer.WIND, { pick(RadarLayer.WIND) }) { WindGlyph() }
                ToggleButton(layer == RadarLayer.TEMP, { pick(RadarLayer.TEMP) }) { TempGlyph() }
                ToggleButton(layer == RadarLayer.CLOUDS, { pick(RadarLayer.CLOUDS) }) { CloudGlyph() }
                ToggleButton(warnings, {
                    haptic(de.schee.wetter.ui.Haptics.Kind.SNAP); warnings = !warnings
                    AppLog.tap("Vollbild-Radar → Warnungen ${if (warnings) "an" else "aus"}")
                }) { WarnGlyph() }
            }
            // Angetippter Punkt: Karte im iOS-Stil über dem Bedienfeld
            androidx.compose.animation.AnimatedVisibility(picked != null,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it / 3 },
                exit = androidx.compose.animation.fadeOut()) {
                val p = picked
                if (p != null) PickCard(p, units, onSave = { savePlace(p, weatherVm, haptic) { toast = it } },
                    onClose = { AppLog.tap("Vollbild-Radar → Punkt-Karte geschlossen"); picked = null })
            }
            androidx.compose.animation.AnimatedVisibility(toast != null, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(toast ?: "", style = WType.small, modifier = Modifier.padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xE61C2233)).padding(horizontal = 14.dp, vertical = 9.dp))
            }
            LaunchedEffect(toast) { if (toast != null) { delay(2200); toast = null } }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xE6141927)).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (layer) {
                    RadarLayer.RAIN -> RainTimeline(ui, index, playing, onPlay = {
                        playing = !playing; AppLog.tap("Vollbild-Radar → ${if (playing) "Abspielen" else "Pause"}")
                    }, onIndex = { playing = false; index = it }, onDone = { AppLog.tap("Vollbild-Radar → Zeitleiste auf ${label(ui, index)}") })
                    RadarLayer.WIND, RadarLayer.TEMP -> WorldTimeline(ui, hour, playing, temp = layer == RadarLayer.TEMP, onPlay = {
                        playing = !playing; AppLog.tap("$title → ${if (playing) "Abspielen" else "Pause"}")
                    }, onHour = { playing = false; hour = it }, onDone = { AppLog.tap("$title → Zeitleiste auf Stunde +$hour") })
                    RadarLayer.CLOUDS -> CloudInfo(ui)
                    null -> if (!lightning) Text("Wähle rechts eine Ebene.", style = WType.bodyDim)
                }
                if (lightning) LightningInfo(ui, withRain = layer == RadarLayer.RAIN)
                if (showRain) motion?.let { MotionLine(it) }
                if (warnings) Text("Amtliche Warnungen des DWD (nur Deutschland) · gelb bis violett = Wetterwarnung bis extremes Unwetter",
                    style = WType.label.copy(color = WColors.Label))
            }
        }
    }
}

// ------------------------------------------------------------------ Antippen

/** Ein angetippter Punkt auf der Karte. */
data class Picked(val lat: Double, val lon: Double, val name: String?, val temp: Double?, val windKmh: Double?, val rain: String?)

private fun savePlace(p: Picked, vm: de.schee.wetter.ui.WeatherViewModel?, haptic: (de.schee.wetter.ui.Haptics.Kind) -> Unit, toast: (String) -> Unit) {
    val name = p.name ?: "%.2f°, %.2f°".format(java.util.Locale.GERMANY, p.lat, p.lon)
    vm?.addPlace(de.schee.wetter.data.Place(name, p.lat, p.lon, false))
    haptic(de.schee.wetter.ui.Haptics.Kind.SUCCESS)
    AppLog.tap("Vollbild-Radar → Ort „$name“ gespeichert")
    toast("„$name“ zu deinen Orten hinzugefügt")
}

@Composable
private fun PickCard(p: Picked, units: de.schee.wetter.data.Units, onSave: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xF0141927)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.name ?: "Ausgewählter Punkt", style = WType.condition, modifier = Modifier.weight(1f), maxLines = 1)
            Box(Modifier.size(28.dp).clip(CircleShape).background(Color.White.copy(alpha = .12f)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(10.dp)) {
                    val st = 1.8.dp.toPx()
                    drawLine(Color.White, Offset.Zero, Offset(size.width, size.height), st, androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(Color.White, Offset(size.width, 0f), Offset(0f, size.height), st, androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
        }
        val parts = listOfNotNull(p.temp?.let { units.t(it) }, p.windKmh?.let { "Wind ${units.w(it)}" })
        if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = WType.body)
        p.rain?.let { Text(it, style = WType.bodyDim.copy(color = if (it.startsWith("Kein")) WColors.TextDim else WColors.Rain)) }
        Text("+ Zu meinen Orten", style = WType.small.copy(color = WColors.Accent),
            modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onSave).padding(vertical = 4.dp))
    }
}

/** „Regen zieht nach Nordosten · 35 km/h“ mit kleinem Pfeil. */
@Composable
private fun MotionLine(m: de.schee.wetter.data.Nowcast.Motion) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.size(14.dp).graphicsLayer { rotationZ = m.towardDeg.toFloat() }) {
            val w = size.width; val h = size.height; val st = 1.8.dp.toPx()
            drawLine(Color.White, Offset(w / 2, h * .92f), Offset(w / 2, h * .1f), st, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(Color.White, Offset(w * .2f, h * .38f), Offset(w / 2, h * .08f), st, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(Color.White, Offset(w * .8f, h * .38f), Offset(w / 2, h * .08f), st, androidx.compose.ui.graphics.StrokeCap.Round)
        }
        Text("Regen zieht nach ${m.towardName} · etwa ${(m.kmh / 5).roundToInt() * 5} km/h", style = WType.label)
    }
}

/** Testbild: Pfeil über der Vorschau-Karte (in der App zeichnet ihn die Karte selbst). */
@Composable
private fun MotionArrowPreview(m: de.schee.wetter.data.Nowcast.Motion, t: MapTransform) {
    Canvas(Modifier.fillMaxSize()) {
        val a = Offset(t.x(m.fromLon), t.y(m.fromLat)); val b = Offset(t.x(m.toLon), t.y(m.toLat))
        val d = b - a; val len = d.getDistance(); if (len < 1f) return@Canvas
        val u = d / len; val n = Offset(-u.y, u.x); val head = len * .28f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(a.x, a.y); lineTo(b.x, b.y)
            val c = b - u * head
            moveTo((c + n * head * .62f).x, (c + n * head * .62f).y); lineTo(b.x, b.y); lineTo((c - n * head * .62f).x, (c - n * head * .62f).y)
        }
        val cap = androidx.compose.ui.graphics.StrokeCap.Round; val join = androidx.compose.ui.graphics.StrokeJoin.Round
        drawPath(path, Color.Black.copy(alpha = .55f), style = Stroke(6.5.dp.toPx(), cap = cap, join = join))
        drawPath(path, Color.White.copy(alpha = .92f), style = Stroke(3.dp.toPx(), cap = cap, join = join))
    }
}

// ------------------------------------------------------------------ Testbild

private fun previewTransform(w: Float, h: Float, area: DoubleArray?): MapTransform {
    val west = area?.get(0) ?: RadarData.WEST; val east = area?.get(1) ?: RadarData.EAST
    val south = area?.get(2) ?: RadarData.SOUTH; val north = area?.get(3) ?: RadarData.NORTH
    val span = RadarData.mercX(east) - RadarData.mercX(west)
    val cy = (RadarData.mercY(north) + RadarData.mercY(south)) / 2
    val half = span * h / w / 2
    fun lat(m: Double) = 360.0 / PI * atan(exp(m * 180.0 / 20037508.34 * PI / 180.0)) - 90.0
    return MapTransform(west, east, lat(cy + half), lat(cy - half), w, h)
}

@Composable
private fun PreviewMap(base: Bitmap, overlay: Bitmap?, field: Bitmap?, clouds: Bitmap?, t: MapTransform) {
    Box(Modifier.fillMaxSize()) {
        // Weltbilder an ihre Stelle (Mercator ±85°)
        for (bmp in listOfNotNull(field, clouds)) {
            val img = remember(bmp) { bmp.asImageBitmap() }
            Canvas(Modifier.fillMaxSize()) {
                val l = t.xRaw(-180.0); val r = t.xRaw(180.0); val top = t.y(Satellite.MAX_LAT); val b = t.y(-Satellite.MAX_LAT)
                drawImage(img, dstOffset = IntOffset(l.toInt(), top.toInt()), dstSize = IntSize((r - l).toInt(), (b - top).toInt()),
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
            }
        }
        Image(base.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        overlay?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().background(Color.Transparent), contentScale = ContentScale.Fit, alpha = .9f) }
    }
}

// ------------------------------------------------------------------ Standort

/** Blauer Punkt wie in Karten-Apps: weißer Rand, heller Kreis für die Ungenauigkeit, sanftes Pulsieren. */
@Composable
private fun MyLocationDot(fix: LocationSource.Fix, t: MapTransform) {
    val x = t.x(fix.lon); val y = t.y(fix.lat)
    if (!t.visible(x, y, 50f)) return
    val animate = LocalAnimate.current
    val pulse = if (animate) {
        androidx.compose.animation.core.rememberInfiniteTransition(label = "puls").animateFloat(
            0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1800)), label = "puls",
        ).value
    } else 0.4f
    val pxPerM = t.w / ((t.east - t.west) * 111_320.0 * kotlin.math.cos(Math.toRadians(fix.lat)))
    val acc = (fix.accuracyM * pxPerM).toFloat()
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(x, y)
        val blue = Color(0xFF0A84FF)
        if (acc > 12.dp.toPx()) drawCircle(blue.copy(alpha = .16f), acc.coerceAtMost(size.maxDimension), c)
        drawCircle(blue.copy(alpha = .35f * (1 - pulse)), 8.dp.toPx() + 14.dp.toPx() * pulse, c)
        drawCircle(Color.White, 9.dp.toPx(), c)
        drawCircle(blue, 6.5.dp.toPx(), c)
    }
}

// ------------------------------------------------------------------ Temperatur und Wind als Farbfläche

/** Temperaturfarben der Karte: ruhig, nicht zu bunt; kalt violett/blau, mild grün, heiß orange/rot. */
private val TEMP_STOPS = listOf(
    -30.0 to 0xFF6A5AA8, -15.0 to 0xFF4A6CC0, 0.0 to 0xFF4C9CC4, 8.0 to 0xFF4FA88C,
    15.0 to 0xFF9AB35A, 22.0 to 0xFFD2A546, 30.0 to 0xFFD9743C, 40.0 to 0xFFB8404A,
)
private val WIND_STOPS = listOf(
    0.0 to 0xFF2A4A7A, 10.0 to 0xFF2F7A92, 20.0 to 0xFF3C9C76, 35.0 to 0xFFC4B24A,
    50.0 to 0xFFD8803C, 70.0 to 0xFFCC4558, 100.0 to 0xFF9452C4,
)

private fun ramp(stops: List<Pair<Double, Long>>, v: Double): Int {
    if (v <= stops[0].first) return stops[0].second.toInt()
    for (i in 1 until stops.size) {
        val (a, ca) = stops[i - 1]; val (b, cb) = stops[i]
        if (v <= b) {
            val f = ((v - a) / (b - a)).toFloat()
            fun ch(s: Int) = ((((ca shr s) and 255) + ((((cb shr s) and 255) - ((ca shr s) and 255)) * f)).toInt()) and 255
            return ((255 shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0))
        }
    }
    return stops.last().second.toInt()
}

internal fun tempColorMap(c: Double): Int = ramp(TEMP_STOPS, c)
internal fun windColor(kmh: Double): Int = ramp(WIND_STOPS, kmh)

/** Weltbild (Mercator ±85°) mit Temperatur- oder Windfarben – die Karte zeichnet es weich. */
fun worldFieldBitmap(ww: WorldWeather, temp: Boolean, size: Int = 1024): Bitmap {
    val px = IntArray(size * size)
    val o = DoubleArray(2)
    for (y in 0 until size) {
        val lat = Satellite.latOfRow(y + 0.5, size)
        for (x in 0 until size) {
            val lon = Satellite.lonOfCol(x + 0.5, size)
            val c = if (temp) ww.tempAt(lat, lon).let { if (it.isNaN()) 0 else tempColorMap(it) }
            else { ww.windAt(lat, lon, o); windColor(hypot(o[0], o[1])) }
            px[y * size + x] = (c and 0xFFFFFF) or (0xA8 shl 24)
        }
    }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

/** Modell-Niederschlag (mm/h) im groben Ausschnitt-Gitter; über Deutschland liegt das DWD-Bild, dort ausgeblendet. */
fun precipViewBitmap(g: WindGrid, hour: Int): Bitmap {
    val w = g.cols * 2; val h = g.rows * 2
    val west = g.lon0 - g.step / 2; val east = g.lon1 + g.step / 2
    val yTop = RadarData.mercY((g.lat1 + g.step / 2).coerceAtMost(85.0)); val yBot = RadarData.mercY((g.lat0 - g.step / 2).coerceAtLeast(-85.0))
    val px = IntArray(w * h)
    for (y in 0 until h) {
        val my = (yTop + (y + 0.5) / h * (yBot - yTop)) * 180.0 / 20037508.34
        val lat = 360.0 / PI * atan(exp(my * PI / 180.0)) - 90.0
        for (x in 0 until w) {
            val lon = west + (x + 0.5) / w * (east - west)
            val mm = g.precipAt(hour, lat, lon)
            if (mm < 0.1) continue
            val dwd = minOf(lat - RadarData.SOUTH, RadarData.NORTH - lat, lon - RadarData.WEST, RadarData.EAST - lon)
            val keep = (1 - dwd / 0.5).coerceIn(0.0, 1.0)
            val edge = ((g.inside(lat, lon) + g.step / 2) / g.step).coerceIn(0.0, 1.0)
            val a = (if (mm < 1.0) 0xB0 else 0xD8) * keep * edge * ((mm - 0.1) / 0.3).coerceIn(0.0, 1.0)
            px[y * w + x] = (RadarData.colorFor(mm) and 0xFFFFFF) or (a.toInt() shl 24)
        }
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}

// ------------------------------------------------------------------ Wind: wandernde Linien

/** Länge der Spur: so viele Punkte, alle [TRAIL_MS] Millisekunden einer. */
private const val TRAIL = 14
private const val TRAIL_MS = 55L
/** Stufen für das Ausblenden (Lebensalter) und für den Verlauf der Spur (Schwanz blass → Kopf hell). */
private const val LIFE_STEPS = 4
private const val TAIL_STEPS = 4

/**
 * Strömungslinien: Jeder Strich ist die echte Spur eines Teilchens, das mit dem Wind treibt (Kevin am 10.11.2026:
 * „die sollen um die Kurve gehen“). Dadurch biegen sich die Linien in Wirbeln und Tiefs mit, statt als steife
 * Stäbe zu drehen. Die Spur liegt in Kartenkoordinaten und wandert beim Verschieben mit; der Schwanz wird
 * zum Ende hin blasser. Gezeichnet wird gebündelt (16 Pfade statt tausender Einzelstriche) → flüssig.
 */
@Composable
internal fun WindParticles(ww: WorldWeather, t: MapTransform, count: Int = 900) {
    val animate = LocalAnimate.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val field by androidx.compose.runtime.rememberUpdatedState(ww)
    fun area(m: MapTransform) = doubleArrayOf(maxOf(m.south, -84.0), minOf(m.north, 84.0), m.west, m.east)
    // je Teilchen: Spur als Ring (lat/lon), Anzahl gültiger Punkte, Kopf-Index, Lebensalter 0…1
    val lat = remember { DoubleArray(count * TRAIL) }
    val lon = remember { DoubleArray(count * TRAIL) }
    val n = remember { IntArray(count) }
    val head = remember { IntArray(count) }
    val life = remember { DoubleArray(count) }
    fun spawn(i: Int, a: DoubleArray) {
        if (a[0] >= a[1]) { life[i] = 2.0; return }
        val y0 = RadarData.mercY(a[0]); val y1 = RadarData.mercY(a[1])
        val my = (y0 + Random.nextDouble() * (y1 - y0)) * 180.0 / 20037508.34
        val k = i * TRAIL
        lat[k] = 360.0 / PI * atan(exp(my * PI / 180.0)) - 90.0
        lon[k] = a[2] + Random.nextDouble() * (a[3] - a[2])
        head[i] = 0; n[i] = 1; life[i] = 0.0
    }
    remember { val a = area(t); for (i in 0 until count) { spawn(i, a); life[i] = Random.nextDouble() }; true }
    var tick by remember { mutableFloatStateOf(0f) }
    val tr by androidx.compose.runtime.rememberUpdatedState(t)
    val density = LocalDensity.current.density
    // Testbild (ohne Animation): Spuren sofort „vorspulen“, damit die gebogenen Linien zu sehen sind
    remember(animate) {
        if (!animate) {
            val m = tr; val w = DoubleArray(2)
            val degX = (m.east - m.west) / m.w; val degY = (m.north - m.south) / m.h
            val step = 1.2 * density * TRAIL_MS / 1000.0 * 3
            for (i in 0 until count) {
                if (life[i] > 1.0) continue
                life[i] = .5
                for (s in 1 until TRAIL) {
                    val k = i * TRAIL + head[i]
                    field.windAt(lat[k], lon[k], w)
                    val nh = (head[i] + 1) % TRAIL
                    lon[i * TRAIL + nh] = lon[k] + w[0] * step * degX
                    lat[i * TRAIL + nh] = lat[k] + w[1] * step * degY
                    head[i] = nh; n[i] = minOf(TRAIL, n[i] + 1)
                }
            }
        }
        true
    }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = 0L
            var lastSpan = 0.0
            var acc = 0L
            val w = DoubleArray(2); val w2 = DoubleArray(2)
            // laufende Position zwischen zwei Spurpunkten
            val cur = DoubleArray(2 * count)
            for (i in 0 until count) { val k = i * TRAIL + head[i]; cur[2 * i] = lat[k]; cur[2 * i + 1] = lon[k] }
            fun respawn(i: Int, a: DoubleArray) { spawn(i, a); cur[2 * i] = lat[i * TRAIL]; cur[2 * i + 1] = lon[i * TRAIL] }
            while (isActive) {
                androidx.compose.runtime.withFrameNanos { now ->
                    val dtN = if (last == 0L) 0L else (now - last).coerceAtMost(50_000_000L)
                    val dt = dtN / 1e9
                    last = now
                    val m = tr
                    val a = area(m)
                    val degX = (m.east - m.west) / m.w; val degY = (m.north - m.south) / m.h
                    // Deutlich gezoomt: alle Striche sofort im ganzen Bild neu verteilen
                    val span = m.east - m.west
                    val rezoom = lastSpan > 0 && (span > lastSpan * 1.25 || span < lastSpan / 1.25)
                    if (rezoom || lastSpan == 0.0) lastSpan = span
                    acc += dtN
                    val sample = acc >= TRAIL_MS * 1_000_000L
                    if (sample) acc %= TRAIL_MS * 1_000_000L
                    val k1 = 1.2 * density * dt
                    for (i in 0 until count) {
                        if (rezoom) { respawn(i, a); life[i] = Random.nextDouble() * .8; continue }
                        if (life[i] > 1.5) { respawn(i, a); continue }
                        // Mittelpunkt-Verfahren: folgt Kurven sauber, auch im Zentrum von Wirbeln
                        val la = cur[2 * i]; val lo = cur[2 * i + 1]
                        field.windAt(la, lo, w)
                        field.windAt(la + w[1] * k1 * degY * .5, lo + w[0] * k1 * degX * .5, w2)
                        val nla = la + w2[1] * k1 * degY; val nlo = lo + w2[0] * k1 * degX
                        cur[2 * i] = nla; cur[2 * i + 1] = nlo
                        life[i] += dt / 2.6
                        if (sample) { head[i] = (head[i] + 1) % TRAIL; n[i] = minOf(TRAIL, n[i] + 1) }
                        val k = i * TRAIL + head[i]
                        lat[k] = nla; lon[k] = nlo
                        val ln = m.norm(nlo)
                        if (life[i] > 1.0 || nla < a[0] || nla > a[1] || ln < a[2] || ln > a[3]) respawn(i, a)
                    }
                    tick = (now / 1e9).toFloat()
                }
            }
        }
    }
    val paths = remember { Array(LIFE_STEPS * TAIL_STEPS) { androidx.compose.ui.graphics.Path() } }
    val stroke = remember(density) {
        androidx.compose.ui.graphics.drawscope.Stroke(width = 1.3f * density, cap = StrokeCap.Butt, join = androidx.compose.ui.graphics.StrokeJoin.Round)
    }
    Canvas(Modifier.fillMaxSize()) {
        @Suppress("UNUSED_VARIABLE") val redraw = tick
        for (p in paths) p.rewind()
        for (i in 0 until count) {
            val lf = life[i]
            val cnt = n[i]
            if (lf > 1.0 || cnt < 2) continue
            val fade = kotlin.math.sin(lf * PI)
            if (fade < .08) continue
            val ls = (fade * LIFE_STEPS).toInt().coerceIn(0, LIFE_STEPS - 1)
            // vom ältesten zum neuesten Punkt; Sprung über die Datumsgrenze auslassen
            // Innerhalb einer Helligkeitsstufe als ein Zug (lineTo) – sonst leuchten die Stoßstellen als Pünktchen
            var px = 0f; var py = 0f; var pl = 0.0; var open = -1
            for (j in 0 until cnt) {
                val idx = i * TRAIL + ((head[i] - (cnt - 1 - j)) % TRAIL + TRAIL) % TRAIL
                val x = t.x(lon[idx]); val y = t.y(lat[idx])
                if (j > 0 && kotlin.math.abs(lon[idx] - pl) < 90) {
                    val b = ls * TAIL_STEPS + (j * TAIL_STEPS / cnt).coerceIn(0, TAIL_STEPS - 1)
                    val path = paths[b]
                    if (open != b) path.moveTo(px, py)
                    path.lineTo(x, y); open = b
                } else open = -1
                px = x; py = y; pl = lon[idx]
            }
        }
        for (ls in 0 until LIFE_STEPS) for (ts in 0 until TAIL_STEPS) {
            val alpha = ((ls + 1f) / LIFE_STEPS) * (.1f + .8f * ((ts + 1f) / TAIL_STEPS))
            drawPath(paths[ls * TAIL_STEPS + ts], Color.White.copy(alpha = alpha * .78f), style = stroke)
        }
    }
}

// ------------------------------------------------------------------ Blitze

/**
 * Blitze als Punkte (wie bei WetterOnline): neue Blitze leuchten rot-gelb mit wachsendem Ring auf,
 * ältere werden gelb und dann orange und blasser. Alter gemessen am jüngsten Abschnitt mit Daten.
 */
@Composable
private fun LightningDots(strikes: List<Satellite.Strike>, newest: Instant?, t: MapTransform) {
    if (strikes.isEmpty() || newest == null) return
    val animate = LocalAnimate.current
    val pulse = if (animate) androidx.compose.animation.core.rememberInfiniteTransition(label = "blitz").animateFloat(
        0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1400)), label = "blitz").value else 0.5f
    val density = LocalDensity.current.density
    val visible = remember(strikes, t) {
        strikes.mapNotNull { s ->
            val x = t.x(s.lon); val y = t.y(s.lat)
            if (!t.visible(x, y, 20f)) null else Triple(x, y, s)
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val yellow = Color(0xFFFFE135); val orange = Color(0xFFF2A12E); val red = Color(0xFFE5322D)
        for ((x, y, s) in visible) {
            val age = (newest.epochSecond - s.time.epochSecond) / 60.0
            val r = (3.0f + 2.5f * s.strength) * density
            val c = Offset(x, y)
            when {
                age < 10 -> {
                    drawCircle(yellow, r * 1.25f, c)
                    drawCircle(red, r * 0.55f, c)
                    drawCircle(red.copy(alpha = .9f), r * 1.25f, c, style = Stroke(1.2f * density))
                }
                age < 25 -> { drawCircle(yellow, r, c); drawCircle(orange, r, c, style = Stroke(1f * density)) }
                age < 45 -> drawCircle(yellow.copy(alpha = .85f), r * .85f, c)
                else -> drawCircle(orange.copy(alpha = .6f), r * .7f, c)
            }
        }
        // Ringe um die jüngsten (nur die stärksten, höchstens 12, damit es ruhig bleibt)
        for ((x, y, s) in visible.filter { (_, _, s) -> newest.epochSecond - s.time.epochSecond < 300 && s.strength >= .7f }.sortedByDescending { it.third.strength }.take(12)) {
            drawCircle(Color.White.copy(alpha = .75f * (1 - pulse)), (8 + 22 * pulse) * density, Offset(x, y), style = Stroke(1.4f * density))
        }
    }
}

// ------------------------------------------------------------------ Orte und Werte

/** Ort, den die Karte beschriftet (fromMap) oder aus der Weltliste; kleinerer Rang = wichtiger. */
data class MapCity(val name: String, val lat: Double, val lon: Double, val rank: Int = 50, val fromMap: Boolean = true)

/**
 * Ortsnamen und Werte wie bei WetterOnline:
 *  - Werte (Temperatur bzw. Wind) über den Städten, die die Karte zeigt – bei weitem Herauszoomen
 *    zusätzlich Großstädte aus der Weltliste (mit eigenem Namen und Punkt)
 *  - dazwischen einzelne „Hotspots“: auffällig warme/kalte bzw. windige Stellen, auch in der Wüste oder auf See
 *  - der eigene Ort mit kleinem weißem Punkt
 * Die Auswahl wird nur nach dem Verschieben/Zoomen neu bestimmt ([idle]); beim Wischen wandern die Werte mit.
 */
@Composable
internal fun rememberLabels(
    idle: MapTransform, cities: List<MapCity>, ww: WorldWeather?, temp: Boolean,
    units: de.schee.wetter.data.Units, place: RadarPlace?, fix: LocationSource.Fix?, mini: Boolean = false,
    /** Hotspots (auffällige Stellen ohne Ort) nur bei Temperatur und Wind. */
    hotspots: Boolean = true,
): List<MapLabel> {
    // Testbilder: sofort (synchron) berechnen
    val density = LocalDensity.current.density
    val idx = Places.index.collectAsState().value
    val picks = remember(idle, cities, ww != null, temp, place, hotspots, idx) { pickLabels(idle, cities, ww, temp, place, fix, mini, hotspots, density, idx) }
    return remember(picks, ww, temp, place) { labelValues(picks, ww, temp, units, place) }
}

/**
 * Auf dem Handy: dieselbe Auswahl im Hintergrund (Dispatchers.Default) – die Karte ruckelt beim Stillstand nicht mehr
 * (bis 0.6.6 lief die Suche in 139 000 Orten im Haupt-Thread). Der Standort-Takt löst keine Neuberechnung aus.
 */
@Composable
internal fun rememberLabelsAsync(
    idle: MapTransform?, cities: List<MapCity>, ww: WorldWeather?, temp: Boolean,
    units: de.schee.wetter.data.Units, place: RadarPlace?, fix: LocationSource.Fix?, mini: Boolean = false, hotspots: Boolean = true,
): List<MapLabel> {
    val density = LocalDensity.current.density
    val idx = Places.index.collectAsState().value
    val fixNow by androidx.compose.runtime.rememberUpdatedState(fix)
    var picks by remember { mutableStateOf<List<LabelPick>>(emptyList()) }
    var labels by remember { mutableStateOf<List<MapLabel>>(emptyList()) }
    LaunchedEffect(idle, cities, ww != null, temp, place, hotspots, idx) {
        val area = idle ?: return@LaunchedEffect
        delay(60) // mehrere Änderungen kurz hintereinander zusammenfassen
        picks = withContext(Dispatchers.Default) { pickLabels(area, cities, ww, temp, place, fixNow, mini, hotspots, density, idx) }
    }
    LaunchedEffect(picks, ww, temp, place) {
        labels = withContext(Dispatchers.Default) { labelValues(picks, ww, temp, units, place) }
    }
    return labels
}

/** Gewählte Stelle für eine Beschriftung. kind wie bei [MapLabel]. */
internal data class LabelPick(val lat: Double, val lon: Double, val name: String?, val kind: Int)

internal fun pickLabels(
    idle: MapTransform, cities: List<MapCity>, ww: WorldWeather?, temp: Boolean, place: RadarPlace?, fix: LocationSource.Fix?,
    mini: Boolean, hotspots: Boolean, density: Float, idx: Places.Index?,
): List<LabelPick> {
    val L = ::LabelPick
    run {
        val out = mutableListOf<LabelPick>()
        // Platz jeder Beschriftung als Rechteck (Name oben, Pille unten) – nichts darf sich überlappen
        val boxes = ArrayList<FloatArray>()
        val gap = 4 * density
        fun overlaps(b: FloatArray) = boxes.any { o -> b[0] < o[2] + gap && b[2] > o[0] - gap && b[1] < o[3] + gap && b[3] > o[1] - gap }
        fun inUi(b: FloatArray) = !mini && (b[1] < 84 * density || b[3] > idle.h - 214 * density || (b[2] > idle.w - 74 * density && b[3] > idle.h - 560 * density))
        // Breite schätzen: Name (≈ 7 dp je Zeichen in 12,5 sp fett) bzw. Pille (≈ 46 dp)
        fun box(x: Float, y: Float, name: String?, kind: Int): FloatArray {
            val nameW = (name?.length ?: 0) * 6.8f * density + 4 * density
            val w = maxOf(nameW, (if (temp) 34f else 58f) * density)
            return when (kind) {
                0 -> floatArrayOf(x - w / 2, y - 10 * density, x + w / 2, y + 10 * density)
                2 -> floatArrayOf(x - w / 2, y - 29 * density, x + w / 2, y + 29 * density)
                else -> floatArrayOf(x - w / 2, y - 20 * density, x + w / 2, y + 21 * density)
            }
        }
        fun add(l: LabelPick): Boolean {
            val x = idle.x(l.lon); val y = idle.y(l.lat)
            if (!idle.visible(x, y)) return false
            val b = box(x, y, l.name, l.kind)
            if (b[0] < 4 * density || b[2] > idle.w - 4 * density || inUi(b) || overlaps(b)) return false
            out += l; boxes += b; return true
        }
        // eigener Ort zuerst (Name über, Wert unter dem blauen Punkt)
        place?.let { p ->
            val x = idle.x(p.lon); val y = idle.y(p.lat)
            if (idle.visible(x, y)) { out += L(p.lat, p.lon, null, 2); boxes += box(x, y, p.name.substringBefore(" · "), 2) }
        }
        fix?.let { f -> val x = idle.x(f.lon); val y = idle.y(f.lat); boxes += floatArrayOf(x - 10 * density, y - 10 * density, x + 10 * density, y + 10 * density) }
        val ownName = place?.name?.substringBefore(" · ")
        // Orte nach Größe (GeoNames): füllt den freien Platz von groß nach klein – so dicht, wie es passt
        if (idx != null) {
            for (i in Places.inView(idx, idle.south, idle.north, idle.west, idle.east, limit = if (mini) 400 else 4000)) {
                val n = idx.name(i); if (n == ownName) continue
                add(L(idx.lat[i].toDouble(), idx.lon[i].toDouble(), n, 3))
                if (out.size > 70) break
            }
        }
        // dazu Orte aus den Kartendaten (beim Hineinzoomen auch Dörfer unter 1000 Einwohnern)
        val taken = out.mapNotNull { it.name }.toHashSet()
        if (idx != null) for (c in cities.sortedBy { it.rank }) if (c.name != ownName && c.name !in taken) add(L(c.lat, c.lon, c.name, 3))
        if (idx == null) {
            // Ortsliste noch nicht geladen: Großstädte der eingebauten Weltliste
            for (c in WorldCities.ALL.sortedBy { it.rank }) add(L(c.lat, c.lon, c.name, 3))
        }
        // Hotspots: auffällige Stellen im verbleibenden freien Raum (nur bei Temperatur/Wind)
        if (ww != null && hotspots && !mini) {
            val stepPx = 34 * density
            val nx = (idle.w / stepPx).toInt() + 1; val ny = (idle.h / stepPx).toInt() + 1
            val v = Array(ny) { DoubleArray(nx) }
            val o = DoubleArray(2)
            for (j in 0 until ny) for (i in 0 until nx) {
                val lat = idle.lat(j * stepPx).coerceIn(-84.0, 84.0); val lon = idle.lon(i * stepPx)
                v[j][i] = if (temp) ww.tempAt(lat, lon) else { ww.windAt(lat, lon, o); hypot(o[0], o[1]) }
            }
            data class H(val i: Int, val j: Int, val score: Double)
            val cand = mutableListOf<H>()
            val r = 3
            for (j in 0 until ny) for (i in 0 until nx) {
                val c = v[j][i]; if (c.isNaN()) continue
                var max = true; var min = true; var sum = 0.0; var n = 0
                for (dj in -r..r) for (di in -r..r) {
                    if (di == 0 && dj == 0) continue
                    val q = v.getOrNull(j + dj)?.getOrNull(i + di) ?: continue
                    if (q.isNaN()) continue
                    if (q > c) max = false; if (q < c) min = false; sum += q; n++
                }
                if (n == 0) continue
                val dev = c - sum / n
                if (temp && (max || min) && abs(dev) > 0.8) cand += H(i, j, abs(dev))
                if (!temp && max && c > 8) cand += H(i, j, c)
            }
            for (h in cand.sortedByDescending { it.score }.take(14)) {
                add(L(idle.lat(h.j * stepPx), idle.lon(h.i * stepPx), null, 0))
            }
        }
        return out
    }
}

/** Werte zur gewählten Stunde (ändern sich beim Abspielen, die Auswahl der Orte bleibt). */
internal fun labelValues(picks: List<LabelPick>, ww: WorldWeather?, temp: Boolean, units: de.schee.wetter.data.Units, place: RadarPlace?): List<MapLabel> {
    run {
        val o = DoubleArray(2)
        return picks.mapNotNull { l ->
            var color: Int? = null
            val value: String? = when {
                ww != null && temp -> ww.tempAt(l.lat, l.lon).takeIf { !it.isNaN() }?.let { color = tempColorMap(it); "${units.tNum(it)}°" }
                ww != null -> { ww.windAt(l.lat, l.lon, o); val k = hypot(o[0], o[1]); color = windColor(k); "${units.wNum(k)} ${units.windLabel}" }
                l.kind == 2 -> place?.tempLabel
                else -> null
            }
            // eigener Ort: Name ohne Zusatz („Saffig · Rheinland-Pfalz …“ → „Saffig“)
            val name = if (l.kind == 2) place?.name?.substringBefore(" · ")?.takeIf { color != null } else l.name
            if (value == null && l.kind != 3) null else MapLabel(l.lat, l.lon, value, name, l.kind, color)
        }
    }
}

/** Nur für die Testbilder am PC (dort läuft MapLibre nicht): dieselbe Beschriftung mit Compose gezeichnet. */
@Composable
private fun ComposeLabels(t: MapTransform, labels: List<MapLabel>) {
    val density = LocalDensity.current.density
    val shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = .9f), Offset(0f, 1.5f), 5f)
    val nameStyle = WType.small.copy(fontWeight = FontWeight.Bold, shadow = shadow, fontSize = 12.5.sp)
    for (l in labels) {
        val x = t.x(l.lon); val y = t.y(l.lat)
        if (!t.visible(x, y, 40f)) continue
        val pill = l.color?.let { Color(pillColor(it)) }
        if (pill == null && l.kind != 3) {
            // schlichter Wert (z. B. eigener Ort bei Regen)
            androidx.compose.ui.layout.Layout(content = { Text(l.value ?: "", style = nameStyle) }) { m, c ->
                val p = m.first().measure(c.copy(minWidth = 0, minHeight = 0))
                layout(p.width, p.height) { p.place((x - p.width / 2).toInt(), (y - p.height - 6 * density).toInt()) }
            }
            continue
        }
        if (l.kind != 0 && l.kind != 2) Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color(0xFFF2F3F5), (if (l.kind == 2) 3.4f else 2.6f) * density, Offset(x, y))
            drawCircle(Color.Black.copy(alpha = .6f), (if (l.kind == 2) 3.4f else 2.6f) * density, Offset(x, y), style = Stroke(1f))
        }
        androidx.compose.ui.layout.Layout(content = {
            if (l.name != null && l.kind != 0) Text(l.name, style = nameStyle)
            if (l.value != null && l.color != null) { val img = remember(l.value, l.color) { pillBitmap(l.value, l.color, density).asImageBitmap() }; Image(img, null) }
        }) { ms, c ->
            val ps = ms.map { it.measure(c.copy(minWidth = 0, minHeight = 0)) }
            layout(t.w.toInt(), t.h.toInt()) {
                var i = 0
                if (l.name != null && l.kind != 0) { val n = ps[i++]; n.place((x - n.width / 2).toInt(), (y - n.height - (if (l.kind == 2) 11 else 4) * density).toInt()) }
                if (i < ps.size) { val v = ps[i]; val top = if (l.kind == 0) y - v.height / 2 else y + (if (l.kind == 2) 12 else 4) * density; v.place((x - v.width / 2).toInt(), top.toInt()) }
            }
        }
    }
}

// ------------------------------------------------------------------ Bedienfeld unten

private fun label(ui: RadarUi, i: Int): String {
    val f = ui.frames.getOrNull(i) ?: return ""
    val m = f.minutes
    val rel = when {
        m == 0 -> "Jetzt"
        m < 0 -> "vor ${-m} Min."
        m < 60 -> "in $m Min."
        else -> "in ${m / 60} Std." + if (m % 60 != 0) " ${m % 60} Min." else ""
    }
    val src = when (f.kind) { RadarData.Kind.RADAR -> "Radar"; RadarData.Kind.FORECAST -> "Radar-Vorhersage"; RadarData.Kind.MODEL -> "Modell" }
    return "${hhmm.format(f.time)} · $rel · $src"
}

@Composable
private fun RainTimeline(ui: RadarUi, index: Int, playing: Boolean, onPlay: () -> Unit, onIndex: (Int) -> Unit, onDone: () -> Unit) {
    when {
        ui.error != null -> { Text(ui.error, style = WType.body.copy(color = WColors.Warn)); return }
        ui.frames.isEmpty() -> { Text("Radar wird geladen …", style = WType.body); return }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundButton(onPlay, dark = false) { if (playing) PauseGlyph() else PlayGlyph() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label(ui, index), style = WType.small)
            val missing = ui.frames.size - ui.loaded
            Text(
                if (missing > 0) "Lade Radarbilder … ${ui.loaded}/${ui.frames.size}"
                else if (ui.bytes[index] == null) "Für diesen Zeitpunkt gibt es kein Bild."
                else "DWD · weltweit RainViewer",
                style = WType.bodyDim,
            )
        }
    }
    TimelineSlider(index.coerceAtLeast(0), ui.frames.size, ui.nowIndex, onIndex, onDone,
        modelFrom = ui.frames.indexOfFirst { it.kind == RadarData.Kind.MODEL },
        major = { i -> i == ui.nowIndex || ui.frames.getOrNull(i)?.let { f -> f.kind == RadarData.Kind.MODEL || f.time.epochSecond % 3600 == 0L } == true })
    Legend(RadarData.LEGEND.map { it.first to Color(RadarData.colorFor(it.second)) })
}

@Composable
private fun WorldTimeline(ui: RadarUi, hour: Int, playing: Boolean, temp: Boolean, onPlay: () -> Unit, onHour: (Int) -> Unit, onDone: () -> Unit) {
    val hours = ui.worldHours
    when {
        ui.worldNow == null && ui.worldError != null -> { Text(ui.worldError, style = WType.body.copy(color = WColors.Warn)); return }
        ui.worldNow == null -> { Text(if (temp) "Temperaturen werden geladen …" else "Winddaten werden geladen …", style = WType.body); return }
    }
    val h = hour.coerceIn(0, hours.size - 1)
    val loaded = ui.world[hours[h]] != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundButton(onPlay, dark = false) { if (playing) PauseGlyph() else PlayGlyph() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${hhmm.format(hours[h])} · ${if (h == 0) "Jetzt" else "in $h Std."}", style = WType.small)
            Text(if (!loaded) "Diese Stunde wird geladen …" else if (temp) "Temperatur in 2 m Höhe · DWD (ICON)" else "Wind in 10 m Höhe, km/h · DWD (ICON)", style = WType.bodyDim)
        }
    }
    TimelineSlider(h, hours.size, 0, onHour, onDone, modelFrom = -1)
    if (temp) Legend(listOf(-20, -10, 0, 10, 20, 30, 40).map { "$it°" to Color(tempColorMap(it.toDouble())) })
    else Legend(listOf(0, 10, 20, 35, 50, 70, 100).map { "$it" to Color(windColor(it.toDouble())) })
}

@Composable
private fun CloudInfo(ui: RadarUi) {
    val c = ui.clouds
    Text(if (c != null) "Wolken · Satellitenbild, geladen ${hhmm.format(Instant.ofEpochMilli(c.loadedAt))}" else ui.cloudsError ?: "Wolken werden geladen …", style = WType.small)
    Text("Europa/Afrika alle 10 Min., sonst alle 3 Std. · © EUMETSAT", style = WType.bodyDim)
}

@Composable
private fun LightningInfo(ui: RadarUi, withRain: Boolean) {
    if (!withRain) Text(ui.strikesProgress?.let { (d, n) -> "Blitze werden geladen … $d/$n" + (ui.strikesNewest?.let { " · Stand ${hhmm.format(it)}" } ?: "") }
        ?: if (ui.strikesNewest != null) "Blitze der letzten Stunde · Stand ${hhmm.format(ui.strikesNewest)}" else "Gerade keine Blitze in Europa und Afrika", style = WType.small)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        @Composable fun dot(c: Color, ring: Color?, text: String) {
            Canvas(Modifier.size(10.dp)) { drawCircle(c, size.minDimension / 2); ring?.let { drawCircle(it, size.minDimension / 2, style = Stroke(1.5f)) } }
            Text(text, style = WType.label)
        }
        dot(Color(0xFFE5322D), Color(0xFFFFE135), "neu")
        dot(Color(0xFFFFE135), Color(0xFFF2A12E), "15 Min.")
        dot(Color(0xFFFFE135).copy(alpha = .85f), null, "30 Min.")
        dot(Color(0xFFF2A12E).copy(alpha = .6f), null, "60 Min.")
    }
    if (!withRain) Text("Satellit MTG: Europa, Afrika, Atlantik · © EUMETSAT", style = WType.bodyDim)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelineSlider(index: Int, count: Int, nowIndex: Int, onIndex: (Int) -> Unit, onDone: () -> Unit, modelFrom: Int, major: (Int) -> Boolean = { true }) {
    if (count < 2) return
    Box {
        Canvas(Modifier.fillMaxWidth().height(40.dp)) {
            val pad = 10.dp.toPx(); val wd = size.width - 2 * pad
            fun x(i: Int) = pad + wd * i / (count - 1)
            drawLine(Color.White.copy(alpha = .7f), Offset(x(nowIndex), size.height / 2 - 10.dp.toPx()), Offset(x(nowIndex), size.height / 2 + 10.dp.toPx()), 2.dp.toPx())
            if (modelFrom > 0) drawLine(WColors.Accent.copy(alpha = .6f), Offset(x(modelFrom), size.height / 2 - 7.dp.toPx()), Offset(x(modelFrom), size.height / 2 + 7.dp.toPx()), 2.dp.toPx())
        }
        val haptic = de.schee.wetter.ui.rememberHaptics()
        val current by androidx.compose.runtime.rememberUpdatedState(index)
        Slider(
            value = index.toFloat(),
            onValueChange = {
                val i = it.toInt().coerceIn(0, count - 1)
                if (i != current) haptic(if (major(i)) de.schee.wetter.ui.Haptics.Kind.HOUR else de.schee.wetter.ui.Haptics.Kind.TICK)
                onIndex(i)
            },
            onValueChangeFinished = onDone,
            valueRange = 0f..(count - 1).toFloat(),
            modifier = Modifier.fillMaxWidth().height(40.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White, activeTrackColor = WColors.Accent, inactiveTrackColor = Color.White.copy(alpha = .18f),
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
            ),
            thumb = { Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White)) },
        )
    }
}

@Composable
private fun Legend(items: List<Pair<String, Color>>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Brush.horizontalGradient(items.map { it.second })))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            items.forEach { Text(it.first, style = WType.label) }
        }
    }
}

// ------------------------------------------------------------------ Knöpfe

@Composable
private fun RoundButton(onClick: () -> Unit, dark: Boolean = true, content: @Composable () -> Unit) {
    Box(
        Modifier.pressable(scale = .88f, onClick = onClick).size(44.dp).clip(CircleShape).background(if (dark) Color(0xE61C2233) else Color.White.copy(alpha = .14f)),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Runder Schalter; eingeschaltet hell hinterlegt. */
@Composable
private fun ToggleButton(on: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.pressable(scale = .88f, haptic = null, onClick = onClick).size(46.dp).clip(CircleShape)
            .background(animateColorAsState(if (on) WColors.Accent.copy(alpha = .9f) else Color(0xE61C2233), label = "toggle").value),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable private fun PlayGlyph() = Canvas(Modifier.size(16.dp)) {
    val p = androidx.compose.ui.graphics.Path().apply { moveTo(size.width * .15f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * .15f, size.height); close() }
    drawPath(p, Color.White)
}

@Composable private fun PauseGlyph() = Canvas(Modifier.size(16.dp)) {
    val w = size.width * .3f
    drawRect(Color.White, Offset(size.width * .1f, 0f), androidx.compose.ui.geometry.Size(w, size.height))
    drawRect(Color.White, Offset(size.width * .6f, 0f), androidx.compose.ui.geometry.Size(w, size.height))
}

/** Pfeil wie „Mein Standort“ in Karten-Apps. */
@Composable private fun LocateGlyph() = Canvas(Modifier.size(18.dp)) {
    val p = androidx.compose.ui.graphics.Path().apply {
        moveTo(size.width, 0f); lineTo(size.width * .55f, size.height); lineTo(size.width * .45f, size.height * .55f); lineTo(0f, size.height * .45f); close()
    }
    drawPath(p, Color.White)
}

/** Wolke mit Regentropfen. */
@Composable private fun RainGlyph() = Canvas(Modifier.size(22.dp)) {
    val s = size.width
    drawCircle(Color.White, s * .2f, Offset(s * .35f, s * .38f)); drawCircle(Color.White, s * .25f, Offset(s * .58f, s * .32f))
    drawRoundRect(Color.White, Offset(s * .12f, s * .35f), androidx.compose.ui.geometry.Size(s * .76f, s * .2f), androidx.compose.ui.geometry.CornerRadius(s * .1f))
    for (i in 0..2) {
        val x = s * (.3f + i * .2f)
        drawLine(Color.White, Offset(x, s * .66f), Offset(x - s * .07f, s * .9f), 1.8.dp.toPx(), StrokeCap.Round)
    }
}

/** Drei geschwungene Windlinien. */
@Composable private fun WindGlyph() = Canvas(Modifier.size(22.dp)) {
    val s = size.width; val st = Stroke(1.9.dp.toPx(), cap = StrokeCap.Round)
    fun line(y: Float, len: Float) = androidx.compose.ui.graphics.Path().apply {
        moveTo(s * .08f, y); lineTo(s * len, y)
        quadraticTo(s * (len + .16f), y, s * (len + .16f), y - s * .1f); quadraticTo(s * (len + .16f), y - s * .2f, s * (len + .05f), y - s * .2f)
    }
    drawPath(line(s * .32f, .62f), Color.White, style = st)
    drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(s * .08f, s * .55f); lineTo(s * .86f, s * .55f) }, Color.White, style = st)
    drawPath(androidx.compose.ui.graphics.Path().apply {
        moveTo(s * .08f, s * .76f); lineTo(s * .55f, s * .76f)
        quadraticTo(s * .7f, s * .76f, s * .7f, s * .86f); quadraticTo(s * .7f, s * .96f, s * .58f, s * .96f)
    }, Color.White, style = st)
}

/** Wolke. */
@Composable private fun CloudGlyph() = Canvas(Modifier.size(22.dp)) {
    val s = size.width
    drawCircle(Color.White, s * .2f, Offset(s * .34f, s * .52f)); drawCircle(Color.White, s * .27f, Offset(s * .58f, s * .44f))
    drawRoundRect(Color.White, Offset(s * .1f, s * .5f), androidx.compose.ui.geometry.Size(s * .8f, s * .24f), androidx.compose.ui.geometry.CornerRadius(s * .12f))
}

/** Blitz. */
/** Warndreieck. */
@Composable private fun WarnGlyph() = Canvas(Modifier.size(22.dp)) {
    val s = size.width; val st = 1.8.dp.toPx()
    val p = androidx.compose.ui.graphics.Path().apply { moveTo(s * .5f, s * .1f); lineTo(s * .94f, s * .88f); lineTo(s * .06f, s * .88f); close() }
    drawPath(p, Color.White, style = Stroke(st, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    drawLine(Color.White, Offset(s * .5f, s * .38f), Offset(s * .5f, s * .62f), st, androidx.compose.ui.graphics.StrokeCap.Round)
    drawCircle(Color.White, st * .7f, Offset(s * .5f, s * .75f))
}

@Composable private fun BoltGlyph() = Canvas(Modifier.size(22.dp)) {
    val s = size.width
    val p = androidx.compose.ui.graphics.Path().apply {
        moveTo(s * .6f, s * .04f); lineTo(s * .2f, s * .56f); lineTo(s * .47f, s * .56f); lineTo(s * .38f, s * .96f); lineTo(s * .8f, s * .4f); lineTo(s * .53f, s * .4f); close()
    }
    drawPath(p, Color.White)
}

/** Thermometer. */
@Composable private fun TempGlyph() = Canvas(Modifier.size(22.dp)) {
    val s = size.width; val st = Stroke(1.8.dp.toPx())
    drawRoundRect(Color.White, Offset(s * .4f, s * .06f), androidx.compose.ui.geometry.Size(s * .2f, s * .6f), androidx.compose.ui.geometry.CornerRadius(s * .1f), style = st)
    drawCircle(Color.White, s * .17f, Offset(s * .5f, s * .76f))
    drawLine(Color.White, Offset(s * .5f, s * .35f), Offset(s * .5f, s * .7f), 2.2.dp.toPx(), StrokeCap.Round)
}

// ------------------------------------------------------------------ Kacheln auf der Hauptseite

/**
 * Live-Karte für die Kacheln „Regenradar“ und „Windkarte“: echte Karte um den Ort, nicht bedienbar
 * (Tippen öffnet wie gewohnt das Vollbild – die Tippfläche liegt über der Karte).
 * Nutzt die beim Öffnen der App vorgeladenen Daten. [wind] = Windkarte, sonst Regen.
 */
@Composable
fun MiniRadar(place: RadarPlace, wind: Boolean, modifier: Modifier = Modifier, vm: RadarViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val units = remember { de.schee.wetter.data.Units.from(context) }
    var transform by remember { mutableStateOf<MapTransform?>(null) }
    var idle by remember { mutableStateOf<MapTransform?>(null) }
    var cities by remember { mutableStateOf<List<MapCity>>(emptyList()) }
    var shown by remember { mutableStateOf<Bitmap?>(null) }
    val nowBytes = ui.bytes[ui.nowIndex]
    LaunchedEffect(nowBytes, wind) {
        if (wind) return@LaunchedEffect
        val b = nowBytes ?: return@LaunchedEffect
        shown = withContext(Dispatchers.Default) { RadarData.render(b, RadarData.Kind.RADAR) }
    }
    val rvIndex = ui.frames.getOrNull(ui.nowIndex)?.let { de.schee.wetter.data.RainViewer.indexFor(ui.rainViewer, it.time) }
    val ww = ui.worldNow
    var field by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(wind, ww) {
        field = if (wind && ww != null) withContext(Dispatchers.Default) { worldFieldBitmap(ww, temp = false, size = 768) } else null
    }
    val labelArea = idle ?: transform
    val labels = rememberLabelsAsync(labelArea, cities, ww, !wind, units, place, null, mini = true, hotspots = false)
    Box(modifier) {
        RadarMap(place, if (!wind) shown else null, if (!wind) ui.rainViewer else emptyList(), rvIndex,
            null, field, null, mini = true, onCities = { c, t -> cities = c; idle = t }, labels = labels) { transform = it }
        val t = transform
        if (wind && t != null && ww?.wind != null) WindParticles(ww, t, count = 250)
        // Tippfläche über der Karte: nimmt Berührungen an, damit die Karte sie nicht verschluckt.
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent() }
        })
    }
}
