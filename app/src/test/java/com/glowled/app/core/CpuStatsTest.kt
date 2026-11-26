package com.glowled.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuStatsTest {

    @Test
    fun parsesModernTopOutputWithAnsiCodes() {
        val raw = "\u001B[H\u001B[J800%cpu 234%user   0%nice  81%sys 466%idle   0%iow  13%irq   6%sirq   0%host"
        val pct = CpuStats.parseTopLine(raw)
        // (800 - 466) * 100 / 800 = 33400 / 800 = 41%
        assertEquals(41, pct)
    }

    @Test
    fun parsesLegacyTopOutput() {
        val raw = "User 25%, System 10%, IOW 0%, IRQ 5%"
        assertEquals(40, CpuStats.parseTopLine(raw))
    }

    @Test
    fun parsesProcStatDiffCorrectly() {
        val line1 = "cpu  1000 0 500 8500 0 0 0 0 0 0"
        val line2 = "cpu  1200 0 600 9200 0 0 0 0 0 0"
        // First sample seeds baseline -> -1; second sample: diffTotal = 1000, diffIdle = 700 -> 30%
        CpuStats.parseProcStatLine(line1)
        val usage = CpuStats.parseProcStatLine(line2)
        assertEquals(30, usage)
    }

    @Test
    fun invalidTopOutputReturnsMinusOne() {
        assertEquals(-1, CpuStats.parseTopLine("unknown output format"))
    }

    @Test
    fun normalizesFreqRatioWithMinAndMax() {
        assertEquals(0f, CpuStats.freqRatio(300_000f, 300_000f, 1_800_000f), 0.001f)
        assertEquals(0.5f, CpuStats.freqRatio(1_050_000f, 300_000f, 1_800_000f), 0.001f)
        assertEquals(1f, CpuStats.freqRatio(1_800_000f, 300_000f, 1_800_000f), 0.001f)
        assertTrue(CpuStats.freqRatio(500_000f, 0f, 0f) < 0f)
    }
}
