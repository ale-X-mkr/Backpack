package com.backpack.app

import com.backpack.app.core.LatLon
import com.backpack.app.core.RoutePlan

/**
 * The trip being planned, kept outside the screen so a rotation or a
 * day/night switch (which rebuilds the screen) no longer loses the route.
 * Memory only: nothing about where you go is written to storage.
 */
object TripState {
    var customStart: LatLon? = null
    var destination: LatLon? = null
    val waypoints: MutableList<LatLon> = mutableListOf()
    var plan: RoutePlan? = null
    var selectedIndex: Int = 0

    // Tourist mode results and what they were searched for.
    var touristResults: List<com.backpack.app.core.TouristPoi> = emptyList()
    /** Everything found (scored), re-ranked locally when another route is picked. */
    var touristCandidates: List<com.backpack.app.core.TouristPoi> = emptyList()
    var touristRouteKey: Any? = null
    /** Plan already given the Weekend sights choice (applied at most once). */
    var scenicCheckedPlan: Any? = null
    var touristAreaCenter: LatLon? = null
    /** How far along the route the well-known places were searched (metres). */
    var touristCoveredM: Double = 0.0

    /** The temporary "visit on the way" stop (in [waypoints]; not one of your 2 stops). */
    var detour: LatLon? = null

    /** Destination and stops before a detour, a replaced stop or "Go here",
     *  for "Resume previous route" (kept until arrival or clear). */
    data class Snapshot(val destination: LatLon, val waypoints: List<LatLon>)
    var previous: Snapshot? = null
        set(v) { field = v; visitedSinceSnapshot.clear() }
    /** Stops of [previous] you have been at since it was remembered. */
    val visitedSinceSnapshot: MutableSet<LatLon> = mutableSetOf()

    /** Remembers the current trip once (the first change is what "resume" goes back to). */
    fun rememberForResume(currentStops: List<LatLon>) {
        if (previous != null) return
        val d = destination ?: return
        previous = Snapshot(d, currentStops.filter { it != detour })
    }

    fun clear() {
        customStart = null
        destination = null
        waypoints.clear()
        plan = null
        selectedIndex = 0
        detour = null
        previous = null
    }
}
