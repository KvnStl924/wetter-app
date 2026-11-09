package de.schee.wetter.log

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException

/**
 * Google Drive über die Android-Dateiauswahl (Storage Access Framework), wie in StreetLab.
 * Der Nutzer wählt einmal den Ordner „Meine Ablage › Apps › Wetter-App“, die App merkt sich
 * die Berechtigung. Kein Google-Login.
 *
 * Ablage:
 *   03 Logs - Fehlerberichte/Logs/
 *   03 Logs - Fehlerberichte/Absturzberichte/
 *   03 Logs - Fehlerberichte/Update-Protokoll/
 *   02 APK - zum Installieren/
 */
object DriveSync {
    const val LOGS_ROOT = "03 Logs - Fehlerberichte"
    const val LOGS = "Logs"
    const val CRASHES = "Absturzberichte"
    const val UPDATES = "Update-Protokoll"
    const val APK_FOLDER = "02 APK - zum Installieren"
    /** Der Ordner, den man in der Auswahl wählen soll: Meine Ablage › Apps › Wetter-App. */
    const val APP_FOLDER = "Wetter-App"

    private const val PREFS = "drive"
    private const val KEY_TREE = "tree_uri"

    data class Entry(val id: String, val name: String, val mime: String, val size: Long)

    fun treeUri(context: Context): Uri? {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, null) ?: return null
        val uri = Uri.parse(value)
        val granted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        return if (granted) uri else null
    }

    fun saveTree(context: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TREE, uri.toString()).apply()
        AppLog.i(AppLog.Cat.DRIVE, "Drive-Ordner gewählt: ${rootName(context.contentResolver, uri)}")
    }

    fun rootName(resolver: ContentResolver, tree: Uri): String = runCatching {
        resolver.query(root(tree), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) ?: "" else ""
        } ?: ""
    }.getOrDefault("")

    /**
     * Lädt alle wartenden Dateien hoch und löscht sie danach lokal.
     * Läuft nur bei geöffneter oder gerade verlassener App, nie periodisch.
     * @return Anzahl hochgeladener Dateien
     */
    @Synchronized
    fun uploadPending(context: Context): Int {
        val pending = AppLog.pendingFiles()
        if (pending.isEmpty()) return 0
        val tree = treeUri(context)
        if (tree == null) {
            AppLog.i(AppLog.Cat.DRIVE, "Kein Drive-Ordner gewählt – ${pending.size} Datei(en) warten")
            return 0
        }
        val resolver = context.contentResolver
        // Falls versehentlich „03 Logs - Fehlerberichte“ selbst gewählt wurde: dort direkt ablegen statt einen zweiten 03-Ordner darin anzulegen.
        val insideLogsRoot = rootName(resolver, tree) == LOGS_ROOT
        var ok = 0
        for ((file, sub) in pending) {
            val started = System.currentTimeMillis()
            try {
                val folder = if (insideLogsRoot) folder(resolver, tree, sub) else folder(resolver, tree, LOGS_ROOT, sub)
                copyFile(resolver, folder, file, "text/plain")
                val bytes = file.length()
                if (file.delete()) ok++
                AppLog.i(AppLog.Cat.DRIVE, "Upload ok (${file.name}, ${bytes / 1024} kB, ${System.currentTimeMillis() - started} ms) → lokal gelöscht")
                setLastError(context, null)
            } catch (e: Exception) {
                AppLog.error("Upload fehlgeschlagen (${file.name}) – bleibt bis zum nächsten Öffnen liegen", e)
                setLastError(context, "${java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.GERMANY).format(java.util.Date())} – ${e.message ?: e.javaClass.simpleName}")
                break // ohne Netz scheitern auch die übrigen
            }
        }
        return ok
    }

    /** Legt die eigene APK in „02 APK - zum Installieren“ ab, falls diese Version dort noch fehlt. */
    fun copyOwnApkIfMissing(context: Context, versionName: String) {
        val tree = treeUri(context) ?: return
        val resolver = context.contentResolver
        val name = "Wetter-v$versionName.apk"
        try {
            if (rootName(resolver, tree) != APP_FOLDER) {
                AppLog.i(AppLog.Cat.DRIVE, "APK nicht abgelegt: gewählter Ordner ist nicht „$APP_FOLDER“")
                return
            }
            val folder = folder(resolver, tree, APK_FOLDER)
            if (list(resolver, tree, folder).any { it.name == name }) return
            val apk = File(context.applicationInfo.sourceDir)
            val started = System.currentTimeMillis()
            copyFile(resolver, folder, apk, "application/vnd.android.package-archive", name)
            AppLog.i(AppLog.Cat.DRIVE, "APK abgelegt: $name (${apk.length() / 1024} kB, ${System.currentTimeMillis() - started} ms)")
        } catch (e: Exception) {
            AppLog.error("APK konnte nicht nach Drive kopiert werden", e)
        }
    }

    fun countPending(): Int = AppLog.pendingFiles().size

    /** Letzter Upload-Fehler zum Anzeigen in den Einstellungen (null = zuletzt alles ok). */
    fun lastError(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last_error", null)

    private fun setLastError(context: Context, text: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last_error", text).apply()
    }

    // ---------- Ordner-Hilfen (nach StreetLab DriveFolders) ----------

    private fun root(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /**
     * Gefundene/angelegte Ordner werden gemerkt. Grund: Die Drive-Anbindung von Android zeigt
     * neu angelegte Ordner erst mit Verzögerung in der Liste – ohne Merken entstanden Duplikate
     * (zweimal „Logs“, gefunden im Log vom 08.11.2026).
     */
    private val folderCache = mutableMapOf<String, Uri>()
    private var cachePrefs: android.content.SharedPreferences? = null

    fun initCache(context: Context) {
        cachePrefs = context.getSharedPreferences("drive_folders", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun folder(resolver: ContentResolver, tree: Uri, vararg path: String): Uri {
        val key = tree.toString() + "|" + path.joinToString("/")
        val known = folderCache[key] ?: cachePrefs?.getString(key, null)?.let(Uri::parse)
        if (known != null && exists(resolver, known)) return known.also { folderCache[key] = it }
        var current = root(tree)
        val walked = mutableListOf<String>()
        for (name in path) {
            walked += name
            val subKey = tree.toString() + "|" + walked.joinToString("/")
            val subKnown = folderCache[subKey] ?: cachePrefs?.getString(subKey, null)?.let(Uri::parse)
            current = if (subKnown != null && exists(resolver, subKnown)) subKnown else child(resolver, tree, current, name)
            folderCache[subKey] = current
            cachePrefs?.edit()?.putString(subKey, current.toString())?.apply()
        }
        return current
    }

    private fun exists(resolver: ContentResolver, doc: Uri): Boolean = runCatching {
        resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } ?: false
    }.getOrDefault(false)

    private fun child(resolver: ContentResolver, tree: Uri, parent: Uri, name: String): Uri {
        list(resolver, tree, parent).firstOrNull { it.mime == DocumentsContract.Document.MIME_TYPE_DIR && it.name == name }?.let {
            return DocumentsContract.buildDocumentUriUsingTree(tree, it.id)
        }
        return DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw IOException("Drive-Ordner \"$name\" konnte nicht angelegt werden")
    }

    fun list(resolver: ContentResolver, tree: Uri, folder: Uri): List<Entry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(folder))
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val out = mutableListOf<Entry>()
        resolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += Entry(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", if (c.isNull(3)) -1 else c.getLong(3))
            }
        } ?: throw IOException("Drive-Ordner nicht lesbar")
        return out
    }

    private fun copyFile(resolver: ContentResolver, folder: Uri, file: File, mime: String, name: String = file.name) {
        val target = DocumentsContract.createDocument(resolver, folder, mime, name)
            ?: throw IOException("Datei \"$name\" konnte auf Drive nicht angelegt werden")
        resolver.openOutputStream(target, "w")?.use { out ->
            file.inputStream().use { it.copyTo(out, 64 * 1024) }
        } ?: throw IOException("Datei \"$name\" auf Drive nicht beschreibbar")
    }
}
