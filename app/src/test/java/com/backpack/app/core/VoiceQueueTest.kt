package com.backpack.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.2.1: instructions first, info queued, resumed per sentence, heads-ups in gaps. */
class VoiceQueueTest {

    private fun said(s: VoiceQueue.Say) = (s as VoiceQueue.Say.Sentence).text

    @Test fun sentences() {
        assertEquals(listOf("One.", "Two!", "Three?"), VoiceQueue.sentences("One. Two!  Three?"))
        val long = VoiceQueue.sentences("x".repeat(50) + ", " + "y ".repeat(200))
        assertTrue(long.size > 1 && long.all { it.length <= 280 })
    }

    @Test fun readingResumesAfterInstruction() {
        val q = VoiceQueue()
        q.add(VoiceQueue.Item(VoiceQueue.Kind.READING, "First part. Second part."))
        assertEquals("First part.", said(q.next(0, null)))
        q.sentenceDone()
        assertEquals("Second part.", said(q.next(0, null)))
        q.navStarted() // cut in the middle of the second sentence
        assertEquals(VoiceQueue.Say.Nothing, q.next(0, null))
        q.navDone(1000)
        assertEquals("Second part.", said(q.next(1000, null))) // again from its start
        q.sentenceDone()
        assertEquals(VoiceQueue.Say.Nothing, q.next(1000, null))
    }

    @Test fun headsUpWaitsForGapAndOneAtATime() {
        val q = VoiceQueue()
        var shown = 0
        q.navStarted(); q.navDone(10_000)
        q.add(VoiceQueue.Item(VoiceQueue.Kind.HEADS_UP, { "Castle ahead." }, { true }, { shown++ }))
        assertEquals(VoiceQueue.Say.Nothing, q.next(12_000, 60.0))   // instruction just said
        assertEquals(VoiceQueue.Say.Nothing, q.next(20_000, 10.0))   // next turn too close
        assertEquals("Castle ahead.", said(q.next(20_000, 60.0)))
        assertEquals(1, shown)
        q.add(VoiceQueue.Item(VoiceQueue.Kind.READING, "About the castle."))
        assertEquals(VoiceQueue.Say.Nothing, q.next(20_500, 60.0))   // never cuts the heads-up
        q.sentenceDone()
        assertEquals("About the castle.", said(q.next(21_000, 60.0)))
    }

    @Test fun passedHeadsUpDroppedAndStopReading() {
        val q = VoiceQueue()
        var ahead = true
        q.add(VoiceQueue.Item(VoiceQueue.Kind.HEADS_UP, { "Lake." }, { ahead }, {}))
        q.add(VoiceQueue.Item(VoiceQueue.Kind.READING, "Text."))
        ahead = false
        assertEquals("Text.", said(q.next(0, null)))
        assertTrue(q.dropReadings())
        assertEquals(VoiceQueue.Say.Nothing, q.next(0, null))
    }
}
