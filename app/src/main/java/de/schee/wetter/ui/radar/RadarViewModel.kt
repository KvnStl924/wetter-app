package de.schee.wetter.ui.radar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.schee.wetter.data.RadarData
import de.schee.wetter.data.WindGrid
import de.schee.wetter.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.Instant

/** Zustand des Vollbild-Radars. Lädt nur, solange der Bildschirm offen ist (kein Hintergrund). */
data class RadarUi(
    val frames: List<RadarData.Frame> = emptyList(),
    /** Index → PNG-Bytes; fehlt ein Eintrag, ist das Bild noch nicht da; null = keine Daten. */
    val bytes: Map<Int, ByteArray?> = emptyMap(),
    val nowIndex: Int = 0,
    val error: String? = null,
    val wind: WindGrid? = null,
    /** Grobes Gitter über dem sichtbaren Ausschnitt (außerhalb von Deutschland). */
    val windView: WindGrid? = null,
    /** Weltweites Radar (RainViewer), leer wenn nicht erreichbar. */
    val rainViewer: List<de.schee.wetter.data.RainViewer.Frame> = emptyList(),
    val windError: String? = null,
) {
    val loaded get() = bytes.size
}

class RadarViewModel : ViewModel() {
    private val _ui = MutableStateFlow(cache?.takeIf { System.currentTimeMillis() - cacheAt < 5 * 60_000 } ?: RadarUi())
    val ui: StateFlow<RadarUi> = _ui
    private var job: Job? = null
    private var windJob: Job? = null

    companion object {
        // Zwischenspeicher für erneutes Öffnen innerhalb von 5 Minuten (nur im Speicher, solange die App lebt).
        private var cache: RadarUi? = null
        private var cacheAt = 0L
        private var windAt = 0L
    }

    fun load() {
        if (job?.isActive == true) return
        if (_ui.value.frames.isNotEmpty() && System.currentTimeMillis() - cacheAt < 5 * 60_000) return
        if (_ui.value.frames.isNotEmpty()) _ui.value = RadarUi(wind = _ui.value.wind, windView = _ui.value.windView) // älter als 5 Minuten → neu laden
        job = viewModelScope.launch(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            // Jüngste Messung finden: geschätzte Zeit, sonst 5 bzw. 10 Minuten früher.
            var latest = RadarData.latestGuess(Instant.now())
            var nowBytes: ByteArray? = null
            for (i in 0 until 3) {
                nowBytes = runCatching { RadarData.fetch(RadarData.Frame(latest, 0, RadarData.Kind.RADAR)) }.getOrNull()
                if (nowBytes != null) break
                latest = latest.minusSeconds(300)
            }
            if (nowBytes == null) {
                AppLog.data("Radar: aktuelles Bild nicht erreichbar")
                _ui.value = _ui.value.copy(error = "Das Radar des Deutschen Wetterdienstes ist gerade nicht erreichbar.")
                return@launch
            }
            val rv = runCatching { de.schee.wetter.data.RainViewer.load() }.getOrDefault(emptyList())
            val frames = RadarData.frames(latest)
            val nowIndex = frames.indexOfFirst { it.minutes == 0 }
            _ui.value = RadarUi(frames, mapOf(nowIndex to nowBytes), nowIndex, wind = _ui.value.wind, windView = _ui.value.windView, rainViewer = rv)
            // Reihenfolge: von „Jetzt“ aus abwechselnd vor und zurück, damit das Abspielen schnell beginnen kann.
            val order = frames.indices.filter { it != nowIndex }.sortedBy { kotlin.math.abs(it - nowIndex) + if (it < nowIndex) 0.5 else 0.0 }
            val gate = Semaphore(4)
            val missing = java.util.concurrent.atomic.AtomicInteger()
            order.map { i ->
                async {
                    gate.withPermit {
                        val b = runCatching { RadarData.fetch(frames[i]) }.getOrNull()
                        if (b == null) missing.incrementAndGet()
                        synchronized(this@RadarViewModel) { _ui.value = _ui.value.copy(bytes = _ui.value.bytes + (i to b)) }
                    }
                }
            }.awaitAll()
            val kb = _ui.value.bytes.values.sumOf { it?.size ?: 0 } / 1024
            AppLog.data("Radar: ${frames.size - missing.get()}/${frames.size} Bilder geladen (${System.currentTimeMillis() - started} ms, $kb kB, jüngste Messung $latest)")
            cache = _ui.value; cacheAt = System.currentTimeMillis()
        }
    }

    fun loadWind() {
        if (windJob?.isActive == true) return
        if (_ui.value.wind != null && System.currentTimeMillis() - windAt < 30 * 60_000) return
        windJob = viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { WindGrid.load(WindGrid.GERMANY, "Open-Meteo Windgitter Deutschland") } }
            _ui.value = r.fold(
                { windAt = System.currentTimeMillis(); _ui.value.copy(wind = it, windError = null) },
                { _ui.value.copy(windError = "Winddaten gerade nicht erreichbar.") },
            )
            if (_ui.value.frames.isNotEmpty()) cache = _ui.value
        }
    }

    private var viewJob: Job? = null
    private var viewPauseUntil = 0L
    /** Abrufe der letzten Minute (Zeitpunkt, Gitterpunkte). */
    private val budget = ArrayDeque<Pair<Long, Int>>()

    /**
     * Sichtbarer Ausschnitt nach dem Verschieben/Zoomen: Liegt er (teilweise) außerhalb des Deutschland-Gitters,
     * wird ein grobes Gitter für genau diesen Ausschnitt geladen (10 × 10 Punkte, echte Werte).
     */
    fun ensureView(south: Double, north: Double, west: Double, east: Double) {
        val g = WindGrid.GERMANY
        if (south >= g.lat0 && north <= g.lat0 + (g.rows - 1) * g.step && west >= g.lon0 && east <= g.lon0 + (g.cols - 1) * g.step) return
        val span = maxOf(north - south, east - west)
        _ui.value.windView?.let { v -> if (v.covers(south, north, west, east) && v.step <= span / 3) return }
        // Open-Meteo schonen: höchstens ein Abruf alle 5 s (der letzte Wunsch wird nachgeholt),
        // nach „zu viele Abrufe“ (429) eine Minute Pause.
        val wait = (viewPauseUntil - System.currentTimeMillis()).coerceAtLeast(0)
        viewJob?.cancel()
        viewJob = viewModelScope.launch {
            kotlinx.coroutines.delay(wait)
            // Budget: höchstens 300 Gitterpunkte pro Minute für Ausschnitte (Open-Meteo erlaubt 600 –
            // der Rest bleibt für Vorhersage und Deutschland-Gitter). Sonst warten statt abgelehnt zu werden.
            val points = WindGrid.VIEW_POINTS * WindGrid.VIEW_POINTS
            while (true) {
                val now = System.currentTimeMillis()
                budget.removeAll { now - it.first > 60_000 }
                if (budget.sumOf { it.second } + points <= 300) break
                kotlinx.coroutines.delay((60_000 - (now - budget.first().first)).coerceAtLeast(500))
            }
            budget += System.currentTimeMillis() to points
            viewPauseUntil = System.currentTimeMillis() + 5_000
            val spec = WindGrid.viewSpec(south, north, west, east)
            val r = withContext(Dispatchers.IO) { runCatching { WindGrid.load(spec, "Open-Meteo Gitter Ausschnitt (Wind + Regen)") } }
            r.onSuccess { _ui.value = _ui.value.copy(windView = it) }
            r.onFailure { if (it.message?.contains("429") == true) { viewPauseUntil = System.currentTimeMillis() + 60_000; AppLog.data("Open-Meteo: zu viele Abrufe – Ausschnitt-Gitter 1 Minute pausiert") } }
        }
    }

    /** Beim Verlassen des Bildschirms oder der App: Laden abbrechen (keine Arbeit im Hintergrund). */
    fun stop() {
        if (job?.isActive == true) { job?.cancel(); _ui.value = RadarUi(wind = _ui.value.wind) }
        windJob?.cancel(); viewJob?.cancel()
    }

    /** Für die Testbilder am PC. */
    fun preview(ui: RadarUi) { _ui.value = ui }
}
