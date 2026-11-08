package de.schee.wetter

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import de.schee.wetter.log.AppLog
import de.schee.wetter.log.DriveSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Steuert, wann die App arbeitet: nur zwischen Öffnen und Verlassen.
 * Öffnen → Log starten, wartende Dateien hochladen, eigene APK nach Drive.
 * Verlassen → Log schließen, einmal hochladen, lokal löschen. Danach läuft nichts mehr.
 */
class WetterApp : Application() {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        DriveSync.initCache(this)
        installCrashHandler()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = onAppOpened()
            override fun onStop(owner: LifecycleOwner) = onAppLeft()
        })
    }

    private fun onAppOpened() {
        AppLog.startSession(BuildConfig.VERSION_NAME)
        lastUploadNote(this)?.let { AppLog.i(AppLog.Cat.DRIVE, "Letzter Upload beim Verlassen: $it") }
        io.launch {
            val n = DriveSync.uploadPending(this@WetterApp)
            if (n > 0) AppLog.i(AppLog.Cat.DRIVE, "$n wartende Datei(en) beim Öffnen hochgeladen")
            DriveSync.copyOwnApkIfMissing(this@WetterApp, BuildConfig.VERSION_NAME)
        }
    }

    private fun onAppLeft() {
        AppLog.endSession("App verlassen → Log wird hochgeladen") {
            io.launch {
                val started = System.currentTimeMillis()
                val waiting = DriveSync.countPending()
                val n = DriveSync.uploadPending(this@WetterApp)
                val note = if (n == waiting) "$n Datei(en) in ${System.currentTimeMillis() - started} ms"
                else "$n von $waiting Datei(en) – Rest folgt beim nächsten Öffnen"
                getSharedPreferences("drive", Context.MODE_PRIVATE).edit().putString("last_upload", note).apply()
            }
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            AppLog.writeCrash(thread, error, BuildConfig.VERSION_NAME)
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        fun lastUploadNote(context: Context): String? {
            val prefs = context.getSharedPreferences("drive", Context.MODE_PRIVATE)
            val v = prefs.getString("last_upload", null)
            prefs.edit().remove("last_upload").apply()
            return v
        }
    }
}
