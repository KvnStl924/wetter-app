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
        if (_ui.value.frames.isNotEmpty()) _ui.value = RadarUi(wind = _ui.value.wind) // älter als 5 Minuten → neu laden
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
            val frames = RadarData.frames(latest)
            val nowIndex = frames.indexOfFirst { it.minutes == 0 }
            _ui.value = RadarUi(frames, mapOf(nowIndex to nowBytes), nowIndex, wind = _ui.value.wind)
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
            val r = withContext(Dispatchers.IO) { runCatching { WindGrid.load() } }
            _ui.value = r.fold(
                { windAt = System.currentTimeMillis(); _ui.value.copy(wind = it, windError = null) },
                { _ui.value.copy(windError = "Winddaten gerade nicht erreichbar.") },
            )
            if (_ui.value.frames.isNotEmpty()) cache = _ui.value
        }
    }

    /** Beim Verlassen des Bildschirms oder der App: Laden abbrechen (keine Arbeit im Hintergrund). */
    fun stop() {
        if (job?.isActive == true) { job?.cancel(); _ui.value = RadarUi(wind = _ui.value.wind) }
        windJob?.cancel()
    }

    /** Für die Testbilder am PC. */
    fun preview(ui: RadarUi) { _ui.value = ui }
}
