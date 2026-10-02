package com.backpack.app.core

import org.json.JSONArray
import org.json.JSONObject

/** One search suggestion. */
data class Place(val name: String, val detail: String, val point: LatLon)

/**
 * Address / place search, data side. Photon (komoot, open source, OSM data)
 * for suggestions while typing; Nominatim (OSM Foundation) only as a
 * fallback when the search is submitted -- its policy forbids
 * search-as-you-type. Typed coordinates ("45.19, 5.72") need no server.
 */
object Geocode {

    fun photonUrl(query: String, near: LatLon?, limit: Int = 6): String {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        // Nearby results first, from a ~1 km rounded position only.
        val bias = near?.let { "&lat=%.2f&lon=%.2f".format(java.util.Locale.ROOT, it.lat, it.lon) } ?: ""
        return "https://photon.komoot.io/api/?q=$q&limit=$limit&lang=en$bias"
    }

    fun nominatimUrl(query: String, limit: Int = 5): String =
        "https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&limit=$limit&q=" +
            java.net.URLEncoder.encode(query, "UTF-8")

    fun parsePhoton(body: String): List<Place> {
        val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
        val out = mutableListOf<Place>()
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            if (c.length() < 2) continue
            val p = f.optJSONObject("properties") ?: JSONObject()
            val street = listOf(p.optString("street", ""), p.optString("housenumber", "")).filter { it.isNotBlank() }.joinToString(" ")
            val name = p.optString("name", "").ifBlank { street }.ifBlank { p.optString("city", "") }
            if (name.isBlank()) continue
            val detail = listOf(
                if (name != street) street else "",
                p.optString("postcode", ""),
                p.optString("city", "").takeIf { it != name } ?: "",
                p.optString("country", "")
            ).filter { it.isNotBlank() }.joinToString(", ")
            out.add(Place(name, detail, LatLon(c.getDouble(1), c.getDouble(0))))
        }
        return out.distinctBy { "${it.name}|${it.detail}" }
    }

    fun parseNominatim(body: String): List<Place> {
        val arr = JSONArray(body)
        val out = mutableListOf<Place>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val lat = o.optString("lat", "").toDoubleOrNull() ?: continue
            val lon = o.optString("lon", "").toDoubleOrNull() ?: continue
            val display = o.optString("display_name", "")
            val name = o.optString("name", "").ifBlank { display.substringBefore(",") }
            val detail = display.substringAfter(", ", "")
            out.add(Place(name, detail, LatLon(lat, lon)))
        }
        return out
    }

    /** "45.19, 5.72" or "45.19 5.72" -> point, else null. */
    fun parseCoordinates(text: String): LatLon? {
        val m = Regex("^\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*[,; ]\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$").find(text) ?: return null
        val lat = m.groupValues[1].toDouble()
        val lon = m.groupValues[2].toDouble()
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return LatLon(lat, lon)
    }
}
