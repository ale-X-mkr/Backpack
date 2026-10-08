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
    /** 1.0.6: alternatives under review after "find alternative route"
     *  (navigation stopped) and each one's request, used when it is started. */
    var altReviewPlan: RoutePlan? = null
    var altReviewRequests: Map<Int, com.backpack.app.core.RouteRequest> = emptyMap()
    var selectedIndex: Int = 0

    // Tourist mode results and what they were searched for.
    var touristResults: List<com.backpack.app.core.TouristPoi> = emptyList()
    /** Everything found (scored), re-ranked locally when another route is picked. */
    var touristCandidates: List<com.backpack.app.core.TouristPoi> = emptyList()
    /** 1.3.0: places of the routes not chosen (dimmed): never announced. */
    var touristDimKeys: Set<String> = emptySet()
    var touristRouteKey: Any? = null
    /** Plan already given the Weekend sights choice (applied at most once). */
    var scenicCheckedPlan: Any? = null
    var touristAreaCenter: LatLon? = null
    /** How far along the route the well-known places were searched (metres). */
    var touristCoveredM: Double = 0.0

    /** A place sent by another app, waiting for the first GPS fix (survives rotation). */
    var pendingLink: Pair<LatLon, String>? = null
    /** Travel mode sent with it (applied when the route is planned). */
    var pendingLinkMode: com.backpack.app.core.TravelMode? = null

    /** The temporary "visit on the way" stop (in [waypoints]; not one of your 2 stops). */
    var detour: LatLon? = null
    /** True once a route through [detour] was received (only then can "passed" be judged). */
    var detourApplied = false

    /** Removes the detour from the stops too (it never stays as a stop of its own). */
    fun dropDetour() {
        detour?.let { waypoints.remove(it) }
        detour = null
        detourApplied = false
    }

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

    /** Trip fields as they were before a change, to put back if the new route fails. */
    class Copy(
        val destination: LatLon?, val waypoints: List<LatLon>, val detour: LatLon?, val detourApplied: Boolean,
        val previous: Snapshot?, val visited: Set<LatLon>
    )

    fun copy() = Copy(destination, waypoints.toList(), detour, detourApplied, previous, visitedSinceSnapshot.toSet())

    fun restore(c: Copy) {
        val visitedNow = visitedSinceSnapshot.toSet()
        destination = c.destination
        waypoints.clear()
        waypoints.addAll(c.waypoints)
        detour = c.detour
        detourApplied = c.detourApplied
        previous = c.previous
        // Stops passed while the request ran stay passed.
        visitedSinceSnapshot.addAll(c.visited + visitedNow)
    }

    /** The trip as it was when its route last arrived: what a failed or
     *  interrupted change goes back to (null = no route yet). */
    var committed: Copy? = null
    /** A trip change is waiting for its route (survives a screen rebuild). */
    var changePending = false

    fun clear() {
        customStart = null
        altReviewPlan = null
        altReviewRequests = emptyMap()
        destination = null
        waypoints.clear()
        plan = null
        selectedIndex = 0
        detour = null
        detourApplied = false
        previous = null
        committed = null
        changePending = false
    }
}
