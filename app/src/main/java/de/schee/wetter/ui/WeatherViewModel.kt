package de.schee.wetter.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.schee.wetter.data.ClimateRepository
import de.schee.wetter.data.Forecast
import de.schee.wetter.data.LocationSource
import de.schee.wetter.data.Place
import de.schee.wetter.data.PlaceStore
import de.schee.wetter.data.WarningsRepository
import de.schee.wetter.data.Warning
import de.schee.wetter.data.WeatherRepository
import de.schee.wetter.log.AppLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId


data class WeatherUi(
    val place: Place? = null,
    val forecast: Forecast? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val needsPermission: Boolean = false,
    val warnings: List<Warning> = emptyList(),
    val warningsLoaded: Boolean = false,
    val normalMax: Double? = null,
    val maps: de.schee.wetter.data.MapImages.Layers? = null,
)

/**
 * Wetter für mehrere Orte (Phase 5): Seite 0 = „Mein Standort“ (wenn erlaubt), danach die gespeicherten Orte.
 * Geladen wird nur die gerade sichtbare Seite – und nur, wenn die App sichtbar ist (Aufruf aus repeatOnLifecycle).
 * Alte Daten bleiben sichtbar, bis neue da sind; ohne Netz wird die gespeicherte Vorhersage gezeigt.
 */
class WeatherViewModel(app: Application) : AndroidViewModel(app) {
    companion object { const val GPS = "gps" }

    private val repo = WeatherRepository(app)
    private val climate = ClimateRepository(app)
    private val store = PlaceStore(app)
    private val jobs = mutableMapOf<String, Job>()

    private val _saved = MutableStateFlow(store.load())
    val saved: StateFlow<List<Place>> = _saved
    private val _gpsAllowed = MutableStateFlow(LocationSource.hasPermission(app))
    val gpsAllowed: StateFlow<Boolean> = _gpsAllowed
    private val _states = MutableStateFlow<Map<String, WeatherUi>>(emptyMap())
    val states: StateFlow<Map<String, WeatherUi>> = _states
    private val _selected = MutableStateFlow(0)
    val selected: StateFlow<Int> = _selected

    /**
     * Seite, auf die die Hauptansicht blättern soll (Ortsliste, neuer Ort, Berechtigung); null = keine.
     * Bis 3.1.2 blätterte die Ansicht bei JEDER Änderung von [selected], und Wischen änderte [selected] – beim Start
     * mit einer anderen Seite als gemerkt schaukelten sich beide auf (Kevin am 11. und 17.11.2026: Hunderte Wechsel
     * zwischen „Mein Standort“ und Köln). Jetzt blättert die Ansicht nur noch auf ausdrücklichen Wunsch.
     */
    private val _jump = MutableStateFlow<Int?>(null)
    val jump: StateFlow<Int?> = _jump

    /** Seiten in Wisch-Reihenfolge (Kennungen). */
    val pages: StateFlow<List<String>> = combine(_gpsAllowed, _saved) { gps, saved ->
        (if (gps) listOf(GPS) else emptyList()) + saved.map { PlaceStore.id(it) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, (if (_gpsAllowed.value) listOf(GPS) else emptyList()) + _saved.value.map { PlaceStore.id(it) })

    /** Zustand der gerade gezeigten Seite (für Radar, Kacheln usw.). */
    val ui: StateFlow<WeatherUi> = combine(_states, pages, _selected) { s, p, i -> uiOf(s, p, i) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, WeatherUi())

    /** Seiten-Kennungen sofort (ohne auf den Fluss zu warten). */
    private fun ids(): List<String> = (if (_gpsAllowed.value) listOf(GPS) else emptyList()) + _saved.value.map { PlaceStore.id(it) }

    private fun uiOf(s: Map<String, WeatherUi>, p: List<String>, i: Int): WeatherUi {
        val id = p.getOrNull(i.coerceIn(0, (p.size - 1).coerceAtLeast(0))) ?: return WeatherUi(needsPermission = true)
        return s[id] ?: WeatherUi(place = placeOf(id), loading = true)
    }

    fun uiFor(id: String): WeatherUi = _states.value[id] ?: WeatherUi(place = placeOf(id))
    private fun placeOf(id: String): Place? = if (id == GPS) null else _saved.value.firstOrNull { PlaceStore.id(it) == id }

    fun select(index: Int) {
        if (index == _selected.value) return
        _selected.value = index
        ids().getOrNull(index)?.let { id -> AppLog.i(AppLog.Cat.ORT, "Seite: ${uiFor(id).place?.name ?: if (id == GPS) "Mein Standort" else id}"); loadIfStale(id) }
        preloadNeighbours(index)
    }

    /** Auf eine Seite springen (z. B. aus der Ortsliste) – die Hauptansicht blättert dorthin. */
    fun jumpTo(index: Int) { select(index); _jump.value = index }

    /** Die Hauptansicht hat geblättert. */
    fun jumpDone() { _jump.value = null }

    /**
     * Nachbarseiten gleich mitladen (erst der gespeicherte Stand, dann frisch): Beim Wischen ist der nächste Ort
     * sofort mit Wetter da, statt erst einen Ladekreis zu zeigen.
     */
    private fun preloadNeighbours(index: Int) {
        val all = ids()
        listOf(index - 1, index + 1).mapNotNull { all.getOrNull(it) }.forEach { loadIfStale(it) }
    }

    /** Beim Sichtbarwerden: aktuelle Seite neu laden, wenn älter als 10 Minuten. */
    fun onVisible() {
        _gpsAllowed.value = LocationSource.hasPermission(getApplication())
        ids().getOrNull(_selected.value)?.let { loadIfStale(it) }
        preloadNeighbours(_selected.value)
    }

    private fun loadIfStale(id: String) {
        val f = _states.value[id]?.forecast
        if (f == null || System.currentTimeMillis() - f.fetchedAt > 10 * 60_000) refresh(id, "Seite sichtbar")
    }

    /** Aktuelle Seite neu laden (Herunterziehen). */
    fun refresh(reason: String) { ids().getOrNull(_selected.value)?.let { refresh(it, reason) } }

    private fun set(id: String, f: (WeatherUi) -> WeatherUi) = _states.update { it + (id to f(it[id] ?: WeatherUi(place = placeOf(id)))) }

    fun refresh(id: String, reason: String) {
        if (jobs[id]?.isActive == true) return
        jobs[id] = viewModelScope.launch {
            val ctx = getApplication<Application>()
            AppLog.i(AppLog.Cat.DATEN, "Aktualisieren ($reason)")
            set(id) { it.copy(loading = true, error = null) }
            if (id != GPS) {
                val place = placeOf(id) ?: return@launch set(id) { it.copy(loading = false) }
                loadPlace(id, place, cachedFirst = _states.value[id]?.forecast == null)
                return@launch
            }
            // „Mein Standort“ (Kevin am 17.11.2026: bei schlechtem Netz bis zu mehrere 10 Sekunden „Suche Standort“):
            // sofort mit dem Standort, den das Handy schon kennt; die genaue Ortung läuft daneben und korrigiert,
            // falls man inzwischen woanders ist.
            val quick = quickGps(ctx)
            if (quick == null) {
                val place = gpsPlace(ctx) ?: return@launch set(id) { it.copy(loading = false, error = "Standort gerade nicht verfügbar.") }
                loadPlace(id, place, cachedFirst = _states.value[id]?.forecast == null)
                return@launch
            }
            val fix = async { gpsPlace(ctx) }
            loadPlace(id, quick, cachedFirst = _states.value[id]?.forecast == null)
            val now = fix.await() ?: return@launch
            val km = km(now.lat, now.lon, quick.lat, quick.lon)
            if (km > 1.5) {
                AppLog.i(AppLog.Cat.ORT, "Genauer Standort liegt ${"%.1f".format(java.util.Locale.GERMANY, km)} km weiter → Wetter für ${now.name} neu laden")
                set(id) { it.copy(loading = true) }
                loadPlace(id, now, cachedFirst = true)
            } else if (now.name != quick.name && !now.name.contains("°")) set(id) { it.copy(place = it.place?.copy(name = now.name) ?: now) }
        }
    }

    private fun km(aLat: Double, aLon: Double, bLat: Double, bLon: Double) =
        kotlin.math.hypot(aLat - bLat, (aLon - bLon) * kotlin.math.cos(Math.toRadians(aLat))) * 111.2

    /**
     * Sofort-Standort: der zuletzt bekannte des Handys (ohne Suche, Millisekunden), sonst der zuletzt in der App
     * gespeicherte. Liegt er nah am gespeicherten Ort, wird dessen Name genommen (ohne Netz-Nachschlagen).
     */
    private suspend fun quickGps(ctx: Application): Place? {
        val last = _states.value[GPS]?.place ?: store.lastGps()
        val loc = LocationSource.lastKnown(ctx)
        if (loc == null) {
            if (last != null) AppLog.i(AppLog.Cat.ORT, "Sofort-Standort: zuletzt gespeicherter (${last.name})")
            return last
        }
        val near = last != null && km(loc.latitude, loc.longitude, last.lat, last.lon) < 5
        val name = if (near) last!!.name else LocationSource.placeName(ctx, loc.latitude, loc.longitude)
        val age = (System.currentTimeMillis() - loc.time) / 60_000
        AppLog.i(AppLog.Cat.ORT, "Sofort-Standort vom Handy: $name ($age min alt, Genauigkeit ${loc.accuracy.toInt()} m)")
        return Place(name, loc.latitude, loc.longitude, fromGps = true)
    }

    /** Wetter für einen Ort laden: erst gespeicherte Vorhersage zeigen ([cachedFirst]), dann frisch aus dem Netz. */
    private suspend fun loadPlace(id: String, place: Place, cachedFirst: Boolean) {
            if (cachedFirst) repo.cached(place.lat, place.lon)?.let { cached ->
                set(id) { it.copy(place = place, forecast = cached) }
                AppLog.i(AppLog.Cat.DATEN, "Gespeicherte Vorhersage angezeigt (${place.name}, Stand ${java.time.Instant.ofEpochMilli(cached.fetchedAt)})")
            }
            set(id) { it.copy(place = place, needsPermission = false) }
            runCatching { repo.load(place.lat, place.lon) }
                .onSuccess { f -> set(id) { it.copy(forecast = f, loading = false, error = null) } }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    set(id) { it.copy(loading = false, error = if (it.forecast != null) "Keine Verbindung – gezeigt wird der letzte Stand." else "Wetterdaten konnten nicht geladen werden.") }
                    AppLog.error("Vorhersage nicht geladen (${place.name})", e)
                }
            // Ergänzende Daten, unabhängig voneinander
            viewModelScope.launch {
                runCatching { WarningsRepository.load(place.lat, place.lon) }
                    .onSuccess { w -> set(id) { it.copy(warnings = w, warningsLoaded = true) }; if (w.isNotEmpty()) AppLog.i(AppLog.Cat.DATEN, "DWD-Warnungen: ${w.joinToString { it.event }}") }
            }
            viewModelScope.launch {
                runCatching { de.schee.wetter.data.MapImages.load(place.lat, place.lon) }.onSuccess { m -> set(id) { it.copy(maps = m) } }
            }
            viewModelScope.launch {
                val today = LocalDate.now(ZoneId.of(_states.value[id]?.forecast?.timezone ?: "Europe/Berlin"))
                runCatching { climate.normalMax(place.lat, place.lon, today) }.onSuccess { n -> set(id) { it.copy(normalMax = n) } }
            }
    }

    /**
     * „Mein Standort“: aktuelle Position; ohne Signal die zuletzt bekannte. Ohne Netz kann Android den
     * Ortsnamen nicht nachschlagen – liegt man nah am zuletzt bekannten Ort (< 5 km), bleibt dessen Name.
     */
    private suspend fun gpsPlace(ctx: Application): Place? {
        val last = _states.value[GPS]?.place ?: store.lastGps()
        val now = LocationSource.current(ctx) ?: return last
        val coordName = now.name.contains("°")
        val near = last != null && kotlin.math.hypot(now.lat - last.lat, (now.lon - last.lon) * kotlin.math.cos(Math.toRadians(now.lat))) < 0.045
        val p = if (coordName && near) now.copy(name = last!!.name) else now
        if (!p.name.contains("°")) store.saveLastGps(p)
        return p
    }

    /** Für die Ortsliste: kurze Vorhersage aller Orte laden, die noch keine haben (nur bei offener Liste). */
    fun loadAllForList() {
        ids().forEach { id -> if (_states.value[id]?.forecast == null) refresh(id, "Ortsliste") }
    }

    fun onPermissionResult(granted: Boolean) {
        AppLog.i(AppLog.Cat.ORT, if (granted) "Standort-Berechtigung erteilt" else "Standort-Berechtigung abgelehnt")
        _gpsAllowed.value = granted
        if (granted) { _selected.value = 0; _jump.value = 0; refresh(GPS, "Berechtigung erteilt") }
    }

    // ---------------------------------------------------------------- Ortsliste bearbeiten

    /** Ort hinzufügen (oder vorhandenen wählen) und dorthin springen. */
    fun addPlace(p: Place): Int {
        val id = PlaceStore.id(p)
        if (_saved.value.none { PlaceStore.id(it) == id }) {
            _saved.value = _saved.value + p.copy(fromGps = false)
            store.save(_saved.value)
            AppLog.i(AppLog.Cat.ORT, "Ort hinzugefügt: ${p.name}")
        }
        val index = ids().indexOf(id).coerceAtLeast(0)
        _selected.value = index
        _jump.value = index
        refresh(id, "Ort hinzugefügt")
        return index
    }

    fun removePlace(p: Place) {
        val id = PlaceStore.id(p)
        _saved.value = _saved.value.filterNot { PlaceStore.id(it) == id }
        store.save(_saved.value)
        _states.update { it - id }
        _selected.value = _selected.value.coerceIn(0, (ids().size - 1).coerceAtLeast(0))
        _jump.value = _selected.value
        AppLog.i(AppLog.Cat.ORT, "Ort gelöscht: ${p.name}")
    }

    /** Nur für die Testbilder am PC: Orte und Daten setzen, ohne zu speichern oder zu laden. */
    fun preview(places: List<Place>, data: Map<String, WeatherUi>, gps: Boolean = true) {
        _gpsAllowed.value = gps; _saved.value = places; _states.value = data
    }

    fun movePlace(from: Int, to: Int) {
        if (from == to || from !in _saved.value.indices) return
        val m = _saved.value.toMutableList()
        val item = m.removeAt(from); m.add(to.coerceIn(0, m.size), item)
        _saved.value = m; store.save(m)
    }
}
