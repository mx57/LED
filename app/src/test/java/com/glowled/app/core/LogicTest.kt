package com.glowled.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NotificationStoreTest {

    @Before
    fun reset() = NotificationStore.clear()

    @Test
    fun colorsAreNewestFirstAndDistinct() {
        NotificationStore.put(NotificationStore.Entry("a", "p1", 0xFFFF0000.toInt(), 1))
        NotificationStore.put(NotificationStore.Entry("b", "p2", 0xFF00FF00.toInt(), 3))
        NotificationStore.put(NotificationStore.Entry("c", "p1", 0xFFFF0000.toInt(), 2))
        assertArrayEquals(intArrayOf(0xFF00FF00.toInt(), 0xFFFF0000.toInt()), NotificationStore.colors())
    }

    @Test
    fun repostedSeenNotificationLightsAgain() {
        // e.g. "clear after unlock", then the same WhatsApp chat (same key) gets a new message.
        NotificationStore.put(NotificationStore.Entry("a", "p", 1, 1))
        NotificationStore.markAllSeen()
        NotificationStore.put(NotificationStore.Entry("a", "p", 1, 1))
        assertTrue(NotificationStore.isEmpty()) // a silent update stays seen
        assertTrue(NotificationStore.put(NotificationStore.Entry("a", "p", 1, 2), alert = true))
        assertFalse(NotificationStore.isEmpty())
    }

    @Test
    fun seenNotificationsDoNotLight() {
        NotificationStore.put(NotificationStore.Entry("a", "p", 1, 1))
        NotificationStore.markAllSeen()
        assertTrue(NotificationStore.isEmpty())
        NotificationStore.put(NotificationStore.Entry("b", "p", 2, 2))
        assertFalse(NotificationStore.isEmpty())
        assertArrayEquals(intArrayOf(2), NotificationStore.colors())
    }

    @Test
    fun appsAreGroupedNewestFirstWithCounts() {
        NotificationStore.put(NotificationStore.Entry("a1", "chat", 1, 1))
        NotificationStore.put(NotificationStore.Entry("b1", "mail", 2, 2))
        NotificationStore.put(NotificationStore.Entry("a2", "chat", 1, 3))
        val apps = NotificationStore.apps()
        org.junit.Assert.assertEquals(listOf("chat", "mail"), apps.map { it.pkg })
        org.junit.Assert.assertEquals(listOf(2, 1), apps.map { it.count })
        NotificationStore.markAllSeen()
        assertTrue(NotificationStore.apps().isEmpty())
    }

    @Test
    fun replaceAllReportsChangesOnly() {
        val list = listOf(NotificationStore.Entry("a", "p", 1, 1))
        assertTrue(NotificationStore.replaceAll(list))
        assertFalse(NotificationStore.replaceAll(list))
        assertTrue(NotificationStore.replaceAll(emptyList()))
    }

    @Test
    fun putReportsNewOrRecoloured() {
        assertTrue(NotificationStore.put(NotificationStore.Entry("a", "p", 1, 1)))
        assertFalse(NotificationStore.put(NotificationStore.Entry("a", "p", 1, 5)))
        assertTrue(NotificationStore.put(NotificationStore.Entry("a", "p", 2, 6)))
        assertTrue(NotificationStore.remove("a"))
        assertFalse(NotificationStore.remove("a"))
    }
}

class NotificationContentTest {
    @Test
    fun mediaKindFromText() {
        org.junit.Assert.assertEquals(NotificationStore.Kind.IMAGE, NotificationContent.guessKind("📷 Photo", null))
        org.junit.Assert.assertEquals(NotificationStore.Kind.VIDEO, NotificationContent.guessKind("🎥 Video", null))
        org.junit.Assert.assertEquals(NotificationStore.Kind.AUDIO, NotificationContent.guessKind("🎤 Voice message (0:12)", null))
        org.junit.Assert.assertEquals(NotificationStore.Kind.FILE, NotificationContent.guessKind("📄 report.pdf", null))
        org.junit.Assert.assertEquals(NotificationStore.Kind.TEXT, NotificationContent.guessKind("See you at 6", null))
    }

    @Test
    fun mediaKindFromMime() {
        org.junit.Assert.assertEquals(NotificationStore.Kind.IMAGE, NotificationContent.guessKind("", "image/jpeg"))
        org.junit.Assert.assertEquals(NotificationStore.Kind.VIDEO, NotificationContent.guessKind(null, "video/mp4"))
        org.junit.Assert.assertEquals(NotificationStore.Kind.AUDIO, NotificationContent.guessKind(null, "audio/ogg"))
    }

    @Test
    fun contentChangeCountsAsChange() {
        NotificationStore.clear()
        val a = NotificationStore.Entry("k", "p", 1, 1, title = "Ankit", lines = listOf("hi"))
        assertTrue(NotificationStore.put(a))
        assertFalse(NotificationStore.put(a.copy(time = 2)))
        assertTrue(NotificationStore.put(a.copy(lines = listOf("hi", "are you there?"))))
        org.junit.Assert.assertEquals(2, NotificationStore.entriesFor("p").first().lines.size)
    }
}

class QuietHoursTest {
    @Test
    fun sameDayWindow() {
        assertTrue(PowerGuard.inWindow(13 * 60, 12 * 60, 14 * 60))
        assertFalse(PowerGuard.inWindow(14 * 60, 12 * 60, 14 * 60))
    }

    @Test
    fun overnightWindow() {
        assertTrue(PowerGuard.inWindow(23 * 60 + 30, 23 * 60, 7 * 60))
        assertTrue(PowerGuard.inWindow(3 * 60, 23 * 60, 7 * 60))
        assertFalse(PowerGuard.inWindow(12 * 60, 23 * 60, 7 * 60))
    }

    @Test
    fun emptyWindowNeverMatches() = assertFalse(PowerGuard.inWindow(0, 600, 600))
}

class BatteryStatsTest {
    @Test
    fun formatsSelectedMetricsProperly() {
        val info = BatteryInfo(
            level = 85,
            voltageMv = 4250,
            currentMa = 1500,
            powerWatts = 6.375f,
            isCharging = true,
        )
        // All metrics enabled: 2 lines
        val all = info.formatString(showPercent = true, showVoltage = true, showCurrent = true, showPower = true)
        org.junit.Assert.assertEquals("85% · 4.25V\n1500 mA · 6.4W", all)

        // Only percent and voltage
        val pv = info.formatString(showPercent = true, showVoltage = true, showCurrent = false, showPower = false)
        org.junit.Assert.assertEquals("85% · 4.25V", pv)

        // Only current and power
        val cp = info.formatString(showPercent = false, showVoltage = false, showCurrent = true, showPower = true)
        org.junit.Assert.assertEquals("1500 mA · 6.4W", cp)

        // None enabled
        val none = info.formatString(showPercent = false, showVoltage = false, showCurrent = false, showPower = false)
        org.junit.Assert.assertNull(none)
    }

    @Test
    fun handlesZeroAndNegativeEdgeCases() {
        val info = BatteryInfo(
            level = -1, // invalid level
            voltageMv = 0, // no voltage
            currentMa = -500, // discharging
            powerWatts = 0.02f, // too low power (< 0.05W)
            isCharging = false,
        )
        // Level -1 and voltage 0 should be omitted from row1.
        // Current -500 should show as 500 mA, power 0.02 should be omitted.
        val res = info.formatString(showPercent = true, showVoltage = true, showCurrent = true, showPower = true)
        org.junit.Assert.assertEquals("500 mA", res)
    }
}

class NotifyListenerReadWordsTest {
    @Test
    fun matchesReadActionsInMultipleLanguages() {
        val regex = com.glowled.app.service.NotifyListenerService.READ_WORDS
        assertTrue(regex.containsMatchIn("read"))
        assertTrue(regex.containsMatchIn("Mark as read"))
        assertTrue(regex.containsMatchIn("lida"))
        assertTrue(regex.containsMatchIn("marcar como lido"))
        assertTrue(regex.containsMatchIn("Прочитано"))
        assertTrue(regex.containsMatchIn("прочитать"))
        assertTrue(regex.containsMatchIn("Отметить как прочитанное"))
    }

    @Test
    fun doesNotMatchNonReadActions() {
        val regex = com.glowled.app.service.NotifyListenerService.READ_WORDS
        assertFalse(regex.containsMatchIn("reply"))
        assertFalse(regex.containsMatchIn("ответить"))
        assertFalse(regex.containsMatchIn("удалить"))
        assertFalse(regex.containsMatchIn("archive"))
    }
}

class CpuStatsTest {
    @Test
    fun parseTopLineModernToybox() {
        val line = "800%cpu 125%user 0%nice 68%sys 600%idle 0%iow 5%irq 2%sirq 0%host"
        val usage = CpuStats.parseTopLine(line)
        org.junit.Assert.assertEquals(25, usage)
    }

    @Test
    fun parseTopLineLegacyFormat() {
        val line = "User 25%, System 10%, IOW 0%, IRQ 2%"
        val usage = CpuStats.parseTopLine(line)
        org.junit.Assert.assertEquals(37, usage)
    }

    @Test
    fun parseTopLineInvalidReturnsMinusOne() {
        val line = "unknown output format"
        val usage = CpuStats.parseTopLine(line)
        org.junit.Assert.assertEquals(-1, usage)
    }

    @Test
    fun parseTopLineWithAnsiCodes() {
        val line = "\u001B[7m800%cpu\u001B[0m 100%user 0%nice 50%sys \u001B[1m650%idle\u001B[0m 0%iow"
        val usage = CpuStats.parseTopLine(line)
        org.junit.Assert.assertEquals(18, usage)
    }

    @Test
    fun parseProcStatDelta() {
        val line1 = "cpu  1000 200 300 8000 50 10 20 0 0 0"
        CpuStats.parseProcStatLine(line1)

        val line2 = "cpu  1030 210 310 8050 50 10 20 0 0 0"
        val usage = CpuStats.parseProcStatLine(line2)
        org.junit.Assert.assertEquals(50, usage)
    }
}
