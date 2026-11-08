package de.schee.wetter.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import de.schee.wetter.BuildConfig
import de.schee.wetter.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Version „X.Y.Z“ als vergleichbare Zahlen. */
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version) = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
    override fun toString() = "$major.$minor.$patch"

    companion object {
        /** Akzeptiert „0.2.0“ und „v0.2.0“. Gibt null zurück, wenn es keine X.Y.Z-Version ist. */
        fun parse(text: String): Version? {
            val m = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""").matchEntire(text.trim()) ?: return null
            return Version(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
    }
}

data class Release(val version: Version, val notes: String, val apkUrl: String, val apkName: String, val apkBytes: Long, val sha256Url: String?)

/**
 * Update nur auf Knopfdruck: [check] fragt das neueste GitHub-Release ab,
 * [download] lädt die APK mit Fortschritt und prüft die Prüfsumme,
 * [install] übergibt sie dem Android-Installer (der fragt einmal „Installieren?“).
 * Kein Zeitplan, kein Hintergrund-Worker.
 */
object Updater {
    private const val API = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"

    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        AppLog.update("Suche nach Updates (installiert: ${BuildConfig.VERSION_NAME})")
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Wetter-App/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = conn.responseCode
            if (code == 404) {
                AppLog.update("Noch kein Release veröffentlicht")
                return@withContext null
            }
            if (code != 200) throw IOException("GitHub antwortet mit $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            AppLog.data("GitHub-Release geladen (${System.currentTimeMillis() - started} ms, ${body.length / 1024} kB)")
            val json = JSONObject(body)
            val version = Version.parse(json.getString("tag_name")) ?: throw IOException("Unbekannte Versionsangabe ${json.getString("tag_name")}")
            val assets = json.getJSONArray("assets")
            var apk: JSONObject? = null
            var sha: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val n = a.getString("name")
                if (n.endsWith(".apk")) apk = a
                if (n.endsWith(".apk.sha256")) sha = a.getString("browser_download_url")
            }
            apk ?: throw IOException("Release ${json.getString("tag_name")} enthält keine APK")
            Release(version, json.optString("body", "").trim(), apk.getString("browser_download_url"), apk.getString("name"), apk.optLong("size", -1), sha)
                .also { AppLog.update("Neueste Version auf GitHub: ${it.version}") }
        } finally {
            conn.disconnect()
        }
    }

    fun isNewer(release: Release): Boolean {
        val installed = Version.parse(BuildConfig.VERSION_NAME) ?: return true
        return release.version > installed
    }

    /** Lädt die APK in den Cache. [progress] bekommt 0f..1f. */
    suspend fun download(context: Context, release: Release, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val target = File(dir, release.apkName)
        val started = System.currentTimeMillis()
        AppLog.update("Download startet: ${release.apkName}")
        val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Wetter-App/${BuildConfig.VERSION_NAME}")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            if (conn.responseCode != 200) throw IOException("Download fehlgeschlagen (${conn.responseCode})")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkBytes
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); digest.update(buf, 0, n)
                        done += n
                        if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        release.sha256Url?.let { url ->
            val expected = URL(url).openStream().bufferedReader().use { it.readText() }.trim().split(Regex("\\s+")).first().lowercase()
            if (expected != actual) {
                target.delete()
                AppLog.update("Prüfsumme falsch (erwartet $expected, erhalten $actual) – Datei verworfen")
                throw IOException("Die heruntergeladene Datei ist beschädigt. Bitte erneut versuchen.")
            }
        }
        AppLog.update("Download fertig (${target.length() / 1024} kB, ${System.currentTimeMillis() - started} ms, Prüfsumme ok)")
        target
    }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}"))

    /** Übergibt die APK dem Android-Installer. Die Bestätigung zeigt [InstallResultReceiver]. */
    fun install(context: Context, apk: File) {
        AppLog.update("Installation an Android übergeben")
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("wetter.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(context, id, intent, flags)
            session.commit(pi.intentSender)
        }
    }
}

/** Empfängt die Antwort des Installers und zeigt bei Bedarf den Bestätigungsdialog. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                AppLog.update("Android fragt nach Bestätigung")
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm != null) context.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> AppLog.update("Installation erfolgreich")
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                AppLog.update("Installation abgebrochen")
                UpdateState.failed("Installation abgebrochen.")
            }
            else -> {
                AppLog.update("Installation fehlgeschlagen (Status $status: $message)")
                UpdateState.failed("Installation fehlgeschlagen: ${message ?: "Status $status"}")
            }
        }
    }
}
