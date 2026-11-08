package de.schee.wetter.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.schee.wetter.log.AppLog
import de.schee.wetter.log.DriveSync
import de.schee.wetter.ui.icons.IconKind
import de.schee.wetter.ui.icons.WeatherIcon
import de.schee.wetter.ui.sky.SkyBackground
import de.schee.wetter.ui.sky.SkyTime
import de.schee.wetter.ui.sky.SkyWeather
import de.schee.wetter.ui.theme.WColors
import de.schee.wetter.ui.theme.WType
import java.util.Calendar

/**
 * Version 0.1.0: Hauptbildschirm als Gerüst. Echte Wetterdaten kommen mit 0.2.0,
 * bis dahin lassen sich alle Hintergründe direkt auf dem Handy ausprobieren.
 */
@Composable
fun MainScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    var weather by rememberSaveable { mutableStateOf(SkyWeather.PARTLY) }
    var timeChoice by rememberSaveable { mutableStateOf<SkyTime?>(null) } // null = nach Uhrzeit
    val time = timeChoice ?: timeFromClock()
    var driveConnected by remember { mutableStateOf(DriveSync.treeUri(context) != null) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            DriveSync.saveTree(context, uri); driveConnected = true
        } else AppLog.tap("Drive-Ordnerauswahl abgebrochen")
    }

    Box(Modifier.fillMaxSize()) {
        SkyBackground(weather, time, Modifier.fillMaxSize())

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(weather, time)

            if (!driveConnected) {
                Tile("Google Drive verbinden") {
                    Text(
                        "Damit Logs und Fehlerberichte automatisch in deinem Drive landen, wähle einmal den Ordner " +
                            "„Meine Ablage › Apps › Wetter-App“.",
                        style = WType.bodyDim,
                    )
                    ActionButton("Ordner auswählen", "Kachel Drive → Ordner auswählen") { pickFolder.launch(null) }
                }
            }

            Tile("Wetterlage · Beispiel") { HourlyDemo(weather, time) }

            Tile("Hintergrund ausprobieren") {
                Text("Wetter", style = WType.bodyDim)
                PillRow(SkyWeather.entries, weather, { it.label }, "Hintergrund Wetter") { weather = it }
                Spacer(Modifier.height(2.dp))
                Text("Tageszeit", style = WType.bodyDim)
                val times: List<SkyTime?> = listOf(null) + SkyTime.entries
                PillRow(times, timeChoice, { it?.label ?: "Nach Uhrzeit" }, "Hintergrund Tageszeit") { timeChoice = it }
            }

            Tile("Version ${de.schee.wetter.BuildConfig.VERSION_NAME}") {
                Text(
                    "Das ist das Grundgerüst: Hintergrund, Logging zu Google Drive und der Update-Knopf in den " +
                        "Einstellungen. Echte Wetterdaten für deinen Standort kommen mit Version 0.2.0.",
                    style = WType.bodyDim,
                )
            }
            Spacer(Modifier.height(96.dp))
        }

        BottomBar(
            Modifier.align(Alignment.BottomCenter),
            onMap = { AppLog.tap("Leiste unten → Karte (kommt mit 0.4.0)") },
            onList = onOpenSettings,
        )
    }
}

@Composable
private fun Header(weather: SkyWeather, time: SkyTime) {
    val night = time == SkyTime.NIGHT
    val cond = when (weather) {
        SkyWeather.CLEAR -> if (night) "Klar" else "Sonnig"
        else -> weather.label
    }
    Column(Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("➤  MEIN STANDORT", style = WType.label.copy(color = WColors.Text))
        Text("Vorschau", style = WType.city)
        Text("6°", style = WType.bigTemp, modifier = Modifier.padding(start = 24.dp))
        Text(cond, style = WType.condition)
        Text("H: 10°  T: 4°", style = WType.highLow)
    }
}

@Composable
private fun HourlyDemo(weather: SkyWeather, time: SkyTime) {
    val night = time == SkyTime.NIGHT
    val icon = when (weather) {
        SkyWeather.CLEAR -> if (night) IconKind.MOON else IconKind.SUN
        SkyWeather.PARTLY -> if (night) IconKind.MOON_CLOUD else IconKind.SUN_CLOUD
        SkyWeather.OVERCAST -> IconKind.CLOUD
        SkyWeather.RAIN -> IconKind.RAIN
        SkyWeather.STORM -> IconKind.STORM
        SkyWeather.SNOW -> IconKind.SNOW
        SkyWeather.FOG -> IconKind.FOG
    }
    data class H(val label: String, val icon: IconKind, val value: String)
    val items = listOf(
        H("Jetzt", icon, "6°"), H("16 Uhr", icon, "6°"),
        H("16:58", IconKind.SUNSET, "Unterg."), H("17 Uhr", if (weather == SkyWeather.PARTLY) IconKind.MOON_CLOUD else icon, "5°"),
        H("18 Uhr", if (weather == SkyWeather.CLEAR) IconKind.MOON else icon, "4°"), H("19 Uhr", icon, "4°"),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        items.forEach { h ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(h.label, style = WType.small)
                WeatherIcon(h.icon)
                Text(h.value, style = WType.small, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun BottomBar(modifier: Modifier, onMap: () -> Unit, onList: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .55f))))
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        RoundButton("map", onMap)
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(WColors.Glass).padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("➤", style = WType.label.copy(color = WColors.Text))
            repeat(3) { Box(Modifier.size(6.dp).clip(CircleShape).background(WColors.Label)) }
        }
        RoundButton("list", onList)
    }
}

@Composable
private fun RoundButton(glyph: String, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(WColors.Glass).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
            val s = size.minDimension / 24f
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8f * s, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            if (glyph == "map") {
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(3f * s, 6f * s); lineTo(9f * s, 4f * s); lineTo(15f * s, 6f * s); lineTo(21f * s, 4f * s)
                    lineTo(21f * s, 18f * s); lineTo(15f * s, 20f * s); lineTo(9f * s, 18f * s); lineTo(3f * s, 20f * s); close()
                    moveTo(9f * s, 4f * s); lineTo(9f * s, 18f * s); moveTo(15f * s, 6f * s); lineTo(15f * s, 20f * s)
                }
                drawPath(p, WColors.Text, style = stroke)
            } else {
                listOf(6f, 12f, 18f).forEach { y ->
                    drawCircle(WColors.Text, 1.2f * s, androidx.compose.ui.geometry.Offset(4f * s, y * s))
                    drawLine(WColors.Text, androidx.compose.ui.geometry.Offset(8f * s, y * s), androidx.compose.ui.geometry.Offset(21f * s, y * s), 1.8f * s, androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
        }
    }
}

/** Grobe Tageszeit nach Uhr, bis echte Sonnenzeiten (0.2.0) da sind. */
private fun timeFromClock(): SkyTime {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (h) {
        in 6..7 -> SkyTime.DAWN
        in 8..15 -> SkyTime.DAY
        in 16..17 -> SkyTime.DUSK
        else -> SkyTime.NIGHT
    }
}
