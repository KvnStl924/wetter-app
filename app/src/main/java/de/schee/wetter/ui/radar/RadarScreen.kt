package de.schee.wetter.ui.radar

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.schee.wetter.data.RadarData
import de.schee.wetter.data.WindGrid
import de.schee.wetter.log.AppLog
import de.schee.wetter.ui.sky.LocalAnimate
import de.schee.wetter.ui.theme.WColors
import de.schee.wetter.ui.theme.WType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.random.Random

enum class RadarLayer(val label: String) { RAIN("Niederschlag"), WIND("Wind") }

/** Ort in der Mitte der Karte mit aktueller Temperatur. */
data class RadarPlace(val name: String, val lat: Double, val lon: Double, val tempLabel: String)

/**
 * Lineare Abbildung Mercator → Bildschirm (die Karte lässt sich nicht drehen oder kippen).
 * Wird bei jeder Kamerabewegung aus dem sichtbaren Ausschnitt neu berechnet.
 */
data class MapTransform(val west: Double, val east: Double, val north: Double, val south: Double, val w: Float, val h: Float) {
    private val x0 = RadarData.mercX(west); private val x1 = RadarData.mercX(east)
    private val y0 = RadarData.mercY(north); private val y1 = RadarData.mercY(south)
    fun x(lon: Double) = ((RadarData.mercX(lon) - x0) / (x1 - x0) * w).toFloat()
    fun y(lat: Double) = ((RadarData.mercY(lat) - y0) / (y1 - y0) * h).toFloat()
    fun lon(px: Float) = (x0 + px / w * (x1 - x0)) * 180.0 / 20037508.34
    fun lat(py: Float): Double {
        val my = (y0 + py / h * (y1 - y0)) * 180.0 / 20037508.34
        return 360.0 / PI * atan(exp(my * PI / 180.0)) - 90.0
    }
}

private val hhmm = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/**
 * Vollbild-Radar: dunkle Karte (MapLibre + OpenFreeMap), DWD-Radar −2 h … +2 h, Modell bis +12 h,
 * Zeitleiste mit Abspielen, Legende und Ebene „Wind“ mit Strömungslinien.
 * [preview] = Testbild am PC: statt der Karte ein fester Ausschnitt (MapLibre läuft dort nicht).
 */
@Composable
fun RadarScreen(
    place: RadarPlace?,
    initialLayer: RadarLayer,
    onClose: () -> Unit,
    vm: RadarViewModel = viewModel(),
    preview: Bitmap? = null,
) {
    val ui by vm.ui.collectAsState()
    var layer by remember { mutableStateOf(initialLayer) }
    var index by remember { mutableIntStateOf(if (ui.frames.isNotEmpty()) ui.nowIndex else -1) }
    var windHour by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var transform by remember { mutableStateOf<MapTransform?>(null) }
    var shown by remember { mutableStateOf<Bitmap?>(null) }
    var menu by remember { mutableStateOf(false) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Laden nur bei sichtbarem Bildschirm; beim Schließen oder Verlassen der App wird abgebrochen.
    LaunchedEffect(Unit) {
        if (preview != null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.load()
            try {
                androidx.compose.runtime.snapshotFlow { layer }.collect { if (it == RadarLayer.WIND) vm.loadWind() }
            } finally { vm.stop() }
        }
    }
    LaunchedEffect(ui.frames.size) { if (index < 0 && ui.frames.isNotEmpty()) index = ui.nowIndex }

    // Bild zum gewählten Zeitpunkt dekodieren und umfärben (im Hintergrund-Thread, ca. 30 ms).
    val bytes = ui.bytes[index]
    LaunchedEffect(index, bytes, layer) {
        if (layer != RadarLayer.RAIN) return@LaunchedEffect
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
                    val n = ui.wind?.hours?.size ?: 0
                    delay(900)
                    if (n > 0) windHour = (windHour + 1) % n
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF0C0C0C))) {
        if (preview != null) {
            // Testbild am PC: alles ohne Hintergrund-Effekte berechnen, damit es sofort gezeichnet wird.
            transform = previewTransform(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
            val kind = ui.frames.getOrNull(index)?.kind
            val overlay = if (layer == RadarLayer.RAIN && bytes != null && kind != null) remember(index) { RadarData.render(bytes, kind) } else null
            PreviewMap(preview, overlay, if (layer == RadarLayer.WIND) ui.wind else null, windHour, transform!!)
        } else {
            RadarMap(place, if (layer == RadarLayer.RAIN) shown else null, if (layer == RadarLayer.WIND) ui.wind else null, windHour) { transform = it }
        }
        val t = transform
        if (layer == RadarLayer.WIND && t != null) ui.wind?.let { WindParticles(it, windHour, t) }
        if (place != null && t != null) PlaceMarker(place, t, if (layer == RadarLayer.WIND) ui.wind?.let { windLabel(it, windHour, place) } else null)

        // Oben: Schließen, Titel, Ebenen
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton({ AppLog.tap("Vollbild-Radar → Schließen"); onClose() }) { CloseGlyph() }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(layer.label, style = WType.small)
                if (place != null) Text(place.name, style = WType.bodyDim)
            }
            RoundButton({ AppLog.tap("Vollbild-Radar → Ebenen-Menü"); menu = !menu }) { LayersGlyph() }
        }
        if (menu) {
            Column(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 64.dp, end = 16.dp)
                    .clip(RoundedCornerShape(14.dp)).background(Color(0xF01C2233)).width(190.dp),
            ) {
                RadarLayer.entries.forEach { l ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            AppLog.tap("Vollbild-Radar → Ebene „${l.label}“"); layer = l; menu = false; playing = false
                        }.padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(l.label, style = WType.body, modifier = Modifier.weight(1f))
                        if (l == layer) Text("✓", style = WType.body.copy(color = WColors.Accent))
                    }
                }
            }
        }

        // Unten: Zeitleiste und Legende
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp)
                .clip(RoundedCornerShape(20.dp)).background(Color(0xE6141927)).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (layer == RadarLayer.RAIN) RainTimeline(ui, index, playing, onPlay = {
                playing = !playing; AppLog.tap("Vollbild-Radar → ${if (playing) "Abspielen" else "Pause"}")
            }, onIndex = { playing = false; index = it }, onDone = { AppLog.tap("Vollbild-Radar → Zeitleiste auf ${label(ui, index)}") })
            else WindTimeline(ui, windHour, playing, onPlay = {
                playing = !playing; AppLog.tap("Windkarte → ${if (playing) "Abspielen" else "Pause"}")
            }, onHour = { playing = false; windHour = it }, onDone = { AppLog.tap("Windkarte → Zeitleiste auf Stunde +$windHour") })
        }
    }
}

// ------------------------------------------------------------------ Karte

private fun previewTransform(w: Float, h: Float): MapTransform {
    // Ausschnitt so wählen, dass das ganze Radarbild (Deutschland) in der Breite passt.
    val span = RadarData.mercX(RadarData.EAST) - RadarData.mercX(RadarData.WEST)
    val cy = (RadarData.mercY(RadarData.NORTH) + RadarData.mercY(RadarData.SOUTH)) / 2
    val half = span * h / w / 2
    fun lat(m: Double) = 360.0 / PI * atan(exp(m * 180.0 / 20037508.34 * PI / 180.0)) - 90.0
    return MapTransform(RadarData.WEST, RadarData.EAST, lat(cy + half), lat(cy - half), w, h)
}

@Composable
private fun PreviewMap(base: Bitmap, overlay: Bitmap?, wind: WindGrid?, windHour: Int, t: MapTransform) {
    Box(Modifier.fillMaxSize()) {
        if (wind != null) {
            val img = remember(windHour) { windBitmap(wind, windHour.coerceIn(0, wind.hours.size - 1)).asImageBitmap() }
            Canvas(Modifier.fillMaxSize()) {
                val l = t.x(WindGrid.LON0 - WindGrid.STEP / 2); val r = t.x(WindGrid.LON1 + WindGrid.STEP / 2)
                val top = t.y(WindGrid.LAT1 + WindGrid.STEP / 2); val b = t.y(WindGrid.LAT0 - WindGrid.STEP / 2)
                drawImage(img, dstOffset = IntOffset(l.toInt(), top.toInt()), dstSize = androidx.compose.ui.unit.IntSize((r - l).toInt(), (b - top).toInt()),
                    alpha = .75f, filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
            }
        }
        Image(base.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        overlay?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().background(Color.Transparent), contentScale = ContentScale.Fit, alpha = .9f) }
    }
}

// ------------------------------------------------------------------ Ort

@Composable
private fun PlaceMarker(place: RadarPlace, t: MapTransform, windText: String?) {
    val d = LocalDensity.current
    val x = t.x(place.lon); val y = t.y(place.lat)
    if (x < 0 || y < 0 || x > t.w || y > t.h) return
    val size = with(d) { 40.dp.toPx() }
    Box(
        Modifier.offset { IntOffset((x - size / 2).toInt(), (y - size / 2).toInt()) }.size(40.dp)
            .clip(CircleShape).background(Color(0xF21C2233)),
        contentAlignment = Alignment.Center,
    ) { Text(windText ?: place.tempLabel, style = WType.small.copy(fontWeight = FontWeight.SemiBold)) }
}

private fun windLabel(g: WindGrid, hour: Int, p: RadarPlace): String {
    val out = DoubleArray(2); g.at(hour.coerceIn(0, g.hours.size - 1), p.lat, p.lon, out)
    return hypot(out[0], out[1]).toInt().toString()
}

// ------------------------------------------------------------------ Wind

/**
 * Strömungslinien: kurze Striche, die mit dem Wind wandern. Läuft nur bei sichtbarem Bildschirm.
 * Die Striche haben Kartenkoordinaten (Breite/Länge) und wandern beim Verschieben der Karte mit;
 * Striche, die aus dem Bild geraten, entstehen sofort neu im sichtbaren Teil.
 */
@Composable
private fun WindParticles(g: WindGrid, hour: Int, t: MapTransform) {
    val animate = LocalAnimate.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val n = 900
    // Bereich mit Winddaten, 1° vom Rand des Gitters entfernt (dort blendet die Farbfläche aus).
    val latMin = WindGrid.LAT0 + 1; val latMax = WindGrid.LAT1 - 1; val lonMin = WindGrid.LON0 + 1; val lonMax = WindGrid.LON1 - 1
    fun area(m: MapTransform) = doubleArrayOf(maxOf(m.south, latMin), minOf(m.north, latMax), maxOf(m.west, lonMin), minOf(m.east, lonMax))
    fun spawn(p: DoubleArray, a: DoubleArray) {
        if (a[0] >= a[1] || a[2] >= a[3]) { p[2] = 2.0; return } // nichts sichtbar
        // gleichmäßig auf dem Bildschirm verteilt (Breite über Mercator)
        val y0 = RadarData.mercY(a[0]); val y1 = RadarData.mercY(a[1])
        val my = (y0 + Random.nextDouble() * (y1 - y0)) * 180.0 / 20037508.34
        p[0] = 360.0 / PI * atan(exp(my * PI / 180.0)) - 90.0
        p[1] = a[2] + Random.nextDouble() * (a[3] - a[2])
        p[2] = 0.0
    }
    val parts = remember { val a = area(t); Array(n) { DoubleArray(3).also { spawn(it, a); it[2] = Random.nextDouble() } } }
    var tick by remember { mutableFloatStateOf(0f) }
    val tr by androidx.compose.runtime.rememberUpdatedState(t)
    val h by androidx.compose.runtime.rememberUpdatedState(hour.coerceIn(0, g.hours.size - 1))
    val density = LocalDensity.current.density
    LaunchedEffect(g, animate) {
        if (!animate) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = 0L
            val w = DoubleArray(2)
            while (isActive) {
                androidx.compose.runtime.withFrameNanos { now ->
                    val dt = if (last == 0L) 0.0 else ((now - last) / 1e9).coerceAtMost(.05)
                    last = now
                    val m = tr
                    val a = area(m)
                    val degX = (m.east - m.west) / m.w; val degY = (m.north - m.south) / m.h
                    for (p in parts) {
                        g.at(h, p[0], p[1], w)
                        // 1 km/h ≈ 1,2 dp pro Sekunde auf dem Bildschirm – gleich schnell bei jedem Zoom.
                        p[1] += w[0] * 1.2 * density * dt * degX
                        p[0] += w[1] * 1.2 * density * dt * degY
                        p[2] += dt / 2.2
                        if (p[2] > 1.0 || p[0] < a[0] || p[0] > a[1] || p[1] < a[2] || p[1] > a[3]) spawn(p, a)
                    }
                    tick = (now / 1e9).toFloat()
                }
            }
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        @Suppress("UNUSED_VARIABLE") val redraw = tick
        val w = DoubleArray(2)
        for (p in parts) {
            if (p[2] > 1.0) continue
            g.at(h, p[0], p[1], w)
            val speed = hypot(w[0], w[1])
            if (speed < 0.5) continue
            val x = t.x(p[1]); val y = t.y(p[0])
            val len = (4.0 + speed * 0.25).coerceAtMost(18.0) * density
            val dx = (w[0] / speed * len).toFloat(); val dy = (-w[1] / speed * len).toFloat()
            // am Rand der Daten weich ausblenden
            val edge = minOf(p[0] - latMin, latMax - p[0], p[1] - lonMin, lonMax - p[1]).coerceIn(0.0, 0.75) / 0.75
            val a = (kotlin.math.sin(p[2] * PI) * .85 * edge).toFloat().coerceAtLeast(0f)
            drawLine(Color.White.copy(alpha = a * .75f), Offset(x - dx, y - dy), Offset(x, y), strokeWidth = 1.3f * density, cap = StrokeCap.Round)
        }
    }
}

/** Farbe der Windfläche nach Geschwindigkeit (km/h). Gleiche Skala wie die Legende. */
internal fun windColor(kmh: Double): Int {
    val steps = listOf(0.0 to 0xFF1B3A6B, 10.0 to 0xFF1F6F8B, 20.0 to 0xFF2E9E6E, 35.0 to 0xFFC9B52E, 50.0 to 0xFFE0782A, 70.0 to 0xFFD8344A, 100.0 to 0xFF9B3FD1)
    if (kmh <= 0) return steps[0].second.toInt()
    for (i in 1 until steps.size) {
        val (a, ca) = steps[i - 1]; val (b, cb) = steps[i]
        if (kmh <= b) {
            val f = ((kmh - a) / (b - a)).toFloat()
            fun ch(s: Int) = ((((ca shr s) and 255) + ((((cb shr s) and 255) - ((ca shr s) and 255)) * f)).toInt()) and 255
            return ((255 shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0))
        }
    }
    return steps.last().second.toInt()
}

/** Kleines Bild (1 Pixel je Gitterpunkt) mit der Windfarbe; die Karte zeichnet es weich vergrößert. */
fun windBitmap(g: WindGrid, hour: Int): Bitmap {
    val w = WindGrid.COLS; val h = WindGrid.ROWS
    val px = IntArray(w * h)
    val out = DoubleArray(2)
    for (r in 0 until h) for (c in 0 until w) {
        g.at(hour, WindGrid.LAT0 + r * WindGrid.STEP, WindGrid.LON0 + c * WindGrid.STEP, out)
        // zum Rand des Gitters hin ausblenden, damit kein hartes Viereck entsteht
        val edge = (minOf(r, c, h - 1 - r, w - 1 - c) / 3.0).coerceIn(0.0, 1.0)
        px[(h - 1 - r) * w + c] = (windColor(hypot(out[0], out[1])) and 0xFFFFFF) or ((0xA0 * edge).toInt() shl 24)
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}

// ------------------------------------------------------------------ Zeitleiste

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

@OptIn(ExperimentalMaterial3Api::class)
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
                else "Deutscher Wetterdienst",
                style = WType.bodyDim,
            )
        }
    }
    TimelineSlider(index.coerceAtLeast(0), ui.frames.size, ui.nowIndex, onIndex, onDone,
        modelFrom = ui.frames.indexOfFirst { it.kind == RadarData.Kind.MODEL })
    Legend(RadarData.LEGEND.map { it.first to Color(RadarData.colorFor(it.second)) })
}

@Composable
private fun WindTimeline(ui: RadarUi, hour: Int, playing: Boolean, onPlay: () -> Unit, onHour: (Int) -> Unit, onDone: () -> Unit) {
    val g = ui.wind
    when {
        ui.windError != null -> { Text(ui.windError, style = WType.body.copy(color = WColors.Warn)); return }
        g == null -> { Text("Winddaten werden geladen …", style = WType.body); return }
    }
    val h = hour.coerceIn(0, g.hours.size - 1)
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundButton(onPlay, dark = false) { if (playing) PauseGlyph() else PlayGlyph() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${hhmm.format(g.hours[h])} · ${if (h == 0) "Jetzt" else "in $h Std."}", style = WType.small)
            Text("Wind in 10 m Höhe, km/h · Open-Meteo (ICON)", style = WType.bodyDim)
        }
    }
    TimelineSlider(h, g.hours.size, 0, onHour, onDone, modelFrom = -1)
    Legend(listOf(0, 10, 20, 35, 50, 70).map { "$it" to Color(windColor(it.toDouble())) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelineSlider(index: Int, count: Int, nowIndex: Int, onIndex: (Int) -> Unit, onDone: () -> Unit, modelFrom: Int) {
    if (count < 2) return
    Box {
        // Markierungen: „Jetzt“ und Beginn des Modells
        Canvas(Modifier.fillMaxWidth().height(40.dp)) {
            val pad = 10.dp.toPx(); val wd = size.width - 2 * pad
            fun x(i: Int) = pad + wd * i / (count - 1)
            drawLine(Color.White.copy(alpha = .7f), Offset(x(nowIndex), size.height / 2 - 10.dp.toPx()), Offset(x(nowIndex), size.height / 2 + 10.dp.toPx()), 2.dp.toPx())
            if (modelFrom > 0) drawLine(WColors.Accent.copy(alpha = .6f), Offset(x(modelFrom), size.height / 2 - 7.dp.toPx()), Offset(x(modelFrom), size.height / 2 + 7.dp.toPx()), 2.dp.toPx())
        }
        Slider(
            value = index.toFloat(),
            onValueChange = { onIndex(it.toInt().coerceIn(0, count - 1)) },
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
        Modifier.size(44.dp).clip(CircleShape).background(if (dark) Color(0xE61C2233) else Color.White.copy(alpha = .14f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable private fun CloseGlyph() = Canvas(Modifier.size(16.dp)) {
    val s = 2.dp.toPx()
    drawLine(Color.White, Offset(0f, 0f), Offset(size.width, size.height), s, StrokeCap.Round)
    drawLine(Color.White, Offset(size.width, 0f), Offset(0f, size.height), s, StrokeCap.Round)
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

@Composable private fun LayersGlyph() = Canvas(Modifier.size(20.dp)) {
    val s = 1.8.dp.toPx()
    fun diamond(y: Float) = androidx.compose.ui.graphics.Path().apply {
        moveTo(size.width / 2, y); lineTo(size.width, y + size.height * .22f); lineTo(size.width / 2, y + size.height * .44f); lineTo(0f, y + size.height * .22f); close()
    }
    drawPath(diamond(size.height * .1f), Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(s))
    drawPath(diamond(size.height * .42f), Color.White.copy(alpha = .6f), style = androidx.compose.ui.graphics.drawscope.Stroke(s))
}
