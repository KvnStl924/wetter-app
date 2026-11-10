package de.schee.wetter.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale


/**
 * Gespeicherte Orte (Reihenfolge = Reihenfolge beim Wischen). „Mein Standort“ ist kein Eintrag hier,
 * sondern immer die erste Seite, wenn der Standort erlaubt ist.
 * Übernimmt beim ersten Start den früher einzeln gewählten Ort („manual“, bis 0.4.x).
 */
class PlaceStore(context: Context) {
    private val prefs = context.getSharedPreferences("places", Context.MODE_PRIVATE)

    fun load(): List<Place> {
        val list = prefs.getString("list", null)?.let { parse(it) } ?: emptyList()
        if (list.isEmpty()) prefs.getString("manual", null)?.let { m ->
            val p = runCatching { JSONObject(m).let { o -> Place(o.getString("name").substringBefore(" · "), o.getDouble("lat"), o.getDouble("lon"), false) } }.getOrNull()
            if (p != null) { save(listOf(p)); prefs.edit().remove("manual").apply(); return listOf(p) }
        }
        return list
    }

    fun save(places: List<Place>) {
        prefs.edit().putString("list", serialize(places)).apply()
    }

    /** Zuletzt ermittelter „Mein Standort“ – für den Start ohne Netz bzw. ohne GPS-Signal. */
    fun lastGps(): Place? = prefs.getString("last_gps", null)?.let { parse("[$it]").firstOrNull()?.copy(fromGps = true) }

    fun saveLastGps(p: Place) {
        prefs.edit().putString("last_gps", serialize(listOf(p)).removePrefix("[").removeSuffix("]")).apply()
    }

    companion object {
        fun id(p: Place) = "%.3f,%.3f".format(Locale.US, p.lat, p.lon)

        fun serialize(places: List<Place>): String = JSONArray().apply {
            places.forEach { put(JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon)) }
        }.toString()

        fun parse(json: String): List<Place> = runCatching {
            val a = JSONArray(json)
            (0 until a.length()).map { i -> a.getJSONObject(i).let { Place(it.getString("name"), it.getDouble("lat"), it.getDouble("lon"), false) } }
        }.getOrDefault(emptyList())
    }
}
