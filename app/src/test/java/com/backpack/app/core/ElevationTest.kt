package com.backpack.app.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevationTest {
    @Test fun parsedWithHeights() {
        val coords = JSONArray().put(JSONArray().put(6.0).put(45.0).put(400.0))
            .put(JSONArray().put(6.0).put(45.01).put(450.5)).put(JSONArray().put(6.0).put(45.02).put(430.0))
        val f = JSONObject().put("geometry", JSONObject().put("coordinates", coords))
            .put("properties", JSONObject().put("summary", JSONObject().put("distance", 2200.0).put("duration", 900.0))
                .put("ascent", 50.5).put("descent", 20.5))
        val r = Ors.parseRoutes(JSONObject().put("features", JSONArray().put(f)).toString())[0]
        assertEquals(listOf(400.0, 450.5, 430.0), r.elevations)
        assertEquals(50.5 to 20.5, Elevation.gain(r))
        val prof = Elevation.profile(r)
        assertEquals(3, prof.size)
        assertEquals(450.5, prof[1].second, 1e-9)
        assertTrue(Ors.directionsBody(listOf(LatLon(45.0, 6.0), LatLon(45.1, 6.0)), "fastest", emptyList(), null, emptyList(), false,
            language = "es", elevation = true).let { it.getBoolean("elevation") && it.getString("language") == "es" })
    }

    @Test fun gainSummedWhenNotGiven() {
        val pts = (0..4).map { LatLon(45.0 + it * 0.001, 6.0) }
        val r = RouteResult(pts, 500.0, 300.0, elevations = listOf(100.0, 110.0, 110.5, 105.0, 120.0))
        assertEquals(25.0 to 5.0, Elevation.gain(r))
        assertTrue(Elevation.profile(RouteResult(pts, 500.0, 300.0)).isEmpty())
    }
}
