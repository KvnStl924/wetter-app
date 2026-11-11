package de.schee.wetter.ui.radar

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.schee.wetter.data.RadarData
import de.schee.wetter.data.RainViewer
import de.schee.wetter.data.Satellite
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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

private const val STYLE = "https://tiles.openfreemap.org/styles/dark"

/** Weltbild um [side] · 360° verschoben (östlich +1, westlich −1). */
private fun worldCopy(side: Int) = quad(-180.0 + side * 360, 180.0 + side * 360, -Satellite.MAX_LAT, Satellite.MAX_LAT)

/**
 * Wert/Name an einem Ort. kind: 0 = Hotspot, 1 = Stadt der Karte, 2 = eigener Ort, 3 = Großstadt aus der Weltliste,
 * 4 = Land mit Höchst- ([value]) und Tiefstwert ([value2]).
 */
data class MapLabel(val lat: Double, val lon: Double, val value: String?, val name: String?, val kind: Int, val color: Int? = null,
                    val value2: String? = null, val color2: Int? = null,
                    /** Land: kleines Land → kleinere Schrift und Pille. */
                    val small: Boolean = false)

/** Schrift und Pille einer Länder-Beschriftung (kind 4). */
internal fun countryStyle(l: MapLabel) = if (l.small) COUNTRY_SMALL else COUNTRY_BIG

/** Farbe für Zahl und Rand der Pille: Wertfarbe, aufgehellt (gut lesbar auf dunklem Grund). */
internal fun pillColor(c: Int, white: Double = 0.45): Int {
    fun ch(s: Int) = (((c shr s) and 255) * (1 - white) + 255 * white).toInt().coerceIn(0, 255)
    return (255 shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** Name des fertigen Wert-Bilds (Pille mit Zahl) für Text und Farbe. */
private fun pillName(c: Int, text: String) = "v_%06x_%s".format(pillColor(c) and 0xFFFFFF, text)

/** Bild-Name eines Beschriftungs-Werts (einfache Pille bzw. Max/Min-Pille eines Landes). */
private fun pillName(l: MapLabel): String? {
    val v = l.value ?: return null; val c = l.color ?: return null
    val v2 = l.value2; val c2 = l.color2
    return if (v2 != null && c2 != null) "r%s_%06x_%s_%06x_%s".format(if (l.small) "s" else "b", pillColor(c) and 0xFFFFFF, v, pillColor(c2) and 0xFFFFFF, v2) else pillName(c, v)
}

/** Bild zu [pillName]: einfache Pille bzw. Max/Min-Pille. */
internal fun labelBitmap(l: MapLabel, density: Float): Bitmap? {
    val v = l.value ?: return null; val c = l.color ?: return null
    val v2 = l.value2; val c2 = l.color2
    return if (v2 != null && c2 != null) rangeBitmap(v, c, v2, c2, density * countryStyle(l).pill) else pillBitmap(v, c, density)
}

/**
 * Max/Min-Pille eines Landes: „▲ 24° │ ▼ 12°“ – Dreiecke und Zahlen je in ihrer Temperaturfarbe,
 * gleiche Höhe und Schrift wie die einfachen Pillen, Rand dezent weiß.
 */
internal fun rangeBitmap(max: String, cMax: Int, min: String, cMin: Int, density: Float): Bitmap {
    val font = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    val tMax = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = pillColor(cMax, 0.6); textSize = 11.5f * density; typeface = font }
    val tMin = android.graphics.Paint(tMax).apply { color = pillColor(cMin, 0.6) }
    val h = 17f * density; val padX = 7f * density; val stroke = 1.1f * density
    val tri = 6.4f * density; val gap = 3.2f * density; val sep = 6.5f * density
    val wMax = tMax.measureText(max); val wMin = tMin.measureText(min)
    val w = padX + tri + gap + wMax + sep + 1f * density + sep + tri + gap + wMin + padX
    val bmp = Bitmap.createBitmap(Math.ceil(w.toDouble()).toInt(), Math.ceil(h.toDouble()).toInt(), Bitmap.Config.ARGB_8888)
    val cv = android.graphics.Canvas(bmp)
    val rect = android.graphics.RectF(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2)
    cv.drawRoundRect(rect, h / 2, h / 2, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6121821.toInt() })
    cv.drawRoundRect(rect, h / 2, h / 2, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x59FFFFFF; style = android.graphics.Paint.Style.STROKE; strokeWidth = stroke
    })
    val fm = tMax.fontMetrics
    val base = h / 2 - (fm.ascent + fm.descent) / 2
    val cy = h / 2; val th = tri * .78f
    fun triangle(x: Float, up: Boolean, p: android.graphics.Paint) {
        val path = android.graphics.Path()
        if (up) { path.moveTo(x, cy + th / 2); path.lineTo(x + tri, cy + th / 2); path.lineTo(x + tri / 2, cy - th / 2) }
        else { path.moveTo(x, cy - th / 2); path.lineTo(x + tri, cy - th / 2); path.lineTo(x + tri / 2, cy + th / 2) }
        path.close(); cv.drawPath(path, p)
    }
    var x = padX
    triangle(x, true, tMax); x += tri + gap
    cv.drawText(max, x, base, tMax); x += wMax + sep
    cv.drawRect(x, h * .26f, x + 1f * density, h * .74f, android.graphics.Paint().apply { color = 0x40FFFFFF }); x += 1f * density + sep
    triangle(x, false, tMin); x += tri + gap
    cv.drawText(min, x, base, tMin)
    return bmp
}

/** Schriftgröße der Namen ohne Länder-Stil (Orte, Hotspots, eigener Ort). */
internal const val LABEL_TEXT = 12.5f

internal fun labelFeatures(labels: List<MapLabel>) = FeatureCollection.fromFeatures(labels.map { l ->
    Feature.fromGeometry(Point.fromLngLat(l.lon, l.lat)).apply {
        addNumberProperty("kind", l.kind)
        l.value?.let { addStringProperty("value", it) }
        l.name?.let { addStringProperty("name", it) }
        pillName(l)?.let { addStringProperty("pill", it) }
        // Schriftgröße immer mitgeben: fehlte sie, machte die Karte daraus Größe 0 – alle Ortsnamen waren weg (3.0.2, behoben 3.0.3)
        addNumberProperty("ts", if (l.kind == 4) countryStyle(l).text else LABEL_TEXT)
    }
})

/**
 * Fertiges kleines Bild für einen Wert: Pille (dunkel, Rand in der Wertfarbe) mit der Zahl mittig in derselben Farbe.
 * Klein wie bei Sky Radar (Kevin am 09.11.2026: „Zahlen und Hintergrund viel zu groß“).
 */
internal fun pillBitmap(text: String, c: Int, density: Float): Bitmap {
    val col = pillColor(c)
    val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = pillColor(c, 0.68); textSize = 11.5f * density; typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    val tw = tp.measureText(text)
    val h = 17f * density; val padX = 6.5f * density; val stroke = 1.2f * density
    val w = tw + 2 * padX
    val bmp = Bitmap.createBitmap(Math.ceil(w.toDouble()).toInt(), Math.ceil(h.toDouble()).toInt(), Bitmap.Config.ARGB_8888)
    val cv = android.graphics.Canvas(bmp)
    val rect = android.graphics.RectF(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2)
    cv.drawRoundRect(rect, h / 2, h / 2, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xE0121821.toInt() })
    cv.drawRoundRect(rect, h / 2, h / 2, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = col; style = android.graphics.Paint.Style.STROKE; strokeWidth = stroke
    })
    val fm = tp.fontMetrics
    cv.drawText(text, padX, h / 2 - (fm.ascent + fm.descent) / 2, tp)
    return bmp
}

private fun ensurePill(s: Style, l: MapLabel, density: Float) {
    val name = pillName(l) ?: return
    if (s.getImage(name) == null) labelBitmap(l, density)?.let { s.addImage(name, it) }
}

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
    // Beschriftung wie bei Sky Radar (Kevin am 09.11.2026): kleiner Punkt am Ort, Name weiß und fett darüber,
    // Wert darunter in einer Pille (dunkel, Rand und Zahl in der Wertfarbe). Hotspots: nur die Pille.
    val kind = Expression.get("kind")
    val withPill = Expression.has("pill")
    s.addLayer(org.maplibre.android.style.layers.CircleLayer("lbl-dot", "labels").withProperties(
        PropertyFactory.circleRadius(2.6f),
        PropertyFactory.circleColor("#F2F3F5"),
        PropertyFactory.circleStrokeWidth(1f), PropertyFactory.circleStrokeColor("rgba(0,0,0,0.6)"),
    ).withFilter(Expression.all(Expression.neq(kind, Expression.literal(2)), Expression.neq(kind, Expression.literal(4)), Expression.any(Expression.all(Expression.neq(kind, Expression.literal(0)), withPill), Expression.eq(kind, Expression.literal(3))))))
    s.addLayer(SymbolLayer("lbl-name", "labels").withProperties(
        PropertyFactory.textField(Expression.get("name")),
        PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
        // Länder (Max/Min) kleiner, kleine Länder noch kleiner; sonst 12,5. „ts“ steht an jedem Punkt (siehe labelFeatures) –
        // toNumber macht aus einem fehlenden Wert 0 statt den Ersatzwert zu nehmen.
        PropertyFactory.textSize(Expression.toNumber(Expression.get("ts"))),
        PropertyFactory.textColor("#FFFFFF"), PropertyFactory.textHaloColor("rgba(0,0,0,0.9)"), PropertyFactory.textHaloWidth(1.4f), PropertyFactory.textHaloBlur(.4f),
        PropertyFactory.textAnchor(Property.TEXT_ANCHOR_BOTTOM),
        // eigener Ort: Name über, Wert unter dem blauen Standortpunkt (der sonst den Wert verdeckte, 0.6.4)
        PropertyFactory.textOffset(Expression.match(kind, Expression.literal(arrayOf(0f, -0.35f)), Expression.stop(2, Expression.literal(arrayOf(0f, -0.95f))))),
        PropertyFactory.textAllowOverlap(true), PropertyFactory.textIgnorePlacement(false),
    ).withFilter(Expression.all(Expression.has("name"), Expression.any(withPill, Expression.eq(kind, Expression.literal(3))))))
    s.addLayer(SymbolLayer("lbl-value", "labels").withProperties(
        PropertyFactory.iconImage(Expression.get("pill")),
        PropertyFactory.iconAnchor(Expression.match(kind, Expression.literal(Property.ICON_ANCHOR_TOP), Expression.stop(0, Expression.literal(Property.ICON_ANCHOR_CENTER)))),
        PropertyFactory.iconOffset(Expression.match(kind, Expression.literal(arrayOf(0f, 4f)), Expression.stop(0, Expression.literal(arrayOf(0f, 0f))), Expression.stop(2, Expression.literal(arrayOf(0f, 12f))))),
        PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(false),
    ).withFilter(withPill))
    // ohne Farbe (z. B. eigener Ort bei Regen): schlichter Wert über dem Punkt
    s.addLayer(SymbolLayer("lbl-plain", "labels").withProperties(
        PropertyFactory.textField(Expression.get("value")),
        PropertyFactory.textFont(arrayOf("Noto Sans Bold")), PropertyFactory.textSize(15f),
        PropertyFactory.textColor("#FFFFFF"), PropertyFactory.textHaloColor("rgba(0,0,0,0.85)"), PropertyFactory.textHaloWidth(1.3f),
        PropertyFactory.textAnchor(Property.TEXT_ANCHOR_BOTTOM), PropertyFactory.textOffset(arrayOf(0f, -0.3f)),
        PropertyFactory.textAllowOverlap(true), PropertyFactory.textIgnorePlacement(false),
    ).withFilter(Expression.all(Expression.has("value"), Expression.not(withPill))))
}

/** Standort, Zugrichtung und angetippter Punkt – ganz oben. */
private fun addMeLayers(s: Style) {
    s.addSource(GeoJsonSource("motion")); s.addSource(GeoJsonSource("pick")); s.addSource(GeoJsonSource("me"))
    s.addLayer(org.maplibre.android.style.layers.LineLayer("motion-casing", "motion").withProperties(
        PropertyFactory.lineColor("rgba(0,0,0,0.55)"), PropertyFactory.lineWidth(6.5f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
    s.addLayer(org.maplibre.android.style.layers.LineLayer("motion-line", "motion").withProperties(
        PropertyFactory.lineColor("#FFFFFF"), PropertyFactory.lineWidth(3f), PropertyFactory.lineOpacity(.92f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
    s.addLayer(org.maplibre.android.style.layers.CircleLayer("pick-ring", "pick").withProperties(
        PropertyFactory.circleRadius(9f), PropertyFactory.circleColor("rgba(255,255,255,0.18)"),
        PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2.2f)))
    s.addLayer(org.maplibre.android.style.layers.CircleLayer("me-acc", "me").withProperties(
        PropertyFactory.circleRadius(0f), PropertyFactory.circleColor("#0A84FF"), PropertyFactory.circleOpacity(.14f), PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP)))
    s.addLayer(org.maplibre.android.style.layers.CircleLayer("me-pulse", "me").withProperties(
        PropertyFactory.circleRadius(12f), PropertyFactory.circleColor("#0A84FF"), PropertyFactory.circleOpacity(.2f)))
    s.addLayer(org.maplibre.android.style.layers.CircleLayer("me-dot", "me").withProperties(
        PropertyFactory.circleRadius(6.5f), PropertyFactory.circleColor("#0A84FF"),
        PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2.5f)))
}

/** Pfeil der Zugrichtung: Schaft und zwei Spitzen-Striche (in Mercator gerechnet, damit er nicht verzerrt). */
private fun motionFeatures(m: de.schee.wetter.data.Nowcast.Motion?): FeatureCollection {
    if (m == null) return FeatureCollection.fromFeatures(emptyList())
    val x0 = RadarData.mercX(m.fromLon); val y0 = RadarData.mercY(m.fromLat)
    val x1 = RadarData.mercX(m.toLon); val y1 = RadarData.mercY(m.toLat)
    val len = kotlin.math.hypot(x1 - x0, y1 - y0); if (len == 0.0) return FeatureCollection.fromFeatures(emptyList())
    val ux = (x1 - x0) / len; val uy = (y1 - y0) / len
    val head = len * .28
    fun pt(x: Double, y: Double): Point {
        val lat = Math.toDegrees(2 * kotlin.math.atan(kotlin.math.exp(y / 20037508.34 * Math.PI)) - Math.PI / 2)
        return Point.fromLngLat(x * 180.0 / 20037508.34, lat)
    }
    fun side(sign: Double) = pt(x1 - ux * head + sign * -uy * head * .62, y1 - uy * head + sign * ux * head * .62)
    return FeatureCollection.fromFeatures(listOf(
        Feature.fromGeometry(org.maplibre.geojson.LineString.fromLngLats(listOf(pt(x0, y0), pt(x1, y1)))),
        Feature.fromGeometry(org.maplibre.geojson.LineString.fromLngLats(listOf(side(1.0), pt(x1, y1), side(-1.0)))),
    ))
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
    /** Regen-Modell (Zukunft) außerhalb Deutschlands (Weltbild, Mercator ±85°). */
    precipView: Bitmap?,
    /** Farbfläche Temperatur oder Wind (Weltbild, Mercator ±85°). */
    field: Bitmap?,
    /** Wolken (Weltbild, Mercator ±85°). */
    clouds: Bitmap?,
    /** Scharfe Wolken für den Ausschnitt (über dem Weltbild; deckt es den Ausschnitt, blendet das Weltbild aus). */
    cloudDetail: Satellite.CloudDetail? = null,
    /** Farbfläche nur über Land (Meer bleibt frei, z. B. Temperatur) – sonst auch über dem Meer (Wind). */
    fieldLandOnly: Boolean = false,
    /** Ländernamen der Karte zeigen (aus beim Max/Min-Schalter – dort stehen die eigenen Ländernamen). */
    countryNames: Boolean = true,
    /** Weltweites Radarbild von „Jetzt“, das in der Vorhersage stehen bleibt (außerhalb des DWD-Radars), Deckkraft 0…1. */
    holdFrame: RainViewer.Frame? = null, holdOpacity: Float = 0f,
    /** Deckkraft des Regen-Modells 0…1 (blendet in den ersten zwei Stunden langsam ein). */
    precipOpacity: Float = 1f,
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
    /** Blauer Standort-Punkt – in der Karte gezeichnet, damit er beim Wischen fest sitzt. */
    fix: de.schee.wetter.data.LocationSource.Fix? = null,
    /** Zugrichtung des Regens (Pfeil), null = keiner. */
    motion: de.schee.wetter.data.Nowcast.Motion? = null,
    /** Amtliche Warngebiete des DWD einblenden. */
    warnings: Boolean = false,
    /** Angetippter Punkt (weißer Ring). */
    pick: Pair<Double, Double>? = null,
    /** Tippen (lang = true) auf die Karte. */
    onTap: ((Double, Double, Boolean) -> Unit)? = null,
    /** Karte wird gerade verschoben/gezoomt (true) bzw. steht wieder (false). */
    onMoving: (Boolean) -> Unit = {},
    onTransform: (MapTransform) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val report by rememberUpdatedState(onTransform)
    val reportCities by rememberUpdatedState(onCities)
    val tap by rememberUpdatedState(onTap)
    val moving by rememberUpdatedState(onMoving)
    val detailNow by rememberUpdatedState(cloudDetail)
    val cloudsOn by rememberUpdatedState(clouds != null)
    var syncClouds by remember { mutableStateOf<(() -> Unit)?>(null) }

    val view = remember {
        MapLibre.getInstance(context)
        de.schee.wetter.data.TileMask.install()
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
                // Kein „i“-Knopf (Kevin am 11.11.2026) – die Pflicht-Quellenangabe steht klein und blass im Vollbild-Radar
                isAttributionEnabled = false
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
            var worldCloudsHidden: Boolean? = null
            // Scharfe Wolken decken den Ausschnitt → Weltbild darunter weich ausblenden (sonst doppelte Wolken)
            fun clouds(cur: MapTransform?) {
                val d = detailNow
                val hide = cloudsOn && d != null && cur != null && d.covers(cur.west, cur.east, cur.south, cur.north)
                if (hide == worldCloudsHidden) return
                val st = m.style ?: return
                worldCloudsHidden = hide
                listOf("clouds-layer", "clouds2-layer").forEach { st.getLayer(it)?.setProperties(PropertyFactory.rasterOpacity(if (hide) 0f else 1f)) }
                st.getLayer("clouds-hi-layer")?.setProperties(PropertyFactory.rasterOpacity(if (hide) 1f else 0f))
            }
            syncClouds = { worldCloudsHidden = null; clouds(current()) }
            fun send() {
                val cur = current()
                cur?.let { report(it) }
                clouds(cur)
                // Kopie der Weltbilder auf die Seite legen, zu der die Bildmitte näher an der Datumsgrenze liegt
                val side = if ((m.cameraPosition.target?.longitude ?: 0.0) >= 0) 1 else -1
                if (side != copySide) {
                    copySide = side
                    m.style?.let { st -> listOf("field2", "clouds2", "precip2").forEach { (st.getSource(it) as? ImageSource)?.setCoordinates(worldCopy(side)) } }
                }
            }
            m.addOnCameraMoveListener { send() }
            m.addOnCameraMoveStartedListener { moving(true) }
            if (!mini) {
                m.addOnMapClickListener { ll -> tap?.invoke(ll.latitude, ll.longitude, false); tap != null }
                m.addOnMapLongClickListener { ll -> tap?.invoke(ll.latitude, ll.longitude, true); tap != null }
            }
            // Orte aus den Beschriftungs-Ebenen des Kartenstils (die Karte hat sie schon so verteilt, dass nichts überlappt)
            fun cities() {
                runCatching {
                    // Orte direkt aus den Kartendaten lesen – nicht aus den gezeichneten Namen: die blendet die App
                    // aus, sobald sie eigene Werte zeigt (sonst fehlten die Städte, Fehler in 0.6.3)
                    val st = m.style ?: return@runCatching
                    val srcId = (st.getLayer("place_city") as? SymbolLayer)?.sourceId ?: "openmaptiles"
                    val vs = st.getSourceAs<org.maplibre.android.style.sources.VectorSource>(srcId) ?: return@runCatching
                    val zoom = m.cameraPosition.zoom
                    if (zoom < 7.0 && de.schee.wetter.data.Places.index.value != null) { reportCities(emptyList(), current()); return@runCatching }
                    val classes = listOfNotNull("city", "town".takeIf { zoom >= 5.0 }, "village".takeIf { zoom >= 7.5 })
                    val w = view.width.toFloat(); val h = view.height.toFloat()
                    val found = vs.querySourceFeatures(arrayOf("place"), null).mapNotNull { f ->
                        val p = f.geometry() as? org.maplibre.geojson.Point ?: return@mapNotNull null
                        val sp = m.projection.toScreenLocation(LatLng(p.latitude(), p.longitude()))
                        if (sp.x < 0 || sp.y < 0 || sp.x > w || sp.y > h) return@mapNotNull null
                        val name = (if (f.hasProperty("name:de")) f.getStringProperty("name:de") else null) ?: (if (f.hasProperty("name")) f.getStringProperty("name") else null) ?: return@mapNotNull null
                        val cls = if (f.hasProperty("class")) f.getStringProperty("class") else "city"
                        if (cls !in classes) return@mapNotNull null
                        val rank = if (f.hasProperty("rank")) f.getNumberProperty("rank").toInt() else 20
                        val capital = if (f.hasProperty("capital")) f.getNumberProperty("capital").toInt() else 0
                        // Reihenfolge: Hauptstädte, große Städte, Städte, Kleinstädte, Dörfer
                        val prio = when (cls) { "city" -> 0; "town" -> 100; else -> 200 } + rank - (if (capital in 1..2) 5 else 0)
                        MapCity(name, p.latitude(), p.longitude(), prio, fromMap = true)
                    }.sortedBy { it.rank }.distinctBy { it.name }.take(400)
                    reportCities(found, current())
                }
            }
            m.addOnCameraIdleListener { send(); cities(); moving(false) }
            // nach dem Nachladen von Kacheln höchstens alle 1,5 s erneut (vorher bei jeder Kachel → Ruckeln)
            var lastCities = 0L
            view.addOnDidBecomeIdleListener { val now = System.currentTimeMillis(); if (now - lastCities > 1500) { lastCities = now; cities() } }
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
                s.addSource(ImageSource("precip", WORLD, empty))
                s.addSource(ImageSource("precip2", worldCopy(1), empty))
                s.addSource(ImageSource("radar", quad(RadarData.WEST, RadarData.EAST, RadarData.SOUTH, RadarData.NORTH), empty))
                fun raster(id: String, src: String, opacity: Float) = RasterLayer(id, src).withProperties(
                    PropertyFactory.rasterOpacity(opacity), PropertyFactory.rasterFadeDuration(0f), PropertyFactory.rasterResampling("linear"))
                // Farbflächen und Wolken UNTER Flüssen und Grenzen, damit die Karte lesbar bleibt (Kevin am 09.11.2026).
                // Zwei Varianten der Farbfläche: unter dem Meer (nur Land eingefärbt) oder über dem Meer.
                val firstLine = s.layers.firstOrNull { it.id == "waterway" || it.id.startsWith("boundary") }?.id ?: labels
                fun below(l: RasterLayer, id: String?) { if (id != null && s.getLayer(id) != null) s.addLayerBelow(l, id) else s.addLayer(l) }
                listOf(raster("field-land", "field", 1f), raster("field2-land", "field2", 1f)).forEach { below(it, "water") }
                // Dunkler Schleier über dem Meer (nur bei Wind): Wind bleibt sichtbar, Küsten heben sich ab (Kevin am 09.11.2026)
                val waterSrc = (s.getLayer("water") as? org.maplibre.android.style.layers.FillLayer)?.sourceId
                if (waterSrc != null) {
                    val veil = org.maplibre.android.style.layers.FillLayer("water-veil", waterSrc).withSourceLayer("water").withProperties(
                        PropertyFactory.fillColor("rgb(16,21,28)"), PropertyFactory.fillOpacity(0.55f), PropertyFactory.fillAntialias(false), PropertyFactory.visibility(Property.NONE))
                    if (firstLine != null && s.getLayer(firstLine) != null) s.addLayerBelow(veil, firstLine) else s.addLayer(veil)
                }
                val fieldBelow = if (s.getLayer("water-veil") != null) "water-veil" else firstLine
                listOf(raster("field-layer", "field", 1f), raster("field2-layer", "field2", 1f)).forEach { below(it, fieldBelow) }
                listOf(raster("clouds-layer", "clouds", 1f), raster("clouds2-layer", "clouds2", 1f)).forEach { below(it, firstLine) }
                s.addSource(ImageSource("clouds-hi", quad(0.0, 1.0, 0.0, 1.0), empty))
                below(raster("clouds-hi-layer", "clouds-hi", 0f), firstLine)
                // Überblenden zwischen grobem und scharfem Bild: weich statt hart umspringen
                listOf("clouds-layer", "clouds2-layer", "clouds-hi-layer").forEach { id ->
                    (s.getLayer(id) as? RasterLayer)?.rasterOpacityTransition = org.maplibre.android.style.layers.TransitionOptions(320, 0)
                }
                // Regen darüber (über Grenzen, unter Ortsnamen)
                listOf(raster("precip-layer", "precip", 0.9f), raster("precip2-layer", "precip2", 0.9f), raster("radar-layer", "radar", 0.9f)).forEach { below(it, labels) }
                // Amtliche Warngebiete (DWD-WMS, Gemeinden) – erst beim Einschalten sichtbar
                val warnTiles = org.maplibre.android.style.sources.TileSet("2.2.0",
                    "https://maps.dwd.de/geoserver/dwd/wms?service=WMS&version=1.3.0&request=GetMap&layers=dwd:Warnungen_Gemeinden&styles=" +
                        "&format=image/png&transparent=true&width=512&height=512&crs=EPSG:3857&bbox={bbox-epsg-3857}").apply {
                    attribution = "Warnungen: Deutscher Wetterdienst"; minZoom = 4f; maxZoom = 11f
                }
                s.addSource(org.maplibre.android.style.sources.RasterSource("warn", warnTiles, 512))
                below(RasterLayer("warn-layer", "warn").withProperties(PropertyFactory.rasterOpacity(.55f), PropertyFactory.rasterFadeDuration(150f),
                    PropertyFactory.visibility(Property.NONE)), firstLine)
                addOverlayLayers(s)
                addMeLayers(s)
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

    // Stehendes Radarbild für die Vorhersage: eigene Kachel-Ebene (schon bei „Jetzt“ unsichtbar vorgeladen), deren
    // Kacheln im Messgebiet des DWD-Radars geleert sind (TileMask) – dort gilt nur die DWD-Vorhersage.
    LaunchedEffect(style, holdFrame) {
        val s = style ?: return@LaunchedEffect
        val key = holdFrame?.time?.epochSecond
        s.layers.map { it.id }.filter { it.startsWith("rvh-layer-") && it != "rvh-layer-$key" }.forEach { s.removeLayer(it) }
        s.sources.map { it.id }.filter { it.startsWith("rvh-") && it != "rvh-$key" }.forEach { s.removeSource(it) }
        if (holdFrame == null || s.getSource("rvh-$key") != null) return@LaunchedEffect
        val tiles = org.maplibre.android.style.sources.TileSet("2.2.0", de.schee.wetter.data.TileMask.marked(holdFrame.tiles)).apply {
            maxZoom = 7f; attribution = "<a href=\"https://www.rainviewer.com/\">Radar weltweit: RainViewer</a>"
        }
        s.addSource(org.maplibre.android.style.sources.RasterSource("rvh-$key", tiles, 512))
        s.addLayerBelow(RasterLayer("rvh-layer-$key", "rvh-$key").withProperties(
            PropertyFactory.rasterOpacity(0f), PropertyFactory.rasterFadeDuration(0f), PropertyFactory.rasterResampling("linear")), "radar-layer")
    }
    LaunchedEffect(style, holdFrame, holdOpacity) {
        val s = style ?: return@LaunchedEffect
        s.layers.filter { it.id.startsWith("rvh-layer-") }.forEach { it.setProperties(PropertyFactory.rasterOpacity(0.8f * holdOpacity)) }
    }
    LaunchedEffect(style, precipOpacity) {
        val s = style ?: return@LaunchedEffect
        listOf("precip-layer", "precip2-layer").forEach { s.getLayer(it)?.setProperties(PropertyFactory.rasterOpacity(0.9f * precipOpacity)) }
    }

    // Bilder austauschen, wenn sich Zeitpunkt oder Ebene ändern.
    val empty = remember { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }
    LaunchedEffect(style, radar) { (style?.getSource("radar") as? ImageSource)?.setImage(radar ?: empty) }
    LaunchedEffect(style, field) { listOf("field", "field2").forEach { (style?.getSource(it) as? ImageSource)?.setImage(field ?: empty) } }
    LaunchedEffect(style, fieldLandOnly, field != null) {
        val s = style ?: return@LaunchedEffect
        fun vis(on: Boolean) = PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE)
        listOf("field-land", "field2-land").forEach { s.getLayer(it)?.setProperties(vis(fieldLandOnly)) }
        listOf("field-layer", "field2-layer").forEach { s.getLayer(it)?.setProperties(vis(!fieldLandOnly)) }
        s.getLayer("water-veil")?.setProperties(vis(field != null && !fieldLandOnly))
        // Eisflächen (Grönland, Antarktis) lagen grau über den Farben – bei Temperatur/Wind aus (3.0.1)
        listOf("landcover_ice_shelf", "landcover_glacier").forEach { s.getLayer(it)?.setProperties(vis(field == null)) }
    }
    LaunchedEffect(style, countryNames) {
        val s = style ?: return@LaunchedEffect
        val v = PropertyFactory.visibility(if (countryNames) Property.VISIBLE else Property.NONE)
        s.layers.filter { it.id.startsWith("place_country") }.forEach { it.setProperties(v) }
    }
    LaunchedEffect(style, clouds) { listOf("clouds", "clouds2").forEach { (style?.getSource(it) as? ImageSource)?.setImage(clouds ?: empty) } }
    LaunchedEffect(style, cloudDetail) {
        val s = style ?: return@LaunchedEffect
        val src = s.getSource("clouds-hi") as? ImageSource ?: return@LaunchedEffect
        val d = cloudDetail
        if (d == null) src.setImage(empty) else { src.setCoordinates(quad(d.west, d.east, d.south, d.north)); src.setImage(d.bitmap) }
    }
    LaunchedEffect(style, cloudDetail, clouds != null) { syncClouds?.invoke() }
    val density = context.resources.displayMetrics.density
    LaunchedEffect(style, labels) {
        val s = style ?: return@LaunchedEffect
        labels.forEach { l -> ensurePill(s, l, density) }
        (s.getSource("labels") as? GeoJsonSource)?.setGeoJson(labelFeatures(labels))
        // Ortsnamen zeichnet nur die App selbst (immer mit Wert) – die der Karte bleiben aus (Kevin am 09.11.2026)
        listOf("place_city_large", "place_city", "place_town", "place_village").forEach {
            s.getLayer(it)?.setProperties(PropertyFactory.visibility(Property.NONE))
        }
    }
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
    LaunchedEffect(style, precipView) { listOf("precip", "precip2").forEach { (style?.getSource(it) as? ImageSource)?.setImage(precipView ?: empty) } }

    LaunchedEffect(style, warnings) {
        style?.getLayer("warn-layer")?.setProperties(PropertyFactory.visibility(if (warnings) Property.VISIBLE else Property.NONE))
    }
    LaunchedEffect(style, motion) { (style?.getSource("motion") as? GeoJsonSource)?.setGeoJson(motionFeatures(motion)) }
    LaunchedEffect(style, pick) {
        (style?.getSource("pick") as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(
            listOfNotNull(pick?.let { Feature.fromGeometry(Point.fromLngLat(it.second, it.first)) })))
    }
    // Standort: Punkt + Genauigkeitskreis (Meter → Bildpunkte je nach Zoom) + sanftes Pulsieren
    LaunchedEffect(style, fix) {
        val s = style ?: return@LaunchedEffect
        val f = fix
        (s.getSource("me") as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(
            listOfNotNull(f?.let { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) })))
        if (f != null) {
            val dp0 = f.accuracyM * 512.0 / (40_075_016.0 * kotlin.math.cos(Math.toRadians(f.lat)))
            s.getLayer("me-acc")?.setProperties(PropertyFactory.circleRadius(Expression.interpolate(Expression.exponential(2), Expression.zoom(),
                Expression.stop(0, dp0), Expression.stop(22, dp0 * 4194304.0))))
        }
    }
    LaunchedEffect(style, fix != null, animate) {
        val s = style ?: return@LaunchedEffect
        if (fix == null || !animate) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val start = System.nanoTime()
            while (true) {
                val p = (((System.nanoTime() - start) / 1e9) % 1.8 / 1.8).toFloat()
                s.getLayer("me-pulse")?.setProperties(PropertyFactory.circleRadius(8f + 14f * p), PropertyFactory.circleOpacity(.35f * (1 - p)))
                kotlinx.coroutines.delay(40)
            }
        }
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
