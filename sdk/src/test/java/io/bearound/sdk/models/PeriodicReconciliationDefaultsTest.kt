package io.bearound.sdk.models

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PeriodicReconciliationDefaultsTest {

    private val min = PeriodicReconciliationDefaults.MINIMUM_INTERVAL_MILLIS
    private val max = PeriodicReconciliationDefaults.MAXIMUM_INTERVAL_MILLIS
    private val def = PeriodicReconciliationDefaults.DEFAULT_INTERVAL_MILLIS

    @Test
    fun `default interval is 20 minutes`() {
        assertEquals(20L * 60L * 1000L, def)
    }

    @Test
    fun `15 minutes is accepted as-is (WorkManager minimum)`() {
        assertEquals(min, PeriodicReconciliationDefaults.sanitizedInterval(min))
    }

    @Test
    fun `intervals between 15 and 20 minutes are accepted as-is`() {
        val seventeen = 17L * 60L * 1000L
        assertEquals(seventeen, PeriodicReconciliationDefaults.sanitizedInterval(seventeen))
    }

    @Test
    fun `intervals above 20 minutes are accepted as-is`() {
        val oneHour = 60L * 60L * 1000L
        assertEquals(oneHour, PeriodicReconciliationDefaults.sanitizedInterval(oneHour))
    }

    @Test
    fun `sub-15-minute intervals clamp to the WorkManager minimum with a warning`() {
        // WorkManager silently raises these anyway — the SDK clamps loudly instead.
        for (aggressive in listOf(1L, 60_000L, 5L * 60L * 1000L, min - 1)) {
            assertEquals(min, PeriodicReconciliationDefaults.sanitizedInterval(aggressive))
        }
    }

    @Test
    fun `zero and negative intervals fall back to the default`() {
        assertEquals(def, PeriodicReconciliationDefaults.sanitizedInterval(0L))
        assertEquals(def, PeriodicReconciliationDefaults.sanitizedInterval(-1L))
        assertEquals(def, PeriodicReconciliationDefaults.sanitizedInterval(Long.MIN_VALUE))
    }

    @Test
    fun `effectively-never intervals clamp to the 24h ceiling`() {
        assertEquals(max, PeriodicReconciliationDefaults.sanitizedInterval(7L * 24L * 60L * 60L * 1000L))
        assertEquals(max, PeriodicReconciliationDefaults.sanitizedInterval(Long.MAX_VALUE))
    }

    @Test
    fun `scan durations below 3s clamp up`() {
        assertEquals(
            PeriodicReconciliationDefaults.MINIMUM_SCAN_DURATION_MILLIS,
            PeriodicReconciliationDefaults.sanitizedScanDuration(500L)
        )
    }

    @Test
    fun `scan durations above 30s clamp down`() {
        for (oversized in listOf(31_000L, 60_000L, 500_000L)) {
            assertEquals(
                PeriodicReconciliationDefaults.MAXIMUM_SCAN_DURATION_MILLIS,
                PeriodicReconciliationDefaults.sanitizedScanDuration(oversized)
            )
        }
    }

    @Test
    fun `invalid scan durations fall back to the default`() {
        assertEquals(
            PeriodicReconciliationDefaults.DEFAULT_SCAN_DURATION_MILLIS,
            PeriodicReconciliationDefaults.sanitizedScanDuration(0L)
        )
        assertEquals(
            PeriodicReconciliationDefaults.DEFAULT_SCAN_DURATION_MILLIS,
            PeriodicReconciliationDefaults.sanitizedScanDuration(-5L)
        )
    }

    @Test
    fun `in-range scan durations pass through`() {
        assertEquals(12_000L, PeriodicReconciliationDefaults.sanitizedScanDuration(12_000L))
        assertEquals(3_000L, PeriodicReconciliationDefaults.sanitizedScanDuration(3_000L))
        assertEquals(30_000L, PeriodicReconciliationDefaults.sanitizedScanDuration(30_000L))
    }
}
