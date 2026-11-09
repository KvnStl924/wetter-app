package de.schee.wetter.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network as AndroidNetwork
import android.net.NetworkCapabilities
import de.schee.wetter.log.AppLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Netz da oder nicht? Wird nur beobachtet, solange die App sichtbar ist (Aufruf aus repeatOnLifecycle) –
 * kein Hintergrund. [online] ist der letzte bekannte Stand für die Anzeige („Offline · Stand …“).
 */
object Network {
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online

    /** Nur für die Testbilder am PC. */
    fun preview(on: Boolean) { _online.value = on }

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Fluss „online ja/nein“; beim Sammeln angemeldet, beim Abbrechen wieder abgemeldet. */
    fun watch(context: Context): Flow<Boolean> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) { trySend(true); awaitClose { }; return@callbackFlow }
        fun update() { val on = isOnline(context); _online.value = on; trySend(on) }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: AndroidNetwork) = update()
            override fun onLost(network: AndroidNetwork) = update()
            override fun onCapabilitiesChanged(network: AndroidNetwork, caps: NetworkCapabilities) = update()
        }
        update()
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onFailure { AppLog.error("Netz-Beobachtung nicht möglich", it) }
        awaitClose { runCatching { cm.unregisterNetworkCallback(cb) } }
    }.distinctUntilChanged()
}
