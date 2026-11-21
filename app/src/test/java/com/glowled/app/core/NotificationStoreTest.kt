package com.glowled.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
        assertEquals(listOf("chat", "mail"), apps.map { it.pkg })
        assertEquals(listOf(2, 1), apps.map { it.count })
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

    @Test
    fun individualMarkSeenAndEntriesForApp() {
        NotificationStore.put(NotificationStore.Entry("k1", "pkg1", 10, 100, title = "First"))
        NotificationStore.put(NotificationStore.Entry("k2", "pkg1", 10, 200, title = "Second"))
        NotificationStore.put(NotificationStore.Entry("k3", "pkg2", 20, 150, title = "Other"))

        // Entries for pkg1 should be ordered newest first (k2 then k1)
        val entries = NotificationStore.entriesFor("pkg1")
        assertEquals(2, entries.size)
        assertEquals("k2", entries[0].key)
        assertEquals("k1", entries[1].key)

        // Mark k2 as seen
        NotificationStore.markSeen("k2")
        val remaining = NotificationStore.entriesFor("pkg1")
        assertEquals(1, remaining.size)
        assertEquals("k1", remaining[0].key)

        // Newest unseen time should be k3 (150)
        assertEquals(150L, NotificationStore.newestTime())
    }

    @Test
    fun concurrentAccessDoesNotThrowOrCorrupt() {
        val threadCount = 8
        val iterationsPerThread = 50
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        for (t in 0 until threadCount) {
            Thread {
                try {
                    for (i in 0 until iterationsPerThread) {
                        val key = "key_${t}_$i"
                        NotificationStore.put(NotificationStore.Entry(key, "pkg_$t", t + 1, System.currentTimeMillis()))
                        NotificationStore.colors()
                        NotificationStore.apps()
                        if (i % 2 == 0) NotificationStore.markSeen(key)
                        if (i % 5 == 0) NotificationStore.remove(key)
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(0, errors.get())
    }
}
