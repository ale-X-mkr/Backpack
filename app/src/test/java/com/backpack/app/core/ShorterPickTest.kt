package com.backpack.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ShorterPickTest {
    private fun r(km: Double, min: Double, tollKm: Double = 0.0) = RouteResult(
        points = listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.1)),
        distanceMeters = km * 1000, durationSeconds = min * 60, tollDistanceMeters = tollKm * 1000
    )

    @Test fun takesClearlyShorterWithinExtraTime() {
        val routes = listOf(r(60.0, 50.0), r(50.0, 52.0), r(48.0, 60.0))
        assertEquals(1, ShorterPick.pick(routes, 0.05))
        assertEquals(2, ShorterPick.pick(routes, 0.25))
        assertEquals(0, ShorterPick.pick(routes, 0.0))
    }

    @Test fun ignoresTinySavingsAndMoreToll() {
        assertEquals(0, ShorterPick.pick(listOf(r(60.0, 50.0), r(59.5, 50.5)), 0.25))
        assertEquals(0, ShorterPick.pick(listOf(r(60.0, 50.0, 0.0), r(50.0, 51.0, 20.0)), 0.25))
    }
}
