package com.backpack.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StarterTrialTest {
    @Test fun limits() {
        var s: StarterTrial.State? = null
        assertEquals(StarterTrial.Verdict.OK, StarterTrial.verdict(s, 100L))
        repeat(StarterTrial.PER_DAY) { s = StarterTrial.after(s, 100L) }
        assertEquals(StarterTrial.Verdict.DAILY_LIMIT, StarterTrial.verdict(s, 100L))
        assertEquals(StarterTrial.Verdict.OK, StarterTrial.verdict(s, 101L))
        repeat(StarterTrial.TOTAL) { s = StarterTrial.after(s, 101L + it / 30) }
        assertEquals(StarterTrial.Verdict.USED_UP, StarterTrial.verdict(s, 110L))
        val young = StarterTrial.State(100, 3, 100, 3)
        assertEquals(StarterTrial.Verdict.EXPIRED, StarterTrial.verdict(young, 100L + StarterTrial.DAYS))
        assertEquals(young, StarterTrial.decode(StarterTrial.encode(young)))
        assertEquals(null, StarterTrial.decode("garbage"))
    }
}
