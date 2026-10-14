package io.bearound.sdk.utilities

import io.bearound.sdk.sampling.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure JVM tests for [DiagnosticsStore].
 *
 * [DiagnosticsStore] is a singleton `object` with mutable process-lifetime state, so each test
 * would otherwise inherit state from the previous one. [DiagnosticsStore.reset] drains it in
 * [setUp] so the tests are fully order-independent. Uses the public test hook (not reflection)
 * so the test also passes against the R8-minified release variant.
 */
class DiagnosticsStoreTest {

    @Before
    fun setUp() {
        DiagnosticsStore.reset()
    }

    @Test
    fun `recordScan updates lastScanAt and beacon count`() {
        val before = System.currentTimeMillis()
        DiagnosticsStore.recordScan(beaconCount = 7)
        val after = System.currentTimeMillis()

        val at = DiagnosticsStore.lastScanAt()
        assertTrue("lastScanAt should be set", at != null)
        assertTrue("lastScanAt should be within the call window", at!! in before..after)
        assertEquals(7, DiagnosticsStore.lastScanBeaconCount())
    }

    @Test
    fun `recordSync updates success and beacon count`() {
        val before = System.currentTimeMillis()
        DiagnosticsStore.recordSync(success = true, beaconCount = 3)
        val after = System.currentTimeMillis()

        val at = DiagnosticsStore.lastSyncAt()
        assertTrue("lastSyncAt should be set", at != null)
        assertTrue("lastSyncAt should be within the call window", at!! in before..after)
        assertEquals(true, DiagnosticsStore.lastSyncSuccess())
        assertEquals(3, DiagnosticsStore.lastSyncBeaconCount())
    }

    @Test
    fun `recordSync preserves failure outcome`() {
        DiagnosticsStore.recordSync(success = false, beaconCount = 0)

        assertEquals(false, DiagnosticsStore.lastSyncSuccess())
        assertEquals(0, DiagnosticsStore.lastSyncBeaconCount())
    }

    @Test
    fun `recordScan and recordSync do not bleed into each other`() {
        DiagnosticsStore.recordScan(beaconCount = 5)

        // A scan must not populate any sync field.
        assertNull(DiagnosticsStore.lastSyncAt())
        assertNull(DiagnosticsStore.lastSyncSuccess())
        assertNull(DiagnosticsStore.lastSyncBeaconCount())
    }

    @Test
    fun `recordError formats entry as epochMillis pipe message`() {
        val before = System.currentTimeMillis()
        DiagnosticsStore.recordError("boom")
        val after = System.currentTimeMillis()

        val errors = DiagnosticsStore.recentErrors()
        assertEquals(1, errors.size)

        val entry = errors.first()
        val parts = entry.split(" | ", limit = 2)
        assertEquals("entry should be '<epochMillis> | <message>'", 2, parts.size)

        val ts = parts[0].toLong()
        assertTrue("timestamp should be within the call window", ts in before..after)
        assertEquals("boom", parts[1])
    }

    @Test
    fun `recentErrors is a ring buffer capped at 10 dropping the oldest`() {
        // Add 12 distinct errors; only the last 10 should survive, oldest-first.
        repeat(12) { i -> DiagnosticsStore.recordError("err-$i") }

        val errors = DiagnosticsStore.recentErrors()
        assertEquals("ring buffer must cap at 10", 10, errors.size)

        // The oldest two (err-0, err-1) must have been dropped.
        assertTrue("err-0 should have been evicted", errors.none { it.endsWith(" | err-0") })
        assertTrue("err-1 should have been evicted", errors.none { it.endsWith(" | err-1") })

        // The surviving window is err-2 .. err-11, oldest first.
        assertTrue("oldest survivor should be err-2", errors.first().endsWith(" | err-2"))
        assertTrue("newest should be err-11", errors.last().endsWith(" | err-11"))

        val messages = errors.map { it.substringAfter(" | ") }
        assertEquals((2..11).map { "err-$it" }, messages)
    }

    @Test
    fun `recentErrors returns a detached snapshot`() {
        DiagnosticsStore.recordError("a")
        val snapshot = DiagnosticsStore.recentErrors()
        assertEquals(1, snapshot.size)

        // A later error must not retroactively grow a previously taken snapshot.
        DiagnosticsStore.recordError("b")

        assertEquals("snapshot must be detached from later writes", 1, snapshot.size)
        assertEquals(2, DiagnosticsStore.recentErrors().size)
    }
    @Test fun `diagnostics distinguish actual observations cache policy and transport outcomes`() {
        val evidence = MotionEvidence(MotionState.STATIONARY_INFERRED, MotionSource.CONTEXT_FUSION,
            MotionConfidence.MEDIUM, MotionReason.CONTEXT_STABLE, 100L, 5L, true)
        DiagnosticsStore.recordSamplingPolicy(evidence, SamplingPlan(true, 300_000L, 300_000L, 5_000L), true, clockUncertaintyMs = 1.0, nextLiveAttemptElapsedMs = 5_000L)
        DiagnosticsStore.recordObservation(unique = 3, cached = 7, contextRead = true, buckets = 2, gaps = 1, denied = 1)
        DiagnosticsStore.recordSamplingAttempt("live", 100, true)
        DiagnosticsStore.recordSamplingAttempt("summary", 200, false, lagMs = 1_000)
        DiagnosticsStore.recordSamplingAttempt("transition", 50, true, lagMs = 300, archiveOnly = true)
        val snapshot = DiagnosticsStore.sampling()
        assertEquals("stationary_inferred", snapshot.state); assertEquals("context_fusion", snapshot.source)
        assertEquals(1.0, snapshot.clockUncertaintyMs!!, 0.0); assertEquals(5_000L, snapshot.nextLiveAttemptElapsedMs)
        assertEquals("separated", snapshot.mode); assertEquals("context_stable", snapshot.reason); assertEquals("active", snapshot.capabilityStatus)
        assertEquals(3L, snapshot.uniqueSamples); assertEquals(7L, snapshot.cachedObservations)
        assertEquals(1L, snapshot.contextReads); assertEquals(2L, snapshot.bucketCount); assertEquals(1L, snapshot.gapCount)
        assertEquals(1L, snapshot.liveAttempts); assertEquals(1L, snapshot.summaryAttempts); assertEquals(1L, snapshot.transitionAttempts)
        assertEquals(350L, snapshot.serializedBytes); assertEquals(1L, snapshot.failures); assertEquals(1_000L, snapshot.retryLagMs)
        assertEquals(1L, snapshot.archiveOnlyCount); assertEquals(1L, snapshot.policyDenials)
        DiagnosticsStore.reset()
        assertEquals("unknown", DiagnosticsStore.sampling().state); assertEquals(0L, DiagnosticsStore.sampling().serializedBytes)
        assertEquals(350L, snapshot.serializedBytes)
    }

}
