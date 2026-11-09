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
    place: RadarPlace?, radar: Bitmap?, wind: WindGrid?, windHour: Int,
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
            m.setMinZoomPreference(4.8); m.setMaxZoomPreference(11.5)
            m.setLatLngBoundsForCameraTarget(LatLngBounds.from(RadarData.NORTH, RadarData.EAST, RadarData.SOUTH, RadarData.WEST))
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
                s.addSource(ImageSource("wind", quad(
                    WindGrid.LON0 - WindGrid.STEP / 2, WindGrid.LON1 + WindGrid.STEP / 2,
                    WindGrid.LAT0 - WindGrid.STEP / 2, WindGrid.LAT1 + WindGrid.STEP / 2), empty))
                s.addSource(ImageSource("radar", quad(RadarData.WEST, RadarData.EAST, RadarData.SOUTH, RadarData.NORTH), empty))
                val windLayer = RasterLayer("wind-layer", "wind").withProperties(
                    PropertyFactory.rasterOpacity(0.75f), PropertyFactory.rasterFadeDuration(0f), PropertyFactory.rasterResampling("linear"))
                val radarLayer = RasterLayer("radar-layer", "radar").withProperties(
                    PropertyFactory.rasterOpacity(0.9f), PropertyFactory.rasterFadeDuration(0f), PropertyFactory.rasterResampling("linear"))
                if (labels != null) { s.addLayerBelow(windLayer, labels); s.addLayerBelow(radarLayer, labels) } else { s.addLayer(windLayer); s.addLayer(radarLayer) }
                style = s
                send()
            }
        }
    }

    // Bilder austauschen, wenn sich Zeitpunkt oder Ebene ändern.
    val empty = remember { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }
    LaunchedEffect(style, radar) {
        (style?.getSource("radar") as? ImageSource)?.setImage(radar ?: empty)
    }
    LaunchedEffect(style, wind, windHour) {
        (style?.getSource("wind") as? ImageSource)?.setImage(wind?.let { windBitmap(it, windHour.coerceIn(0, it.hours.size - 1)) } ?: empty)
    }

    LaunchedEffect(centerTick) {
        if (centerTick == 0) return@LaunchedEffect
        val c = center ?: return@LaunchedEffect
        map?.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(LatLng(c.first, c.second), maxOf(map?.cameraPosition?.zoom ?: 8.0, 8.5)), 600)
    }

    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
}
