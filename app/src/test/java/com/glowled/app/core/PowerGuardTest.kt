package com.glowled.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerGuardTest {

    @Test
    fun regularWindowInsideAndOutside() {
        // Window from 12:00 (720 min) to 14:00 (840 min)
        val start = 12 * 60
        val end = 14 * 60

        // Exact start is inclusive
        assertTrue(PowerGuard.inWindow(start, start, end))
        // Inside
        assertTrue(PowerGuard.inWindow(13 * 60, start, end))
        assertTrue(PowerGuard.inWindow(839, start, end))
        // Exact end is exclusive
        assertFalse(PowerGuard.inWindow(end, start, end))
        // Before start
        assertFalse(PowerGuard.inWindow(719, start, end))
        // Way after
        assertFalse(PowerGuard.inWindow(18 * 60, start, end))
    }

    @Test
    fun overnightWindowSpanningMidnight() {
        // Window from 23:00 (1380 min) to 07:00 (420 min)
        val start = 23 * 60
        val end = 7 * 60

        // Exact start is inclusive
        assertTrue(PowerGuard.inWindow(start, start, end))
        // Late night
        assertTrue(PowerGuard.inWindow(23 * 60 + 30, start, end))
        assertTrue(PowerGuard.inWindow(23 * 60 + 59, start, end))
        // Midnight exact
        assertTrue(PowerGuard.inWindow(0, start, end))
        // Early morning
        assertTrue(PowerGuard.inWindow(3 * 60, start, end))
        assertTrue(PowerGuard.inWindow(7 * 60 - 1, start, end))
        // Exact end is exclusive
        assertFalse(PowerGuard.inWindow(end, start, end))
        // Daytime
        assertFalse(PowerGuard.inWindow(12 * 60, start, end))
        assertFalse(PowerGuard.inWindow(22 * 60 + 59, start, end))
    }

    @Test
    fun emptyWindowNeverMatches() {
        assertFalse(PowerGuard.inWindow(0, 600, 600))
        assertFalse(PowerGuard.inWindow(600, 600, 600))
        assertFalse(PowerGuard.inWindow(1200, 600, 600))
    }

    @Test
    fun boundaryMinuteValues() {
        // Whole day window minus one minute: 00:00 to 23:59
        assertTrue(PowerGuard.inWindow(0, 0, 1439))
        assertTrue(PowerGuard.inWindow(1200, 0, 1439))
        assertFalse(PowerGuard.inWindow(1439, 0, 1439))
    }
}
