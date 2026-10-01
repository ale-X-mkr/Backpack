package com.backpack.app.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Plain latitude/longitude pair. Kept free of Android and map-library
 *  types so the routing and navigation core runs (and is tested) on a
 *  plain JVM. */
data class LatLon(val lat: Double, val lon: Double)

object Geo {
    const val EARTH_RADIUS_M = 6371000.0

    fun distance(a: LatLon, b: LatLon): Double = distance(a.lat, a.lon, b.lat, b.lon)

    /** Haversine distance in metres. */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val s = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(s.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing a -> b in degrees 0..360. */
    fun bearing(a: LatLon, b: LatLon): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Destination point from [from] after [distanceM] metres on [bearingDeg]. */
    fun project(from: LatLon, bearingDeg: Double, distanceM: Double): LatLon {
        val br = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(from.lat)
        val lon1 = Math.toRadians(from.lon)
        val d = distanceM / EARTH_RADIUS_M
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(br))
        val lon2 = lon1 + atan2(sin(br) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return LatLon(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    /** Smallest angle between two bearings, 0..180. */
    fun angleDiff(a: Double, b: Double): Double {
        val d = Math.abs(((a - b) % 360.0 + 360.0) % 360.0)
        return if (d > 180.0) 360.0 - d else d
    }
}

/** Result of snapping a position onto a polyline. */
data class Snap(
    /** Index of the segment start vertex (segment = points[i]..points[i+1]). */
    val segmentIndex: Int,
    /** 0..1 position along that segment. */
    val fraction: Double,
    /** Perpendicular distance from the position to the line, metres. */
    val offsetM: Double,
    /** Distance from the polyline start to the snapped point, metres. */
    val alongM: Double,
    val point: LatLon
)

/**
 * A route line with precomputed cumulative distances, so "how far along
 * am I" and "how far to point i" are O(1) after snapping.
 *
 * Snapping uses a local flat projection per segment (accurate to well
 * under a metre for segment lengths found in routes) and measures the
 * distance to the nearest SEGMENT, not just the nearest vertex -- the
 * old vertex-only check reported "off route" on long straight roads
 * whose vertices are far apart.
 */
class Polyline(val points: List<LatLon>) {
    val cumulative: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + Geo.distance(points[i - 1], points[i])
    }
    val length: Double get() = if (cumulative.isEmpty()) 0.0 else cumulative[cumulative.size - 1]

    fun alongAt(index: Int): Double = cumulative[index.coerceIn(0, cumulative.size - 1)]

    /**
     * Snaps [p] to the line. When [hintSegment] is given, only segments
     * from slightly behind it to [lookAheadM] metres ahead are searched
     * first -- this keeps the match on the current part of the route where
     * the route doubles back on itself (out-and-back, ramps, roundabouts).
     * Falls back to a full search when the local match is poor.
     */
    fun snap(p: LatLon, hintSegment: Int = -1, lookAheadM: Double = 2000.0): Snap? {
        if (points.size < 2) {
            return if (points.isEmpty()) null else Snap(0, 0.0, Geo.distance(p, points[0]), 0.0, points[0])
        }
        if (hintSegment >= 0) {
            val from = (hintSegment - 3).coerceAtLeast(0)
            val limit = alongAt(hintSegment) + lookAheadM
            var to = hintSegment
            while (to < points.size - 2 && cumulative[to + 1] < limit) to++
            val local = snapRange(p, from, to)
            if (local != null && local.offsetM < 60.0) return local
        }
        return snapRange(p, 0, points.size - 2)
    }

    private fun snapRange(p: LatLon, from: Int, to: Int): Snap? {
        var best: Snap? = null
        for (i in from..to) {
            val s = snapToSegment(p, i)
            if (best == null || s.offsetM < best.offsetM) best = s
        }
        return best
    }

    private fun snapToSegment(p: LatLon, i: Int): Snap {
        val a = points[i]
        val b = points[i + 1]
        // Equirectangular projection around a, metres.
        val k = Math.cos(Math.toRadians(a.lat))
        val mPerDeg = Math.PI * Geo.EARTH_RADIUS_M / 180.0
        val bx = (b.lon - a.lon) * k * mPerDeg
        val by = (b.lat - a.lat) * mPerDeg
        val px = (p.lon - a.lon) * k * mPerDeg
        val py = (p.lat - a.lat) * mPerDeg
        val len2 = bx * bx + by * by
        val t = if (len2 <= 1e-9) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val sx = bx * t
        val sy = by * t
        val off = Math.hypot(px - sx, py - sy)
        val segLen = cumulative[i + 1] - cumulative[i]
        val snapped = LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
        return Snap(i, t, off, cumulative[i] + segLen * t, snapped)
    }

    /** Bearing of the segment that starts at [segmentIndex]. */
    fun segmentBearing(segmentIndex: Int): Double {
        val i = segmentIndex.coerceIn(0, points.size - 2)
        return Geo.bearing(points[i], points[i + 1])
    }
}
