package com.backpack.app.nav

import android.content.Context
import com.backpack.app.core.LatLon
import org.json.JSONArray
import org.json.JSONObject

/**
 * 1.4.0: the navigation you stopped before arriving (destination, stops not
 * yet visited, travel mode), kept on the phone so "Resume navigation" can
 * start it again -- also after closing the app. Cleared on arrival, when a
 * new navigation starts, or by holding the Resume button.
 */
object LastTrip {
    data class Trip(val destination: LatLon, val stops: List<LatLon>, val mode: String)

    private const val PREFS = "backpack_last_trip"

    fun save(c: Context, t: Trip) {
        val o = JSONObject().put("d", JSONArray().put(t.destination.lat).put(t.destination.lon)).put("m", t.mode)
        val s = JSONArray()
        for (p in t.stops) s.put(JSONArray().put(p.lat).put(p.lon))
        o.put("s", s)
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("trip", o.toString()).apply()
    }

    fun load(c: Context): Trip? = try {
        val text = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("trip", null)
        text?.let {
            val o = JSONObject(it)
            val d = o.getJSONArray("d")
            val s = o.optJSONArray("s") ?: JSONArray()
            Trip(LatLon(d.getDouble(0), d.getDouble(1)),
                (0 until s.length()).map { i -> s.getJSONArray(i).let { a -> LatLon(a.getDouble(0), a.getDouble(1)) } },
                o.optString("m", "DRIVING"))
        }
    } catch (e: Exception) { null }

    fun clear(c: Context) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("trip").apply()
    }
}
