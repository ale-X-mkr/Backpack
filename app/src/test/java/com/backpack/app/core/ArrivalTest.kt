package com.backpack.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.3.0: a stop or the destination passed close by counts as reached. */
class ArrivalTest {
    private val origin = LatLon(45.0, 6.0)
    private val corner = Geo.project(origin, 0.0, 1000.0)
    private val end = Geo.project(corner, 90.0, 1000.0)
    private var t0 = 0L
    private fun fix(p: LatLon, acc: Float = 5f) = Fix(p.lat, p.lon, 10f, null, acc, (t0++) * 1000)

    private fun route(withStop: Boolean): RouteResult {
        val pts = (0..10).map { Geo.project(origin, 0.0, it * 100.0) } + (1..10).map { Geo.project(corner, 90.0, it * 100.0) }
        val steps = if (withStop) listOf(
            NavStep("Head north", Maneuver.DEPART, 1000.0, 100.0, 0, 10),
            NavStep("Arrive at your stop", Maneuver.GOAL, 0.0, 0.0, 10, 10),
            NavStep("Turn right", Maneuver.RIGHT, 1000.0, 100.0, 10, 20),
            NavStep("Arrive", Maneuver.GOAL, 0.0, 0.0, 20, 20)
        ) else listOf(
            NavStep("Head north", Maneuver.DEPART, 1000.0, 100.0, 0, 10),
            NavStep("Turn right", Maneuver.RIGHT, 1000.0, 100.0, 10, 20),
            NavStep("Arrive", Maneuver.GOAL, 0.0, 0.0, 20, 20)
        )
        return RouteResult(pts, 2000.0, 200.0, steps = steps)
    }

    @Test fun destinationPassedBySideStreet() {
        val t = NavigationTracker(route(false), TravelMode.DRIVING, metric = true)
        var d = 0.0
        while (d <= 1000.0) { assertFalse(t.update(fix(Geo.project(origin, 0.0, d))).arrived); d += 50.0 }
        d = 50.0
        while (d <= 850.0) { assertFalse(t.update(fix(Geo.project(corner, 90.0, d))).arrived); d += 50.0 }
        // A parallel street 70 m south: closest 70 m (< 2 x 40), then moving away.
        val par = Geo.project(end, 180.0, 70.0)
        assertFalse(t.update(fix(Geo.project(par, 270.0, 60.0))).arrived)
        assertFalse(t.update(fix(par)).arrived)
        assertTrue(t.update(fix(Geo.project(par, 90.0, 80.0))).arrived)
    }

    @Test fun destinationOffTheLineCounts() {
        val t = NavigationTracker(route(false), TravelMode.WALKING, metric = true)
        t.update(fix(Geo.project(corner, 90.0, 800.0)))
        // Parked 18 m beside the end point (off the line): arrived.
        assertTrue(t.update(fix(Geo.project(end, 0.0, 18.0))).arrived)
    }

    @Test fun stopReachedNearbyIsNotGuidedBackTo() {
        val t = NavigationTracker(route(true), TravelMode.DRIVING, metric = true)
        var d = 0.0
        while (d <= 900.0) { t.update(fix(Geo.project(origin, 0.0, d))); d += 50.0 }
        // Cut the corner 45 m away from the stop (inside 1.5 x 40 m).
        val cut = Geo.project(corner, 225.0, 45.0)
        val pr = t.update(fix(cut))
        assertEquals("You have reached your stop", pr.speech)
        assertFalse(pr.offRoute)
        assertEquals(1, t.passedStops())
    }
}
