package com.backpack.app.core

/**
 * 1.4.0: MapLibre's "line-progress" (0..1 along a drawn line) is measured on
 * the Web-Mercator map, not in metres. To fade the route exactly where the
 * arrow is, metres along the route are turned into that same 0..1.
 */
/** 1.4.0 "Adaptive" 3D view: 30° when slow, rising to 50° at 90 km/h and above. */
object NavTilt {
    fun auto(speedKmh: Double): Double = (30.0 + (speedKmh - 30.0) / 60.0 * 20.0).coerceIn(30.0, 50.0)
}

object LineProgress {

    /** Cumulative length of the line on the Mercator plane (unitless). */
    fun mercatorCumulative(points: List<LatLon>): DoubleArray {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dx = (b.lon - a.lon) / 360.0
            val dy = mercY(b.lat) - mercY(a.lat)
            out[i] = out[i - 1] + Math.sqrt(dx * dx + dy * dy)
        }
        return out
    }

    private fun mercY(lat: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return Math.log(Math.tan(Math.PI / 4 + r / 2)) / (2 * Math.PI)
    }

    /** Metres [alongM] on [line] -> line-progress 0..1. */
    fun fraction(line: Polyline, merc: DoubleArray, alongM: Double): Double {
        val c = line.cumulative
        if (c.size < 2 || merc.size != c.size) return 0.0
        val total = merc[merc.size - 1]
        if (total <= 0.0) return 0.0
        if (alongM <= 0.0) return 0.0
        if (alongM >= c[c.size - 1]) return 1.0
        var lo = 0
        var hi = c.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (c[mid] <= alongM) lo = mid else hi = mid }
        val seg = c[hi] - c[lo]
        val t = if (seg <= 0.0) 0.0 else (alongM - c[lo]) / seg
        return ((merc[lo] + (merc[hi] - merc[lo]) * t) / total).coerceIn(0.0, 1.0)
    }
}
