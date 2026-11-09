package de.schee.wetter

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
        activity.setContent { androidx.compose.runtime.CompositionLocalProvider(de.schee.wetter.ui.sky.LocalAnimate provides false) { WetterTheme { MainScreen(onOpenSettings = {}, preview = ui, previewNow = now) } } }
        settle()
        save(activity.window.decorView, File(out, "main_top.png"))
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

    @Test fun radar() = radarShot(de.schee.wetter.ui.radar.RadarLayer.RAIN, "radar.png")
    @Test fun wind() = radarShot(de.schee.wetter.ui.radar.RadarLayer.WIND, "wind.png")
    @Test fun radarForecast() = radarShot(de.schee.wetter.ui.radar.RadarLayer.RAIN, "radar_forecast.png", forecast = true)
    @Test fun temp() = radarShot(de.schee.wetter.ui.radar.RadarLayer.TEMP, "temp.png")

    private fun radarShot(layer: de.schee.wetter.ui.radar.RadarLayer, name: String, forecast: Boolean = false) {
        val target = System.getProperty("wetter.shots").orEmpty()
        assumeTrue(target.isNotEmpty())
        fun bytes(n: String) = javaClass.classLoader!!.getResource(n)!!.readBytes()
        val kb = bytes("kreise_de.png")
        val base = de.schee.wetter.data.MapImages.bordersOnly(android.graphics.BitmapFactory.decodeByteArray(kb, 0, kb.size))
        val frames = de.schee.wetter.data.RadarData.frames(java.time.Instant.parse("2026-11-09T07:30:00Z"))
        val now = frames.indexOfFirst { it.minutes == 0 }
        val vm = de.schee.wetter.ui.radar.RadarViewModel()
        vm.preview(de.schee.wetter.ui.radar.RadarUi(frames, frames.indices.associateWith { if (it == now || it % 3 == 0) bytes("radar_de.png") else null }.filterValues { it != null } + (now to bytes("radar_de.png")), now,
            wind = de.schee.wetter.data.WindGrid.parse(res("windgrid.json")),
            windView = de.schee.wetter.data.WindGrid.parse(res("viewgrid.json"), de.schee.wetter.data.WindGrid.Spec(22.5, -16.0, 3.5, 16, 16))))
        if (forecast) vm.preview(vm.ui.value.copy(nowIndex = frames.size - 3, bytes = vm.ui.value.bytes + (frames.size - 3 to bytes("model_de.png"))))
        val place = de.schee.wetter.ui.radar.RadarPlace("Mechernich", 50.59, 6.65, "3°")
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { androidx.compose.runtime.CompositionLocalProvider(de.schee.wetter.ui.sky.LocalAnimate provides false) { WetterTheme { de.schee.wetter.ui.radar.RadarScreen(place, layer, onClose = {}, vm = vm, preview = base, previewFix = de.schee.wetter.data.LocationSource.Fix(50.60, 6.66, 25f),
            previewCities = listOf(de.schee.wetter.ui.radar.MapCity("Köln", 50.94, 6.96), de.schee.wetter.ui.radar.MapCity("Düsseldorf", 51.23, 6.78), de.schee.wetter.ui.radar.MapCity("Berlin", 52.52, 13.40), de.schee.wetter.ui.radar.MapCity("Hamburg", 53.55, 9.99), de.schee.wetter.ui.radar.MapCity("München", 48.14, 11.58), de.schee.wetter.ui.radar.MapCity("Frankfurt am Main", 50.11, 8.68))) } } }
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
