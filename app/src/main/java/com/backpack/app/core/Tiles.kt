package com.backpack.app.core

import kotlin.math.cos
import kotlin.math.floor

/**
 * Fixed grid of map squares (0.05° ≈ 5.5 km north-south, 3.5-4 km east-west
 * in Europe). Places are fetched and cached per square: a new plan or a map
 * move in an area already seen needs no server at all, and the server only
 * learns which squares were looked at, never the route line itself.
 */
data class Tile(val x: Int, val y: Int) {
    val south: Double get() = y * Tiles.SIZE
    val west: Double get() = x * Tiles.SIZE
    val north: Double get() = (y + 1) * Tiles.SIZE
    val east: Double get() = (x + 1) * Tiles.SIZE
    val key: String get() = "${x}_$y"

    /** Overpass bbox "(south,west,north,east)". */
    val bbox: String get() = "(%.4f,%.4f,%.4f,%.4f)".format(java.util.Locale.ROOT, south, west, north, east)

    fun contains(p: LatLon): Boolean = p.lat >= south && p.lat < north && p.lon >= west && p.lon < east
}

object Tiles {
    const val SIZE = 0.05

    fun of(p: LatLon): Tile = Tile(floor(p.lon / SIZE).toInt(), floor(p.lat / SIZE).toInt())

    /** Squares covering a box, nearest to its centre first, at most [max]. */
    fun inBox(south: Double, west: Double, north: Double, east: Double, max: Int = 25): List<Tile> {
        val x0 = floor(west / SIZE).toInt()
        val x1 = floor(east / SIZE).toInt()
        val y0 = floor(south / SIZE).toInt()
        val y1 = floor(north / SIZE).toInt()
        val cx = (west + east) / 2
        val cy = (south + north) / 2
        val out = mutableListOf<Tile>()
        for (x in x0..x1) for (y in y0..y1) out.add(Tile(x, y))
        return out.sortedBy {
            val dx = (it.west + SIZE / 2 - cx)
            val dy = (it.south + SIZE / 2 - cy)
            dx * dx + dy * dy
        }.take(max)
    }

    /** Squares within [radiusM] of one or more lines, nearest-along first (lines interleaved). */
    fun alongLines(lines: List<List<LatLon>>, radiusM: Double): List<Tile> = alongLinesOrdered(lines, radiusM).map { it.first }

    /**
     * Same, with the distance along its line where each square is first
     * needed. All lines are interleaved by that distance, so cutting the list
     * keeps the same stretch of every alternative, not all of the first line.
     */
    fun alongLinesOrdered(lines: List<List<LatLon>>, radiusM: Double, size: Double = SIZE): List<Pair<Tile, Double>> {
        val first = HashMap<Tile, Pair<Double, Int>>()
        for ((li, points) in lines.withIndex()) {
            if (points.isEmpty()) continue
            val line = Polyline(points)
            val step = minOf(radiusM, 1000.0 * size / SIZE).coerceAtLeast(100.0)
            var at = 0.0
            var i = 0
            while (true) {
                while (i < points.size - 2 && line.cumulative[i + 1] < at) i++
                val p = if (points.size == 1) points[0] else pointAt(line, i, at)
                val dLat = radiusM / 111_000.0
                val dLon = radiusM / (111_000.0 * cos(Math.toRadians(p.lat)).coerceAtLeast(0.2))
                val x0 = floor((p.lon - dLon) / size).toInt()
                val x1 = floor((p.lon + dLon) / size).toInt()
                val y0 = floor((p.lat - dLat) / size).toInt()
                val y1 = floor((p.lat + dLat) / size).toInt()
                for (x in x0..x1) for (y in y0..y1) {
                    val t = Tile(x, y)
                    val old = first[t]
                    if (old == null || at < old.first) first[t] = at to li
                }
                if (at >= line.length) break
                at = minOf(at + step, line.length)
            }
        }
        return first.entries.sortedWith(compareBy({ it.value.first }, { it.value.second }))
            .map { it.key to it.value.first }
    }

    private fun pointAt(line: Polyline, i: Int, at: Double): LatLon {
        val a = line.points[i]
        val b = line.points[i + 1]
        val seg = line.cumulative[i + 1] - line.cumulative[i]
        val t = if (seg <= 0.0) 0.0 else ((at - line.cumulative[i]) / seg).coerceIn(0.0, 1.0)
        return LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    }
}
