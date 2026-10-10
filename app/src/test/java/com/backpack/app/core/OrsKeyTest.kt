package com.backpack.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrsKeyTest {
    private val current = "eyJvcmciOiI1YjNjZTM1OTc4NTExMTAwMDFjZjYyNDgiLCJpZCI6IjAwMDAiLCJoIjoibXVybXVyNjQifQ=="

    @Test fun findsKeysOnTheClipboard() {
        assertEquals(current, OrsKey.fromClipboard("  $current \n"))
        assertEquals(current, OrsKey.fromClipboard("\"$current\""))
        val hex = "5b3ce3597851110001cf6248" + "0123456789abcdef0123456789abcdef"
        assertEquals(hex, OrsKey.fromClipboard(hex))
        assertNull(OrsKey.fromClipboard("hello world"))
        assertNull(OrsKey.fromClipboard("https://account.heigit.org"))
        assertNull(OrsKey.fromClipboard(null))
    }

    @Test fun plausibleAndHttp() {
        assertTrue(OrsKey.plausible(current))
        assertFalse(OrsKey.plausible("my key"))
        assertEquals(OrsKey.Check.OK, OrsKey.checkFromHttp(200))
        assertEquals(OrsKey.Check.REFUSED, OrsKey.checkFromHttp(403))
        assertEquals(OrsKey.Check.QUOTA, OrsKey.checkFromHttp(429))
        assertEquals(OrsKey.Check.UNREACHABLE, OrsKey.checkFromHttp(null))
    }
}
