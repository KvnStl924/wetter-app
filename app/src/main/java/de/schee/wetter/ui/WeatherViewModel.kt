package de.schee.wetter.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.schee.wetter.data.ClimateRepository
import de.schee.wetter.data.Forecast
import de.schee.wetter.data.LocationSource
import de.schee.wetter.data.Place
import de.schee.wetter.data.WarningsRepository
import de.schee.wetter.data.Warning
import de.schee.wetter.data.WeatherRepository
import de.schee.wetter.log.AppLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
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
 * Lädt Standort und Wetter – nur wenn die App sichtbar ist (Aufruf aus repeatOnLifecycle).
 * Alte Daten bleiben sichtbar, bis neue da sind; ohne Netz wird die gespeicherte Vorhersage gezeigt.
 */
class WeatherViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = WeatherRepository(app)
    private val climate = ClimateRepository(app)
    private val prefs = app.getSharedPreferences("places", Context.MODE_PRIVATE)
    private val _ui = MutableStateFlow(WeatherUi())
    val ui: StateFlow<WeatherUi> = _ui
    private var job: Job? = null

    /** Beim Sichtbarwerden: neu laden, wenn älter als 10 Minuten. */
    fun onVisible() {
        val f = _ui.value.forecast
        if (f == null || System.currentTimeMillis() - f.fetchedAt > 10 * 60_000) refresh("App sichtbar")
    }

    fun refresh(reason: String) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            val ctx = getApplication<Application>()
            AppLog.i(AppLog.Cat.DATEN, "Aktualisieren ($reason)")
            _ui.update { it.copy(loading = true, error = null) }
            val place = when {
                LocationSource.hasPermission(ctx) -> LocationSource.current(ctx) ?: manualPlace() ?: _ui.value.place
                else -> manualPlace()
            }
            if (place == null) {
                _ui.update { it.copy(loading = false, needsPermission = true) }
                AppLog.i(AppLog.Cat.ORT, "Kein Standort: Berechtigung fehlt und kein Ort gewählt")
                return@launch
            }
            if (_ui.value.forecast == null) repo.cached(place.lat, place.lon)?.let { cached ->
                _ui.update { it.copy(place = place, forecast = cached) }
                AppLog.i(AppLog.Cat.DATEN, "Gespeicherte Vorhersage angezeigt (Stand ${java.time.Instant.ofEpochMilli(cached.fetchedAt)})")
            }
            _ui.update { it.copy(place = place, needsPermission = false) }
            runCatching { repo.load(place.lat, place.lon) }
                .onSuccess { f -> _ui.update { it.copy(forecast = f, loading = false, error = null) } }
                .onFailure { e ->
                    _ui.update { it.copy(loading = false, error = if (it.forecast != null) "Keine Verbindung – gezeigt wird der letzte Stand." else "Wetterdaten konnten nicht geladen werden.") }
                    AppLog.error("Vorhersage nicht geladen", e)
                }
            // Ergänzende Daten, unabhängig voneinander
            launch {
                runCatching { WarningsRepository.load(place.lat, place.lon) }
                    .onSuccess { w -> _ui.update { it.copy(warnings = w, warningsLoaded = true) }; if (w.isNotEmpty()) AppLog.i(AppLog.Cat.DATEN, "DWD-Warnungen: ${w.joinToString { it.event }}") }
            }
            launch {
                runCatching { de.schee.wetter.data.MapImages.load(place.lat, place.lon) }.onSuccess { m -> _ui.update { it.copy(maps = m) } }
            }
            launch {
                val today = LocalDate.now(ZoneId.of(_ui.value.forecast?.timezone ?: "Europe/Berlin"))
                runCatching { climate.normalMax(place.lat, place.lon, today) }.onSuccess { n -> _ui.update { it.copy(normalMax = n) } }
            }
        }
    }

    fun onPermissionResult(granted: Boolean) {
        AppLog.i(AppLog.Cat.ORT, if (granted) "Standort-Berechtigung erteilt" else "Standort-Berechtigung abgelehnt")
        if (granted) refresh("Berechtigung erteilt") else _ui.update { it.copy(needsPermission = manualPlace() == null) }
    }

    fun choosePlace(p: Place) {
        prefs.edit().putString("manual", JSONObject().put("name", p.name).put("lat", p.lat).put("lon", p.lon).toString()).apply()
        AppLog.i(AppLog.Cat.ORT, "Ort gewählt: ${p.name}")
        _ui.update { it.copy(place = p, forecast = null, warnings = emptyList(), normalMax = null, maps = null) }
        job?.cancel(); refresh("Ort gewählt")
    }

    private fun manualPlace(): Place? = prefs.getString("manual", null)?.let {
        runCatching { JSONObject(it).let { o -> Place(o.getString("name").substringBefore(" · "), o.getDouble("lat"), o.getDouble("lon"), false) } }.getOrNull()
    }
}
