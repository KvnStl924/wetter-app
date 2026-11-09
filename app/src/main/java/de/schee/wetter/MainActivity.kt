package de.schee.wetter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import de.schee.wetter.log.AppLog
import de.schee.wetter.ui.MainScreen
import de.schee.wetter.ui.WeatherViewModel
import de.schee.wetter.ui.radar.RadarLayer
import de.schee.wetter.ui.radar.RadarPlace
import de.schee.wetter.ui.radar.RadarScreen
import de.schee.wetter.data.Units
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import de.schee.wetter.ui.SettingsScreen
import de.schee.wetter.ui.theme.WetterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            WetterTheme {
                var screen by rememberSaveable { mutableStateOf("main") }
                val weather by viewModel<WeatherViewModel>().ui.collectAsState()
                BackHandler(enabled = screen != "main") {
                    AppLog.tap("Zurück-Geste → Hauptbildschirm")
                    screen = "main"
                }
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                    label = "screen",
                ) { s ->
                    when (s) {
                        "settings" -> SettingsScreen(onBack = { AppLog.tap("Einstellungen → Fertig"); screen = "main" })
                        "radar", "wind" -> {
                            val w = weather.forecast
                            val p = weather.place
                            val place = if (p != null && w != null) RadarPlace(p.name, p.lat, p.lon, Units.from(this@MainActivity).t(w.current.temp)) else null
                            RadarScreen(place, if (s == "wind") RadarLayer.WIND else RadarLayer.RAIN, onClose = { screen = "main" })
                        }
                        else -> MainScreen(
                            onOpenSettings = { AppLog.tap("Leiste unten → Einstellungen geöffnet"); screen = "settings" },
                            onOpenRadar = { layer, from ->
                                AppLog.tap("$from → Vollbild ${layer.label} geöffnet")
                                screen = if (layer == RadarLayer.WIND) "wind" else "radar"
                            },
                        )
                    }
                }
            }
        }
    }
}
