package de.schee.wetter

import androidx.compose.foundation.background
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import de.schee.wetter.data.ForecastParser
import de.schee.wetter.data.Place
import de.schee.wetter.ui.MainScreen
import de.schee.wetter.ui.WeatherUi
import de.schee.wetter.ui.theme.WetterTheme
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.Duration

/**
 * Zeichnet Bildschirme als PNG, damit die Optik ohne Handy geprüft werden kann.
 * Nur aktiv mit WETTER_SCREENSHOTS=<ordner> ./gradlew testDebugUnitTest --tests '*ScreenshotDump'
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotDump {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun main() {
        val target = System.getProperty("wetter.shots").orEmpty()
        assumeTrue(target.isNotEmpty())
        val out = File(target).apply { mkdirs() }
        val f = ForecastParser.parse(res("icon.json"), res("ecmwf.json"), System.currentTimeMillis())
        val ui = WeatherUi(place = Place("Saffig", 50.41, 7.42, true), forecast = f, normalMax = 9.0,
            warnings = de.schee.wetter.data.WarningsRepository.parse(res("warnings.json")))
        val now = f.days[0].sunrise!!.plusHours(5)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        // Regen auf die Minute: trocken, ab 15 Minuten leichter, dann mäßiger Regen
        val t0 = java.time.Instant.now()
        val nc = de.schee.wetter.data.Nowcast.Series((0..24).map { i -> t0.plusSeconds(i * 300L) to when { i < 3 -> 0.0; i < 8 -> 0.4 * (i - 2); i < 14 -> 3.0; i < 18 -> 1.0; else -> 0.0 } })
        activity.setContent { androidx.compose.runtime.CompositionLocalProvider(de.schee.wetter.ui.sky.LocalAnimate provides false) { WetterTheme { MainScreen(onOpenSettings = {}, preview = ui, previewNow = now, previewPageCount = 3, previewNowcast = nc,
            previewThunder = de.schee.wetter.data.Thunder.Near(12.4, 225.0, java.time.Instant.now(), -1, true)) } } }
        settle()
        save(activity.window.decorView, File(out, "main_top.png"))
    }

    @Test fun mainNight() {
        val target = System.getProperty("wetter.shots").orEmpty()
        assumeTrue(target.isNotEmpty())
        val f = ForecastParser.parse(res("icon.json"), res("ecmwf.json"), System.currentTimeMillis())
        // klarer Himmel erzwingen, damit Mond und Sterne zu sehen sind
        val ui = WeatherUi(place = Place("Mechernich", 50.59, 6.65, true), forecast = f.copy(current = f.current.copy(code = 0, isDay = false)), normalMax = 9.0)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { androidx.compose.runtime.CompositionLocalProvider(de.schee.wetter.ui.sky.LocalAnimate provides false) { WetterTheme { MainScreen(onOpenSettings = {}, preview = ui, previewNow = f.days[0].sunset!!.plusHours(4)) } } }
        settle()
        save(activity.window.decorView, File(target, "main_nacht.png"))
    }

    @Test fun mainScrolled() {
        val target = System.getProperty("wetter.shots").orEmpty()
        assumeTrue(target.isNotEmpty())
        val f = ForecastParser.parse(res("icon.json"), res("ecmwf.json"), System.currentTimeMillis(), res("multi.json"))
        val d = f.days.first { f.confidence[it.date] != null }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { androidx.compose.runtime.CompositionLocalProvider(de.schee.wetter.ui.sky.LocalAnimate provides false) { WetterTheme {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Color(0xFF141A2B))) {
                de.schee.wetter.ui.main.DayDetails(f, d, d.date, de.schee.wetter.data.Units("°C", "km/h"))
            }
        } } }
        settle()
        save(activity.window.decorView, File(target, "tag_sicherheit.png"))
    }

    @Test fun mainOffline() {
        val target = System.getProperty("wetter.shots").orEmpty()
        assumeTrue(target.isNotEmpty())
        val f = ForecastParser.parse(res("icon.json"), res("ecmwf.json"), System.currentTimeMillis() - 3 * 3600_000L)
        val ui = WeatherUi(place = Place("Saffig", 50.41, 7.42, true), forecast = f, error = "Keine Verbindung – gezeigt wird der letzte Stand.")
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        de.schee.wetter.data.Network.preview(false)
        try {
            activity.setContent { androidx.compose.runtime.CompositionLocalProvider(de.schee.wetter.ui.sky.LocalAnimate provides false) { WetterTheme { MainScreen(onOpenSettings = {}, preview = ui, previewNow = f.days[0].sunrise!!.plusHours(5)) } } }
            settle()
            save(activity.window.decorView, File(target, "main_offline.png").also { it.parentFile?.mkdirs() })
        } finally { de.schee.wetter.data.Network.preview(true) }
    }

    /** Sehr hohes Fenster, damit die ganze Seite auf ein Bild passt. */
    @Test @Config(qualifiers = "w411dp-h5200dp-xxhdpi")
    fun mainFull() {
        val target = System.getProperty("wetter.shots").orEmpty()
        assumeTrue(target.isNotEmpty())
        val f = ForecastParser.parse(res("icon.json"), res("ecmwf.json"), System.currentTimeMillis())
        val ui = WeatherUi(place = Place("Saffig", 50.41, 7.42, true), forecast = f, normalMax = 9.0,
            warnings = de.schee.wetter.data.WarningsRepository.parse(res("warnings.json")), maps = maps())
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { androidx.compose.runtime.CompositionLocalProvider(de.schee.wetter.ui.sky.LocalAnimate provides false) { WetterTheme { MainScreen(onOpenSettings = {}, preview = ui, previewNow = f.days[0].sunset!!.minusMinutes(20)) } } }
        settle()
        save(activity.window.decorView, File(target, "main_full.png").also { it.parentFile?.mkdirs() })
    }

    @Test @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun editTiles() {
        val target = System.getProperty("wetter.shots").orEmpty()
        assumeTrue(target.isNotEmpty())
        val f = ForecastParser.parse(res("icon.json"), res("ecmwf.json"), System.currentTimeMillis())
        val ui = WeatherUi(place = Place("Saffig", 50.41, 7.42, true), forecast = f)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { androidx.compose.runtime.CompositionLocalProvider(de.schee.wetter.ui.sky.LocalAnimate provides false) { WetterTheme { MainScreen(onOpenSettings = {}, preview = ui, previewNow = f.days[0].sunrise!!.plusHours(3), previewEditTiles = true) } } }
        settle()
        save(activity.window.decorView, File(target, "edit_tiles.png").also { it.parentFile?.mkdirs() })
    }

    /** Ortsliste mit „Mein Standort“ und zwei gespeicherten Orten. */
    @Test fun places() {
        val target = System.getProperty("wetter.shots").orEmpty()
        assumeTrue(target.isNotEmpty())
        val f = ForecastParser.parse(res("icon.json"), res("ecmwf.json"), System.currentTimeMillis())
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val vm = de.schee.wetter.ui.WeatherViewModel(activity.application)
        val koeln = Place("Köln", 50.94, 6.96, false); val palma = Place("Palma", 39.57, 2.65, false)
        vm.preview(listOf(koeln, palma), mapOf(
            de.schee.wetter.ui.WeatherViewModel.GPS to WeatherUi(place = Place("Mechernich", 50.59, 6.65, true), forecast = f),
            de.schee.wetter.data.PlaceStore.id(koeln) to WeatherUi(place = koeln, forecast = f),
            de.schee.wetter.data.PlaceStore.id(palma) to WeatherUi(place = palma, forecast = f)))
        activity.setContent { androidx.compose.runtime.CompositionLocalProvider(de.schee.wetter.ui.sky.LocalAnimate provides false) { WetterTheme { de.schee.wetter.ui.PlacesScreen(onClose = {}, vm = vm) } } }
        settle()
        save(activity.window.decorView, File(target, "places.png").also { it.parentFile?.mkdirs() })
    }

    @Test fun settings() {
        val target = System.getProperty("wetter.shots").orEmpty()
        assumeTrue(target.isNotEmpty())
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { androidx.compose.runtime.CompositionLocalProvider(de.schee.wetter.ui.sky.LocalAnimate provides false) { WetterTheme { de.schee.wetter.ui.SettingsScreen(onBack = {}) } } }
        settle()
        save(activity.window.decorView, File(target, "settings.png").also { it.parentFile?.mkdirs() })
    }

    @Test fun radar() = radarShot(de.schee.wetter.ui.radar.RadarLayer.RAIN, "radar.png")
    @Test fun wind() = radarShot(de.schee.wetter.ui.radar.RadarLayer.WIND, "wind.png")
    @Test fun radarForecast() = radarShot(de.schee.wetter.ui.radar.RadarLayer.RAIN, "radar_forecast.png", forecast = true)
    @Test fun temp() = radarShot(de.schee.wetter.ui.radar.RadarLayer.TEMP, "temp.png")
    @Test fun tempWorld() = radarShot(de.schee.wetter.ui.radar.RadarLayer.TEMP, "temp_welt.png", area = doubleArrayOf(-30.0, 60.0, -40.0, 60.0))
    @Test fun tempMinMax() = radarShot(de.schee.wetter.ui.radar.RadarLayer.TEMP, "temp_maxmin.png", minMax = true)
    @Test fun tempMinMaxEurope() = radarShot(de.schee.wetter.ui.radar.RadarLayer.TEMP, "temp_maxmin_europa.png", area = doubleArrayOf(-12.0, 32.0, 35.0, 62.0), minMax = true)
    @Test fun clouds() = radarShot(de.schee.wetter.ui.radar.RadarLayer.CLOUDS, "wolken.png")
    @Test fun cloudsWorld() = radarShot(de.schee.wetter.ui.radar.RadarLayer.CLOUDS, "wolken_welt.png", area = doubleArrayOf(-100.0, 60.0, -60.0, 75.0))
    @Test fun lightning() = radarShot(null, "blitze.png", lightning = true, area = doubleArrayOf(0.0, 25.0, -12.0, 14.0))
    @Test fun rainLightning() = radarShot(de.schee.wetter.ui.radar.RadarLayer.RAIN, "regen_blitze.png", lightning = true)
    @Test fun radarPick() = radarShot(de.schee.wetter.ui.radar.RadarLayer.RAIN, "radar_antippen.png", pick = true)

    private fun radarShot(layer: de.schee.wetter.ui.radar.RadarLayer?, name: String, forecast: Boolean = false, lightning: Boolean = false, area: DoubleArray? = null, pick: Boolean = false, minMax: Boolean = false) {
        val target = System.getProperty("wetter.shots").orEmpty()
        assumeTrue(target.isNotEmpty())
        fun bytes(n: String) = javaClass.classLoader!!.getResource(n)!!.readBytes()
        val kb = bytes("kreise_de.png")
        val base = if (area == null) de.schee.wetter.data.MapImages.bordersOnly(android.graphics.BitmapFactory.decodeByteArray(kb, 0, kb.size))
            else android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        val frames = de.schee.wetter.data.RadarData.frames(java.time.Instant.parse("2026-11-09T07:30:00Z"))
        val now = frames.indexOfFirst { it.minutes == 0 }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val vm = de.schee.wetter.ui.radar.RadarViewModel(activity.application)
        de.schee.wetter.data.Places.load(activity)
        val start = java.time.Instant.parse("2026-11-09T18:00:00Z")
        val ww = de.schee.wetter.data.WorldWeather(start, de.schee.wetter.data.WorldGrid.fromGeoTiff(bytes("world_t2m.tif")), de.schee.wetter.data.WorldGrid.fromGeoTiff(bytes("world_uv10m.tif")))
        fun gray(n: String): IntArray { val b = bytes(n); val bmp = android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size); val s = android.graphics.Bitmap.createScaledBitmap(bmp, 1800, 1800, true); val p = IntArray(1800 * 1800); s.getPixels(p, 0, 1800, 0, 0, 1800, 1800); return IntArray(p.size) { (p[it] shr 8) and 0xFF } }
        val clouds = if (layer == de.schee.wetter.ui.radar.RadarLayer.CLOUDS) de.schee.wetter.data.Satellite.Clouds(android.graphics.Bitmap.createBitmap(
            de.schee.wetter.data.Satellite.cloudPixels(gray("world_clouds.jpg"), gray("world_clouds_mtg.jpg"), 1800, 1800, ww), 1800, 1800, android.graphics.Bitmap.Config.ARGB_8888), System.currentTimeMillis()) else null
        val strikes = if (lightning) {
            val b = bytes("blitze_afa.png"); val bmp = android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size)
            val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val all = de.schee.wetter.data.Satellite.strikesFrom(px, bmp.width, bmp.height, start)
            // verschiedene Alter vortäuschen, damit alle Darstellungen zu sehen sind
            all.mapIndexed { i, s -> s.copy(time = start.minusSeconds((i % 12) * 300L)) }
        } else emptyList()
        vm.preview(de.schee.wetter.ui.radar.RadarUi(frames, frames.indices.associateWith { if (it == now || it % 3 == 0) bytes("radar_de.png") else null }.filterValues { it != null } + (now to bytes("radar_de.png")), now,
            windView = de.schee.wetter.data.WindGrid.parse(res("viewgrid.json"), de.schee.wetter.data.WindGrid.Spec(22.5, -16.0, 3.5, 16, 16)),
            world = mapOf(start to ww), worldStart = start, clouds = clouds, strikes = strikes, strikesNewest = start))
        if (forecast) vm.preview(vm.ui.value.copy(nowIndex = frames.size - 3, bytes = vm.ui.value.bytes + (frames.size - 3 to bytes("model_de.png"))))
        val place = de.schee.wetter.ui.radar.RadarPlace("Mechernich", 50.59, 6.65, "3°")
        fun c(n: String, lat: Double, lon: Double, r: Int) = de.schee.wetter.ui.radar.MapCity(n, lat, lon, r)
        activity.setContent { androidx.compose.runtime.CompositionLocalProvider(de.schee.wetter.ui.sky.LocalAnimate provides false) { WetterTheme { de.schee.wetter.ui.radar.RadarScreen(place, layer, onClose = {}, vm = vm, preview = base, previewFix = de.schee.wetter.data.LocationSource.Fix(50.60, 6.66, 25f),
            previewCities = if (area != null) emptyList() else listOf(c("Köln", 50.94, 6.96, 3), c("Düsseldorf", 51.23, 6.78, 3), c("Berlin", 52.52, 13.40, 1), c("Hamburg", 53.55, 9.99, 2), c("München", 48.14, 11.58, 2), c("Frankfurt am Main", 50.11, 8.68, 3), c("Leipzig", 51.34, 12.37, 4), c("Hannover", 52.38, 9.73, 4)),
            previewLightning = lightning, previewArea = area, previewMinMax = minMax,
            previewPick = if (pick) de.schee.wetter.ui.radar.Picked(50.94, 6.96, "Köln", 4.2, 18.0, "Regen in 25 Min.") else null,
            previewMotion = if (pick) de.schee.wetter.data.Nowcast.Motion(60.0, 35.0, 50.2, 5.9, 50.5, 6.6) else null) } } }
        settle(); Thread.sleep(500); settle()
        save(activity.window.decorView, File(target, name).also { it.parentFile?.mkdirs() })
    }

    private fun maps(): de.schee.wetter.data.MapImages.Layers {
        val bytes = javaClass.classLoader!!.getResource("kreise.png")!!.readBytes()
        val b = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        return de.schee.wetter.data.MapImages.Layers(de.schee.wetter.data.MapImages.bordersOnly(b), null, 0L)
    }

    private fun settle() { repeat(6) { ShadowLooper.idleMainLooper(200, java.util.concurrent.TimeUnit.MILLISECONDS) } }

    private fun save(v: View, file: File) {
        val bmp = Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
