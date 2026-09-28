package io.bearound.sdk.visit

import org.junit.Assert.assertEquals
import org.junit.Test

class VisitDetectorFactoryTest {

    private val now = 1_800_000_000_000L

    private fun permissions(
        sdkInt: Int,
        fine: Boolean = true,
        background: Boolean = false,
        playServices: Boolean = true
    ) = VisitPermissions(
        sdkInt = sdkInt,
        fineLocation = fine,
        coarseLocation = fine,
        backgroundLocation = background,
        playServicesAvailable = playServices
    )

    @Test
    fun `api 29 plus with background location granted uses native geofences`() {
        assertEquals(
            VisitDetectionMode.NATIVE_GEOFENCE,
            VisitDetectorFactory.choose(permissions(34, background = true), null, now)
        )
    }

    @Test
    fun `api 29 plus with foreground location only uses the soft fence`() {
        assertEquals(
            VisitDetectionMode.SOFT_FENCE,
            VisitDetectorFactory.choose(permissions(29, background = false), null, now)
        )
    }

    @Test
    fun `below api 29 fine location alone uses native geofences`() {
        assertEquals(
            VisitDetectionMode.NATIVE_GEOFENCE,
            VisitDetectorFactory.choose(permissions(28, background = false), null, now)
        )
    }

    @Test
    fun `below api 29 without fine location uses the soft fence`() {
        assertEquals(
            VisitDetectionMode.SOFT_FENCE,
            VisitDetectorFactory.choose(permissions(28, fine = false), null, now)
        )
    }

    @Test
    fun `background location without fine location uses the soft fence`() {
        assertEquals(
            VisitDetectionMode.SOFT_FENCE,
            VisitDetectorFactory.choose(permissions(34, fine = false, background = true), null, now)
        )
    }

    @Test
    fun `no play services uses the soft fence even with background location`() {
        assertEquals(
            VisitDetectionMode.SOFT_FENCE,
            VisitDetectorFactory.choose(permissions(34, background = true, playServices = false), null, now)
        )
    }

    @Test
    fun `a recent native registration failure falls back to the soft fence until the retry window passes`() {
        val granted = permissions(34, background = true)
        assertEquals(
            VisitDetectionMode.SOFT_FENCE,
            VisitDetectorFactory.choose(granted, now - 1_000L, now)
        )
        assertEquals(
            VisitDetectionMode.NATIVE_GEOFENCE,
            VisitDetectorFactory.choose(granted, now - VisitDetectorFactory.NATIVE_RETRY_AFTER_MS, now)
        )
    }
}
