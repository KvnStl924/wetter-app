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
    @Test @Config(qualifiers = "w411dp-h3000dp-xxhdpi")
    fun mainFull() {
        val target = System.getProperty("wetter.shots").orEmpty()
        assumeTrue(target.isNotEmpty())
        val f = ForecastParser.parse(res("icon.json"), res("ecmwf.json"), System.currentTimeMillis())
        val ui = WeatherUi(place = Place("Saffig", 50.41, 7.42, true), forecast = f, normalMax = 9.0,
            warnings = de.schee.wetter.data.WarningsRepository.parse(res("warnings.json")))
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { androidx.compose.runtime.CompositionLocalProvider(de.schee.wetter.ui.sky.LocalAnimate provides false) { WetterTheme { MainScreen(onOpenSettings = {}, preview = ui, previewNow = f.days[0].sunset!!.minusMinutes(20)) } } }
        settle()
        save(activity.window.decorView, File(target, "main_full.png").also { it.parentFile?.mkdirs() })
    }

    private fun settle() { repeat(6) { ShadowLooper.idleMainLooper(200, java.util.concurrent.TimeUnit.MILLISECONDS) } }

    private fun save(v: View, file: File) {
        val bmp = Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
