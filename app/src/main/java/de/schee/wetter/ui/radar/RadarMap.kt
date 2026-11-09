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
import de.schee.wetter.data.RainViewer
import de.schee.wetter.data.Satellite
import de.schee.wetter.data.WindGrid
import de.schee.wetter.log.AppLog
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.expressions.Expression
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.pow

private const val STYLE = "https://tiles.openfreemap.org/styles/dark"

/** Weltbild um [side] · 360° verschoben (östlich +1, westlich −1). */
private fun worldCopy(side: Int) = quad(-180.0 + side * 360, 180.0 + side * 360, -Satellite.MAX_LAT, Satellite.MAX_LAT)

/** Wert/Name an einem Ort. kind: 0 = Hotspot, 1 = Stadt der Karte, 2 = eigener Ort, 3 = Großstadt aus der Weltliste. */
data class MapLabel(val lat: Double, val lon: Double, val value: String?, val name: String?, val kind: Int)

private fun labelFeatures(labels: List<MapLabel>) = FeatureCollection.fromFeatures(labels.map { l ->
    Feature.fromGeometry(Point.fromLngLat(l.lon, l.lat)).apply {
        addNumberProperty("kind", l.kind)
        l.value?.let { addStringProperty("value", it) }
        l.name?.let { addStringProperty("name", it) }
    }
})

/**
 * Blitze → Punkte mit Alters-Stufe (0 = neu < 10 Min., 1 = < 25, 2 = < 45, 3 = älter), Größe nach Stärke,
 * Ring nur bei den stärksten neuesten (höchstens 12). Ältere zuerst, damit die neuen obenauf liegen.
 */
private fun strikeFeatures(strikes: List<Satellite.Strike>, newest: java.time.Instant?): FeatureCollection {
    if (newest == null) return FeatureCollection.fromFeatures(emptyList())
    val rings = strikes.filter { newest.epochSecond - it.time.epochSecond < 300 && it.strength >= .7f }.sortedByDescending { it.strength }.take(12).toSet()
    return FeatureCollection.fromFeatures(strikes.sortedBy { it.time }.map { s ->
        val age = (newest.epochSecond - s.time.epochSecond) / 60.0
        Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat)).apply {
            addNumberProperty("cat", when { age < 10 -> 0; age < 25 -> 1; age < 45 -> 2; else -> 3 })
            addNumberProperty("r", 2.6 + 2.2 * s.strength)
            addNumberProperty("ring", if (s in rings) 1 else 0)
        }
    })
}

/** Blitz- und Beschriftungs-Ebenen ganz oben (über den Ortsnamen der Karte). */
private fun addOverlayLayers(s: Style) {
    s.addSource(GeoJsonSource("strikes"))
    s.addSource(GeoJsonSource("labels"))
    val cat = Expression.get("cat")
    // Größe wächst mit dem Zoom: weit heraus kleine Punkte, nah größer
    fun radius(f: Double) = Expression.interpolate(Expression.linear(), Expression.zoom(),
        Expression.stop(1.5, Expression.product(Expression.get("r"), Expression.literal(.45 * f))),
        Expression.stop(5, Expression.product(Expression.get("r"), Expression.literal(.8 * f))),
        Expression.stop(9, Expression.product(Expression.get("r"), Expression.literal(1.25 * f))))
    val yellow = "#FFE135"; val orange = "#F2A12E"; val red = "#E5322D"
    s.addLayer(org.maplibre.android.style.layers.CircleLayer("strike-dots", "strikes").withProperties(
        PropertyFactory.circleRadius(radius(1.0)),
        PropertyFactory.circleColor(Expression.match(cat, Expression.literal(yellow), Expression.stop(3, Expression.literal(orange)))),
        PropertyFactory.circleOpacity(Expression.match(cat, Expression.literal(1f), Expression.stop(2, Expression.literal(.85f)), Expression.stop(3, Expression.literal(.55f)))),
        PropertyFactory.circleStrokeWidth(Expression.match(cat, Expression.literal(0f), Expression.stop(0, Expression.literal(1.1f)), Expression.stop(1, Expression.literal(.9f)))),
        PropertyFactory.circleStrokeColor(Expression.match(cat, Expression.literal(orange), Expression.stop(0, Expression.literal(red)))),
    ))
    s.addLayer(org.maplibre.android.style.layers.CircleLayer("strike-core", "strikes").withProperties(
        PropertyFactory.circleRadius(radius(.5)), PropertyFactory.circleColor(red),
    ).withFilter(Expression.eq(cat, Expression.literal(0))))
    s.addLayer(org.maplibre.android.style.layers.CircleLayer("strike-rings", "strikes").withProperties(
        PropertyFactory.circleRadius(14f), PropertyFactory.circleColor("rgba(0,0,0,0)"),
        PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(1.4f), PropertyFactory.circleStrokeOpacity(.5f),
    ).withFilter(Expression.eq(Expression.get("ring"), Expression.literal(1))))
    // Beschriftung: Punkt (eigener Ort weiß mit Rand, Weltliste hell, Hotspot klein), Wert darüber, Name darunter
    val kind = Expression.get("kind")
    s.addLayer(org.maplibre.android.style.layers.CircleLayer("lbl-dot", "labels").withProperties(
        PropertyFactory.circleRadius(Expression.match(kind, Expression.literal(2.4f), Expression.stop(2, Expression.literal(3.6f)), Expression.stop(0, Expression.literal(1.8f)))),
        PropertyFactory.circleColor(Expression.match(kind, Expression.literal("#E6E8EB"), Expression.stop(0, Expression.literal("rgba(255,255,255,0.55)")))),
        PropertyFactory.circleStrokeWidth(Expression.match(kind, Expression.literal(0f), Expression.stop(2, Expression.literal(1f)))),
        PropertyFactory.circleStrokeColor("rgba(0,0,0,0.6)"),
    ).withFilter(Expression.neq(kind, Expression.literal(1))))
    s.addLayer(SymbolLayer("lbl-value", "labels").withProperties(
        PropertyFactory.textField(Expression.get("value")),
        PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
        PropertyFactory.textSize(Expression.match(kind, Expression.literal(14.5f), Expression.stop(2, Expression.literal(16f)))),
        PropertyFactory.textColor("#FFFFFF"), PropertyFactory.textHaloColor("rgba(0,0,0,0.85)"), PropertyFactory.textHaloWidth(1.3f), PropertyFactory.textHaloBlur(.6f),
        PropertyFactory.textAnchor(Property.TEXT_ANCHOR_BOTTOM),
        PropertyFactory.textOffset(Expression.match(kind, Expression.literal(arrayOf(0f, -0.3f)), Expression.stop(1, Expression.literal(arrayOf(0f, -0.75f))))),
        PropertyFactory.textAllowOverlap(true), PropertyFactory.textIgnorePlacement(true),
    ).withFilter(Expression.has("value")))
    s.addLayer(SymbolLayer("lbl-name", "labels").withProperties(
        PropertyFactory.textField(Expression.get("name")),
        PropertyFactory.textFont(arrayOf("Noto Sans Bold")), PropertyFactory.textSize(12.5f),
        PropertyFactory.textColor("#C9CDD3"), PropertyFactory.textHaloColor("rgba(0,0,0,0.85)"), PropertyFactory.textHaloWidth(1.2f),
        PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP), PropertyFactory.textOffset(arrayOf(0f, 0.45f)),
        PropertyFactory.textAllowOverlap(true), PropertyFactory.textIgnorePlacement(true),
    ).withFilter(Expression.all(Expression.eq(kind, Expression.literal(3)), Expression.has("name"))))
}

private fun quad(west: Double, east: Double, south: Double, north: Double) =
    LatLngQuad(LatLng(north, west), LatLng(north, east), LatLng(south, east), LatLng(south, west))

/** Ganze Welt (Mercator, ±85°) – für die vorgeladenen Weltbilder. */
private val WORLD = quad(-180.0, 180.0, -Satellite.MAX_LAT, Satellite.MAX_LAT)

/**
 * Die Karte (MapLibre, schlichter dunkler Stil auf Basis von OpenFreeMap, Daten © OpenStreetMap).
 * Alle Wetterbilder liegen unter den Ortsnamen. Die Weltbilder (Temperatur/Wind, Wolken) sind schon
 * vorgeladen – beim Verschieben wird nichts nachgeladen.
 */
@Composable
fun RadarMap(
    place: RadarPlace?,
    /** DWD-Radar bzw. ICON-EU über Deutschland (eigenes, umgefärbtes Bild). */
    radar: Bitmap?,
    /** Weltweites Radar (RainViewer) und welches Bild gerade sichtbar ist (null = keins). */
    rainViewer: List<RainViewer.Frame>, rvIndex: Int?,
    /** Regen-Modell (Zukunft) außerhalb Deutschlands: grobes Gitter des sichtbaren Ausschnitts mit Bild. */
    precipView: Pair<WindGrid, Bitmap>?,
    /** Farbfläche Temperatur oder Wind (Weltbild, Mercator ±85°). */
    field: Bitmap?,
    /** Wolken (Weltbild, Mercator ±85°). */
    clouds: Bitmap?,
    /** Farbfläche nur über Land (Meer bleibt frei, z. B. Temperatur) – sonst auch über dem Meer (Wind). */
    fieldLandOnly: Boolean = false,
    /** Live-Standort; [centerTick] erhöht = Karte dorthin schwenken. */
    center: Pair<Double, Double>? = null, centerTick: Int = 0,
    /** Orte, die die Karte gerade beschriftet (nach jedem Verschieben/Zoomen), mit dem Ausschnitt dazu. */
    onCities: (List<MapCity>, MapTransform?) -> Unit = { _, _ -> },
    /** Kachel-Modus: kleine, nicht bedienbare Karte (kein Verschieben/Zoomen, keine Knöpfe), Zoomstufe fest. */
    mini: Boolean = false,
    /** Werte und Namen (Städte, Hotspots, eigener Ort) – direkt in der Karte gezeichnet, damit sie beim Wischen exakt mitgehen. */
    labels: List<MapLabel> = emptyList(),
    /** Blitze (leer = aus) und der jüngste Abschnitt mit Daten (für das Alter). */
    strikes: List<Satellite.Strike> = emptyList(), strikesNewest: java.time.Instant? = null,
    onTransform: (MapTransform) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val report by rememberUpdatedState(onTransform)
    val reportCities by rememberUpdatedState(onCities)

    val view = remember {
        MapLibre.getInstance(context)
        // In der Kachel als TextureView, damit die Karte beim Scrollen der Hauptseite sauber mitläuft.
        val opts = org.maplibre.android.maps.MapLibreMapOptions.createFromAttributes(context).textureMode(mini)
        MapView(context, opts).apply { onCreate(null) }
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
        val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { de.schee.wetter.data.MapStyle.load(context) }
        view.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                isRotateGesturesEnabled = false; isTiltGesturesEnabled = false; isCompassEnabled = false
                isLogoEnabled = false
                isAttributionEnabled = !mini
                setAttributionMargins(24, 0, 0, (context.resources.displayMetrics.density * 210).toInt())
                if (mini) setAllGesturesEnabled(false)
            }
            m.setMinZoomPreference(1.5); m.setMaxZoomPreference(11.5)
            val target = place?.let { LatLng(it.lat, it.lon) } ?: LatLng(51.1, 10.4)
            m.cameraPosition = CameraPosition.Builder().target(target).zoom(if (mini) 7.0 else if (place != null) 7.2 else 5.4).build()
            /**
             * Ausschnitt aus Mitte und Zoom berechnen – nicht aus den Ecken: die Ecken springen an der
             * Datumsgrenze (180°) auf −180°, dann stimmten Werte und Windlinien im Pazifik nicht (bis 0.5.2).
             */
            fun current(): MapTransform? {
                if (view.width <= 0) return null
                val cam = m.cameraPosition
                val c = cam.target ?: return null
                // MapLibre: die Welt ist bei Zoom z genau 512 · 2^z dp breit
                val span = 360.0 * (view.width / context.resources.displayMetrics.density) / (512.0 * 2.0.pow(cam.zoom))
                val vr = m.projection.visibleRegion
                return MapTransform(c.longitude - span / 2, c.longitude + span / 2, vr.farLeft!!.latitude, vr.nearLeft!!.latitude, view.width.toFloat(), view.height.toFloat())
            }
            var copySide = 1
            fun send() {
                current()?.let { report(it) }
                // Kopie der Weltbilder auf die Seite legen, zu der die Bildmitte näher an der Datumsgrenze liegt
                val side = if ((m.cameraPosition.target?.longitude ?: 0.0) >= 0) 1 else -1
                if (side != copySide) {
                    copySide = side
                    m.style?.let { st -> listOf("field2", "clouds2").forEach { (st.getSource(it) as? ImageSource)?.setCoordinates(worldCopy(side)) } }
                }
            }
            m.addOnCameraMoveListener { send() }
            // Orte aus den Beschriftungs-Ebenen des Kartenstils (die Karte hat sie schon so verteilt, dass nichts überlappt)
            fun cities() {
                runCatching {
                    val rect = android.graphics.RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
                    val layers = listOf("place_city_large", "place_city", "place_town", "place_village")
                    val found = layers.flatMapIndexed { li, layer ->
                        m.queryRenderedFeatures(rect, layer).mapNotNull { f ->
                            val p = f.geometry() as? org.maplibre.geojson.Point ?: return@mapNotNull null
                            val name = (if (f.hasProperty("name:de")) f.getStringProperty("name:de") else null) ?: (if (f.hasProperty("name")) f.getStringProperty("name") else null) ?: return@mapNotNull null
                            val rank = if (f.hasProperty("rank")) f.getNumberProperty("rank").toInt() else 20
                            MapCity(name, p.latitude(), p.longitude(), li * 100 + rank, fromMap = true)
                        }
                    }.sortedBy { it.rank }.distinctBy { it.name }
                    reportCities(found, current())
                }
            }
            m.addOnCameraIdleListener { send(); cities() }
            view.addOnDidBecomeIdleListener { cities() }
            m.setStyle(if (json != null) Style.Builder().fromJson(json) else Style.Builder().fromUri(STYLE)) { s ->
                AppLog.data("Karte geladen (${System.currentTimeMillis() - started} ms)")
                val labels = s.layers.firstOrNull { it is SymbolLayer }?.id
                val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                s.addSource(ImageSource("field", WORLD, empty))
                s.addSource(ImageSource("clouds", WORLD, empty))
                // Zweite Kopie gleich daneben (östlich bzw. westlich) – MapLibre wiederholt Bild-Ebenen nicht
                // über die Datumsgrenze hinweg; ohne Kopie endete die Fläche dort mit harter Kante (bis 0.5.3).
                s.addSource(ImageSource("field2", worldCopy(1), empty))
                s.addSource(ImageSource("clouds2", worldCopy(1), empty))
                s.addSource(ImageSource("precip", quad(0.0, 1.0, 0.0, 1.0), empty))
                s.addSource(ImageSource("radar", quad(RadarData.WEST, RadarData.EAST, RadarData.SOUTH, RadarData.NORTH), empty))
                fun raster(id: String, src: String, opacity: Float) = RasterLayer(id, src).withProperties(
                    PropertyFactory.rasterOpacity(opacity), PropertyFactory.rasterFadeDuration(0f), PropertyFactory.rasterResampling("linear"))
                // Farbflächen und Wolken UNTER Flüssen und Grenzen, damit die Karte lesbar bleibt (Kevin am 09.11.2026).
                // Zwei Varianten der Farbfläche: unter dem Meer (nur Land eingefärbt) oder über dem Meer.
                val firstLine = s.layers.firstOrNull { it.id == "waterway" || it.id.startsWith("boundary") }?.id ?: labels
                fun below(l: RasterLayer, id: String?) { if (id != null && s.getLayer(id) != null) s.addLayerBelow(l, id) else s.addLayer(l) }
                listOf(raster("field-land", "field", 1f), raster("field2-land", "field2", 1f)).forEach { below(it, "water") }
                listOf(raster("field-layer", "field", 1f), raster("field2-layer", "field2", 1f), raster("clouds-layer", "clouds", 1f), raster("clouds2-layer", "clouds2", 1f)).forEach { below(it, firstLine) }
                // Regen darüber (über Grenzen, unter Ortsnamen)
                listOf(raster("precip-layer", "precip", 0.9f), raster("radar-layer", "radar", 0.9f)).forEach { below(it, labels) }
                addOverlayLayers(s)
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
                maxZoom = 7f; attribution = "<a href=\"https://www.rainviewer.com/\">Radar weltweit: RainViewer</a>"
            }
            s.addSource(org.maplibre.android.style.sources.RasterSource("rv-$i", tiles, 512))
            s.addLayerBelow(RasterLayer("rv-layer-$i", "rv-$i").withProperties(
                PropertyFactory.rasterOpacity(0f), PropertyFactory.visibility(Property.NONE), PropertyFactory.rasterFadeDuration(0f), PropertyFactory.rasterResampling("linear")), "radar-layer")
        }
    }
    LaunchedEffect(style, rainViewer, rvIndex) {
        val s = style ?: return@LaunchedEffect
        // Über alle vorhandenen RainViewer-Ebenen gehen – nicht nur über die aktuelle Liste: beim Abwählen
        // von „Regen“ ist die Liste leer, die Ebenen müssen aber trotzdem ausgeblendet werden (Fehler bis 0.5.1).
        s.layers.mapNotNull { it.id.removePrefix("rv-layer-").takeIf { r -> r != it.id }?.toIntOrNull() }.forEach { i ->
            val active = rvIndex != null && (i == rvIndex || i == rvIndex + 1)
            s.getLayer("rv-layer-$i")?.setProperties(
                PropertyFactory.visibility(if (active) Property.VISIBLE else Property.NONE),
                PropertyFactory.rasterOpacity(if (i == rvIndex) 0.8f else 0f))
        }
    }

    // Bilder austauschen, wenn sich Zeitpunkt oder Ebene ändern.
    val empty = remember { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }
    LaunchedEffect(style, radar) { (style?.getSource("radar") as? ImageSource)?.setImage(radar ?: empty) }
    LaunchedEffect(style, field) { listOf("field", "field2").forEach { (style?.getSource(it) as? ImageSource)?.setImage(field ?: empty) } }
    LaunchedEffect(style, fieldLandOnly) {
        val s = style ?: return@LaunchedEffect
        fun vis(on: Boolean) = PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE)
        listOf("field-land", "field2-land").forEach { s.getLayer(it)?.setProperties(vis(fieldLandOnly)) }
        listOf("field-layer", "field2-layer").forEach { s.getLayer(it)?.setProperties(vis(!fieldLandOnly)) }
    }
    LaunchedEffect(style, clouds) { listOf("clouds", "clouds2").forEach { (style?.getSource(it) as? ImageSource)?.setImage(clouds ?: empty) } }
    LaunchedEffect(style, labels) { (style?.getSource("labels") as? GeoJsonSource)?.setGeoJson(labelFeatures(labels)) }
    LaunchedEffect(style, strikes, strikesNewest) { (style?.getSource("strikes") as? GeoJsonSource)?.setGeoJson(strikeFeatures(strikes, strikesNewest)) }
    // Leuchtringe der neuesten Blitze: sanft wachsen und verblassen (nur solange der Bildschirm sichtbar ist)
    val animate = de.schee.wetter.ui.sky.LocalAnimate.current
    LaunchedEffect(style, strikes.isNotEmpty(), animate) {
        val s = style ?: return@LaunchedEffect
        if (strikes.isEmpty() || !animate) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val start = System.nanoTime()
            while (true) {
                val p = (((System.nanoTime() - start) / 1e9) % 1.4 / 1.4).toFloat()
                s.getLayer("strike-rings")?.setProperties(PropertyFactory.circleRadius(8f + 20f * p), PropertyFactory.circleStrokeOpacity(.8f * (1 - p)))
                kotlinx.coroutines.delay(40)
            }
        }
    }
    LaunchedEffect(style, precipView) {
        val src = style?.getSource("precip") as? ImageSource ?: return@LaunchedEffect
        val v = precipView
        if (v == null) { src.setImage(empty); return@LaunchedEffect }
        val g = v.first
        src.setCoordinates(quad(g.lon0 - g.step / 2, g.lon1 + g.step / 2, (g.lat0 - g.step / 2).coerceAtLeast(-85.0), (g.lat1 + g.step / 2).coerceAtMost(85.0)))
        src.setImage(v.second)
    }

    // Kachel: immer auf den aktuellen Ort ausgerichtet
    LaunchedEffect(map, place?.lat, place?.lon) {
        if (!mini) return@LaunchedEffect
        val p = place ?: return@LaunchedEffect
        map?.cameraPosition = CameraPosition.Builder().target(LatLng(p.lat, p.lon)).zoom(7.0).build()
    }

    LaunchedEffect(centerTick) {
        if (centerTick == 0) return@LaunchedEffect
        val c = center ?: return@LaunchedEffect
        map?.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(LatLng(c.first, c.second), maxOf(map?.cameraPosition?.zoom ?: 8.0, 8.5)), 600)
    }

    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
}
