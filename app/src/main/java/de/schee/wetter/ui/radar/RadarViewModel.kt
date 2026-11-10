package de.schee.wetter.ui.radar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.schee.wetter.data.DwdWorld
import de.schee.wetter.data.RadarData
import de.schee.wetter.data.Satellite
import de.schee.wetter.data.WorldGrid
import de.schee.wetter.data.WorldWeather
import de.schee.wetter.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.Instant


/** Zustand des Radars. Geladen wird nur bei geöffneter App (kein Hintergrund). */
data class RadarUi(
    val frames: List<RadarData.Frame> = emptyList(),
    /** Index → PNG-Bytes; fehlt ein Eintrag, ist das Bild noch nicht da; null = keine Daten. */
    val bytes: Map<Int, ByteArray?> = emptyMap(),
    val nowIndex: Int = 0,
    val error: String? = null,
    /** Regen-Vorhersage weltweit (DWD ICON): Menge der Stunde in mm, Schlüssel = Ende der Stunde. */
    val worldRain: Map<Instant, WorldGrid> = emptyMap(),
    val worldRainLoading: Boolean = false,
    /** Wo das DWD-Radar misst (aus dem jüngsten Radarbild); null = unbekannt. */
    val coverage: RadarData.Coverage? = null,
    /** Weltweites Radar (RainViewer), leer wenn nicht erreichbar. */
    val rainViewer: List<de.schee.wetter.data.RainViewer.Frame> = emptyList(),
    /** Temperatur und Wind weltweit (DWD ICON), je volle Stunde. */
    val world: Map<Instant, WorldWeather> = emptyMap(),
    /** Erste Stunde der Zeitleiste (jetzt). */
    val worldStart: Instant? = null,
    val worldError: String? = null,
    /** Wolken weltweit (Satellit). */
    val clouds: Satellite.Clouds? = null,
    val cloudsError: String? = null,
    /** Scharfer Wolken-Ausschnitt beim Hineinzoomen (null = nur Weltbild). */
    val cloudDetail: Satellite.CloudDetail? = null,
    /** Blitze der letzten Stunde (Satellit), neueste zuletzt. */
    val strikes: List<Satellite.Strike> = emptyList(),
    val strikesNewest: Instant? = null,
    /** Laden der Blitze: (fertig, gesamt), null = fertig. */
    val strikesProgress: Pair<Int, Int>? = null,
) {
    val loaded get() = bytes.size
    /** Stunden der Zeitleiste für Temperatur/Wind: jetzt … +12 h. */
    val worldHours: List<Instant> get() = worldStart?.let { s -> (0..12).map { s.plusSeconds(it * 3600L) } } ?: emptyList()
    val worldNow: WorldWeather? get() = worldStart?.let { world[it] }
}

class RadarViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(cache ?: RadarUi())
    val ui: StateFlow<RadarUi> = _ui
    private var job: Job? = null
    private var worldJob: Job? = null
    private var hoursJob: Job? = null
    private var rainJob: Job? = null
    private var cloudJob: Job? = null
    private var lightningJob: Job? = null

    companion object {
        // Solange die App lebt: Zwischenspeicher im Speicher (zusätzlich liegen Weltdaten als Datei im Cache).
        private var cache: RadarUi? = null
        private var radarAt = 0L
        private var cloudsAt = 0L
        private var strikesAt = 0L
        /** Blitze je 5-Minuten-Abschnitt, wie geladen (für das Nachladen nur neuer Abschnitte). */
        private val strikeFrames = HashMap<Instant, List<Satellite.Strike>>()
    }

    private fun set(f: (RadarUi) -> RadarUi) { _ui.update(f); cache = _ui.value }

    // ---------------------------------------------------------------- Regen (DWD + RainViewer)

    fun load() {
        if (job?.isActive == true) return
        if (_ui.value.frames.isNotEmpty() && System.currentTimeMillis() - radarAt < 5 * 60_000) return
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
                set { it.copy(error = "Das Radar des Deutschen Wetterdienstes ist gerade nicht erreichbar.") }
                return@launch
            }
            // Messgebiet des DWD-Radars – vor den RainViewer-Kacheln, die damit für die Vorhersage geleert werden
            val cov = runCatching { RadarData.coverageOf(nowBytes) }.getOrNull()
            RadarData.coverage = cov
            val rv = runCatching { de.schee.wetter.data.RainViewer.load() }.getOrDefault(emptyList())
            val frames = RadarData.frames(latest)
            val nowIndex = frames.indexOfFirst { it.minutes == 0 }
            set { it.copy(frames = frames, bytes = mapOf(nowIndex to nowBytes), nowIndex = nowIndex, error = null, rainViewer = rv, coverage = cov) }
            // Reihenfolge: von „Jetzt“ aus abwechselnd vor und zurück, damit das Abspielen schnell beginnen kann.
            val order = frames.indices.filter { it != nowIndex }.sortedBy { kotlin.math.abs(it - nowIndex) + if (it < nowIndex) 0.5 else 0.0 }
            val gate = Semaphore(4)
            val missing = java.util.concurrent.atomic.AtomicInteger()
            order.map { i ->
                async {
                    gate.withPermit {
                        val b = runCatching { RadarData.fetch(frames[i]) }.getOrNull()
                        if (b == null) missing.incrementAndGet()
                        set { it.copy(bytes = it.bytes + (i to b)) }
                    }
                }
            }.awaitAll()
            val kb = _ui.value.bytes.values.sumOf { it?.size ?: 0 } / 1024
            AppLog.data("Radar: ${frames.size - missing.get()}/${frames.size} Bilder geladen (${System.currentTimeMillis() - started} ms, $kb kB, jüngste Messung $latest)")
            radarAt = System.currentTimeMillis()
        }
    }

    // ---------------------------------------------------------------- Temperatur + Wind weltweit (DWD)

    private suspend fun loadHour(time: Instant): WorldWeather? = withContext(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        // neuester Modelllauf (bis 3.0.3 kam ohne Angabe der älteste, bis zu 1,5 Tage alt)
        val t = async { runCatching { DwdWorld.loadLatest(ctx, DwdWorld.Field.TEMP, time) }.getOrNull() }
        val w = async { runCatching { DwdWorld.loadLatest(ctx, DwdWorld.Field.WIND, time) }.getOrNull() }
        val tg = t.await(); val wg = w.await()
        if (tg == null && wg == null) null else WorldWeather(time, tg, wg)
    }

    /** Aktuelle Stunde laden (beim Öffnen der App). */
    fun loadWorld() {
        if (worldJob?.isActive == true) return
        val start = DwdWorld.hourOf(Instant.now())
        if (_ui.value.worldStart == start && _ui.value.world[start] != null) return
        worldJob = viewModelScope.launch {
            val started = System.currentTimeMillis()
            // neue Stunde: alte Stunden davor verwerfen
            set { it.copy(worldStart = start, world = it.world.filterKeys { k -> !k.isBefore(start) }) }
            var ww = loadHour(start)
            if (ww == null) ww = loadHour(start.minusSeconds(3600)) // DWD noch nicht so weit → eine Stunde früher
            if (ww == null) ww = withContext(Dispatchers.IO) { DwdWorld.newestCached(getApplication()) }?.also { AppLog.data("Weltdaten: kein Netz – gespeicherter Stand ${it.time}") }
            if (ww == null) { set { it.copy(worldError = "Temperatur und Wind gerade nicht erreichbar (DWD).") }; return@launch }
            set { it.copy(world = it.world + (start to ww), worldError = null) }
            AppLog.data("Weltdaten Temperatur/Wind bereit (${System.currentTimeMillis() - started} ms)")
        }
    }

    /** Weitere Stunden für die Zeitleiste (nur bei geöffnetem Radar mit Temperatur/Wind), nacheinander. */
    fun loadWorldHours() {
        if (hoursJob?.isActive == true) return
        hoursJob = viewModelScope.launch {
            worldJob?.join()
            for (t in _ui.value.worldHours.drop(1)) {
                if (_ui.value.world[t] != null) continue
                val ww = loadHour(t) ?: continue
                set { it.copy(world = it.world + (t to ww)) }
            }
        }
    }

    // ---------------------------------------------------------------- Wolken

    fun loadClouds() {
        if (cloudJob?.isActive == true) return
        if (_ui.value.clouds != null && System.currentTimeMillis() - cloudsAt < 15 * 60_000) return
        cloudJob = viewModelScope.launch {
            // Bodentemperatur hilft, kalten Boden nicht für Wolken zu halten – kurz darauf warten
            kotlinx.coroutines.withTimeoutOrNull(15_000) { worldJob?.join() }
            val started = System.currentTimeMillis()
            val r = withContext(Dispatchers.IO) { runCatching { Satellite.loadClouds(getApplication(), _ui.value.worldNow) } }
            r.onSuccess { c -> cloudsAt = System.currentTimeMillis(); set { it.copy(clouds = c, cloudsError = null) }; AppLog.data("Wolken weltweit bereit (${System.currentTimeMillis() - started} ms)") }
            r.onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) set { it.copy(cloudsError = "Satellitenbild gerade nicht erreichbar.") } }
        }
    }

    private var detailJob: Job? = null

    /**
     * Scharfe Wolken für den Ausschnitt: erst ab etwas Zoom und nur im Bereich des MTG-Satelliten. Geladen wird
     * das Doppelte des Ausschnitts, damit beim Verschieben nicht sofort wieder geladen werden muss.
     */
    fun ensureCloudDetail(west: Double, east: Double, south: Double, north: Double) {
        val spanX = east - west; val spanY = north - south
        if (spanX > 40) return
        val cur = _ui.value.cloudDetail
        val fresh = cur != null && System.currentTimeMillis() - cur.loadedAt < 15 * 60_000
        if (fresh && cur!!.covers(west, east, south, north) && (cur.east - cur.west) <= spanX * 3.2) return
        val w = west - spanX * .5; val e = east + spanX * .5
        val s = (south - spanY * .5).coerceAtLeast(-80.0); val n = (north + spanY * .5).coerceAtMost(80.0)
        if (!Satellite.detailCovers(w, e, s, n)) return
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            kotlinx.coroutines.delay(450)
            val started = System.currentTimeMillis()
            val r = withContext(Dispatchers.IO) { runCatching { Satellite.loadCloudDetail(w, e, s, n, _ui.value.worldNow) } }
            r.onSuccess { d -> set { it.copy(cloudDetail = d) }; AppLog.data("Wolken scharf für Ausschnitt (${System.currentTimeMillis() - started} ms)") }
        }
    }

    // ---------------------------------------------------------------- Blitze

    /**
     * Blitze der letzten Stunde (13 Abschnitte à 5 Minuten). Der EUMETSAT-Server braucht je Bild 7–12 s (am
     * 10.11.2026 gemessen) – deshalb (Kevin: „die Blitze laden sehr langsam“):
     *  - jedes Bild wird sofort gezeigt, sobald es da ist (neueste zuerst), statt auf alle 13 zu warten
     *  - fertige Abschnitte liegen auf dem Handy und werden beim nächsten Öffnen nicht neu geladen
     *  - 6 Bilder gleichzeitig
     *  - leere Abschnitte ganz vorn (Daten noch nicht angekommen) werden beim nächsten Mal erneut versucht
     *    (bis 2.0.0 blieben sie fälschlich als „leer“ gemerkt → die neuesten Blitze fehlten)
     */
    fun loadLightning() {
        if (lightningJob?.isActive == true) return
        if (System.currentTimeMillis() - strikesAt < 4 * 60_000) return
        lightningJob = viewModelScope.launch(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val newest = Satellite.lightningTime(Instant.now())
            val times = (0 until 13).map { newest.minusSeconds(it * 300L) }
            val dir = java.io.File(getApplication<Application>().cacheDir, "lightning").apply { mkdirs() }
            synchronized(strikeFrames) {
                // Gespeichertes vom Handy dazunehmen, Altes wegräumen
                for (t in times) if (t !in strikeFrames) StrikeCache.read(dir, t)?.let { strikeFrames[t] = it }
                strikeFrames.keys.removeAll { it.isBefore(newest.minusSeconds(3600)) }
                // vorderste Abschnitte ohne Blitze: vielleicht nur noch nicht angekommen → neu laden
                times.take(3).forEach { t -> if (strikeFrames[t]?.isEmpty() == true) strikeFrames.remove(t) }
            }
            StrikeCache.prune(dir, newest.minusSeconds(3700))
            fun publish(done: Int, total: Int) {
                val all = synchronized(strikeFrames) { strikeFrames.toMap() }
                val list = Satellite.firstSeen(all)
                val latestWithData = all.filterValues { it.isNotEmpty() }.keys.maxOrNull()
                set { it.copy(strikes = list, strikesNewest = latestWithData, strikesProgress = if (done < total) done to total else null) }
            }
            val todo = times.filter { synchronized(strikeFrames) { it !in strikeFrames } }
            publish(0, todo.size)
            val gate = Semaphore(6)
            var done = 0
            todo.map { t ->
                async {
                    gate.withPermit {
                        val r = runCatching { Satellite.strikes(t) }.getOrNull()
                        if (r != null) {
                            synchronized(strikeFrames) { strikeFrames[t] = r }
                            // nur fertige Abschnitte speichern (die vordersten können noch unvollständig sein)
                            if (t.isBefore(newest.minusSeconds(600)) || r.isNotEmpty()) StrikeCache.write(dir, t, r)
                        }
                        synchronized(this@RadarViewModel) { done++ }
                        publish(done, todo.size)
                    }
                }
            }.awaitAll()
            strikesAt = System.currentTimeMillis()
            publish(1, 1)
            AppLog.data("Blitze: ${_ui.value.strikes.size} Punkte, ${todo.size} Abschnitte geladen, ${times.size - todo.size} schon da (${System.currentTimeMillis() - started} ms)")
        }
    }

    // ---------------------------------------------------------------- Modell-Regen außerhalb Deutschlands (Zukunft)

    // ---------------------------------------------------------------- Regen-Vorhersage weltweit (DWD ICON)

    /**
     * Regen der nächsten Stunden außerhalb Deutschlands: Modell des DWD (ICON, weltweit, 0,5°) – wie Temperatur und Wind.
     * Der DWD liefert die seit Beginn des Modelllaufs aufsummierte Menge; die Menge einer Stunde ist die Differenz
     * zweier Stunden desselben Laufs. Bis 3.0.3 kam das von Open-Meteo aus nur 10 × 10 Punkten über dem
     * Kartenausschnitt – weit herausgezoomt ergab das riesige verschwommene Farbflächen (Kevins Video vom 10.11.2026).
     */
    fun loadWorldRain() {
        if (rainJob?.isActive == true) return
        val first = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusSeconds(3600)
        val hours = (0..12).map { first.plusSeconds(it * 3600L) }
        if (hours.all { _ui.value.worldRain[it] != null }) return
        rainJob = viewModelScope.launch {
            val started = System.currentTimeMillis()
            // neue Stunde: vergangene Stunden verwerfen
            set { it.copy(worldRain = it.worldRain.filterKeys { k -> !k.isBefore(first) }, worldRainLoading = true) }
            val ctx = getApplication<Application>()
            var used: Instant? = null
            try {
                withContext(Dispatchers.IO) {
                    // neuester Modelllauf; fehlen dort noch Stunden (der Lauf wird gerade eingespielt), der Lauf davor
                    for (run in DwdWorld.runs(DwdWorld.Field.RAIN).filter { it.isBefore(first.minusSeconds(3600)) }.take(2)) {
                        if (loadRainRun(ctx, run, hours) && used == null) used = run
                        if (hours.all { _ui.value.worldRain[it] != null }) break
                    }
                }
            } finally {
                set { it.copy(worldRainLoading = false) }
            }
            val n = hours.count { _ui.value.worldRain[it] != null }
            AppLog.data("Regen-Vorhersage weltweit (DWD ICON${used?.let { ", Lauf ${it.toString().substring(11, 16)} UTC" } ?: ""}): $n/${hours.size} Stunden bereit (${System.currentTimeMillis() - started} ms)")
        }
    }

    /** Lädt die noch fehlenden Stunden aus einem Modelllauf (3 Abrufe gleichzeitig); false, wenn der Lauf nichts liefert. */
    private suspend fun loadRainRun(ctx: Application, run: Instant, hours: List<Instant>): Boolean = kotlinx.coroutines.coroutineScope {
        val missing = hours.filter { _ui.value.worldRain[it] == null }
        // je Stunde braucht es die Summe an ihrem Ende und die eine Stunde davor
        val need = (missing + missing.map { it.minusSeconds(3600) }).distinct().sorted()
        if (need.isEmpty()) return@coroutineScope true
        val acc = java.util.concurrent.ConcurrentHashMap<Instant, WorldGrid>()
        // der DWD-Server antwortet gelegentlich mit einem Fehler – dann ein zweiter Versuch
        fun fetch(t: Instant): WorldGrid? {
            repeat(2) { runCatching { return DwdWorld.load(ctx, DwdWorld.Field.RAIN, t, run = run) } }
            return null
        }
        // erst eine Stunde allein: kommt nichts, ist der Lauf (noch) nicht da
        acc[need.first()] = fetch(need.first()) ?: return@coroutineScope false
        val gate = Semaphore(3)
        need.drop(1).map { t ->
            async {
                gate.withPermit {
                    val g = fetch(t)
                    if (g != null) {
                        acc[t] = g
                        for (h in listOf(t, t.plusSeconds(3600))) {
                            val a = acc[h]; val b = acc[h.minusSeconds(3600)]
                            if (a != null && b != null && h in missing) set { if (it.worldRain[h] != null) it else it.copy(worldRain = it.worldRain + (h to a.minus(b))) }
                        }
                    }
                }
            }
        }.awaitAll()
        true
    }


    /** Alles vorladen (beim Öffnen der App). */
    fun preload() {
        load(); loadWorld(); loadClouds(); loadLightning()
        if (de.schee.wetter.data.Places.index.value == null) viewModelScope.launch(Dispatchers.IO) { runCatching { de.schee.wetter.data.Places.load(getApplication()) } }
    }

    /** Beim Verlassen der App: Laden abbrechen (keine Arbeit im Hintergrund). */
    fun stop() {
        if (job?.isActive == true) { job?.cancel(); set { it.copy(frames = emptyList(), bytes = emptyMap()) } }
        worldJob?.cancel(); hoursJob?.cancel(); cloudJob?.cancel(); lightningJob?.cancel(); rainJob?.cancel(); detailJob?.cancel()
    }

    /** Für die Testbilder am PC. */
    fun preview(ui: RadarUi) { _ui.value = ui }
}

/** Blitz-Abschnitte auf dem Handy (fertige Abschnitte ändern sich nicht mehr). Eine Zeile je Blitz: lat;lon;stärke. */
internal object StrikeCache {
    private fun file(dir: java.io.File, t: Instant) = java.io.File(dir, "${t.epochSecond}.txt")

    fun read(dir: java.io.File, t: Instant): List<Satellite.Strike>? = runCatching {
        val f = file(dir, t)
        if (!f.exists()) return null
        f.readLines().filter { it.isNotBlank() }.map { l -> val p = l.split(";"); Satellite.Strike(p[0].toDouble(), p[1].toDouble(), t, p[2].toFloat()) }
    }.getOrNull()

    fun write(dir: java.io.File, t: Instant, list: List<Satellite.Strike>) {
        runCatching { file(dir, t).writeText(list.joinToString("\n") { "${it.lat};${it.lon};${it.strength}" }) }
    }

    fun prune(dir: java.io.File, before: Instant) {
        dir.listFiles()?.forEach { f -> f.nameWithoutExtension.toLongOrNull()?.let { if (it < before.epochSecond) f.delete() } }
    }
}
