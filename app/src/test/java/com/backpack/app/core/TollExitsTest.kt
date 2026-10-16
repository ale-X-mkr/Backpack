package com.backpack.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.8.1: "last exit before the toll". */
class TollExitsTest {

    @Test fun tollRangesFromOrs() {
        val extras = org.json.JSONObject("""{"tollways":{"values":[[0,10,0],[10,25,1],[25,30,1],[30,50,0],[60,80,1]],"summary":[]}}""")
        assertEquals(listOf(10..30, 60..80), Ors.rangesOf(extras, "tollways") { it == 1 })
        assertTrue(Ors.rangesOf(null, "tollways") { it == 1 }.isEmpty())
    }

    @Test fun lastExitWithARoute() {
        // Exits at 5, 18 and 29.95 km; toll from 30 km: 29.95 is the toll's own ramp, 18 km is the last real exit.
        assertEquals(18_000.0, TollExits.lastExitAlong(listOf(5_000.0, 18_000.0, 29_950.0), 30_000.0)!!, 0.1)
        // Nothing within 30 km before the toll.
        assertNull(TollExits.lastExitAlong(listOf(5_000.0), 60_000.0))
    }

    @Test fun lastExitWithoutARoute() {
        val me = LatLon(45.0, 5.0)
        val north = 0.0
        fun at(d: Double, bearing: Double = north) = Geo.project(me, bearing, d)
        val nodes = listOf(
            TollExits.Node(1, at(2_000.0), TollExits.Kind.EXIT),
            TollExits.Node(2, at(5_000.0), TollExits.Kind.EXIT),
            TollExits.Node(3, at(8_000.0), TollExits.Kind.BARRIER),
            TollExits.Node(4, at(9_000.0), TollExits.Kind.EXIT),        // after the barrier
            TollExits.Node(5, at(4_000.0, 90.0), TollExits.Kind.EXIT)   // off to the side
        )
        val (exit, dist) = TollExits.lastExitAhead(me, north, nodes)!!
        assertEquals(2L, exit.id)
        assertEquals(5_000.0, dist, 5.0)
        // No barrier ahead: nothing to say.
        assertNull(TollExits.lastExitAhead(me, 180.0, nodes))
    }

    @Test fun parseNodes() {
        val body = """{"elements":[{"type":"node","id":7,"lat":45.1,"lon":5.1,"tags":{"highway":"motorway_junction","ref":"12"}},
            {"type":"node","id":8,"lat":45.2,"lon":5.1,"tags":{"barrier":"toll_booth"}},{"type":"node","id":9,"lat":45.3,"lon":5.1,"tags":{"amenity":"x"}}]}"""
        val nodes = TollExits.parse(body)
        assertEquals(listOf(TollExits.Kind.EXIT, TollExits.Kind.BARRIER), nodes.map { it.kind })
    }

    @Test fun words() {
        assertEquals("In 2 kilometers, last exit before the toll",
            VoiceText.forLanguage("en").let { it.inDistance(2000.0, true, it.lastExitBeforeToll()) })
        assertEquals("Dernière sortie avant le péage", VoiceText.forLanguage("fr").lastExitBeforeToll())
    }
}
