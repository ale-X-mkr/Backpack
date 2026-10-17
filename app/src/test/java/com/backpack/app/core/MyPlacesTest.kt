package com.backpack.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 1.9.0: Home, Work and favourites. */
class MyPlacesTest {
    private val p = LatLon(45.0, 6.0)

    @Test fun saveAndRoundTrip() {
        var a = MyPlaces.All()
        a = MyPlaces.save(a, MyPlaces.Kind.HOME, "Home", p)!!
        a = MyPlaces.save(a, MyPlaces.Kind.WORK, "Office", LatLon(45.1, 6.1))!!
        a = MyPlaces.save(a, MyPlaces.Kind.FAVOURITE, "Lake", LatLon(45.2, 6.2))!!
        val back = MyPlaces.decode(MyPlaces.encode(a))
        assertEquals(listOf("Home", "Office", "Lake"), back.list.map { it.name })
        assertEquals(45.1, back.work!!.point.lat, 1e-9)
    }

    @Test fun tenFavouritesAtMost() {
        var a = MyPlaces.All()
        repeat(10) { a = MyPlaces.save(a, MyPlaces.Kind.FAVOURITE, "F$it", p)!! }
        assertNull(MyPlaces.save(a, MyPlaces.Kind.FAVOURITE, "F10", p))
        // Home / Work never count against the favourites.
        assertEquals("H", MyPlaces.save(a, MyPlaces.Kind.HOME, "H", p)!!.home!!.name)
    }

    @Test fun editList() {
        var a = MyPlaces.All()
        a = MyPlaces.save(a, MyPlaces.Kind.FAVOURITE, "A", p)!!
        a = MyPlaces.save(a, MyPlaces.Kind.FAVOURITE, "B", LatLon(1.0, 1.0))!!
        a = MyPlaces.moveUp(a, 1)
        assertEquals(listOf("B", "A"), a.favourites.map { it.name })
        a = MyPlaces.rename(a, 0, "Bee")
        a = MyPlaces.remove(a, 1)
        assertEquals(listOf("Bee"), a.favourites.map { it.name })
        // Two equal favourites: only the one chosen goes.
        a = MyPlaces.save(a, MyPlaces.Kind.FAVOURITE, "Bee", p)!!
        a = MyPlaces.save(a, MyPlaces.Kind.HOME, "H", p)!!
        assertEquals(2, MyPlaces.remove(a, 2).list.size)
        assertEquals(0, MyPlaces.decode("garbage").list.size)
    }
}
