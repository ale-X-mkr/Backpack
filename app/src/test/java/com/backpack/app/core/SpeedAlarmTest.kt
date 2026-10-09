package com.backpack.app.core

import org.junit.Assert.assertEquals
import org.junit.Test
import com.backpack.app.core.SpeedAlarm.Beep
import com.backpack.app.core.SpeedAlarm.Ring

/** 1.3.0: the alarm rules moved out of the screen -- same results as before. */
class SpeedAlarmTest {
    @Test fun gauge() {
        assertEquals(120f to 0f, SpeedAlarm.sweep(25.0, 50))
        assertEquals(240f to 30f, SpeedAlarm.sweep(65.0, 50))
        assertEquals(240f to 60f, SpeedAlarm.sweep(120.0, 50))
        assertEquals(0xFF2E7D32.toInt(), SpeedAlarm.colorAt(0.5))
        assertEquals(0xFF1565C0.toInt(), SpeedAlarm.colorAt(0.0))
    }

    private fun s(v: Int, moving: Boolean, limit: Int?) = SpeedAlarm.state(v, moving, limit)

    @Test fun sameRulesAsBefore() {
        assertEquals(SpeedAlarm.State(Ring.GREY, Beep.NONE), s(50, true, null))
        assertEquals(SpeedAlarm.State(Ring.GREEN, Beep.NONE), s(44, true, 50))
        assertEquals(SpeedAlarm.State(Ring.YELLOW, Beep.NONE), s(46, true, 50))
        assertEquals(SpeedAlarm.State(Ring.YELLOW, Beep.NONE), s(50, true, 50))
        assertEquals(SpeedAlarm.State(Ring.RED, Beep.NONE), s(55, true, 50))
        assertEquals(SpeedAlarm.State(Ring.BLINK_RED, Beep.HIGH), s(56, true, 50))
        assertEquals(SpeedAlarm.State(Ring.BLUE, Beep.LOW), s(80, true, 120))       // 1.4.0: under 70 %
        assertEquals(SpeedAlarm.State(Ring.GREEN, Beep.NONE), s(100, true, 120))   // 83 %: fine now
        assertEquals(SpeedAlarm.State(Ring.STOPPED_RED, Beep.LOW), s(0, false, 120))
        assertEquals(SpeedAlarm.State(Ring.GREY, Beep.NONE), s(0, false, 50))
        assertEquals(SpeedAlarm.State(Ring.BLINK_RED, Beep.HIGH), s(126, true, 120))
        assertEquals(SpeedAlarm.State(Ring.BLINK_RED, Beep.CONTINUOUS), s(141, true, 120)) // 1.4.0: +20
        assertEquals(SpeedAlarm.State(Ring.BLINK_RED, Beep.HIGH), s(140, true, 120))
    }
}
