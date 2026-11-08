package de.schee.wetter.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.schee.wetter.data.Day
import de.schee.wetter.data.LocationSource
import de.schee.wetter.data.Place
import de.schee.wetter.data.PlaceSearch
import de.schee.wetter.data.Units
import de.schee.wetter.data.WeatherCodes
import de.schee.wetter.log.AppLog
import de.schee.wetter.log.DriveSync
import de.schee.wetter.ui.main.DailyCard
import de.schee.wetter.ui.main.DayDetails
import de.schee.wetter.ui.main.HourlyCard
import de.schee.wetter.ui.main.Mode
import de.schee.wetter.ui.sky.SkyBackground
import de.schee.wetter.ui.sky.SkyWeather
import de.schee.wetter.ui.theme.WColors
import de.schee.wetter.ui.theme.WType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    vm: WeatherViewModel = viewModel(),
    /** Nur für Bildschirmfotos im Test: feste Daten statt Laden. */
    preview: WeatherUi? = null,
    previewNow: LocalDateTime? = null,
) {
    val context = LocalContext.current
    val live by vm.ui.collectAsState()
    val ui = preview ?: live
    val units = remember(ui.forecast) { Units.from(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var mode by rememberSaveable { mutableStateOf(Mode.TEMP) }
    var openDay by remember { mutableStateOf<Day?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var driveConnected by remember { mutableStateOf(preview != null || DriveSync.treeUri(context) != null) }
    var askedPermission by rememberSaveable { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        vm.onPermissionResult(r.values.any { it })
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { DriveSync.saveTree(context, uri); driveConnected = true } else AppLog.tap("Drive-Ordnerauswahl abgebrochen")
    }

    // Laden nur, solange die App sichtbar ist
    LaunchedEffect(lifecycle) {
        if (preview != null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!LocationSource.hasPermission(context) && !askedPermission) {
                askedPermission = true
                AppLog.i(AppLog.Cat.ORT, "Frage nach Standort-Berechtigung")
                permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            }
            vm.onVisible()
        }
    }

    val f = ui.forecast
    val zone = remember(f?.timezone) { runCatching { ZoneId.of(f?.timezone ?: "Europe/Berlin") }.getOrDefault(ZoneId.systemDefault()) }
    var now by remember { mutableStateOf(previewNow ?: LocalDateTime.now(zone)) }
    LaunchedEffect(zone) { if (previewNow == null) while (true) { now = LocalDateTime.now(zone); delay(60_000) } }
    val today = now.toLocalDate()
    val todayDay = f?.days?.firstOrNull { it.date == today }
    val skyWeather = f?.let { WeatherCodes.sky(it.current.code) } ?: SkyWeather.PARTLY
    val skyTime = WeatherCodes.skyTime(now, todayDay?.sunrise, todayDay?.sunset)

    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val collapsePx = with(density) { 170.dp.toPx() }
    val collapse = (scroll.value / collapsePx).coerceIn(0f, 1f)

    Box(Modifier.fillMaxSize()) {
        SkyBackground(skyWeather, skyTime, Modifier.fillMaxSize())

        PullToRefreshBox(
            isRefreshing = ui.loading && f != null,
            onRefresh = { AppLog.tap("Herunterziehen → Aktualisieren"); vm.refresh("Herunterziehen") },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).statusBarsPadding().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Großer Kopf, blendet beim Scrollen aus
                Column(
                    Modifier.fillMaxWidth().padding(top = 34.dp, bottom = 14.dp).alpha(1f - collapse),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val place = ui.place
                    if (place?.fromGps == true) Text("➤  MEIN STANDORT", style = WType.label.copy(color = WColors.Text))
                    Text(place?.name ?: "Wetter", style = WType.city, textAlign = TextAlign.Center)
                    if (f != null) {
                        Text(units.t(f.current.temp), style = WType.bigTemp, modifier = Modifier.padding(start = 24.dp))
                        Text(WeatherCodes.text(f.current.code, f.current.isDay), style = WType.condition)
                        todayDay?.let { Text("H: ${units.t(it.tMax)}  T: ${units.t(it.tMin)}", style = WType.highLow) }
                    } else if (ui.loading) {
                        Spacer(Modifier.height(24.dp)); CircularProgressIndicator(color = WColors.Text, strokeWidth = 2.dp)
                    }
                    ui.error?.let { Text(it, style = WType.bodyDim, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp)) }
                    if (f != null && ui.error != null) {
                        val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(f.fetchedAt), zone)
                        Text("Stand ${"%02d:%02d".format(t.hour, t.minute)}", style = WType.bodyDim)
                    }
                }

                if (ui.needsPermission && f == null) {
                    Tile("Standort") {
                        Text("Erlaube den Standort, damit die App das Wetter dort zeigt, wo du gerade bist. Oder such dir einen Ort aus.", style = WType.bodyDim)
                        ActionButton("Standort erlauben", "Kachel Standort → erlauben") {
                            permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                        }
                        ActionButton("Ort suchen", "Kachel Standort → Ort suchen", color = WColors.Good) { showSearch = true }
                    }
                }

                if (!driveConnected) {
                    Tile("Google Drive verbinden") {
                        Text("Wähle einmal den Ordner „Meine Ablage › Apps › Wetter-App“, damit Logs und Fehlerberichte automatisch dort landen.", style = WType.bodyDim)
                        ActionButton("Ordner auswählen", "Kachel Drive → Ordner auswählen") { pickFolder.launch(null) }
                    }
                }

                if (f != null) {
                    HourlyCard(f, now, mode, units) { mode = it }
                    DailyCard(f, today, mode, units) { openDay = it }
                    Text(
                        "Wetterdaten: Open-Meteo.com · ICON-D2 (Deutscher Wetterdienst) und ECMWF",
                        style = WType.label.copy(color = WColors.Label), textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(96.dp))
            }
        }

        // Kompakter Kopf beim Scrollen: „6° | Teils bewölkt“
        if (f != null && collapse > 0.6f) {
            Column(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .35f), Color.Transparent)))
                    .statusBarsPadding().padding(top = 8.dp, bottom = 14.dp).alpha(((collapse - .6f) / .4f).coerceIn(0f, 1f)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(ui.place?.name ?: "", style = WType.condition)
                Text("${units.t(f.current.temp)} | ${WeatherCodes.text(f.current.code, f.current.isDay)}", style = WType.bodyDim)
            }
        }

        var hint by remember { mutableStateOf<String?>(null) }
        var hintId by remember { mutableIntStateOf(0) }
        LaunchedEffect(hintId) { if (hint != null) { delay(2600); hint = null } }
        AnimatedVisibility(
            visible = hint != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 74.dp, start = 24.dp, end = 24.dp),
        ) {
            Text(hint ?: "", style = WType.small, textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xE61C2233)).padding(horizontal = 16.dp, vertical = 11.dp))
        }

        BottomBar(
            Modifier.align(Alignment.BottomCenter),
            onMap = { AppLog.tap("Leiste unten → Karte (Hinweis: kommt mit 0.4.0)"); hint = "Die Regenradar-Karte kommt mit Version 0.4.0."; hintId++ },
            onPlaces = { AppLog.tap("Leiste unten → Ort suchen"); showSearch = true },
            onList = onOpenSettings,
        )
    }

    openDay?.let { d ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { AppLog.tap("Tagesdetails geschlossen"); openDay = null }, sheetState = sheet, containerColor = Color(0xFF141A2B)) {
            Column(Modifier.verticalScroll(rememberScrollState())) { f?.let { DayDetails(it, d, today, units) } }
        }
    }
    if (showSearch) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { AppLog.tap("Ortssuche geschlossen"); showSearch = false }, sheetState = sheet, containerColor = Color(0xFF141A2B)) {
            SearchSheet(
                gpsAvailable = LocationSource.hasPermission(context),
                onPick = { p -> AppLog.tap("Ortssuche → ${p.name} gewählt"); vm.choosePlace(p); showSearch = false },
                onGps = {
                    AppLog.tap("Ortssuche → Mein Standort")
                    context.getSharedPreferences("places", android.content.Context.MODE_PRIVATE).edit().remove("manual").apply()
                    showSearch = false; vm.refresh("Mein Standort gewählt")
                },
            )
        }
    }
}

/** Ortssuche (vollständige Ortsliste folgt mit 0.5.0). */
@Composable
private fun SearchSheet(gpsAvailable: Boolean, onPick: (Place) -> Unit, onGps: () -> Unit) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Ort wählen", style = WType.title)
        if (gpsAvailable) ActionButton("➤  Mein Standort", "Ortssuche → Mein Standort", onClick = onGps)
        fun run() { if (q.isNotBlank()) { searching = true; scope.launch { results = runCatching { PlaceSearch.search(q) }.getOrDefault(emptyList()); searching = false } } }
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true,
            placeholder = { Text("Stadt oder Ort", style = WType.bodyDim) },
            textStyle = WType.body, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { AppLog.tap("Ortssuche → Suche „$q“"); run() }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = WColors.Accent, unfocusedBorderColor = WColors.Glass, cursorColor = WColors.Accent),
        )
        if (searching) CircularProgressIndicator(color = WColors.Text, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        results.forEach { p ->
            Text(p.name, style = WType.body, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(p) }.padding(vertical = 10.dp, horizontal = 4.dp))
        }
        if (!searching && results.isEmpty() && q.isNotBlank()) Text("Tippe auf Suchen auf der Tastatur.", style = WType.bodyDim)
    }
}

@Composable
private fun BottomBar(modifier: Modifier, onMap: () -> Unit, onPlaces: () -> Unit, onList: () -> Unit) {
    Row(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .55f))))
            .navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        RoundButton("map", onMap)
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(WColors.Glass).clickable { onPlaces() }.padding(horizontal = 14.dp, vertical = 9.dp),
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
    Box(Modifier.size(42.dp).clip(CircleShape).background(WColors.Glass).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(20.dp)) {
            val s = size.minDimension / 24f
            val stroke = Stroke(width = 1.8f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
            if (glyph == "map") {
                val p = Path().apply {
                    moveTo(3f * s, 6f * s); lineTo(9f * s, 4f * s); lineTo(15f * s, 6f * s); lineTo(21f * s, 4f * s)
                    lineTo(21f * s, 18f * s); lineTo(15f * s, 20f * s); lineTo(9f * s, 18f * s); lineTo(3f * s, 20f * s); close()
                    moveTo(9f * s, 4f * s); lineTo(9f * s, 18f * s); moveTo(15f * s, 6f * s); lineTo(15f * s, 20f * s)
                }
                drawPath(p, WColors.Text, style = stroke)
            } else {
                listOf(6f, 12f, 18f).forEach { y ->
                    drawCircle(WColors.Text, 1.2f * s, Offset(4f * s, y * s))
                    drawLine(WColors.Text, Offset(8f * s, y * s), Offset(21f * s, y * s), 1.8f * s, StrokeCap.Round)
                }
            }
        }
    }
}
