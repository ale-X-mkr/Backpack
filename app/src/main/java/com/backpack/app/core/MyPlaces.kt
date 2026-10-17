package com.backpack.app.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 1.9.0 (Alex): Home, Work and up to [MAX_FAVOURITES] favourites, kept on the
 * phone only (encrypted by the app's Vault). Plain JVM (tested).
 */
object MyPlaces {

    const val MAX_FAVOURITES = 10

    enum class Kind { HOME, WORK, FAVOURITE }
    data class Spot(val kind: Kind, val name: String, val point: LatLon)
    data class All(val home: Spot? = null, val work: Spot? = null, val favourites: List<Spot> = emptyList()) {
        /** Home, Work, then the favourites: the order of the list. */
        val list: List<Spot> get() = listOfNotNull(home, work) + favourites
    }

    fun decode(text: String?): All {
        if (text.isNullOrBlank()) return All()
        return try {
            val o = JSONObject(text)
            fun spot(j: JSONObject?, kind: Kind): Spot? = j?.let {
                val lat = it.optDouble("a", Double.NaN)
                val lon = it.optDouble("o", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) null else Spot(kind, it.optString("n", ""), LatLon(lat, lon))
            }
            val favs = o.optJSONArray("f") ?: JSONArray()
            All(spot(o.optJSONObject("h"), Kind.HOME), spot(o.optJSONObject("w"), Kind.WORK),
                (0 until favs.length()).mapNotNull { spot(favs.optJSONObject(it), Kind.FAVOURITE) }.take(MAX_FAVOURITES))
        } catch (e: Exception) { All() }
    }

    fun encode(a: All): String {
        fun j(s: Spot) = JSONObject().put("n", s.name).put("a", s.point.lat).put("o", s.point.lon)
        val o = JSONObject()
        a.home?.let { o.put("h", j(it)) }
        a.work?.let { o.put("w", j(it)) }
        val f = JSONArray()
        for (s in a.favourites) f.put(j(s))
        o.put("f", f)
        return o.toString()
    }

    /** Saves [p] as Home / Work (replacing) or as a new favourite (null when the 10 are taken). */
    fun save(a: All, kind: Kind, name: String, p: LatLon): All? = when (kind) {
        Kind.HOME -> a.copy(home = Spot(Kind.HOME, name, p))
        Kind.WORK -> a.copy(work = Spot(Kind.WORK, name, p))
        Kind.FAVOURITE -> if (a.favourites.size >= MAX_FAVOURITES) null else a.copy(favourites = a.favourites + Spot(Kind.FAVOURITE, name, p))
    }

    fun remove(a: All, s: Spot): All = when (s.kind) {
        Kind.HOME -> a.copy(home = null)
        Kind.WORK -> a.copy(work = null)
        Kind.FAVOURITE -> a.copy(favourites = a.favourites.filterNot { it == s })
    }

    fun rename(a: All, s: Spot, name: String): All = when (s.kind) {
        Kind.HOME -> a.copy(home = s.copy(name = name))
        Kind.WORK -> a.copy(work = s.copy(name = name))
        Kind.FAVOURITE -> a.copy(favourites = a.favourites.map { if (it == s) it.copy(name = name) else it })
    }

    /** A favourite one place up in the list. */
    fun moveUp(a: All, s: Spot): All {
        val i = a.favourites.indexOf(s)
        if (i <= 0) return a
        val m = a.favourites.toMutableList()
        m.removeAt(i); m.add(i - 1, s)
        return a.copy(favourites = m)
    }
}
