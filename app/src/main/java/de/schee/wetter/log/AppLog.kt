package de.schee.wetter.log

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Schreibt alles mit, was in der App passiert – nur solange sie geöffnet ist.
 *
 * Ablauf: [startSession] beim Öffnen → Zeilen per [i]/[tap]/[data]/[error] →
 * [endSession] beim Verlassen. Danach lädt [DriveSync] die Datei hoch und löscht sie.
 * Ein Schreib-Thread, damit die Oberfläche nie wartet.
 */
object AppLog {
    enum class Cat(val label: String) {
        APP("APP"), TIPP("TIPP"), WISCH("WISCH"), ORT("ORT"), DATEN("DATEN"),
        DRIVE("DRIVE"), UPDATE("UPDATE"), FEHLER("FEHLER"), INFO("INFO")
    }

    /** Höchstens so viel Log-Speicher auf dem Handy, ältere Dateien werden gelöscht. */
    const val MAX_LOCAL_BYTES = 20L * 1024 * 1024

    private val writerThread = Executors.newSingleThreadExecutor { r -> Thread(r, "AppLog").apply { isDaemon = true } }
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.GERMANY)
    private val fileFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.GERMANY)

    private lateinit var appContext: Context
    @Volatile private var current: File? = null
    @Volatile private var writer: FileWriter? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        logDir().mkdirs(); crashDir().mkdirs(); updateDir().mkdirs()
    }

    fun logDir(): File = File(appContext.filesDir, "logs/sessions")
    fun crashDir(): File = File(appContext.filesDir, "logs/crashes")
    fun updateDir(): File = File(appContext.filesDir, "logs/updates")

    val isRunning: Boolean get() = writer != null

    fun startSession(versionName: String) {
        writerThread.execute {
            if (writer != null) return@execute
            val f = File(logDir(), "log_${fileFmt.format(Date())}.txt")
            current = f
            writer = FileWriter(f, true)
            writeLine(Cat.APP, "App geöffnet (Version $versionName, Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL})")
        }
    }

    /** Beendet die Datei. Rückgabe über [onClosed] im Schreib-Thread, sobald sie fertig ist. */
    fun endSession(reason: String, onClosed: () -> Unit = {}) {
        writerThread.execute {
            if (writer != null) {
                writeLine(Cat.APP, reason)
                runCatching { writer?.close() }
                writer = null
                current = null
            }
            enforceLimit()
            onClosed()
        }
    }

    fun i(cat: Cat, msg: String) = writerThread.execute { writeLine(cat, msg) }
    fun tap(msg: String) = i(Cat.TIPP, msg)
    fun data(msg: String) = i(Cat.DATEN, msg)
    fun error(msg: String, t: Throwable? = null) = i(Cat.FEHLER, if (t == null) msg else "$msg: ${t.javaClass.simpleName}: ${t.message}")

    /** Update-Schritte gehen ins Session-Log und zusätzlich in eine eigene Datei fürs Update-Protokoll. */
    fun update(msg: String) {
        i(Cat.UPDATE, msg)
        writerThread.execute {
            val name = current?.name?.replace("log_", "update_") ?: "update_${fileFmt.format(Date())}.txt"
            runCatching {
                File(updateDir(), name).appendText(LogFormat.line(System.currentTimeMillis(), Cat.UPDATE.label, msg, timeFmt) + "\n")
            }
        }
    }

    /** Wird aus dem Absturz-Handler aufgerufen: synchron schreiben, der Prozess endet gleich. */
    fun writeCrash(thread: Thread, t: Throwable, versionName: String) {
        runCatching {
            val sw = StringWriter(); t.printStackTrace(PrintWriter(sw))
            val f = File(crashDir(), "absturz_${fileFmt.format(Date())}.txt")
            f.writeText(
                "Absturz in Version $versionName\nThread: ${thread.name}\n" +
                    "Gerät: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n\n$sw"
            )
            writer?.let {
                it.write(LogFormat.line(System.currentTimeMillis(), Cat.FEHLER.label, "ABSTURZ: ${t.javaClass.simpleName}: ${t.message}", timeFmt) + "\n")
                it.flush()
            }
        }
    }

    private fun writeLine(cat: Cat, msg: String) {
        val w = writer ?: return
        runCatching {
            w.write(LogFormat.line(System.currentTimeMillis(), cat.label, msg, timeFmt) + "\n")
            w.flush()
        }
    }

    /** Fertige Dateien, die auf den Upload warten (ohne die gerade offene). */
    fun pendingFiles(): List<Pair<File, String>> {
        val open = current
        val out = mutableListOf<Pair<File, String>>()
        val openUpdate = open?.name?.replace("log_", "update_")
        logDir().listFiles()?.filter { it != open }?.forEach { out += it to DriveSync.LOGS }
        crashDir().listFiles()?.forEach { out += it to DriveSync.CRASHES }
        updateDir().listFiles()?.filter { it.name != openUpdate }?.forEach { out += it to DriveSync.UPDATES }
        return out.sortedBy { it.first.lastModified() }
    }

    private fun enforceLimit() {
        val files = listOf(logDir(), crashDir(), updateDir())
            .flatMap { it.listFiles()?.toList().orEmpty() }
            .filter { it != current }
        LogFormat.filesToDelete(files.map { LogFormat.FileInfo(it.path, it.length(), it.lastModified()) }, MAX_LOCAL_BYTES)
            .forEach { File(it).delete() }
    }
}
