package com.backpack.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 1.0.1: destinations handed over by other apps. */
class NavLinkTest {

    private fun p(link: String) = NavLink.parse(link)

    @Test fun geoLinks() {
        assertEquals(LatLon(45.19, 5.72), p("geo:45.19,5.72")!!.point)
        assertEquals(LatLon(45.19, 5.72), p("geo:45.19,5.72?z=15")!!.point)
        val labelled = p("geo:0,0?q=45.2,5.7(Hotel%20Bastille)")!!
        assertEquals(LatLon(45.2, 5.7), labelled.point)
        assertEquals("Hotel Bastille", labelled.label)
        val address = p("geo:0,0?q=1600+Amphitheatre+Parkway%2C+Mountain+View")!!
        assertNull(address.point)
        assertEquals("1600 Amphitheatre Parkway, Mountain View", address.query)
        // q wins over the map centre
        assertEquals("Grenoble", p("geo:45.0,5.0?q=Grenoble")!!.query)
        assertNull(p("geo:0,0"))
        assertNull(p("geo:95,5"))
    }

    @Test fun googleNavigationLinks() {
        val a = p("google.navigation:q=45.19,5.72&mode=b")!!
        assertEquals(LatLon(45.19, 5.72), a.point)
        assertEquals(TravelMode.CYCLING, a.mode)
        val b = p("google.navigation:q=Place+Victor+Hugo,+Grenoble")!!
        assertEquals("Place Victor Hugo, Grenoble", b.query)
        assertNull(b.mode)
        assertEquals(TravelMode.WALKING, p("google.navigation:q=45,5&mode=w")!!.mode)
    }

    @Test fun googleMapsWebLinks() {
        assertEquals(LatLon(45.19, 5.72), p("https://maps.google.com/?q=45.19,5.72")!!.point)
        assertEquals(LatLon(45.19, 5.72), p("https://www.google.com/maps/dir/?api=1&destination=45.19%2C5.72&travelmode=driving")!!.point)
        assertEquals(TravelMode.DRIVING, p("https://www.google.com/maps/dir/?api=1&destination=45.19%2C5.72&travelmode=driving")!!.mode)
        assertEquals("Gare de Lyon, Paris", p("https://www.google.com/maps/search/?api=1&query=Gare+de+Lyon%2C+Paris")!!.query)
        val place = p("https://www.google.com/maps/place/Bastille/@45.1983,5.7256,17z/data=!3m1")!!
        assertEquals(LatLon(45.1983, 5.7256), place.point)
        assertEquals("Bastille", place.label)
        assertEquals("Lyon", p("https://www.google.com/maps/dir/Grenoble/Lyon/@45.5,5.2,9z")!!.query)
        assertEquals(LatLon(48.85, 2.35), p("https://maps.google.com/maps?daddr=48.85,2.35")!!.point)
        assertEquals(LatLon(48.85, 2.35), p("https://www.google.fr/maps/@48.85,2.35,15z")!!.point)
        assertNull(p("https://maps.app.goo.gl/abc123"))
        assertNull(p("https://www.google.com/search?q=45,5"))
        assertNull(p("https://example.com/maps?q=45,5"))
        assertNull(p("mailto:x@y.z"))
    }

    @Test fun placeDataSearchPathAndStrayPercent() {
        val l = p("https://www.google.com/maps/place/Bastille/@45.19,5.70,15z/data=!4m6!3m5!1s0x0:0x0!8m2!3d45.1983!4d5.7256")!!
        assertEquals(LatLon(45.1983, 5.7256), l.point)
        assertEquals("Bastille", l.label)
        assertEquals("cafe Grenoble", p("https://www.google.com/maps/search/cafe+Grenoble/@45.18,5.72,14z")!!.query)
        assertEquals("100% bio, Lyon", p("geo:0,0?q=100%25+bio,+Lyon")!!.query)
        assertEquals("100% bio", p("google.navigation:q=100%+bio")!!.query)
    }
}
