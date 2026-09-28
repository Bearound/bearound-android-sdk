package io.bearound.sdk.visit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.bearound.sdk.BeAroundSDK
import io.bearound.sdk.models.SDKConfiguration
import io.bearound.sdk.models.SDKInfo
import io.bearound.sdk.network.APIClient
import io.bearound.sdk.visit.VisitTestFixtures.ORIGIN_LAT
import io.bearound.sdk.visit.VisitTestFixtures.ORIGIN_LNG
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SoftFenceVisitDetectorTest {

    private lateinit var store: VisitStateStore
    private var now = 1_800_000_000_000L
    private var currentFix: VisitFix? = null
    private var fetches = 0
    private val payloads = mutableListOf<JSONObject>()

    private val apiClient = APIClient(SDKConfiguration(businessToken = "token", appId = "app"))
    private val sdkInfo = SDKInfo(appId = "app", build = 1)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = VisitStateStore(context)
        store.clear()
    }

    private fun controller(): VisitController {
        val sink = IngestVisitEventSink(
            deviceSnapshot = { VisitTestFixtures.device() },
            post = { device, trigger ->
                payloads += apiClient.buildPayload(emptyList(), sdkInfo, device, null, trigger)
                Result.success(Unit)
            },
            permanentHttpCodes = BeAroundSDK.PERMANENT_HTTP_CODES
        )
        return VisitController(
            store = store,
            fetcher = PlacesConfigFetching { _, _, _ -> fetches++; PlacesFetchResult.NotModified },
            sink = sink,
            permissions = { VisitPermissions(34, true, true, false, true) },
            locationAllowedByHost = { true },
            lastKnownFix = { currentFix },
            createDetector = { mode, tracker ->
                assertEquals(VisitDetectionMode.SOFT_FENCE, mode)
                SoftFenceVisitDetector(store, tracker)
            },
            clock = { now }
        )
    }

    /** A fix [northMeters] north of the environment, taken [fixAgeMs] before the tick. */
    private fun fixAt(northMeters: Double, fixAgeMs: Long = 20_000L) = VisitFix(
        latitude = ORIGIN_LAT + VisitTestFixtures.metersToLatDegrees(northMeters),
        longitude = ORIGIN_LNG,
        accuracy = 12f,
        timestamp = now - fixAgeMs
    )

    private fun tickAt(minutes: Long, fix: VisitFix, controller: VisitController) = runBlocking {
        now = 1_800_000_000_000L + minutes * 60_000L
        currentFix = fix.copy(timestamp = now - 20_000L)
        controller.tick("test", force = true)
        currentFix!!
    }

    @Test
    fun `a stop produces exactly two visit payloads carrying the real fix times`() = runBlocking {
        store.saveConfig(VisitTestFixtures.configBody(minDwellMinutes = 5), "etag-1", now)
        val controller = controller()
        controller.start()

        val firstInside = tickAt(0, fixAt(10.0), controller)
        tickAt(3, fixAt(20.0), controller)
        tickAt(6, fixAt(15.0), controller) // dwell reached: arrival
        val lastInside = tickAt(10, fixAt(30.0), controller)
        tickAt(12, fixAt(600.0), controller) // outside: departure
        tickAt(14, fixAt(700.0), controller) // still outside: nothing new

        assertEquals(2, payloads.size)
        payloads.forEach { payload ->
            assertEquals("visit", payload.getString("syncTrigger"))
            assertEquals(0, payload.getJSONArray("beacons").length())
            assertEquals("gnss", payload.getJSONObject("location").getString("source"))
        }
        val arrival = payloads[0].getJSONObject("location")
        val departure = payloads[1].getJSONObject("location")
        assertEquals(firstInside.timestamp, arrival.getLong("timestamp"))
        assertEquals(lastInside.timestamp, departure.getLong("timestamp"))
        assertEquals(firstInside.latitude, arrival.getDouble("latitude"), 1e-9)
        // Real fix times, not the send time.
        assertNotEquals(1_800_000_000_000L + 6 * 60_000L, arrival.getLong("timestamp"))
        assertNotEquals(1_800_000_000_000L + 12 * 60_000L, departure.getLong("timestamp"))
        assertEquals(0, store.outbox().size)
        assertEquals(0, fetches)
    }

    @Test
    fun `refresh is fetched only beyond refreshAfterMeters from the origin`() = runBlocking {
        store.saveConfig(VisitTestFixtures.configBody(refreshAfterMeters = 2500.0), "etag-1", now)
        val controller = controller()
        controller.start()
        assertEquals(0, fetches)

        tickAt(1, fixAt(2499.0), controller)
        assertEquals(0, fetches)

        tickAt(2, fixAt(2501.0), controller)
        assertEquals(1, fetches)
    }

    @Test
    fun `kill switch false sends nothing`() = runBlocking {
        store.saveConfig(VisitTestFixtures.configBody(enabled = false, minDwellMinutes = 1), "etag-1", now)
        val controller = controller()
        controller.start()

        tickAt(0, fixAt(10.0), controller)
        tickAt(2, fixAt(10.0), controller)
        tickAt(4, fixAt(900.0), controller)

        assertEquals(0, payloads.size)
    }
}
