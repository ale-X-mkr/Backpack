package com.backpack.app.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {

    // L-shaped test route: 1 km north, then right turn, 1 km east.
    private val origin = LatLon(45.0, 6.0)
    private val corner = Geo.project(origin, 0.0, 1000.0)
    private val end = Geo.project(corner, 90.0, 1000.0)

    private fun lPoints(): List<LatLon> {
        val pts = mutableListOf<LatLon>()
        for (i in 0..10) pts.add(Geo.project(origin, 0.0, i * 100.0))
        for (i in 1..10) pts.add(Geo.project(corner, 90.0, i * 100.0))
        return pts
    }

    private fun orsResponse(
        tollDist: Double = 0.0,
        unpavedDist: Double = 0.0,
        duration: Double = 240.0
    ): String {
        val coords = JSONArray()
        for (p in lPoints()) coords.put(JSONArray().put(p.lon).put(p.lat))
        fun step(type: Int, text: String, name: String, d: Double, t: Double, a: Int, b: Int) =
            JSONObject().put("type", type).put("instruction", text).put("name", name)
                .put("distance", d).put("duration", t).put("way_points", JSONArray().put(a).put(b))
        val steps = JSONArray()
            .put(step(11, "Head north on Rue A", "Rue A", 1000.0, 120.0, 0, 10))
            .put(step(1, "Turn right onto Rue B", "Rue B", 1000.0, 120.0, 10, 20))
            .put(step(10, "Arrive at Rue B, on the left", "-", 0.0, 0.0, 20, 20))
        val extras = JSONObject()
            .put("tollways", JSONObject().put("summary", JSONArray()
                .put(JSONObject().put("value", 0).put("distance", 2000.0 - tollDist))
                .put(JSONObject().put("value", 1).put("distance", tollDist))))
            .put("surface", JSONObject().put("summary", JSONArray()
                .put(JSONObject().put("value", 3).put("distance", 2000.0 - unpavedDist))
                .put(JSONObject().put("value", 11).put("distance", unpavedDist))))
        val props = JSONObject()
            .put("summary", JSONObject().put("distance", 2000.0).put("duration", duration))
            .put("segments", JSONArray().put(JSONObject().put("steps", steps)))
            .put("extras", extras)
        val feature = JSONObject()
            .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coords))
            .put("properties", props)
        return JSONObject().put("features", JSONArray().put(feature)).toString()
    }

    private fun route(toll: Double = 0.0, unpaved: Double = 0.0, duration: Double = 240.0) =
        Ors.parseRoutes(orsResponse(toll, unpaved, duration))[0]

    private fun fixAt(p: LatLon, speed: Float = 10f, acc: Float = 5f) =
        Fix(p.lat, p.lon, speed, null, acc, 0L)

    @Test fun geoDistanceAndProject() {
        assertEquals(1000.0, Geo.distance(origin, corner), 0.5)
        assertEquals(90.0, Geo.bearing(corner, end), 0.5)
        assertEquals(10.0, Geo.angleDiff(355.0, 5.0), 1e-9)
    }

    @Test fun polylineSnapMeasuresToSegmentNotVertex() {
        val line = Polyline(listOf(origin, corner)) // one 1 km segment
        val mid = Geo.project(Geo.project(origin, 0.0, 500.0), 90.0, 20.0)
        val s = line.snap(mid)!!
        assertEquals(20.0, s.offsetM, 0.5) // vertex-only check would say ~500 m
        assertEquals(500.0, s.alongM, 1.0)
    }

    @Test fun parseRoutesReadsStepsExtrasAndManeuvers() {
        val r = route(toll = 300.0, unpaved = 150.0)
        assertEquals(21, r.points.size)
        assertEquals(3, r.steps.size)
        assertEquals(Maneuver.RIGHT, r.steps[1].maneuver)
        assertEquals(10, r.steps[1].wayStart)
        assertEquals("", r.steps[2].streetName) // "-" means unnamed
        assertEquals(300.0, r.tollDistanceMeters, 1e-6)
        assertEquals(150.0, r.unpavedDistanceMeters, 1e-6)
        assertEquals(2000.0, r.line.length, 2.0)
    }

    @Test fun requestBodyHasOptionsAndAlternativesOnlyForTwoPoints() {
        val opts = RouteOptions(carAvoidMotorways = true, carAvoidFerries = true)
        val body = Ors.directionsBody(
            listOf(origin, end), "fastest",
            opts.avoidFeatures(TravelMode.DRIVING, avoidTolls = true),
            opts.profileParams(TravelMode.DRIVING), listOf("tollways", "surface"), alternatives = true
        )
        val avoid = body.getJSONObject("options").getJSONArray("avoid_features")
        assertEquals(3, avoid.length())
        assertNotNull(body.optJSONObject("alternative_routes"))
        val three = Ors.directionsBody(listOf(origin, corner, end), "fastest", emptyList(), null, emptyList(), true)
        assertNull(three.optJSONObject("alternative_routes"))
        assertNull(three.optJSONObject("options"))
        assertEquals(6.0, three.getJSONArray("coordinates").getJSONArray(0).getDouble(0), 1e-9) // lon first
    }

    @Test fun bikeAndWalkProfileParams() {
        val o = RouteOptions(bikeType = BikeType.ELECTRIC, bikeHills = Hills.FLAT, walkGreen = true)
        assertEquals("cycling-electric", o.orsProfile(TravelMode.CYCLING))
        assertEquals(0, o.profileParams(TravelMode.CYCLING)!!.getJSONObject("weightings").getInt("steepness_difficulty"))
        assertEquals(1.0, o.profileParams(TravelMode.WALKING)!!.getJSONObject("weightings").getDouble("green"), 1e-9)
        assertNull(o.profileParams(TravelMode.DRIVING))
        assertTrue(o.avoidFeatures(TravelMode.CYCLING, avoidTolls = true).none { it == "tollways" })
    }

    @Test fun optionsRoundTripAndLenientParse() {
        val o = RouteOptions(vehicle = Vehicle.MOTORBIKE, bikeHills = Hills.ANY, walkQuiet = true)
        assertEquals(o, RouteOptions.fromJson(o.toJson()))
        assertEquals(RouteOptions(), RouteOptions.fromJson("{\"bikeHills\":\"NOPE\"}"))
        assertEquals(RouteOptions(), RouteOptions.fromJson(null))
    }

    @Test fun tollBalanceKeepsStep38Rule() {
        // Toll route 10 min faster, 100 km of toll at 0.12 = 12 EUR.
        val toll = route(toll = 100000.0, duration = 3000.0)
        val free = route(duration = 3600.0)
        val weekend = TollBalance.plan(toll, free, valuePerMinute = 10.0 / 60.0, tollRatePerKm = 0.12)!!
        assertTrue(weekend.routes[0] === free) // 10 min worth ~1.7 EUR < 12 EUR
        val emergency = TollBalance.plan(toll, free, valuePerMinute = 90.0 / 60.0, tollRatePerKm = 0.12)!!
        assertTrue(emergency.routes[0] === toll) // 10 min worth 15 EUR >= 12 EUR
        assertEquals(1, TollBalance.plan(free, free, 1.0, 0.12)!!.routes.size) // no toll involved
        assertNull(TollBalance.plan(null, null, 1.0, 0.12))
    }

    @Test fun preferPavedPicksLessUnpavedWithinTimeLimit() {
        val dirt = route(unpaved = 800.0, duration = 600.0)
        val paved = route(unpaved = 0.0, duration = 700.0)
        val slowPaved = route(unpaved = 0.0, duration = 900.0)
        assertEquals(1, RoutePicker.preferPaved(listOf(dirt, paved)).primaryIndex)
        assertEquals(0, RoutePicker.preferPaved(listOf(dirt, slowPaved)).primaryIndex)
    }

    @Test fun trackerAnnouncesBeforeTheTurnNotAtIt() {
        val r = route()
        val t = NavigationTracker(r, TravelMode.DRIVING, metric = true)
        assertEquals("Head north on Rue A", t.departureSpeech())
        val spoken = mutableListOf<String>()
        var anticipation = false
        // Drive north at 10 m/s, a fix every 20 m.
        var d = 0.0
        while (d < 1000.0) {
            val pr = t.update(fixAt(Geo.project(origin, 0.0, d)))
            assertFalse(pr.offRoute)
            assertEquals(1, pr.nextStepIndex)
            assertEquals(1000.0 - d, pr.distanceToNextM, 2.0)
            pr.speech?.let { spoken.add(it) }
            if (pr.anticipation) anticipation = true
            d += 20.0
        }
        assertTrue(anticipation)
        assertEquals("In 300 meters, turn right onto Rue B", spoken[0])
        assertEquals("Turn right onto Rue B", spoken[1])
        // After the corner the next maneuver is the goal.
        val after = t.update(fixAt(Geo.project(corner, 90.0, 50.0)))
        assertEquals(2, after.nextStepIndex)
        assertTrue(after.stepAdvanced)
        assertEquals(950.0, after.remainingM, 3.0)
        assertEquals(120.0 * 950.0 / 1000.0, after.remainingS, 2.0)
    }

    @Test fun shortVoiceSaysOnlyTheManeuver() {
        val t = NavigationTracker(route(), TravelMode.DRIVING, metric = true, short = true)
        assertNull(t.departureSpeech())
        val spoken = mutableListOf<String>()
        var d = 0.0
        while (d < 1000.0) {
            t.update(fixAt(Geo.project(origin, 0.0, d))).speech?.let { spoken.add(it) }
            d += 20.0
        }
        assertEquals(listOf("In 300 meters, turn right", "Turn right"), spoken)
        assertEquals("Take the 3rd exit", shortPhrase(NavStep("Enter the roundabout and take the 3rd exit onto X", Maneuver.ROUNDABOUT_ENTER, 0.0, 0.0, 0, 1, exitNumber = 3)))
        assertEquals("11th", ordinal(11))
        assertEquals("22nd", ordinal(22))
    }

    @Test fun trackerOffRouteNeedsTwoReadingsAndArrives() {
        val t = NavigationTracker(route(), TravelMode.WALKING, metric = true)
        t.update(fixAt(Geo.project(origin, 0.0, 100.0), 1.4f))
        val side = Geo.project(Geo.project(origin, 0.0, 200.0), 90.0, 80.0)
        assertFalse(t.update(fixAt(side, 1.4f)).offRoute)
        assertTrue(t.update(fixAt(side, 1.4f)).offRoute)
        val far = Geo.project(Geo.project(origin, 0.0, 300.0), 270.0, 400.0)
        val t2 = NavigationTracker(route(), TravelMode.WALKING, metric = true)
        assertTrue(t2.update(fixAt(far, 1.4f)).offRoute) // > 150 m: at once
        val arrived = t.update(fixAt(Geo.project(end, 270.0, 10.0), 1.4f))
        assertTrue(arrived.arrived)
    }

    @Test fun passedStopsCountsVisitedIntermediateGoals() {
        // Two legs: stop at the corner, then the destination.
        val base = route()
        val steps = listOf(
            base.steps[0],
            NavStep("Arrive at stop", Maneuver.GOAL, 0.0, 0.0, 10, 10),
            NavStep("Turn right onto Rue B", Maneuver.RIGHT, 1000.0, 120.0, 10, 20),
            NavStep("Arrive", Maneuver.GOAL, 0.0, 0.0, 20, 20)
        )
        val r = RouteResult(base.points, 2000.0, 240.0, steps = steps)
        val t = NavigationTracker(r, TravelMode.DRIVING, metric = true)
        t.update(fixAt(Geo.project(origin, 0.0, 400.0)))
        assertEquals(0, t.passedStops())
        t.update(fixAt(Geo.project(corner, 90.0, 100.0)))
        assertEquals(1, t.passedStops())
    }

    @Test fun roundTripDoesNotArriveAtTheStart() {
        // Out 1 km north and back: start == destination.
        val pts = (0..10).map { Geo.project(origin, 0.0, it * 100.0) } +
            (9 downTo 0).map { Geo.project(origin, 0.0, it * 100.0 + 0.5) }
        val steps = listOf(
            NavStep("Head north", Maneuver.DEPART, 1000.0, 100.0, 0, 10),
            NavStep("Make a U-turn", Maneuver.UTURN, 1000.0, 100.0, 10, 20),
            NavStep("Arrive", Maneuver.GOAL, 0.0, 0.0, 20, 20)
        )
        val t = NavigationTracker(RouteResult(pts, 2000.0, 200.0, steps = steps), TravelMode.WALKING, true)
        assertFalse(t.update(fixAt(origin, 1.4f)).arrived)
        assertFalse(t.update(fixAt(Geo.project(origin, 0.0, 10.0), 1.4f)).arrived)
    }

    @Test fun spokenDistances() {
        assertEquals("300 meters", Speech.distance(312.0, true))
        assertEquals("40 meters", Speech.distance(38.0, true))
        assertEquals("1.5 kilometers", Speech.distance(1520.0, true))
        assertEquals("2 kilometers", Speech.distance(2010.0, true))
        assertEquals("1 kilometer", Speech.distance(990.0, true))
        assertEquals("500 feet", Speech.distance(152.0, false))
        assertEquals("1 mile", Speech.distance(1609.0, false))
    }
}
