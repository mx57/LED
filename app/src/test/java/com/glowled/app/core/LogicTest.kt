package com.glowled.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
