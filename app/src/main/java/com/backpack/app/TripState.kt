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

    fun clear() {
        customStart = null
        destination = null
        waypoints.clear()
        plan = null
        selectedIndex = 0
    }
}
