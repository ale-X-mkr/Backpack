package com.backpack.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LineProgressTest {
    @Test fun metresToMapProgress() {
        // North then east at 45°: on the Mercator map the northward leg looks longer.
        val o = LatLon(45.0, 6.0)
        val c = Geo.project(o, 0.0, 10_000.0)
        val e = Geo.project(c, 90.0, 10_000.0)
        val line = Polyline(listOf(o, c, e))
        val merc = LineProgress.mercatorCumulative(line.points)
        assertEquals(0.0, LineProgress.fraction(line, merc, 0.0), 1e-9)
        assertEquals(1.0, LineProgress.fraction(line, merc, 20_000.0), 1e-9)
        val half = LineProgress.fraction(line, merc, 10_000.0)
        assertEquals(merc[1] / merc[2], half, 1e-9)
        assertEquals(0.5, half, 0.01) // same scale here: nearly half
    }
}
