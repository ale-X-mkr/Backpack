package com.backpack.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpeningHoursTest {
    private fun at(spec: String?, day: Int, hh: Int, mm: Int = 0) = OpeningHours.isOpen(spec, day, hh * 60 + mm)

    @Test fun common() {
        assertEquals(true, at("24/7", 3, 3))
        assertEquals(true, at("Mo-Fr 08:00-18:00", 0, 9))
        assertEquals(false, at("Mo-Fr 08:00-18:00", 5, 9))      // Saturday
        assertEquals(false, at("Mo-Fr 08:00-18:00", 2, 18))     // closes at 18:00
        assertEquals(false, at("Mo-Fr 08:00-12:00,14:00-18:00", 1, 13))
        assertEquals(true, at("Mo-Fr 08:00-12:00,14:00-18:00", 1, 14, 30))
        assertEquals(true, at("Mo-Sa 09:00-20:00; Su 10:00-14:00", 6, 11))
        assertEquals(false, at("Mo-Sa 09:00-20:00; Su off", 6, 11))
        assertEquals(false, at("Mo-Su 08:00-20:00; We off", 2, 10)) // later rule wins
        assertEquals(true, at("Fr-Sa 20:00-03:00", 5, 1))          // Friday night, Saturday 01:00
        assertEquals(true, at("Fr 20:00-03:00", 5, 2))
        assertEquals(false, at("Fr 20:00-03:00", 5, 4))
        assertEquals(true, at("08:00-20:00", 4, 12))
        assertEquals(true, at("Mo-Fr 17:00+", 0, 23))
        assertEquals(true, at("Su-Tu 10:00-12:00", 0, 11))         // wraps the week
        assertEquals(false, at("Mo-Fr 08:00-12:00, 14:00-18:00", 1, 13))
        assertEquals(true, at("Mo-Fr, Sa 08:00-12:00", 5, 9))
    }

    @Test fun unknownNeverClosed() {
        assertNull(at(null, 0, 10))
        assertNull(at("Jan-Mar Mo-Fr 08:00-12:00", 0, 10))
        assertNull(at("Mo-Fr sunrise-sunset", 0, 10))
        assertEquals(true, at("Mo-Fr 08:00-18:00; PH off", 0, 10)) // holidays can't be known: ignored
        assertNull(at("PH off", 0, 10))
        assertNull(at("by appointment", 0, 10))
    }
}
