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
import androidx.compose.ui.graphics.toArgb
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
import de.schee.wetter.data.LocationSource
import androidx.compose.animation.core.animateFloat
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

enum class RadarLayer(val label: String) { RAIN("Niederschlag"), WIND("Wind"), TEMP("Temperatur") }

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
    previewFix: LocationSource.Fix? = null,
) {
    val ui by vm.ui.collectAsState()
    var layer by remember { mutableStateOf(initialLayer) }
    var index by remember { mutableIntStateOf(if (ui.frames.isNotEmpty()) ui.nowIndex else -1) }
    var windHour by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var transform by remember { mutableStateOf<MapTransform?>(null) }
    var shown by remember { mutableStateOf<Bitmap?>(null) }
    var menu by remember { mutableStateOf(false) }
    var fix by remember { mutableStateOf(previewFix) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var centerTick by remember { mutableIntStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val units = remember { de.schee.wetter.data.Units.from(context) }

    // Blauer Punkt: Live-Standort, nur solange das Radar sichtbar ist (kein Hintergrund).
    LaunchedEffect(Unit) {
        if (preview != null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            LocationSource.updates(context).collect { fix = it }
        }
    }
    // Die Bilder werden schon beim Öffnen der App vorgeladen (MainActivity); hier nur nachladen, falls
    // inzwischen älter als 5 Minuten. Abbruch beim Verlassen der App übernimmt ebenfalls MainActivity.
    LaunchedEffect(Unit) {
        if (preview != null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.load()
            androidx.compose.runtime.snapshotFlow { layer }.collect { if (it != RadarLayer.RAIN) vm.loadWind() }
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

    // Was außerhalb Deutschlands zu sehen ist (weltweit):
    //  - Vergangenheit/Jetzt: RainViewer-Radar (Bild bis 15 Min. vor dem gewählten Zeitpunkt)
    //  - Zukunft: Modell-Niederschlag (Open-Meteo) aus dem Gitter des sichtbaren Ausschnitts, stündlich
    val frame = ui.frames.getOrNull(index)
    val rvIndex = if (layer == RadarLayer.RAIN && frame != null && frame.minutes <= 0) de.schee.wetter.data.RainViewer.indexFor(ui.rainViewer, frame.time) else null
    val viewGrid = ui.windView
    val viewOverlay = remember(layer, viewGrid, index, windHour, ui.wind) {
        val g = viewGrid ?: return@remember null
        when {
            layer == RadarLayer.WIND -> g to windViewBitmap(g, ui.wind?.let { g.hourIndex(it.hours[windHour.coerceIn(0, it.hours.size - 1)]) } ?: windHour, ui.wind)
            layer == RadarLayer.TEMP -> g to tempViewBitmap(g, ui.wind?.let { g.hourIndex(it.hours[windHour.coerceIn(0, it.hours.size - 1)]) } ?: windHour, ui.wind)
            frame != null && frame.minutes > 0 -> {
                // Open-Meteo: Menge der vorangegangenen Stunde → Stunde, in die der Zeitpunkt fällt (aufgerundet)
                val hour = frame.time.truncatedTo(java.time.temporal.ChronoUnit.HOURS).let { if (it == frame.time) it else it.plusSeconds(3600) }
                g to precipViewBitmap(g, g.hourIndex(hour))
            }
            else -> null
        }
    }
    val windBase = remember(layer, ui.wind, windHour) {
        val h = ui.wind?.let { windHour.coerceIn(0, it.hours.size - 1) } ?: 0
        when (layer) {
            RadarLayer.WIND -> ui.wind?.let { it to windBitmap(it, h) }
            RadarLayer.TEMP -> ui.wind?.let { it to tempBitmap(it, h) }
            else -> null
        }
    }

    // Nach dem Verschieben/Zoomen: Gitter für den sichtbaren Ausschnitt nachladen (außerhalb Deutschlands).
    val tNow = transform
    LaunchedEffect(tNow?.let { listOf(Math.round(it.south * 4), Math.round(it.north * 4), Math.round(it.west * 4), Math.round(it.east * 4)) }) {
        if (preview != null || tNow == null) return@LaunchedEffect
        delay(700)
        vm.ensureView(tNow.south, tNow.north, tNow.west, tNow.east)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF0C0C0C))) {
        if (preview != null) {
            // Testbild am PC: alles ohne Hintergrund-Effekte berechnen, damit es sofort gezeichnet wird.
            transform = previewTransform(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
            val kind = ui.frames.getOrNull(index)?.kind
            val overlay = if (layer == RadarLayer.RAIN && bytes != null && kind != null) remember(index) { RadarData.render(bytes, kind) } else null
            PreviewMap(preview, overlay, listOfNotNull(viewOverlay, windBase), transform!!)
        } else {
            RadarMap(place, if (layer == RadarLayer.RAIN) shown else null, if (layer == RadarLayer.RAIN) ui.rainViewer else emptyList(), rvIndex,
                viewOverlay, windBase, center = fix?.let { it.lat to it.lon }, centerTick = centerTick) { transform = it }
        }
        val t = transform
        val field = ui.wind?.let { de.schee.wetter.data.WindField(it, ui.windView) }
        if (layer == RadarLayer.WIND && t != null) field?.let { WindParticles(it, windHour, t) }
        val f = fix
        if (f != null && t != null) MyLocationDot(f, t)
        if (layer != RadarLayer.RAIN && t != null && field != null) WindLabels(field, windHour, t, units, place, f, temp = layer == RadarLayer.TEMP)
        else if (place != null && t != null) PlaceMarker(place, t, null, f)

        // Oben: Schließen, Titel, Ebenen
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton({ AppLog.tap("Vollbild-Radar → Schließen"); onClose() }) { CloseGlyph() }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(layer.label, style = WType.small)
                if (place != null) Text(place.name, style = WType.bodyDim)
            }
            if (fix != null) {
                RoundButton({ AppLog.tap("Vollbild-Radar → Auf meinen Standort zentrieren"); centerTick++ }) { LocateGlyph() }
                Spacer(Modifier.width(8.dp))
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
            else WindTimeline(ui, windHour, playing, temp = layer == RadarLayer.TEMP, onPlay = {
                playing = !playing; AppLog.tap("${layer.label}-Karte → ${if (playing) "Abspielen" else "Pause"}")
            }, onHour = { playing = false; windHour = it }, onDone = { AppLog.tap("${layer.label}-Karte → Zeitleiste auf Stunde +$windHour") })
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
private fun PreviewMap(base: Bitmap, overlay: Bitmap?, grids: List<Pair<WindGrid, Bitmap>>, t: MapTransform) {
    Box(Modifier.fillMaxSize()) {
        for ((wind, bmp) in grids) {
            val img = remember(bmp) { bmp.asImageBitmap() }
            Canvas(Modifier.fillMaxSize()) {
                val l = t.x(wind.lon0 - wind.step / 2); val r = t.x(wind.lon1 + wind.step / 2)
                val top = t.y(wind.lat1 + wind.step / 2); val b = t.y(wind.lat0 - wind.step / 2)
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
private fun PlaceMarker(place: RadarPlace, t: MapTransform, windText: String?, fix: LocationSource.Fix?) {
    val d = LocalDensity.current
    val x = t.x(place.lon); var y = t.y(place.lat)
    if (x < 0 || y < 0 || x > t.w || y > t.h) return
    val size = with(d) { 40.dp.toPx() }
    // Liegt der blaue Punkt darunter, rückt die Temperatur nach oben, damit beide sichtbar bleiben.
    if (fix != null && hypot((t.x(fix.lon) - x).toDouble(), (t.y(fix.lat) - y).toDouble()) < size) y = t.y(fix.lat) - size * 0.95f
    Box(
        Modifier.offset { IntOffset((x - size / 2).toInt(), (y - size / 2).toInt()) }.size(40.dp)
            .clip(CircleShape).background(Color(0xF21C2233)),
        contentAlignment = Alignment.Center,
    ) { Text(windText ?: place.tempLabel, style = WType.small.copy(fontWeight = FontWeight.SemiBold)) }
}

/** Blauer Punkt wie in Karten-Apps: weißer Rand, heller Kreis für die Ungenauigkeit, sanftes Pulsieren. */
@Composable
private fun MyLocationDot(fix: LocationSource.Fix, t: MapTransform) {
    val x = t.x(fix.lon); val y = t.y(fix.lat)
    if (x < -50 || y < -50 || x > t.w + 50 || y > t.h + 50) return
    val animate = LocalAnimate.current
    val pulse = if (animate) {
        androidx.compose.animation.core.rememberInfiniteTransition(label = "puls").animateFloat(
            0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1800)), label = "puls",
        ).value
    } else 0.4f
    // Meter → Bildschirmpunkte (Breitengrad-Korrektur der Länge)
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

private fun windLabel(g: de.schee.wetter.data.WindField, hour: Int, p: RadarPlace): String {
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
private fun WindParticles(g: de.schee.wetter.data.WindField, hour: Int, t: MapTransform) {
    val animate = LocalAnimate.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val n = 900
    // Bereich mit Winddaten, 1° vom Rand des Gitters entfernt (dort blendet die Farbfläche aus).
    // Bereich: sichtbarer Ausschnitt, begrenzt auf das Gebiet mit Daten (Deutschland-Gitter bzw. Ausschnitt-Gitter).
    val gField by androidx.compose.runtime.rememberUpdatedState(g)
    fun area(m: MapTransform): DoubleArray {
        val grids = listOfNotNull(gField.base, gField.view)
        val inset = grids.minOf { it.step }
        return doubleArrayOf(maxOf(m.south, grids.minOf { it.lat0 } + inset), minOf(m.north, grids.maxOf { it.lat1 } - inset),
            maxOf(m.west, grids.minOf { it.lon0 } + inset), minOf(m.east, grids.maxOf { it.lon1 } - inset))
    }
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
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = 0L
            var lastSpan = 0.0
            val w = DoubleArray(2)
            while (isActive) {
                androidx.compose.runtime.withFrameNanos { now ->
                    val dt = if (last == 0L) 0.0 else ((now - last) / 1e9).coerceAtMost(.05)
                    last = now
                    val m = tr
                    val a = area(m)
                    val degX = (m.east - m.west) / m.w; val degY = (m.north - m.south) / m.h
                    // Deutlich gezoomt (z. B. schnell herausgezoomt): alle Striche sofort neu im ganzen Bild verteilen,
                    // sonst hängen sie noch im alten, kleinen Ausschnitt.
                    val span = m.east - m.west
                    if (lastSpan > 0 && (span > lastSpan * 1.25 || span < lastSpan / 1.25)) {
                        for (p in parts) { spawn(p, a); p[2] = Random.nextDouble() * .8 }
                        lastSpan = span
                    } else if (lastSpan == 0.0) lastSpan = span
                    for (p in parts) {
                        gField.at(h, p[0], p[1], w)
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
            gField.at(h, p[0], p[1], w)
            val speed = hypot(w[0], w[1])
            if (speed < 0.5) continue
            val x = t.x(p[1]); val y = t.y(p[0])
            val len = (4.0 + speed * 0.25).coerceAtMost(18.0) * density
            val dx = (w[0] / speed * len).toFloat(); val dy = (-w[1] / speed * len).toFloat()
            // am Rand der Daten weich ausblenden
            val edge = ((gField.inside(p[0], p[1]) - 0.5) / 0.75).coerceIn(0.0, 1.0)
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
fun windBitmap(g: WindGrid, hour: Int): Bitmap = gridBitmap(g, edgeCells = 3.0) { lat, lon, out ->
    g.at(hour, lat, lon, out)
    (windColor(hypot(out[0], out[1])) and 0xFFFFFF) or (0xA0 shl 24)
}

/** Wind im groben Ausschnitt-Gitter; innerhalb des feinen Deutschland-Gitters ausgeblendet (dort liegt das feine). */
fun windViewBitmap(g: WindGrid, hour: Int, base: WindGrid?): Bitmap = gridBitmap(g, edgeCells = 1.0) { lat, lon, out ->
    g.at(hour, lat, lon, out)
    val keep = if (base == null) 1.0 else (1 - (base.inside(lat, lon) - base.step) / (base.step * 2)).coerceIn(0.0, 1.0)
    (windColor(hypot(out[0], out[1])) and 0xFFFFFF) or ((0xA0 * keep).toInt() shl 24)
}

/** Temperatur als Farbfläche (feines Gitter über Deutschland). */
fun tempBitmap(g: WindGrid, hour: Int): Bitmap = gridBitmap(g, edgeCells = 3.0) { lat, lon, _ ->
    val c = g.tempAt(hour, lat, lon)
    if (c.isNaN()) 0 else (de.schee.wetter.ui.main.Timeline.tempColor(c).toArgb() and 0xFFFFFF) or (0x90 shl 24)
}

/** Temperatur im groben Ausschnitt-Gitter; innerhalb des Deutschland-Gitters ausgeblendet. */
fun tempViewBitmap(g: WindGrid, hour: Int, base: WindGrid?): Bitmap = gridBitmap(g, edgeCells = 1.0) { lat, lon, _ ->
    val c = g.tempAt(hour, lat, lon)
    if (c.isNaN()) return@gridBitmap 0
    val keep = if (base == null) 1.0 else (1 - (base.inside(lat, lon) - base.step) / (base.step * 2)).coerceIn(0.0, 1.0)
    (de.schee.wetter.ui.main.Timeline.tempColor(c).toArgb() and 0xFFFFFF) or ((0x90 * keep).toInt() shl 24)
}

/** Modell-Niederschlag (mm/h) im groben Ausschnitt-Gitter; über Deutschland liegt das DWD-Bild, dort ausgeblendet. */
fun precipViewBitmap(g: WindGrid, hour: Int): Bitmap = gridBitmap(g, edgeCells = 1.0) { lat, lon, _ ->
    val mm = g.precipAt(hour, lat, lon)
    if (mm < 0.1) return@gridBitmap 0
    val dwd = minOf(lat - RadarData.SOUTH, RadarData.NORTH - lat, lon - RadarData.WEST, RadarData.EAST - lon)
    val keep = (1 - dwd / 0.5).coerceIn(0.0, 1.0)
    val a = (if (mm < 1.0) 0xB0 else 0xD8) * keep * ((mm - 0.1) / 0.3).coerceIn(0.0, 1.0)
    (RadarData.colorFor(mm) and 0xFFFFFF) or (a.toInt() shl 24)
}

/**
 * Zeichnet ein Gitter als Bild in Mercator-Aufteilung (gleich wie die Karte), damit es auch bei großen
 * Ausschnitten an der richtigen Stelle liegt. [edgeCells] = Ausblenden zum Rand hin (in Gitterzellen).
 */
private inline fun gridBitmap(g: WindGrid, edgeCells: Double, color: (Double, Double, DoubleArray) -> Int): Bitmap {
    val w = g.cols * 2; val h = g.rows * 2
    val west = g.lon0 - g.step / 2; val east = g.lon1 + g.step / 2
    val yTop = RadarData.mercY((g.lat1 + g.step / 2).coerceAtMost(85.0)); val yBot = RadarData.mercY((g.lat0 - g.step / 2).coerceAtLeast(-85.0))
    val px = IntArray(w * h)
    val out = DoubleArray(2)
    for (y in 0 until h) {
        val my = (yTop + (y + 0.5) / h * (yBot - yTop)) * 180.0 / 20037508.34
        val lat = 360.0 / PI * atan(exp(my * PI / 180.0)) - 90.0
        for (x in 0 until w) {
            val lon = west + (x + 0.5) / w * (east - west)
            val c = color(lat, lon, out)
            val edge = ((g.inside(lat, lon) + g.step / 2) / (g.step * edgeCells)).coerceIn(0.0, 1.0)
            px[y * w + x] = ((((c ushr 24) * edge).toInt()) shl 24) or (c and 0xFFFFFF)
        }
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
                else "Radar: DWD, weltweit RainViewer · Vorhersage: DWD, weltweit Open-Meteo",
                style = WType.bodyDim,
            )
        }
    }
    TimelineSlider(index.coerceAtLeast(0), ui.frames.size, ui.nowIndex, onIndex, onDone,
        modelFrom = ui.frames.indexOfFirst { it.kind == RadarData.Kind.MODEL })
    Legend(RadarData.LEGEND.map { it.first to Color(RadarData.colorFor(it.second)) })
}

@Composable
private fun WindTimeline(ui: RadarUi, hour: Int, playing: Boolean, temp: Boolean, onPlay: () -> Unit, onHour: (Int) -> Unit, onDone: () -> Unit) {
    val g = ui.wind
    when {
        ui.windError != null -> { Text(ui.windError, style = WType.body.copy(color = WColors.Warn)); return }
        g == null -> { Text(if (temp) "Temperaturen werden geladen …" else "Winddaten werden geladen …", style = WType.body); return }
    }
    val h = hour.coerceIn(0, g.hours.size - 1)
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundButton(onPlay, dark = false) { if (playing) PauseGlyph() else PlayGlyph() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${hhmm.format(g.hours[h])} · ${if (h == 0) "Jetzt" else "in $h Std."}", style = WType.small)
            Text(if (temp) "Temperatur in 2 m Höhe · Open-Meteo" else "Wind in 10 m Höhe, km/h · Open-Meteo (ICON)", style = WType.bodyDim)
        }
    }
    TimelineSlider(h, g.hours.size, 0, onHour, onDone, modelFrom = -1)
    if (temp) Legend(listOf(-10, 0, 10, 18, 25, 32).map { "$it°" to de.schee.wetter.ui.main.Timeline.tempColor(it.toDouble()) })
    else Legend(listOf(0, 10, 20, 35, 50, 70).map { "$it" to Color(windColor(it.toDouble())) })
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

/** Pfeil wie „Mein Standort“ in Karten-Apps. */
@Composable private fun LocateGlyph() = Canvas(Modifier.size(18.dp)) {
    val p = androidx.compose.ui.graphics.Path().apply {
        moveTo(size.width, 0f); lineTo(size.width * .55f, size.height); lineTo(size.width * .45f, size.height * .55f); lineTo(0f, size.height * .45f); close()
    }
    drawPath(p, Color.White)
}

@Composable private fun LayersGlyph() = Canvas(Modifier.size(20.dp)) {
    val s = 1.8.dp.toPx()
    fun diamond(y: Float) = androidx.compose.ui.graphics.Path().apply {
        moveTo(size.width / 2, y); lineTo(size.width, y + size.height * .22f); lineTo(size.width / 2, y + size.height * .44f); lineTo(0f, y + size.height * .22f); close()
    }
    drawPath(diamond(size.height * .1f), Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(s))
    drawPath(diamond(size.height * .42f), Color.White.copy(alpha = .6f), style = androidx.compose.ui.graphics.drawscope.Stroke(s))
}

// ------------------------------------------------------------------ Windwerte als Text

/** Abstand der Windwerte in Grad: so gewählt, dass etwa alle 100 dp ein Wert steht (feste Rasterpunkte → kein Springen). */
private fun labelSpacing(t: MapTransform, density: Float, factor: Double = 1.0): Double {
    val target = (t.east - t.west) / t.w * 105 * density * factor
    val steps = listOf(0.05, 0.1, 0.2, 0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 4.0, 5.0, 7.5, 10.0, 15.0, 20.0, 30.0, 45.0)
    return steps.lastOrNull { it <= target * 1.15 } ?: steps.first()
}

/**
 * Windgeschwindigkeit als schlichter Text („45 km/h“, ohne Kreis) in einem regelmäßigen Raster wie bei
 * Wetter-Apps üblich. Jede zweite Reihe ist um einen halben Abstand versetzt. Am eigenen Ort steht der Wert
 * mit kleinem Punkt; Rasterwerte in der Nähe entfallen, damit nichts übereinander liegt.
 */
@Composable
private fun WindLabels(g: de.schee.wetter.data.WindField, hour: Int, t: MapTransform, units: de.schee.wetter.data.Units, place: RadarPlace?, fix: LocationSource.Fix?, temp: Boolean = false) {
    val density = LocalDensity.current.density
    val h = hour.coerceIn(0, g.hours.size - 1)
    data class L(val x: Float, val y: Float, val value: Int?, val own: Boolean)
    fun value(lat: Double, lon: Double, w: DoubleArray): Int? =
        if (temp) g.tempAt(h, lat, lon).takeIf { !it.isNaN() }?.let { units.tNum(it) }
        else { g.at(h, lat, lon, w); units.wNum(hypot(w[0], w[1])) }
    val labels = remember(t, h, g, temp) {
        val out = mutableListOf<L>()
        val w = DoubleArray(2)
        val avoid = listOfNotNull(place?.let { t.x(it.lon) to t.y(it.lat) }, fix?.let { t.x(it.lon) to t.y(it.lat) })
        place?.let { p ->
            g.at(h, p.lat, p.lon, w)
            out += L(t.x(p.lon), t.y(p.lat), value(p.lat, p.lon, w), true)
        }
        val sp = labelSpacing(t, density)
        // senkrecht: auf der Karte (Mercator) ist ein Breitengrad länger als ein Längengrad → Abstand angleichen
        val latSp = labelSpacing(t, density, kotlin.math.cos(Math.toRadians((t.north + t.south) / 2)))
        var row = Math.floor(t.south / latSp).toInt()
        while (row * latSp <= t.north + latSp) {
            val lat = row * latSp
            val shift = if (row % 2 == 0) 0.0 else sp / 2
            var col = Math.floor((t.west - shift) / sp).toInt()
            while (col * sp + shift <= t.east + sp) {
                val lon = col * sp + shift
                col++
                if (g.inside(lat, lon) < 0.3) continue
                val x = t.x(lon); val y = t.y(lat)
                if (x < 0 || y < 0 || x > t.w || y > t.h) continue
                if (avoid.any { (ax, ay) -> hypot((ax - x).toDouble(), (ay - y).toDouble()) < 60 * density }) continue
                g.at(h, lat, lon, w)
                out += L(x, y, value(lat, lon, w) ?: continue, false)
            }
            row++
        }
        out
    }
    val shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = .85f), Offset(0f, 1.5f), 6f)
    val big = WType.small.copy(fontWeight = FontWeight.Bold, shadow = shadow)
    val unit = WType.label.copy(color = Color.White.copy(alpha = .9f), fontWeight = FontWeight.SemiBold, shadow = shadow)
    for (l in labels) {
        val v = l.value ?: continue
        androidx.compose.ui.layout.Layout(content = {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(if (temp) "$v°" else v.toString(), style = if (l.own) big.copy(fontSize = big.fontSize * 1.15f) else big)
                if (!temp) Text(" ${units.windLabel}", style = unit, modifier = Modifier.padding(bottom = 1.dp))
            }
        }) { m, c ->
            val p = m.first().measure(c.copy(minWidth = 0, minHeight = 0))
            layout(p.width, p.height) { p.place((l.x - p.width / 2).toInt(), (l.y - p.height / 2 - if (l.own) 20 * density else 0f).toInt()) }
        }
        if (l.own) Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White, 3.5.dp.toPx(), Offset(l.x, l.y)); drawCircle(Color.Black.copy(alpha = .6f), 3.5.dp.toPx(), Offset(l.x, l.y), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
        }
    }
}
