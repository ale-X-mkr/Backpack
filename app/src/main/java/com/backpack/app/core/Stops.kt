package com.backpack.app.core

/**
 * Stop rules (max 2 stops chosen by you, plus at most one temporary
 * "visit on the way" detour that does not count).
 */
object Stops {

    const val MAX = 2

    /** Your own stops, without the temporary detour. */
    fun ownCount(stops: List<LatLon>, detour: LatLon?): Int = stops.count { it != detour }

    /**
     * Adds a temporary detour in route order. An earlier detour is replaced
     * (one at a time). Returns the new stop list.
     */
    fun withDetour(stops: List<LatLon>, oldDetour: LatLon?, detour: LatLon, route: Polyline?): List<LatLon> =
        Tourist.insertStop(stops.filter { it != oldDetour }, detour, route)

    /**
     * Replaces your last own stop (the one furthest along the route) by
     * [newStop], keeping the route order. A temporary detour stays.
     */
    fun replaceLast(stops: List<LatLon>, detour: LatLon?, newStop: LatLon, route: Polyline?): List<LatLon> {
        val own = stops.filter { it != detour }
        if (own.isEmpty()) return Tourist.insertStop(stops, newStop, route)
        val ordered = if (route == null || route.points.size < 2) own
        else own.sortedBy { route.snap(it)?.alongM ?: Double.MAX_VALUE }
        val last = ordered.last()
        val kept = stops.toMutableList().apply { remove(last) }
        return Tourist.insertStop(kept, newStop, route)
    }
}
