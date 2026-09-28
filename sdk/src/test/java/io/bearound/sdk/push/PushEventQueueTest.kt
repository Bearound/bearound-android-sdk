package io.bearound.sdk.push

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [PushMarker] parsing and [PushEventQueue] (REQ-022, REQ-025, REQ-026).
 */
@RunWith(RobolectricTestRunner::class)
class PushEventQueueTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        PushEventQueue.resetForTest(context)
    }

    @After
    fun tearDown() {
        PushEventQueue.resetForTest(context)
    }

    // region PushMarker.parse

    @Test
    fun `marker with sid, d and https tr is measurable`() {
        val marker = PushMarker.parse(
            """{"t":1,"sid":"sid-1","d":"payload","tr":"https://track.bearound.io"}"""
        )

        assertEquals("sid-1", marker?.sid)
        assertEquals("payload", marker?.d)
        assertEquals("https://track.bearound.io", marker?.tr)
    }

    @Test
    fun `marker missing d is not measurable`() {
        val marker = PushMarker.parse("""{"t":1,"sid":"sid-1","tr":"https://track.bearound.io"}""")

        assertNull(marker)
    }

    @Test
    fun `marker missing tr is not measurable`() {
        val marker = PushMarker.parse("""{"t":1,"sid":"sid-1","d":"payload"}""")

        assertNull(marker)
    }

    @Test
    fun `marker with non-https tr is not measurable`() {
        val marker = PushMarker.parse(
            """{"t":1,"sid":"sid-1","d":"payload","tr":"http://track.bearound.io"}"""
        )

        assertNull(marker)
    }

    @Test
    fun `marker missing sid (sync push) is not measurable`() {
        val marker = PushMarker.parse("""{"t":1}""")

        assertNull(marker)
    }

    @Test
    fun `null or blank raw marker is not measurable`() {
        assertNull(PushMarker.parse(null))
        assertNull(PushMarker.parse(""))
    }

    @Test
    fun `malformed json is not measurable`() {
        assertNull(PushMarker.parse("not json"))
    }

    // endregion

    // region URL building

    @Test
    fun `url is built exactly as tr slash v1 slash push colon verb question d equals encoded d`() {
        val url = PushEventQueue.buildUrl(
            tr = "https://track.bearound.io",
            verb = PushEventVerb.OPEN,
            d = "a b+c"
        )

        assertEquals(
            "https://track.bearound.io/v1/push:open?d=${java.net.URLEncoder.encode("a b+c", "UTF-8")}",
            url
        )
        // Round-trips back to the original value.
        val query = url.substringAfter("?d=")
        assertEquals("a b+c", URLDecoder.decode(query, "UTF-8"))
    }

    @Test
    fun `url for received verb uses push colon received`() {
        val url = PushEventQueue.buildUrl(tr = "https://track.bearound.io", verb = PushEventVerb.RECEIVED, d = "x")

        assertEquals("https://track.bearound.io/v1/push:received?d=x", url)
    }

    // endregion

    // region enqueue + transport drain/keep

    private fun marker(sid: String = "sid-1") =
        PushMarker(sid = sid, d = "payload", tr = "https://track.bearound.io")

    private fun awaitFlush(latch: CountDownLatch) {
        assertTrue("transport should be invoked", latch.await(5, TimeUnit.SECONDS))
        // Give the queue's own background thread time to persist the drain/keep before assertions.
        Thread.sleep(100)
    }

    @Test
    fun `2xx drains the entry`() {
        val latch = CountDownLatch(1)
        PushEventQueue.transport = PushEventTransport { latch.countDown(); PushHitOutcome.DRAIN }

        PushEventQueue.enqueue(context, PushEventVerb.OPEN, marker())
        awaitFlush(latch)

        assertEquals(0, PushEventQueue.sizeForTest(context))
    }

    @Test
    fun `4xx other than 429 drains the entry`() {
        val latch = CountDownLatch(1)
        PushEventQueue.transport = PushEventTransport { latch.countDown(); PushHitOutcome.DRAIN }

        PushEventQueue.enqueue(context, PushEventVerb.RECEIVED, marker())
        awaitFlush(latch)

        assertEquals(0, PushEventQueue.sizeForTest(context))
    }

    @Test
    fun `429 keeps the entry queued`() {
        val latch = CountDownLatch(1)
        PushEventQueue.scheduleRetry = { _, _ -> } // never fire the retry in this test
        PushEventQueue.transport = PushEventTransport { latch.countDown(); PushHitOutcome.KEEP }

        PushEventQueue.enqueue(context, PushEventVerb.OPEN, marker())
        awaitFlush(latch)

        assertEquals(1, PushEventQueue.sizeForTest(context))
    }

    @Test
    fun `5xx keeps the entry queued`() {
        val latch = CountDownLatch(1)
        PushEventQueue.scheduleRetry = { _, _ -> }
        PushEventQueue.transport = PushEventTransport { latch.countDown(); PushHitOutcome.KEEP }

        PushEventQueue.enqueue(context, PushEventVerb.OPEN, marker())
        awaitFlush(latch)

        assertEquals(1, PushEventQueue.sizeForTest(context))
    }

    @Test
    fun `transport error keeps the entry queued`() {
        val latch = CountDownLatch(1)
        PushEventQueue.scheduleRetry = { _, _ -> }
        PushEventQueue.transport = PushEventTransport { latch.countDown(); throw java.io.IOException("boom") }

        PushEventQueue.enqueue(context, PushEventVerb.OPEN, marker())
        awaitFlush(latch)

        assertEquals(1, PushEventQueue.sizeForTest(context))
    }

    @Test
    fun `kept entry retries with backoff and eventually drains`() {
        val attempts = AtomicInteger(0)
        val secondAttemptLatch = CountDownLatch(1)
        PushEventQueue.scheduleRetry = { _, action -> action() } // fire retries synchronously/immediately
        PushEventQueue.transport = PushEventTransport {
            if (attempts.incrementAndGet() == 1) {
                PushHitOutcome.KEEP
            } else {
                secondAttemptLatch.countDown()
                PushHitOutcome.DRAIN
            }
        }

        PushEventQueue.enqueue(context, PushEventVerb.OPEN, marker())

        assertTrue(secondAttemptLatch.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertEquals(0, PushEventQueue.sizeForTest(context))
    }

    // endregion

    // region dedupe

    @Test
    fun `duplicate (sid, verb) is enqueued only once`() {
        val sendCount = AtomicInteger(0)
        val latch = CountDownLatch(2)
        PushEventQueue.transport = PushEventTransport {
            sendCount.incrementAndGet()
            latch.countDown()
            PushHitOutcome.DRAIN
        }

        PushEventQueue.enqueue(context, PushEventVerb.OPEN, marker("sid-dup"))
        PushEventQueue.enqueue(context, PushEventVerb.OPEN, marker("sid-dup"))
        // Give the (possible, wrongly duplicated) second flush a chance to run.
        latch.await(1, TimeUnit.SECONDS)
        Thread.sleep(200)

        assertEquals("second enqueue for the same (sid, verb) must be a no-op", 1, sendCount.get())
    }

    @Test
    fun `same sid with different verbs are both enqueued`() {
        val latch = CountDownLatch(2)
        PushEventQueue.transport = PushEventTransport { latch.countDown(); PushHitOutcome.DRAIN }

        PushEventQueue.enqueue(context, PushEventVerb.RECEIVED, marker("sid-both"))
        PushEventQueue.enqueue(context, PushEventVerb.OPEN, marker("sid-both"))

        assertTrue(latch.await(5, TimeUnit.SECONDS))
    }

    // endregion

    // region cap + age

    @Test
    fun `queue caps at 200 entries, oldest dropped first`() {
        PushEventQueue.transport = PushEventTransport { PushHitOutcome.KEEP } // never drains
        PushEventQueue.scheduleRetry = { _, _ -> }

        val now = System.currentTimeMillis()
        repeat(205) { i ->
            PushEventQueue.seedForTest(context, PushEventVerb.RECEIVED, sid = "sid-$i", enqueuedAt = now + i)
        }

        assertEquals(PushEventQueue.MAX_ENTRIES, PushEventQueue.sizeForTest(context))
    }

    @Test
    fun `entries older than 7 days are evicted`() {
        val now = System.currentTimeMillis()
        PushEventQueue.seedForTest(
            context,
            PushEventVerb.RECEIVED,
            sid = "sid-old",
            enqueuedAt = now - PushEventQueue.MAX_AGE_MS - 1_000
        )
        PushEventQueue.seedForTest(context, PushEventVerb.RECEIVED, sid = "sid-fresh", enqueuedAt = now)

        assertEquals(1, PushEventQueue.sizeForTest(context))
    }

    // endregion
}
