package com.backpack.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.1.0: transit wake-up alarm. */
class TransitAlarmTest {

    private val five = TransitAlarm.Lead(minutes = 5)

    @Test fun ringsByTimeOrDistance() {
        // 20 m/s (72 km/h): 5 min = 6 km.
        assertFalse(TransitAlarm.shouldRing(7000.0, 20.0, five))
        assertTrue(TransitAlarm.shouldRing(5900.0, 20.0, five))
        assertFalse(TransitAlarm.shouldRing(5900.0, null, five)) // standing: wait (distance safety still applies)
        assertTrue(TransitAlarm.shouldRing(300.0, null, five))
        assertTrue(TransitAlarm.shouldRing(1900.0, 0.0, TransitAlarm.Lead(km = 2)))
        assertNull(TransitAlarm.etaSeconds(1000.0, 1.0))
        assertEquals(12_500.0, TransitAlarm.trackDistance(10_000.0), 0.0)
    }

    @Test fun paceSlowFarFastNear() {
        assertEquals(300_000L, TransitAlarm.nextPaceMs(200_000.0, 20.0, five)) // far: 5 min
        assertEquals(10_000L, TransitAlarm.nextPaceMs(6500.0, 20.0, five))    // near: 10 s
        val mid = TransitAlarm.nextPaceMs(30_000.0, 20.0, five)               // 24 km to go before ringing at 20 m/s
        assertEquals(300_000L, mid)
        assertEquals(37_500L, TransitAlarm.nextPaceMs(9000.0, 20.0, five))
    }

    @Test fun speedSmoothing() {
        val s = TransitAlarm.Speed()
        s.add(20.0)
        assertEquals(20.0, s.value!!, 0.0)
        s.add(0.0) // stopped at a station: only slowly lower
        assertEquals(18.0, s.value!!, 1e-9)
        s.add(30.0)
        assertEquals(21.6, s.value!!, 1e-9)
    }
}
