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

    fun clear() {
        customStart = null
        destination = null
        waypoints.clear()
        plan = null
        selectedIndex = 0
    }
}
