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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.schee.wetter.BuildConfig
import de.schee.wetter.log.AppLog
import de.schee.wetter.log.DriveSync
import de.schee.wetter.ui.theme.WColors
import de.schee.wetter.ui.theme.WType
import de.schee.wetter.update.UpdateState
import de.schee.wetter.update.UpdateUi
import de.schee.wetter.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Einstellungen: Einheiten, Drive & Logging, Version/Update. */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var tempUnit by remember { mutableStateOf(prefs.getString("unit_temp", "°C")!!) }
    var windUnit by remember { mutableStateOf(prefs.getString("unit_wind", "km/h")!!) }

    LaunchedEffect(Unit) { AppLog.i(AppLog.Cat.INFO, "Einstellungen angezeigt") }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF070B1D), Color(0xFF111833))))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Einstellungen", style = WType.title, modifier = Modifier.weight(1f))
                Text("Fertig", style = WType.small.copy(color = WColors.Accent), modifier = Modifier.clickable { onBack() }.padding(8.dp))
            }

            Tile("Einheiten") {
                Text("Temperatur", style = WType.bodyDim)
                PillRow(listOf("°C", "°F"), tempUnit, { it }, "Einheit Temperatur") {
                    tempUnit = it; prefs.edit().putString("unit_temp", it).apply()
                }
                Text("Wind", style = WType.bodyDim)
                PillRow(listOf("km/h", "m/s", "Bft"), windUnit, { it }, "Einheit Wind") {
                    windUnit = it; prefs.edit().putString("unit_wind", it).apply()
                }
                Text("Niederschlag: mm", style = WType.bodyDim)
            }

            DriveTile()
            UpdateTile()
            SkyPreviewTile()

            Tile("Über") {
                Text("Wetterdaten ab Version 0.2.0: Open-Meteo.com (CC BY 4.0) und Deutscher Wetterdienst (Open Data).", style = WType.bodyDim)
                Text("Schrift: Inter (SIL Open Font License).", style = WType.bodyDim)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Alle Hintergründe zum Durchschalten (früher auf dem Hauptbildschirm). */
@Composable
private fun SkyPreviewTile() {
    var weather by remember { mutableStateOf(de.schee.wetter.ui.sky.SkyWeather.PARTLY) }
    var time by remember { mutableStateOf(de.schee.wetter.ui.sky.SkyTime.NIGHT) }
    Tile("Hintergrund-Vorschau") {
        Box(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(14.dp))) {
            de.schee.wetter.ui.sky.SkyBackground(weather, time, Modifier.fillMaxSize())
        }
        PillRow(de.schee.wetter.ui.sky.SkyWeather.entries, weather, { it.label }, "Hintergrund-Vorschau Wetter") { weather = it }
        PillRow(de.schee.wetter.ui.sky.SkyTime.entries, time, { it.label }, "Hintergrund-Vorschau Tageszeit") { time = it }
    }
}

@Composable
private fun DriveTile() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tree by remember { mutableStateOf(DriveSync.treeUri(context)) }
    var folderName by remember { mutableStateOf("") }
    var pending by remember { mutableIntStateOf(0) }

    LaunchedEffect(tree) {
        withContext(Dispatchers.IO) {
            folderName = tree?.let { DriveSync.rootName(context.contentResolver, it) } ?: ""
            pending = DriveSync.countPending()
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { DriveSync.saveTree(context, uri); tree = DriveSync.treeUri(context) }
        else AppLog.tap("Drive-Ordnerauswahl abgebrochen")
    }

    Tile("Drive & Logging") {
        Text(
            if (tree == null) "Kein Ordner verbunden" else "Verbunden: ${folderName.ifEmpty { "Drive-Ordner" }}",
            style = WType.body,
        )
        if (tree != null && folderName.isNotEmpty() && folderName != DriveSync.APP_FOLDER) {
            Text(
                "Bitte den Ordner „Meine Ablage › Apps › ${DriveSync.APP_FOLDER}“ wählen. " +
                    "Gewählt ist „$folderName“ – dort legt die App keine APK ab.",
                style = WType.bodyDim.copy(color = WColors.Warn),
            )
        }
        Text(
            "Die App schreibt mit, solange sie offen ist, und lädt das Protokoll beim Verlassen nach " +
                "„03 Logs - Fehlerberichte“ hoch. Danach wird es vom Handy gelöscht.",
            style = WType.bodyDim,
        )
        Text(
            if (pending == 0) "Keine Dateien warten auf den Upload." else "$pending Datei(en) warten auf den Upload.",
            style = WType.bodyDim,
        )
        DriveSync.lastError(context)?.let { Text("Letzter Upload-Fehler: $it", style = WType.bodyDim.copy(color = WColors.Warn)) }
        ActionButton(if (tree == null) "Ordner auswählen" else "Ordner neu wählen", "Einstellungen → Drive-Ordner wählen") { pick.launch(null) }
        if (pending > 0 && tree != null) {
            ActionButton("Jetzt hochladen", "Einstellungen → Logs jetzt hochladen", color = WColors.Good) {
                scope.launch(Dispatchers.IO) { DriveSync.uploadPending(context); pending = DriveSync.countPending() }
            }
        }
    }
}

@Composable
private fun UpdateTile() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by UpdateState.ui.collectAsState()
    val installed = BuildConfig.VERSION_NAME

    fun check() = scope.launch {
        UpdateState.set(UpdateUi.Checking)
        runCatching { Updater.check() }
            .onSuccess { r -> UpdateState.set(if (r != null && Updater.isNewer(r)) UpdateUi.Available(r) else UpdateUi.UpToDate) }
            .onFailure { AppLog.update("Suche fehlgeschlagen: ${it.message}"); UpdateState.failed("Keine Verbindung zu GitHub. Bitte später erneut versuchen.") }
    }

    fun install(r: de.schee.wetter.update.Release) = scope.launch {
        if (!Updater.canInstall(context)) {
            AppLog.update("Berechtigung „Apps installieren“ fehlt – Einstellungen geöffnet")
            UpdateState.set(UpdateUi.NeedsPermission(r))
            context.startActivity(Updater.installPermissionIntent(context))
            return@launch
        }
        UpdateState.set(UpdateUi.Downloading(r, 0f))
        runCatching { Updater.download(context, r) { p -> UpdateState.set(UpdateUi.Downloading(r, p)) } }
            .onSuccess { apk -> UpdateState.set(UpdateUi.Installing(r)); Updater.install(context, apk) }
            .onFailure { AppLog.update("Download fehlgeschlagen: ${it.message}"); UpdateState.failed(it.message ?: "Download fehlgeschlagen.") }
    }

    Tile("Version") {
        when (val s = ui) {
            UpdateUi.Idle -> {
                Text("Installiert: Version $installed", style = WType.body)
                ActionButton("Version $installed · Nach Updates suchen", "Einstellungen → Nach Updates suchen") { check() }
            }
            UpdateUi.Checking -> {
                Text("Suche nach Updates …", style = WType.body)
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)), color = WColors.Accent, trackColor = WColors.Glass)
            }
            UpdateUi.UpToDate -> {
                Text("Du hast die neueste Version ($installed).", style = WType.body)
                ActionButton("Erneut suchen", "Einstellungen → Erneut nach Updates suchen") { check() }
            }
            is UpdateUi.Available -> {
                Text("Update auf ${s.release.version} verfügbar", style = WType.body.copy(color = WColors.Good))
                if (s.release.notes.isNotBlank()) Text(s.release.notes.take(600), style = WType.bodyDim)
                ActionButton("Installieren", "Einstellungen → Update ${s.release.version} installieren", color = WColors.Good) { install(s.release) }
            }
            is UpdateUi.NeedsPermission -> {
                Text("Bitte erlaube der Wetter-App einmalig, Apps zu installieren, und tippe dann erneut.", style = WType.bodyDim)
                ActionButton("Installieren", "Einstellungen → Update nach Berechtigung installieren", color = WColors.Good) { install(s.release) }
            }
            is UpdateUi.Downloading -> {
                Text("Lade Version ${s.release.version} … ${(s.progress * 100).toInt()} %", style = WType.body)
                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)), color = WColors.Good, trackColor = WColors.Glass)
            }
            is UpdateUi.Installing -> Text("Android fragt gleich: „Installieren?“ – bitte bestätigen.", style = WType.body)
            is UpdateUi.Error -> {
                Text(s.message, style = WType.body.copy(color = WColors.Warn))
                ActionButton("Erneut versuchen", "Einstellungen → Update erneut versuchen") { check() }
            }
        }
    }
}
