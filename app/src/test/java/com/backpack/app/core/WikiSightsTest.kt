package com.backpack.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.2.1: Wikipedia as the main source of sights, spread along the route. */
class WikiSightsTest {

    @Test fun cells() {
        val c = Tourist.wikiCellOf(LatLon(46.25, 6.14))
        assertEquals(Tile(61, 462), c)
        val m = Tourist.wikiCellCenter(c)
        assertEquals(46.25, m.lat, 1e-9); assertEquals(6.15, m.lon, 1e-9)
        assertEquals(Tile(-1, -1), Tourist.wikiCellOf(LatLon(-0.05, -0.05)))
        val url = Tourist.wikiCellUrl(c)
        assertTrue(url.startsWith("https://en.wikipedia.org/w/api.php?") && "ggslimit=500" in url && "colimit=max" in url && "ggsradius=8000" in url)
        val box = Tourist.wikiCellsInBox(46.0, 6.0, 46.3, 6.3, 4)
        assertEquals(4, box.size)
        assertTrue(Tourist.wikiCellOf(LatLon(46.15, 6.15)) in box)
    }

    @Test fun cellsAlongARoute() {
        // ~33 km east-west at 46°N, 2 km corridor: the cells crossed, in order.
        val line = listOf(LatLon(46.05, 6.02), LatLon(46.05, 6.45))
        val cells = Tourist.wikiCellsAlong(listOf(line), 2000.0)
        assertEquals(Tile(60, 460), cells.first().first)
        assertTrue(cells.map { it.first }.containsAll(listOf(Tile(61, 460), Tile(62, 460), Tile(64, 460))))
        assertTrue(cells.zipWithNext().all { (a, b) -> a.second <= b.second })
    }

    @Test fun kindsAndPlaces() {
        val body = """{"results":{"bindings":[
            {"item":{"value":"http://www.wikidata.org/entity/Q1"},"kind":{"value":"http://www.wikidata.org/entity/Q23413"},"links":{"value":"42"}},
            {"item":{"value":"http://www.wikidata.org/entity/Q1"},"kind":{"value":"http://www.wikidata.org/entity/Q9259"},"links":{"value":"42"}},
            {"item":{"value":"http://www.wikidata.org/entity/Q2"},"kind":{"value":"http://www.wikidata.org/entity/Q5"},"links":{"value":"9"}},
            {"item":{"value":"http://www.wikidata.org/entity/Q3"},"kind":{"value":"http://www.wikidata.org/entity/Q34038"},"links":{"value":"3"}}]}}"""
        val k = Tourist.parseKindsLinks(body)
        assertEquals(TouristCategory.HERITAGE to 42, k["Q1"])
        assertEquals(null, k["Q2"])
        val cell = Tile(61, 462)
        val hits = listOf(
            Tourist.WikiHit("Q1", "Castle", LatLon(46.21, 6.12)),
            Tourist.WikiHit("Q2", "Someone", LatLon(46.22, 6.12)),
            Tourist.WikiHit("Q3", "Falls", LatLon(46.31, 6.12)) // next cell north: not this one
        )
        val places = Tourist.wikiPlaces(cell, hits, k)
        assertEquals(listOf("qQ1"), places.map { it.key })
        assertEquals(42, places[0].sitelinks)
        assertEquals("en:Castle", places[0].wikipedia)
        assertTrue(Tourist.kindsLinksQuery(listOf("Q1", "Q3")).contains("VALUES ?item { wd:Q1 wd:Q3 }"))
    }

    @Test fun spreadAlongTheRoute() {
        fun p(i: Int, along: Double, links: Int) = TouristPoi("n$i", "P$i", LatLon(0.0, 0.0), TouristCategory.SIGHT, "Q$i", null, links, 0.0, along)
        // A famous city at the start (40 places) and a few small places further on.
        val city = (0 until 40).map { p(it, 1000.0 + it, 100 - it) }
        val later = listOf(p(100, 60_000.0, 2), p(101, 90_000.0, 1), p(102, 140_000.0, 3))
        val shown = Tourist.rankAlong(city + later, routeLengthM = 150_000.0)
        assertEquals(25 + 3 * 6, shown.size)
        assertTrue(shown.map { it.key }.containsAll(listOf("n100", "n101", "n102")))
        // Short route: just the best ones.
        assertEquals(25, Tourist.rankAlong(city, routeLengthM = 10_000.0).size)
    }
}
