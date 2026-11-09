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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.graphicsLayer
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


/**
 * Hauptseite: je Ort eine Seite zum Wischen (Seite 0 = „Mein Standort“, wenn erlaubt), unten die Leiste
 * mit Karte, Seitenpunkten (öffnen die Ortsliste) und Einstellungen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    /** Vollbild-Radar öffnen (Ebene, Herkunft fürs Log). */
    onOpenRadar: (de.schee.wetter.ui.radar.RadarLayer, String) -> Unit = { _, _ -> },
    /** Ortsliste öffnen. */
    onOpenPlaces: () -> Unit = {},
    vm: WeatherViewModel = viewModel(),
    /** Nur für Bildschirmfotos im Test: feste Daten statt Laden. */
    preview: WeatherUi? = null,
    previewNow: LocalDateTime? = null,
    previewEditTiles: Boolean = false,
    previewPageCount: Int = 1,
) {
    val context = LocalContext.current
    val livePages by vm.pages.collectAsState()
    val states by vm.states.collectAsState()
    val selected by vm.selected.collectAsState()
    val pageIds = if (preview != null) List(previewPageCount) { if (it == 0) WeatherViewModel.GPS else "p$it" } else livePages
    val tileState = remember { de.schee.wetter.ui.tiles.TileState(de.schee.wetter.ui.tiles.TileStore(context)) }
    var editTiles by rememberSaveable { mutableStateOf(previewEditTiles) }
    var hint by remember { mutableStateOf<String?>(null) }
    var hintId by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var mode by rememberSaveable { mutableStateOf(Mode.TEMP) }
    var openDay by remember { mutableStateOf<Pair<de.schee.wetter.data.Forecast, Day>?>(null) }
    var driveConnected by remember { mutableStateOf(preview != null || DriveSync.treeUri(context) != null) }
    var askedPermission by rememberSaveable { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        vm.onPermissionResult(r.values.any { it })
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { DriveSync.saveTree(context, uri); driveConnected = true } else AppLog.tap("Drive-Ordnerauswahl abgebrochen")
    }

    androidx.activity.compose.BackHandler(enabled = editTiles) { AppLog.tap("Zurück-Geste → Kacheln bearbeiten beendet"); editTiles = false }

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

    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = selected) { pageIds.size.coerceAtLeast(1) }
    // Wischen → Seite merken (lädt sie, falls nötig); Auswahl aus der Ortsliste → hinblättern
    LaunchedEffect(pager.settledPage) { if (preview == null && pageIds.isNotEmpty()) { if (pager.settledPage != selected) AppLog.tap("Wisch → Seite ${pager.settledPage + 1}"); vm.select(pager.settledPage) } }
    LaunchedEffect(selected, pageIds.size) { if (selected != pager.currentPage && selected < pageIds.size) pager.scrollToPage(selected) }

    Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pager, userScrollEnabled = !editTiles, beyondViewportPageCount = 0,
            modifier = Modifier.fillMaxSize(), key = { pageIds.getOrElse(it) { "leer" } },
        ) { page ->
            val id = pageIds.getOrNull(page)
            val ui = preview ?: when (id) {
                null -> WeatherUi(needsPermission = true)
                else -> states[id] ?: vm.uiFor(id).copy(loading = true)
            }
            WeatherPage(
                ui = ui, isGps = id == WeatherViewModel.GPS, previewNow = previewNow, tileState = tileState, editTiles = editTiles,
                mode = mode, driveConnected = driveConnected,
                onMode = { mode = it }, onEdit = { editTiles = it }, onOpenDay = { f, d -> openDay = f to d },
                onRefresh = { AppLog.tap("Herunterziehen → Aktualisieren"); vm.refresh("Herunterziehen") },
                onAllowLocation = { permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) },
                onSearch = onOpenPlaces, onPickFolder = { pickFolder.launch(null) }, onOpenRadar = onOpenRadar,
            )
        }

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
            pageCount = pageIds.size, current = pager.currentPage, gpsFirst = pageIds.firstOrNull() == WeatherViewModel.GPS,
            onMap = { onOpenRadar(de.schee.wetter.ui.radar.RadarLayer.RAIN, "Leiste unten → Karte") },
            onPlaces = { AppLog.tap("Leiste unten → Ortsliste"); onOpenPlaces() },
            onList = onOpenSettings,
        )
    }

    openDay?.let { (f, d) ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val today = LocalDateTime.now(runCatching { ZoneId.of(f.timezone) }.getOrDefault(ZoneId.systemDefault())).toLocalDate()
        ModalBottomSheet(onDismissRequest = { AppLog.tap("Tagesdetails geschlossen"); openDay = null }, sheetState = sheet, containerColor = Color(0xFF141A2B)) {
            Column(Modifier.verticalScroll(rememberScrollState())) { DayDetails(f, d, today, Units.from(context)) }
        }
    }
}

/** Eine Seite (ein Ort): Himmel, Kopf, Stundenliste, 10 Tage, Kacheln. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeatherPage(
    ui: WeatherUi, isGps: Boolean, previewNow: LocalDateTime?, tileState: de.schee.wetter.ui.tiles.TileState, editTiles: Boolean,
    mode: Mode, driveConnected: Boolean,
    onMode: (Mode) -> Unit, onEdit: (Boolean) -> Unit, onOpenDay: (de.schee.wetter.data.Forecast, Day) -> Unit,
    onRefresh: () -> Unit, onAllowLocation: () -> Unit, onSearch: () -> Unit, onPickFolder: () -> Unit,
    onOpenRadar: (de.schee.wetter.ui.radar.RadarLayer, String) -> Unit,
) {
    val context = LocalContext.current
    val units = remember(ui.forecast) { Units.from(context) }
    val f = ui.forecast
    val zone = remember(f?.timezone) { runCatching { ZoneId.of(f?.timezone ?: "Europe/Berlin") }.getOrDefault(ZoneId.systemDefault()) }
    var now by remember(zone) { mutableStateOf(previewNow ?: LocalDateTime.now(zone)) }
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

        PullToRefreshBox(isRefreshing = ui.loading && f != null, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).statusBarsPadding().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Großer Kopf, blendet beim Scrollen aus
                Column(
                    Modifier.fillMaxWidth().padding(top = 34.dp, bottom = 14.dp).alpha(1f - collapse),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val place = ui.place
                    if (isGps) Text("➤  MEIN STANDORT", style = WType.label.copy(color = WColors.Text))
                    Text(place?.name ?: if (isGps) "Standort wird ermittelt …" else "Wetter", style = WType.city, textAlign = TextAlign.Center)
                    if (f != null) {
                        // Temperatur zählt beim Aktualisieren sanft zum neuen Wert
                        val animate = de.schee.wetter.ui.sky.LocalAnimate.current
                        val target = units.tNum(f.current.temp)
                        val shown = if (animate) androidx.compose.animation.core.animateIntAsState(target, androidx.compose.animation.core.tween(700), label = "temp").value else target
                        Text("$shown°", style = WType.bigTemp, modifier = Modifier.padding(start = 24.dp))
                        Text(WeatherCodes.text(f.current.code, f.current.isDay), style = WType.condition)
                        todayDay?.let { Text("H: ${units.t(it.tMax)}  T: ${units.t(it.tMin)}", style = WType.highLow) }
                    } else if (ui.loading) {
                        Spacer(Modifier.height(24.dp)); CircularProgressIndicator(color = WColors.Text, strokeWidth = 2.dp)
                    }
                    // Ohne Netz: kleiner Hinweis mit dem Stand der gezeigten Daten (statt Fehlermeldung)
                    val online by de.schee.wetter.data.Network.online.collectAsState()
                    val offline = !online && f != null
                    AnimatedVisibility(offline, enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut()) {
                        if (f != null) OfflinePill(f.fetchedAt, zone)
                    }
                    if (!offline) {
                        ui.error?.let { Text(it, style = WType.bodyDim, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp)) }
                        if (f != null && ui.error != null) {
                            val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(f.fetchedAt), zone)
                            Text("Stand ${"%02d:%02d".format(t.hour, t.minute)}", style = WType.bodyDim)
                        }
                    }
                }

                if (ui.needsPermission && f == null) {
                    Tile("Standort") {
                        Text("Erlaube den Standort, damit die App das Wetter dort zeigt, wo du gerade bist. Oder such dir einen Ort aus.", style = WType.bodyDim)
                        ActionButton("Standort erlauben", "Kachel Standort → erlauben", onClick = onAllowLocation)
                        ActionButton("Ort suchen", "Kachel Standort → Ort suchen", color = WColors.Good, onClick = onSearch)
                    }
                }

                if (!driveConnected) {
                    Tile("Google Drive verbinden") {
                        Text("Wähle einmal den Ordner „Meine Ablage › Apps › Wetter-App“, damit Logs und Fehlerberichte automatisch dort landen.", style = WType.bodyDim)
                        ActionButton("Ordner auswählen", "Kachel Drive → Ordner auswählen", onClick = onPickFolder)
                    }
                }

                if (f != null) {
                    // Inhalt blendet beim ersten Laden weich ein und gleitet leicht nach oben
                    val animate = de.schee.wetter.ui.sky.LocalAnimate.current
                    var shownOnce by remember { mutableStateOf(!animate) }
                    LaunchedEffect(Unit) { shownOnce = true }
                    val appear by androidx.compose.animation.core.animateFloatAsState(if (shownOnce) 1f else 0f, androidx.compose.animation.core.tween(450), label = "appear")
                    val lift = with(density) { 24.dp.toPx() }
                    Column(
                        Modifier.graphicsLayer { alpha = appear; translationY = (1 - appear) * lift },
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val tileData = de.schee.wetter.ui.tiles.TileData(f, now, units, ui.warnings, ui.normalMax, ui.maps, ui.place?.lat ?: f.lat, ui.place?.lon ?: f.lon)
                        de.schee.wetter.ui.tiles.WarningTile(tileData, tileState) { AppLog.tap("Warnung lange gedrückt → Bearbeiten"); onEdit(true) }
                        HourlyCard(f, now, mode, units, onMode)
                        DailyCard(f, today, mode, units) { onOpenDay(f, it) }
                        de.schee.wetter.ui.tiles.TilesSection(tileData, tileState, editTiles, onEdit = onEdit, onOpenMap = onOpenRadar)
                        Text(
                            "Wetterdaten: Open-Meteo.com · ICON-D2 (Deutscher Wetterdienst) und ECMWF\nWarnungen, Radar und Karten: Deutscher Wetterdienst",
                            style = WType.label.copy(color = WColors.Label), textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
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
    }
}

/** Ortssuche für die Ortsliste. */
@Composable
fun PlaceSearchField(onPick: (Place) -> Unit) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    // Suche beim Tippen (kurz verzögert), nicht erst mit der Taste
    LaunchedEffect(q) {
        if (q.length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(400); searching = true
        results = runCatching { PlaceSearch.search(q) }.getOrDefault(emptyList()); searching = false
        AppLog.tap("Ortsliste → Suche „$q“ (${results.size} Treffer)")
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true,
            placeholder = { Text("Nach Stadt oder Ort suchen", style = WType.bodyDim) },
            textStyle = WType.body, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { scope.launch { } }),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = WColors.Accent, unfocusedBorderColor = WColors.Glass, cursorColor = WColors.Accent,
                focusedContainerColor = Color.White.copy(alpha = .06f), unfocusedContainerColor = Color.White.copy(alpha = .06f)),
        )
        if (searching) CircularProgressIndicator(color = WColors.Text, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        results.forEach { p ->
            Text(p.name, style = WType.body, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .clickable { q = ""; results = emptyList(); onPick(p) }.padding(vertical = 10.dp, horizontal = 4.dp))
        }
        if (!searching && results.isEmpty() && q.length >= 2) Text("Kein Ort gefunden.", style = WType.bodyDim)
    }
}

/** Untere Leiste: Karte · Seitenpunkte (➤ für „Mein Standort“, aktuelle Seite hell) · Einstellungen. */
@Composable
private fun BottomBar(modifier: Modifier, pageCount: Int, current: Int, gpsFirst: Boolean, onMap: () -> Unit, onPlaces: () -> Unit, onList: () -> Unit) {
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
            val n = pageCount.coerceAtLeast(1)
            for (i in 0 until n.coerceAtMost(12)) {
                val color = if (i == current) WColors.Text else WColors.Label
                if (i == 0 && gpsFirst) Text("➤", style = WType.label.copy(color = color))
                else Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            }
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

/** Hinweis ohne Netz: „Offline · Stand 14:05“ – die gezeigten Daten sind der letzte gespeicherte Stand. */
@Composable
private fun OfflinePill(fetchedAt: Long, zone: ZoneId) {
    val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(fetchedAt), zone)
    val day = if (t.toLocalDate() == LocalDateTime.now(zone).toLocalDate()) "" else "${t.dayOfMonth}.${t.monthValue}. "
    Row(
        Modifier.padding(top = 10.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .35f)).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(WColors.Warn))
        Spacer(Modifier.width(7.dp))
        Text("Offline · Stand $day${"%02d:%02d".format(t.hour, t.minute)}", style = WType.small)
    }
}
