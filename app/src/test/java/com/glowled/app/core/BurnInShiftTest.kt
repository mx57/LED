package com.glowled.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BurnInShiftTest {

    @Test
    fun shiftsAreBoundedByMaxShiftPx() {
        val maxShift = 2.5f
        val interval = 60_000L

        // Check 24 hours of operation minute by minute
        for (minute in 0 until 1440) {
            val uptimeMs = minute * interval
            val (dx, dy) = BurnInShiftCalculator.calculateShift(uptimeMs, maxShift, interval)
            assertTrue(
                "Shift ($dx, $dy) exceeded max radius $maxShift at minute $minute",
                BurnInShiftCalculator.isWithinBounds(dx, dy, maxShift)
            )
        }
    }

    @Test
    fun shiftsArePeriodicAndDeterministic() {
        val maxShift = 2f
        val interval = 60_000L

        val shift0 = BurnInShiftCalculator.calculateShift(0L, maxShift, interval)
        val shift8 = BurnInShiftCalculator.calculateShift(8 * interval, maxShift, interval)
        assertEquals(shift0.first, shift8.first, 0.0001f)
        assertEquals(shift0.second, shift8.second, 0.0001f)

        // At step 0: angle = 0, dx = maxShift, dy = 0
        assertEquals(maxShift, shift0.first, 0.0001f)
        assertEquals(0f, shift0.second, 0.0001f)

        // At step 2: angle = 90 deg (PI/2), dx = 0, dy = maxShift
        val shift2 = BurnInShiftCalculator.calculateShift(2 * interval, maxShift, interval)
        assertEquals(0f, shift2.first, 0.0001f)
        assertEquals(maxShift, shift2.second, 0.0001f)
    }

    @Test
    fun handlesZeroRadiusOrInterval() {
        val (dx1, dy1) = BurnInShiftCalculator.calculateShift(100_000L, 0f, 60_000L)
        assertEquals(0f, dx1, 0.0001f)
        assertEquals(0f, dy1, 0.0001f)

        val (dx2, dy2) = BurnInShiftCalculator.calculateShift(100_000L, 2f, 0L)
        assertEquals(0f, dx2, 0.0001f)
        assertEquals(0f, dy2, 0.0001f)
    }
}
