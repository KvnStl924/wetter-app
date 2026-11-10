package de.schee.wetter.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import de.schee.wetter.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.awaitClose
import java.util.Locale


/** Standort nur auf Anfrage bei geöffneter App – kein Dauer-Abo, kein Hintergrund. */
object LocationSource {
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Place? {
        if (!hasPermission(context)) return null
        val started = System.currentTimeMillis()
        val client = LocationServices.getFusedLocationProviderClient(context)
        val cts = CancellationTokenSource()
        val loc = withTimeoutOrNull(12_000) {
            runCatching { client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token).await() }.getOrNull()
        } ?: run {
            cts.cancel()
            runCatching { client.lastLocation.await() }.getOrNull()?.also { AppLog.i(AppLog.Cat.ORT, "Aktueller Standort nicht verfügbar – letzter bekannter wird genutzt") }
        }
        if (loc == null) {
            AppLog.error("Standort konnte nicht ermittelt werden (${System.currentTimeMillis() - started} ms)")
            return null
        }
        val name = placeName(context, loc.latitude, loc.longitude)
        AppLog.i(AppLog.Cat.ORT, "Standort ermittelt: $name (Genauigkeit ${loc.accuracy.toInt()} m, ${System.currentTimeMillis() - started} ms)")
        return Place(name, loc.latitude, loc.longitude, fromGps = true)
    }

    /** Live-Position für den blauen Punkt im Vollbild-Radar. */
    data class Fix(val lat: Double, val lon: Double, val accuracyM: Float)

    /**
     * Laufende Standort-Updates (alle 2–5 s), nur solange der Aufrufer sammelt –
     * das Radar sammelt nur bei sichtbarem Bildschirm. Ohne Berechtigung: leerer Fluss.
     */
    @SuppressLint("MissingPermission")
    fun updates(context: Context): kotlinx.coroutines.flow.Flow<Fix> = kotlinx.coroutines.flow.callbackFlow {
        if (!hasPermission(context)) { close(); return@callbackFlow }
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = com.google.android.gms.location.LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateIntervalMillis(2000).build()
        val cb = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(r: com.google.android.gms.location.LocationResult) {
                r.lastLocation?.let { trySend(Fix(it.latitude, it.longitude, it.accuracy)) }
            }
        }
        runCatching { client.lastLocation.await() }.getOrNull()?.let { trySend(Fix(it.latitude, it.longitude, it.accuracy)) }
        client.requestLocationUpdates(request, cb, android.os.Looper.getMainLooper())
        AppLog.i(AppLog.Cat.ORT, "Live-Standort im Radar gestartet")
        awaitClose { client.removeLocationUpdates(cb); AppLog.i(AppLog.Cat.ORT, "Live-Standort im Radar beendet") }
    }

    @Suppress("DEPRECATION")
    suspend fun placeName(context: Context, lat: Double, lon: Double): String = withContext(Dispatchers.IO) {
        runCatching {
            Geocoder(context, Locale.GERMANY).getFromLocation(lat, lon, 1)?.firstOrNull()?.let { a ->
                a.locality ?: a.subLocality ?: a.subAdminArea ?: a.adminArea
            }
        }.getOrNull() ?: "%.2f°, %.2f°".format(Locale.GERMANY, lat, lon)
    }
}
