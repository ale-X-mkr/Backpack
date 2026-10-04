package com.backpack.app.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 1.0.7: the area reachable in a given time (Sights POI category): the
 * openrouteservice isochrone polygon, or a circle estimate when it is not
 * available (no key, server error, driving over the public API's 1 h).
 */
object Reach {

    /** Choices offered in Settings > Places (minutes). */
    val MINUTES = listOf(30, 60, 120)
    const val DEFAULT_MINUTES = 60

    /** Largest search distance from the origin (one Overpass request stays
     *  light enough for the public servers); farther = cut to this. */
    const val MAX_SEARCH_M = 70_000.0

    /** [ring] with every point farther than [maxM] from [center] pulled
     *  back to [maxM]; second = whether anything was cut. */
    fun clip(ring: List<LatLon>, center: LatLon, maxM: Double = MAX_SEARCH_M): Pair<List<LatLon>, Boolean> {
        var cut = false
        val out = ring.map { p ->
            val d = Geo.distance(center, p)
            if (d <= maxM) p else { cut = true; Geo.project(center, Geo.bearing(center, p), maxM) }
        }
        return out to cut
    }

    /** Public openrouteservice limit for driving isochrones (seconds). */
    const val ORS_DRIVING_MAX_S = 3600

    /** Rough average speed (km/h) for the circle estimate. */
    fun averageKmh(mode: TravelMode): Double = when (mode) {
        TravelMode.WALKING -> 4.5
        TravelMode.CYCLING -> 15.0
        TravelMode.DRIVING -> 60.0
    }

    fun radiusM(mode: TravelMode, minutes: Int): Double = averageKmh(mode) * 1000.0 * minutes / 60.0

    /** openrouteservice isochrone profile for the mode. */
    fun orsProfile(mode: TravelMode): String = when (mode) {
        TravelMode.WALKING -> "foot-walking"
        TravelMode.CYCLING -> "cycling-regular"
        TravelMode.DRIVING -> "driving-car"
    }

    /** Whether the public service can answer (driving is limited to 1 h). */
    fun orsCanAnswer(mode: TravelMode, minutes: Int): Boolean =
        mode != TravelMode.DRIVING || minutes * 60 <= ORS_DRIVING_MAX_S

    /** Body of POST /v2/isochrones/{profile}. */
    fun orsBody(center: LatLon, minutes: Int): JSONObject = JSONObject()
        .put("locations", JSONArray().put(JSONArray().put(center.lon).put(center.lat)))
        .put("range", JSONArray().put(minutes * 60))
        .put("range_type", "time")

    /** Outer ring of the first isochrone polygon, or null. */
    fun parseIsochrone(body: String): List<LatLon>? {
        val features = JSONObject(body).optJSONArray("features") ?: return null
        if (features.length() == 0) return null
        val geom = features.getJSONObject(0).optJSONObject("geometry") ?: return null
        val coords = geom.optJSONArray("coordinates") ?: return null
        val ring = when (geom.optString("type")) {
            "Polygon" -> coords.optJSONArray(0)
            "MultiPolygon" -> coords.optJSONArray(0)?.optJSONArray(0)
            else -> null
        } ?: return null
        val out = ArrayList<LatLon>(ring.length())
        for (i in 0 until ring.length()) {
            val pt = ring.getJSONArray(i)
            out.add(LatLon(pt.getDouble(1), pt.getDouble(0)))
        }
        return out.takeIf { it.size >= 4 }
    }

    /** Circle of [radiusM] around [center] as a ring of [n] points. */
    fun circle(center: LatLon, radiusM: Double, n: Int = 48): List<LatLon> =
        (0 until n).map { Geo.project(center, it * 360.0 / n, radiusM) }

    /** At most [max] points (every k-th kept), for the Overpass poly filter. */
    fun thin(ring: List<LatLon>, max: Int = 120): List<LatLon> {
        if (ring.size <= max) return ring
        val step = Math.ceil(ring.size.toDouble() / max).toInt()
        return ring.filterIndexed { i, _ -> i % step == 0 }
    }

    /** Point-in-polygon (ray casting), for the places of the cached squares. */
    fun contains(ring: List<LatLon>, p: LatLon): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.lat > p.lat) != (b.lat > p.lat) &&
                p.lon < (b.lon - a.lon) * (p.lat - a.lat) / (b.lat - a.lat) + a.lon
            ) inside = !inside
            j = i
        }
        return inside
    }

    /** south, west, north, east of the ring. */
    fun bounds(ring: List<LatLon>): DoubleArray = doubleArrayOf(
        ring.minOf { it.lat }, ring.minOf { it.lon }, ring.maxOf { it.lat }, ring.maxOf { it.lon }
    )

    /** Overpass "poly" filter text: "lat lon lat lon ...". */
    fun polyFilter(ring: List<LatLon>): String =
        thin(ring).joinToString(" ") { "%.4f %.4f".format(java.util.Locale.ROOT, it.lat, it.lon) }
}
