package de.schee.wetter.ui.radar

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.schee.wetter.data.RadarData
import de.schee.wetter.data.WindGrid
import de.schee.wetter.data.RainViewer
import de.schee.wetter.log.AppLog
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.ImageSource

private const val STYLE = "https://tiles.openfreemap.org/styles/dark"

private fun quad(west: Double, east: Double, south: Double, north: Double) =
    LatLngQuad(LatLng(north, west), LatLng(north, east), LatLng(south, east), LatLng(south, west))

/**
 * Die Karte (MapLibre, dunkler Stil von OpenFreeMap, Daten © OpenStreetMap).
 * Radar und Windfarbe liegen als Bild-Ebenen unter den Ortsnamen.
 * Die Karte lebt nur, solange der Bildschirm offen ist (Lebenszyklus wird weitergereicht).
 */
@Composable
fun RadarMap(
    place: RadarPlace?,
    /** DWD-Radar bzw. ICON-EU über Deutschland (eigenes, umgefärbtes Bild). */
    radar: Bitmap?,
    /** Weltweites Radar (RainViewer) und welches Bild gerade sichtbar ist (null = keins). */
    rainViewer: List<RainViewer.Frame>, rvIndex: Int?,
    /** Grobes Gitter über dem sichtbaren Ausschnitt mit fertigem Bild (Regen-Modell oder Wind). */
    viewOverlay: Pair<WindGrid, Bitmap>?,
    /** Feines Windgitter über Deutschland mit Bild. */
    windBase: Pair<WindGrid, Bitmap>?,
    /** Live-Standort; [centerTick] erhöht = Karte dorthin schwenken. */
    center: Pair<Double, Double>? = null, centerTick: Int = 0,
    onTransform: (MapTransform) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val report by rememberUpdatedState(onTransform)

    val view = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStop()
            view.onDestroy()
        }
    }

    LaunchedEffect(view) {
        val started = System.currentTimeMillis()
        // Etwas hellerer Stil mit deutschen Ortsnamen; ohne Netz der Originalstil.
        val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { de.schee.wetter.data.MapStyle.load(context) }
        view.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                isRotateGesturesEnabled = false; isTiltGesturesEnabled = false; isCompassEnabled = false
                isLogoEnabled = false
                isAttributionEnabled = true
                setAttributionMargins(24, 0, 0, (context.resources.displayMetrics.density * 210).toInt())
            }
            // weltweit: frei verschiebbar, von der Weltkarte bis zur Stadt
            m.setMinZoomPreference(1.5); m.setMaxZoomPreference(11.5)
            val target = place?.let { LatLng(it.lat, it.lon) } ?: LatLng(51.1, 10.4)
            m.cameraPosition = CameraPosition.Builder().target(target).zoom(if (place != null) 7.2 else 5.4).build()
            fun send() {
                val b = m.projection.visibleRegion.latLngBounds
                if (view.width > 0) report(MapTransform(b.longitudeWest, b.longitudeEast, b.latitudeNorth, b.latitudeSouth, view.width.toFloat(), view.height.toFloat()))
            }
            m.addOnCameraMoveListener { send() }
            m.addOnCameraIdleListener { send() }
            m.setStyle(if (json != null) Style.Builder().fromJson(json) else Style.Builder().fromUri(STYLE)) { s ->
                AppLog.data("Karte geladen (${System.currentTimeMillis() - started} ms)")
                val labels = s.layers.firstOrNull { it is SymbolLayer }?.id
                val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                val g = WindGrid.GERMANY
                s.addSource(ImageSource("view", quad(0.0, 1.0, 0.0, 1.0), empty))
                s.addSource(ImageSource("wind", quad(g.lon0 - g.step / 2, g.lon0 + (g.cols - 0.5) * g.step, g.lat0 - g.step / 2, g.lat0 + (g.rows - 0.5) * g.step), empty))
                s.addSource(ImageSource("radar", quad(RadarData.WEST, RadarData.EAST, RadarData.SOUTH, RadarData.NORTH), empty))
                fun raster(id: String, src: String, opacity: Float) = RasterLayer(id, src).withProperties(
                    PropertyFactory.rasterOpacity(opacity), PropertyFactory.rasterFadeDuration(0f), PropertyFactory.rasterResampling("linear"))
                // Reihenfolge von unten nach oben: Ausschnitt-Gitter, Wind Deutschland, (RainViewer), DWD-Radar, Ortsnamen
                listOf(raster("view-layer", "view", 0.75f), raster("wind-layer", "wind", 0.75f), raster("radar-layer", "radar", 0.9f)).forEach {
                    if (labels != null) s.addLayerBelow(it, labels) else s.addLayer(it)
                }
                style = s
                send()
            }
        }
    }

    // RainViewer: je Zeitpunkt eine eigene Kachel-Ebene (unsichtbar vorgeladen) – Umschalten ohne Flackern.
    LaunchedEffect(style, rainViewer) {
        val s = style ?: return@LaunchedEffect
        rainViewer.forEachIndexed { i, f ->
            if (s.getSource("rv-$i") != null) return@forEachIndexed
            val tiles = org.maplibre.android.style.sources.TileSet("2.2.0", f.tiles).apply {
                maxZoom = 7f; attribution = "Radar weltweit: RainViewer"
            }
            s.addSource(org.maplibre.android.style.sources.RasterSource("rv-$i", tiles, 256))
            s.addLayerBelow(RasterLayer("rv-layer-$i", "rv-$i").withProperties(
                PropertyFactory.rasterOpacity(0f), PropertyFactory.rasterFadeDuration(0f), PropertyFactory.rasterResampling("linear")), "radar-layer")
        }
    }
    LaunchedEffect(style, rainViewer, rvIndex) {
        val s = style ?: return@LaunchedEffect
        rainViewer.indices.forEach { i -> s.getLayer("rv-layer-$i")?.setProperties(PropertyFactory.rasterOpacity(if (i == rvIndex) 0.8f else 0f)) }
    }

    // Bilder austauschen, wenn sich Zeitpunkt oder Ebene ändern.
    val empty = remember { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }
    LaunchedEffect(style, radar) {
        (style?.getSource("radar") as? ImageSource)?.setImage(radar ?: empty)
    }
    LaunchedEffect(style, windBase) {
        (style?.getSource("wind") as? ImageSource)?.setImage(windBase?.second ?: empty)
    }
    LaunchedEffect(style, viewOverlay) {
        val src = style?.getSource("view") as? ImageSource ?: return@LaunchedEffect
        val v = viewOverlay
        if (v == null) { src.setImage(empty); return@LaunchedEffect }
        val g = v.first
        src.setCoordinates(quad(g.lon0 - g.step / 2, g.lon1 + g.step / 2, (g.lat0 - g.step / 2).coerceAtLeast(-85.0), (g.lat1 + g.step / 2).coerceAtMost(85.0)))
        src.setImage(v.second)
    }

    LaunchedEffect(centerTick) {
        if (centerTick == 0) return@LaunchedEffect
        val c = center ?: return@LaunchedEffect
        map?.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(LatLng(c.first, c.second), maxOf(map?.cameraPosition?.zoom ?: 8.0, 8.5)), 600)
    }

    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
}
