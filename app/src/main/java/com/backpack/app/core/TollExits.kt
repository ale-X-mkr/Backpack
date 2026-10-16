package com.backpack.app.core

import org.json.JSONObject

/**
 * 1.8.1 (Alex): "last exit before the toll". Plain JVM (tested).
 *
 * With a route: openrouteservice says where the toll stretches start; the
 * motorway exits (OSM highway=motorway_junction) on the route in the
 * [LOOK_BACK_M] before each start are looked up, and the last one is the
 * exit to take to stay off the toll.
 *
 * Without a route: a toll barrier (barrier=toll_booth) straight ahead of
 * you on a fast road, and the last exit between you and it (best guess
 * from your direction of travel).
 */
object TollExits {

    /** How far before a toll start an exit still counts. */
    const val LOOK_BACK_M = 30_000.0
    /** No route: a barrier this far ahead at most, within this angle of your
     *  heading and this far sideways (the same road, not a parallel one). */
    const val FREE_AHEAD_M = 12_000.0
    const val FREE_CONE_DEG = 20.0
    const val FREE_SIDE_M = 250.0
    /** No route: only at motorway speed (m/s, ~60 km/h). */
    const val FREE_MIN_SPEED = 16.7f

    enum class Kind { EXIT, BARRIER }
    class Node(val id: Long, val p: LatLon, val kind: Kind)

    /** Where each toll stretch starts, metres along [line]. */
    fun starts(ranges: List<IntRange>, line: Polyline): List<Double> =
        ranges.map { line.alongAt(it.first) }

    /** Exits within [radiusM] of a stretch of route. */
    fun corridorQuery(points: List<LatLon>, radiusM: Int = 12): String {
        val coords = points.joinToString(",") { "%.5f,%.5f".format(java.util.Locale.ROOT, it.lat, it.lon) }
        return "[out:json][timeout:25];node(around:$radiusM,$coords)[\"highway\"=\"motorway_junction\"];out;"
    }

    /** Exits and toll barriers around one position (no route). Barriers only
     *  across a motorway / trunk itself (not the booths on exit ramps of a
     *  closed toll system, where you already pay). */
    fun pointQuery(p: LatLon, radiusM: Int = FREE_AHEAD_M.toInt() + 1000): String {
        val at = "around:$radiusM,%.5f,%.5f".format(java.util.Locale.ROOT, p.lat, p.lon)
        return "[out:json][timeout:25];way($at)[\"highway\"~\"^(motorway|trunk)$\"]->.m;" +
            "(node(w.m)[\"barrier\"=\"toll_booth\"];node($at)[\"highway\"=\"motorway_junction\"];);out;"
    }

    fun parse(body: String): List<Node> {
        val elements = JSONObject(body).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Node>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            if (e.optString("type") != "node") continue
            val lat = e.optDouble("lat", Double.NaN)
            val lon = e.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val tags = e.optJSONObject("tags")
            val kind = when {
                tags?.optString("barrier") == "toll_booth" -> Kind.BARRIER
                tags?.optString("highway") == "motorway_junction" -> Kind.EXIT
                else -> continue
            }
            out.add(Node(e.optLong("id", 0L), LatLon(lat, lon), kind))
        }
        return out
    }

    /**
     * With a route: the last exit before [tollStartM] (metres along the route),
     * from exits given by their position along it; null when none in the
     * [LOOK_BACK_M] before (or the only ones are right at the start).
     */
    fun lastExitAlong(exitsAlongM: List<Double>, tollStartM: Double): Double? =
        exitsAlongM.filter { it < tollStartM - 100.0 && it > tollStartM - LOOK_BACK_M }.maxOrNull()

    /** A stable name for an exit (so a reroute never announces it again). */
    fun key(p: LatLon): String = "%.4f,%.4f".format(java.util.Locale.ROOT, p.lat, p.lon)

    /**
     * Without a route: (exit, metres to it) for the last exit straight ahead
     * before the nearest toll barrier straight ahead; null when there is no
     * barrier ahead or no exit before it.
     */
    fun lastExitAhead(me: LatLon, headingDeg: Double, nodes: List<Node>): Pair<Node, Double>? {
        fun ahead(n: Node): Double? {
            val d = Geo.distance(me, n.p)
            if (d < 30.0 || d > FREE_AHEAD_M) return null
            val angle = Geo.angleDiff(Geo.bearing(me, n.p), headingDeg)
            if (angle > FREE_CONE_DEG || d * Math.sin(Math.toRadians(angle)) > FREE_SIDE_M) return null
            return d
        }
        val barrier = nodes.filter { it.kind == Kind.BARRIER }.mapNotNull { n -> ahead(n)?.let { n to it } }.minByOrNull { it.second }
            ?: return null
        return nodes.filter { it.kind == Kind.EXIT }.mapNotNull { n -> ahead(n)?.let { n to it } }
            .filter { it.second < barrier.second - 150.0 }
            .maxByOrNull { it.second }
    }

    /** First announcement: about a minute ahead (1.5..3 km); then about 10 s ahead (at least 300 m). */
    fun earlyM(speedMs: Double): Double = (speedMs * 60.0).coerceIn(1500.0, 3000.0)
    fun nowM(speedMs: Double): Double = maxOf(300.0, speedMs * 10.0)
}
