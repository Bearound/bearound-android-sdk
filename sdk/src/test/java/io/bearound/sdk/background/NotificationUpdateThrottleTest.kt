package io.bearound.sdk.background

import io.bearound.sdk.background.NotificationUpdateThrottle.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationUpdateThrottleTest {

    /** Drives the throttle the way BeaconScanService does, with a fake clock and scheduler. */
    private class Harness(minIntervalMs: Long = 5_000L) {
        var now = 0L
        val throttle = NotificationUpdateThrottle<String>(minIntervalMs) { now }
        val posts = mutableListOf<Pair<Long, String>>()
        private var trailingDueAt: Long? = null

        fun offer(content: String) {
            when (val decision = throttle.offer(content)) {
                Decision.PostNow -> posts += now to content
                Decision.Skip -> Unit
                is Decision.ScheduleTrailing -> {
                    assertNull("only one trailing post may be pending", trailingDueAt)
                    trailingDueAt = now + decision.delayMs
                }
            }
        }

        fun advanceTo(time: Long) {
            val due = trailingDueAt
            if (due != null && due <= time) {
                now = due
                trailingDueAt = null
                throttle.onTrailingDue()?.let { posts += now to it }
            }
            now = time
        }
    }

    @Test
    fun `100 changing updates in 1 s produce one post plus the trailing one`() {
        val h = Harness()
        repeat(100) { i ->
            h.advanceTo(i * 10L)
            h.offer("beacons=$i")
        }
        h.advanceTo(10_000L)

        assertEquals(2, h.posts.size)
        assertEquals(0L to "beacons=0", h.posts[0])
        // The trailing post carries the LAST content, at the end of the 5 s window.
        assertEquals(5_000L to "beacons=99", h.posts[1])
    }

    @Test
    fun `identical content never re-posts`() {
        val h = Harness()
        h.offer("same")
        for (t in 100L..60_000L step 100L) {
            h.advanceTo(t)
            h.offer("same")
        }
        h.advanceTo(120_000L)

        assertEquals(listOf(0L to "same"), h.posts)
    }

    @Test
    fun `a change after the window posts immediately`() {
        val h = Harness()
        h.offer("a")
        h.advanceTo(6_000L)
        h.offer("b")

        assertEquals(listOf(0L to "a", 6_000L to "b"), h.posts)
    }

    @Test
    fun `a change reverted inside the window is not posted`() {
        val h = Harness()
        h.offer("a")
        h.advanceTo(1_000L)
        h.offer("b")
        h.advanceTo(2_000L)
        h.offer("a")
        h.advanceTo(10_000L)

        assertEquals(listOf(0L to "a"), h.posts)
    }

    @Test
    fun `sustained changes post at most once per window`() {
        val h = Harness()
        for (t in 0L until 30_000L step 100L) {
            h.advanceTo(t)
            h.offer("t=$t")
        }
        h.advanceTo(40_000L)

        // 30 s of updates every 100 ms: one post per 5 s window plus the final state.
        assertTrue("posts=${h.posts.size}", h.posts.size <= 7)
        h.posts.zipWithNext().forEach { (a, b) ->
            assertTrue("gap ${b.first - a.first} ms", b.first - a.first >= 5_000L)
        }
        assertEquals("t=29900", h.posts.last().second)
    }

    @Test
    fun `startForeground post counts as the first post`() {
        val h = Harness()
        h.throttle.recordPosted("initial")
        h.offer("initial")
        h.advanceTo(1_000L)
        h.offer("update")
        h.advanceTo(10_000L)

        assertEquals(listOf(5_000L to "update"), h.posts)
    }
}
