package com.backpack.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxTest {
    @Test fun writeAndStore() {
        val pts = listOf(Gpx.Point(45.0, 6.0, 400.0, 0L), Gpx.Point(45.001, 6.0, Double.NaN, 60_000L))
        val gpx = Gpx.write("Trip <1> & co", pts)
        assertTrue(gpx.contains("<gpx version=\"1.1\""))
        assertTrue(gpx.contains("<name>Trip &lt;1&gt; &amp; co</name>"))
        assertTrue(gpx.contains("<trkpt lat=\"45.000000\" lon=\"6.000000\"><ele>400.0</ele><time>1970-01-01T00:00:00Z</time></trkpt>"))
        assertTrue(gpx.contains("<trkpt lat=\"45.001000\" lon=\"6.000000\"><time>1970-01-01T00:01:00Z</time></trkpt>"))
        val back = Gpx.fromLines(Gpx.toLines(pts))
        assertEquals(2, back.size)
        assertTrue(back[1].ele.isNaN())
        assertEquals(111.0, Gpx.length(pts), 1.0)
    }

    @Test fun thinning() {
        val a = Gpx.Point(45.0, 6.0, 0.0, 0L)
        assertTrue(Gpx.keep(null, a))
        assertFalse(Gpx.keep(a, Gpx.Point(45.00005, 6.0, 0.0, 5_000L)))   // 5 m, 5 s
        assertTrue(Gpx.keep(a, Gpx.Point(45.0002, 6.0, 0.0, 5_000L)))     // 22 m
        assertTrue(Gpx.keep(a, Gpx.Point(45.0, 6.0, 0.0, 31_000L)))       // 31 s
    }
}
