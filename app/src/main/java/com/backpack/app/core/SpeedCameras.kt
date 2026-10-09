package com.backpack.app.core

import org.json.JSONObject

/**
 * 1.4.0: fixed speed cameras from OpenStreetMap (highway=speed_camera),
 * with the country each one stands in, and what the law there allows:
 *  - EXACT: a sonar blip that speeds up as you get closer;
 *  - ZONE (France, and when the country is unknown): only a "danger zone"
 *    of a few km that contains the camera somewhere, never its spot;
 *  - NONE (Germany, Switzerland, Cyprus, North Macedonia): nothing at all.
 * Plain JVM (tested).
 */
object SpeedCameras {

    class Camera(val id: Long, val p: LatLon, val country: String?)

    enum class Rule { EXACT, ZONE, NONE }

    private val FORBIDDEN = setOf("DE", "CH", "CY", "MK")
    private val ZONE_ONLY = setOf("FR")

    fun rule(country: String?): Rule = when {
        country == null -> Rule.ZONE
        country.uppercase() in FORBIDDEN -> Rule.NONE
        country.uppercase() in ZONE_ONLY -> Rule.ZONE
        else -> Rule.EXACT
    }

    /** Each camera is printed, followed by its country (admin level 2). */
    private fun body(around: String): String =
        "[out:json][timeout:25];node($around)[\"highway\"=\"speed_camera\"]->.c;" +
            "foreach.c->.x(.x out;.x is_in->.a;area.a[\"admin_level\"=\"2\"][\"ISO3166-1\"];out tags;);"

    /** Cameras within [radiusM] of a stretch of route. */
    fun corridorQuery(points: List<LatLon>, radiusM: Int = 30): String {
        val coords = points.joinToString(",") { "%.5f,%.5f".format(java.util.Locale.ROOT, it.lat, it.lon) }
        return body("around:$radiusM,$coords")
    }

    /** Cameras around one position (no route). */
    fun pointQuery(p: LatLon, radiusM: Int = 2000): String =
        body("around:$radiusM,%.5f,%.5f".format(java.util.Locale.ROOT, p.lat, p.lon))

    fun parse(body: String): List<Camera> {
        val elements = JSONObject(body).getJSONArray("elements")
        val out = mutableListOf<Camera>()
        var pendingId = 0L
        var pending: LatLon? = null
        var country: String? = null
        fun flush() {
            pending?.let { out.add(Camera(pendingId, it, country)) }
            pending = null
            country = null
        }
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            when (e.optString("type")) {
                "node" -> {
                    flush()
                    val lat = e.optDouble("lat", Double.NaN)
                    val lon = e.optDouble("lon", Double.NaN)
                    if (!lat.isNaN() && !lon.isNaN()) { pendingId = e.optLong("id", 0L); pending = LatLon(lat, lon) }
                }
                "area" -> if (pending != null && country == null) {
                    val code = e.optJSONObject("tags")?.optString("ISO3166-1", "").orEmpty()
                    if (code.length == 2) country = code.uppercase()
                }
            }
        }
        flush()
        return out
    }

    /** Distance ahead (m) at which the blips start: about 30 s, 300..1000 m. */
    fun alertDistance(speedMs: Double): Double = (speedMs * 30.0).coerceIn(300.0, 1000.0)

    /** Time between blips: 1.6 s far away down to 0.2 s at the camera. */
    fun blipIntervalMs(distanceM: Double, alertM: Double): Long {
        val f = (distanceM / alertM).coerceIn(0.0, 1.0)
        return (200 + 1400 * f).toLong()
    }

    /** Danger-zone length (France): 300 m in town, 2 km on roads, 4 km on fast roads. */
    fun zoneLength(limitKmh: Int?): Double = when {
        limitKmh == null -> 2000.0
        limitKmh <= 50 -> 300.0
        limitKmh >= 110 -> 4000.0
        else -> 2000.0
    }

    /**
     * Where the zone starts, ahead of the camera: the camera falls at a
     * fixed but unpredictable 20..80 % of the zone (from its id), so the
     * zone never gives its spot away.
     */
    fun zoneLead(camera: Camera, lengthM: Double): Double {
        val r = ((camera.id * 2654435761L) ushr 8).toInt() and 0xFFFF
        return lengthM * (0.2 + 0.6 * r / 65535.0)
    }
}
