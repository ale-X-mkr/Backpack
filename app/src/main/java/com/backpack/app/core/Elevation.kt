package com.backpack.app.core

/** 1.3.0: height profile of a walking / cycling route. */
object Elevation {

    /** (distance along, height) thinned to about [maxPoints], or empty. */
    fun profile(route: RouteResult, maxPoints: Int = 200): List<Pair<Double, Double>> {
        val h = route.elevations
        if (h.size < 2 || h.size != route.points.size) return emptyList()
        val c = route.line.cumulative
        val step = maxOf(1, h.size / maxPoints)
        val out = ArrayList<Pair<Double, Double>>()
        var i = 0
        while (i < h.size) { out.add(c[i] to h[i]); i += step }
        if (out.last().first != c[h.size - 1]) out.add(c[h.size - 1] to h[h.size - 1])
        return out
    }

    /** Climb and descent (m): the router's own values, else summed (ignoring < 1 m wobbles). */
    fun gain(route: RouteResult): Pair<Double, Double> {
        if (route.ascentM > 0.0 || route.descentM > 0.0) return route.ascentM to route.descentM
        var up = 0.0
        var down = 0.0
        var ref = route.elevations.firstOrNull() ?: return 0.0 to 0.0
        for (v in route.elevations) {
            val d = v - ref
            if (d >= 1.0) { up += d; ref = v } else if (d <= -1.0) { down -= d; ref = v }
        }
        return up to down
    }
}
