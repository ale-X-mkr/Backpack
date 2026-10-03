package com.backpack.app.core

import org.json.JSONObject

/**
 * Speed limits fetched per route, not per position: the roads along the
 * next stretch of the route (ways with a maxspeed tag within a few metres
 * of the line) are loaded once, then every GPS fix looks the limit up on
 * the phone. Far fewer server requests, and the server no longer follows
 * the car fix by fix.
 */
object SpeedLimits {

    /** One road piece with its limit (km/h; null = no limit / unknown). */
    class Way(val id: Long, val limit: Int?, val line: Polyline) {
        val south = line.points.minOf { it.lat }
        val north = line.points.maxOf { it.lat }
        val west = line.points.minOf { it.lon }
        val east = line.points.maxOf { it.lon }
    }

    /** Country default limits in km/h ("FR:urban" style values). */
    private val ZONES = mapOf(
        "FR:urban" to 50, "FR:rural" to 80, "FR:motorway" to 130, "FR:zone30" to 30, "FR:walk" to 20,
        "CH:urban" to 50, "CH:rural" to 80, "CH:trunk" to 100, "CH:motorway" to 120, "CH:zone30" to 30,
        "DE:urban" to 50, "DE:rural" to 100, "DE:zone30" to 30, "DE:living_street" to 7,
        "AT:urban" to 50, "AT:rural" to 100, "AT:motorway" to 130,
        "IT:urban" to 50, "IT:rural" to 90, "IT:trunk" to 110, "IT:motorway" to 130,
        "ES:urban" to 50, "ES:rural" to 90, "ES:trunk" to 100, "ES:motorway" to 120, "ES:zone30" to 30,
        "PT:urban" to 50, "PT:rural" to 90, "PT:trunk" to 100, "PT:motorway" to 120,
        "BE:urban" to 50, "BE-VLG:rural" to 70, "BE-WAL:rural" to 90, "BE:motorway" to 120, "BE:zone30" to 30,
        "NL:urban" to 50, "NL:rural" to 80, "NL:motorway" to 100,
        "LU:urban" to 50, "LU:rural" to 90, "LU:motorway" to 130,
        "GB:nsl_single" to 97, "GB:nsl_dual" to 113, "GB:motorway" to 113
    )

    /** maxspeed tag -> km/h. "50", "30 mph", "FR:urban", "50;70" (lowest). */
    fun parse(raw: String?): Int? {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) return null
        if (v.contains(';')) return v.split(';').mapNotNull { parse(it) }.minOrNull()
        v.toIntOrNull()?.let { return it.takeIf { n -> n in 5..200 } }
        Regex("^(\\d+)\\s*mph$", RegexOption.IGNORE_CASE).find(v)?.let {
            return Math.round(it.groupValues[1].toInt() * 1.609344).toInt()
        }
        ZONES[v]?.let { return it }
        Regex(":zone:?(\\d+)$").find(v)?.let { return it.groupValues[1].toInt() }
        return null
    }

    /** Ways with a speed limit within [radiusM] of a line (Overpass). */
    fun corridorQuery(points: List<LatLon>, radiusM: Int = 20): String {
        val coords = points.joinToString(",") { "%.5f,%.5f".format(java.util.Locale.ROOT, it.lat, it.lon) }
        return "[out:json][timeout:25];way(around:$radiusM,$coords)[\"highway\"][\"maxspeed\"];out tags geom;"
    }

    /** Ways with a speed limit around one position (no route: free driving). */
    fun pointQuery(p: LatLon, radiusM: Int = 25): String =
        "[out:json][timeout:10];way(around:$radiusM,%.5f,%.5f)[\"highway\"][\"maxspeed\"];out tags geom;"
            .format(java.util.Locale.ROOT, p.lat, p.lon)

    fun parseWays(body: String): List<Way> {
        val elements = JSONObject(body).getJSONArray("elements")
        val out = mutableListOf<Way>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val geom = e.optJSONArray("geometry") ?: continue
            val pts = mutableListOf<LatLon>()
            for (j in 0 until geom.length()) {
                val g = geom.optJSONObject(j) ?: continue
                val lat = g.optDouble("lat", Double.NaN)
                val lon = g.optDouble("lon", Double.NaN)
                if (!lat.isNaN() && !lon.isNaN()) pts.add(LatLon(lat, lon))
            }
            if (pts.size < 2) continue
            val tags = e.optJSONObject("tags") ?: continue
            out.add(Way(e.optLong("id", 0L), parse(tags.optString("maxspeed", "")), Polyline(pts)))
        }
        return out
    }

    /** Douglas-Peucker: drops points closer than [toleranceM] to the line through their neighbours. */
    fun simplify(points: List<LatLon>, toleranceM: Double): List<LatLon> {
        if (points.size <= 2) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.add(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b <= a + 1) continue
            val seg = Polyline(listOf(points[a], points[b]))
            var maxD = -1.0
            var idx = -1
            for (i in a + 1 until b) {
                val d = seg.snap(points[i])?.offsetM ?: 0.0
                if (d > maxD) { maxD = d; idx = i }
            }
            if (maxD > toleranceM && idx > 0) {
                keep[idx] = true
                stack.add(a to idx)
                stack.add(idx to b)
            }
        }
        return points.indices.filter { keep[it] }.map { points[it] }
    }

    /** The part of [line] from [fromM] to [fromM] + [lengthM] along it. */
    fun section(line: Polyline, fromM: Double, lengthM: Double): List<LatLon> {
        if (line.points.size < 2) return line.points
        val start = fromM.coerceIn(0.0, line.length)
        val end = (start + lengthM).coerceAtMost(line.length)
        val out = mutableListOf<LatLon>()
        out.add(pointAt(line, start))
        for (i in line.points.indices) {
            val c = line.cumulative[i]
            if (c > start && c < end) out.add(line.points[i])
        }
        out.add(pointAt(line, end))
        return out
    }

    fun pointAt(line: Polyline, alongM: Double): LatLon {
        var i = 0
        while (i < line.points.size - 2 && line.cumulative[i + 1] < alongM) i++
        val a = line.points[i]
        val b = line.points[i + 1]
        val seg = line.cumulative[i + 1] - line.cumulative[i]
        val t = if (seg <= 0.0) 0.0 else ((alongM - line.cumulative[i]) / seg).coerceIn(0.0, 1.0)
        return LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    }
}

/**
 * Speed limits already loaded, and which lines they cover. Memory only.
 * Looks the limit up for a position (and heading, so a bridge or a side
 * street crossing the road does not win).
 */
class SpeedLimitIndex(private val maxWays: Int = 8000, private val maxCovered: Int = 40) {

    private val ways = LinkedHashMap<Long, SpeedLimits.Way>()
    private val covered = ArrayDeque<Pair<Polyline, Double>>()
    /** Single-position lookups (free driving), kept apart so they never push route stretches out. */
    private val spots = ArrayDeque<Pair<LatLon, Double>>()

    val wayCount: Int get() = ways.size

    /** Adds the answer of a query along [line] (searched within [radiusM]). */
    fun add(found: List<SpeedLimits.Way>, line: List<LatLon>, radiusM: Double) {
        for (w in found) { ways.remove(w.id); ways[w.id] = w }
        while (ways.size > maxWays) ways.remove(ways.keys.first())
        if (line.size == 1) {
            spots.addLast(line[0] to radiusM)
            while (spots.size > 20) spots.removeFirst()
        } else if (line.size > 1) {
            covered.addLast(Polyline(line) to radiusM)
            while (covered.size > maxCovered) covered.removeFirst()
        }
    }

    /** True when [p] lies on a stretch that was already searched. */
    fun isCovered(p: LatLon, slackM: Double = 10.0): Boolean =
        spots.any { (c, r) -> Geo.distance(c, p) <= r } ||
            covered.any { (line, r) -> (line.snap(p)?.offsetM ?: Double.MAX_VALUE) <= r + slackM }

    /** Same, for stretches loaded along a route only (not single-position lookups). */
    fun isOnLoadedStretch(p: LatLon, slackM: Double = 10.0): Boolean =
        covered.any { (line, r) -> (line.snap(p)?.offsetM ?: Double.MAX_VALUE) <= r + slackM }

    /** How far along [route] (from [fromM]) the searched stretches reach, checked every 250 m. */
    fun coveredUntil(route: Polyline, fromM: Double, maxM: Double): Double {
        var at = fromM
        while (at <= minOf(route.length, fromM + maxM)) {
            if (!isOnLoadedStretch(SpeedLimits.pointAt(route, at))) return at
            at += 250.0
        }
        return minOf(route.length, fromM + maxM)
    }

    /**
     * Limit of the road at [p]: the nearest way within [maxOffsetM] whose
     * direction matches [bearing] (when moving). Null when no road with a
     * limit is close enough.
     */
    fun limitAt(p: LatLon, bearing: Double?, maxOffsetM: Double = 25.0): Int? {
        val dLat = (maxOffsetM + 10.0) / 111_000.0
        val dLon = dLat / Math.cos(Math.toRadians(p.lat)).coerceAtLeast(0.2)
        var best: SpeedLimits.Way? = null
        var bestScore = Double.MAX_VALUE
        for (w in ways.values) {
            if (p.lat < w.south - dLat || p.lat > w.north + dLat || p.lon < w.west - dLon || p.lon > w.east + dLon) continue
            val s = w.line.snap(p) ?: continue
            if (s.offsetM > maxOffsetM) continue
            var score = s.offsetM
            if (bearing != null) {
                val d = Geo.angleDiff(bearing, w.line.segmentBearing(s.segmentIndex))
                val off = minOf(d, 180.0 - d) // a two-way road can be drawn either way
                if (off > 45.0) continue
                score += off / 3.0
            }
            if (score < bestScore) { bestScore = score; best = w }
        }
        return best?.limit
    }

    fun clear() {
        ways.clear()
        covered.clear()
        spots.clear()
    }
}
