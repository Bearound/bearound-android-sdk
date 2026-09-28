package io.bearound.sdk.visit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.bearound.sdk.visit.VisitTestFixtures.ORIGIN_LAT
import io.bearound.sdk.visit.VisitTestFixtures.ORIGIN_LNG
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VisitControllerGuardsTest {

    @Test
    fun `server refresh knobs are floored and bad values rejected`() {
        assertEquals(900_000L, VisitController.effectiveMaxAgeMs(60.0))
        assertEquals(900_000L, VisitController.effectiveMaxAgeMs(0.0))
        assertEquals(900_000L, VisitController.effectiveMaxAgeMs(-5.0))
        assertEquals(900_000L, VisitController.effectiveMaxAgeMs(Double.NaN))
        assertEquals(900_000L, VisitController.effectiveMaxAgeMs(Double.POSITIVE_INFINITY))
        assertEquals(21_600_000L, VisitController.effectiveMaxAgeMs(21600.0))

        assertEquals(500.0, VisitController.effectiveRefreshAfterMeters(10.0), 0.0)
        assertEquals(500.0, VisitController.effectiveRefreshAfterMeters(-1.0), 0.0)
        assertEquals(500.0, VisitController.effectiveRefreshAfterMeters(Double.NaN), 0.0)
        assertEquals(2500.0, VisitController.effectiveRefreshAfterMeters(2500.0), 0.0)
    }

    @Test
    fun `a tiny maxAgeSeconds does not turn every tick into a fetch`() = runBlocking {
        val store = VisitStateStore(ApplicationProvider.getApplicationContext<Context>())
        store.clear()
        var now = 1_800_000_000_000L
        var fetches = 0
        store.saveConfig(VisitTestFixtures.configBody(maxAgeSeconds = 1.0, refreshAfterMeters = 0.0), "etag-1", now)
        val controller = VisitController(
            store = store,
            fetcher = PlacesConfigFetching { _, _, _ -> fetches++; PlacesFetchResult.NotModified },
            sink = VisitEventSink { VisitSendOutcome.DELIVERED },
            permissions = { VisitPermissions(34, true, true, false, true) },
            locationAllowedByHost = { true },
            // 300 m from the origin: beyond the server's 0 m, inside the 500 m floor.
            lastKnownFix = { VisitFix(ORIGIN_LAT + VisitTestFixtures.metersToLatDegrees(300.0), ORIGIN_LNG, 10f, now) },
            createDetector = { _, tracker -> SoftFenceVisitDetector(store, tracker) },
            clock = { now }
        )

        controller.start()
        now += VisitController.MIN_TICK_INTERVAL_MS
        controller.tick("sync")
        assertEquals(0, fetches)

        now += 15 * 60_000L
        controller.tick("sync")
        assertEquals(1, fetches)
    }

    @Test
    fun `a native plan uses the floored refresh radius`() {
        val config = PlacesConfig(PlacesConfig.Coordinate(ORIGIN_LAT, ORIGIN_LNG), 0.0, 21600.0, true, emptyList())
        val refresh = NativeGeofenceVisitDetector.plan(config).single()
        assertEquals(NativeGeofenceVisitDetector.REFRESH_FENCE_ID, refresh.requestId)
        assertEquals(500f, refresh.radiusMeters)
    }

    @Test
    fun `a guarded visit step never throws into the caller`() = runBlocking {
        val ok = VisitController.runGuarded("test", 1_000L) { throw IllegalStateException("boom") }
        assertFalse(ok)
    }

    @Test
    fun `a guarded visit step is bounded`() = runBlocking {
        val started = System.currentTimeMillis()
        val ok = VisitController.runGuarded("test", 50L) { delay(60_000L) }
        assertFalse(ok)
        assertTrue(System.currentTimeMillis() - started < 5_000L)
    }

    @Test
    fun `a guarded visit step that finishes reports success`() = runBlocking {
        assertTrue(VisitController.runGuarded("test", 1_000L) { })
    }
}
