package com.backpack.app.core

/** Where to put the A / B / C letter of each route on the map. */
object RouteLabels {

    fun letter(index: Int): Char = 'A' + index.coerceIn(0, 25)

    /**
     * One point per route, where it is farthest from the other routes
     * (so each letter clearly sits on its own line), between 15 % and 85 %
     * of its length; the middle when the routes never part.
     */
    fun points(lines: List<Polyline>): List<LatLon?> = lines.mapIndexed { i, line ->
        if (line.points.size < 2) return@mapIndexed line.points.firstOrNull()
        val others = lines.filterIndexed { j, _ -> j != i }
        var best: LatLon? = null
        var bestScore = -1.0
        var f = 0.15
        while (f <= 0.851) {
            val p = SpeedLimits.pointAt(line, line.length * f)
            val apart = others.minOfOrNull { it.snap(p)?.offsetM ?: 0.0 } ?: 0.0
            // Prefer the middle on ties (and when there is only one route).
            val score = apart - Math.abs(f - 0.5)
            if (score > bestScore) { bestScore = score; best = p }
            f += 0.05
        }
        best
    }
}
