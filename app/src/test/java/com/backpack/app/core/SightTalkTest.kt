package com.backpack.app.core

import org.junit.Assert.assertTrue
import org.junit.Test

class SightTalkTest {
    @Test fun summaries() {
        assertTrue(VoiceText.forLanguage("en").severalNear(4).startsWith("Several sights near you, 4."))
        assertTrue(VoiceText.forLanguage("es").startSummary(6).startsWith("6 sitios de interés"))
        assertTrue(VoiceText.forLanguage("de").destinationSummary(8).startsWith("8 Sehenswürdigkeiten"))
    }
}
