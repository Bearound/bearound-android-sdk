package io.bearound.sdk.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.bearound.sdk.models.DataCollectionPolicy
import io.bearound.sdk.models.SDKConfiguration
import io.bearound.sdk.models.SDKInfo
import io.bearound.sdk.models.WifiObservation
import io.bearound.sdk.utilities.OfflineBatchStorage
import io.bearound.sdk.visit.OfflineBatchVisitEventQueue
import io.bearound.sdk.visit.VisitEvent
import io.bearound.sdk.visit.VisitEventKind
import io.bearound.sdk.visit.VisitEventSource
import io.bearound.sdk.visit.VisitFix
import io.bearound.sdk.visit.VisitStateStore
import io.bearound.sdk.visit.VisitStopTracker
import io.bearound.sdk.visit.VisitTestFixtures
import io.bearound.sdk.visit.VisitTestFixtures.ENV_ID
import io.bearound.sdk.visit.VisitTestFixtures.ORIGIN_LAT
import io.bearound.sdk.visit.VisitTestFixtures.ORIGIN_LNG
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The `visit` object of a visit payload, the contract both SDKs send. */
@RunWith(RobolectricTestRunner::class)
class APIClientVisitPayloadTest {
    private val wall = 1_800_000_000_000L
    private val config = SDKConfiguration("fixture-token", "io.fixture")
    private var policy = DataCollectionPolicy.ALL_ENABLED
    private val client = APIClient(config, "http://127.0.0.1:1", { policy }, { true }, { 1_000_000L }, { wall })
    private val fix = VisitFix(ORIGIN_LAT, ORIGIN_LNG, accuracy = 12f, timestamp = wall - 5 * 60_000L)

    private fun payload(event: VisitEvent): JSONObject = client.buildVisitPayload(
        event, SDKInfo(appId = config.appId, build = 1), VisitTestFixtures.device(), null
    )

    @Test
    fun `a fence arrival says what it is, where and when, with the fix accuracy`() {
        val sent = payload(VisitEvent(VisitEventKind.ARRIVAL, fix, ENV_ID))

        val visit = sent.getJSONObject("visit")
        assertEquals("arrival", visit.getString("kind"))
        assertEquals(ENV_ID, visit.getString("environmentId"))
        assertEquals("fence", visit.getString("source"))
        assertEquals(fix.timestamp, visit.getLong("at"))
        // `at` is the instant `location` stands for, never the send time.
        assertEquals(fix.timestamp, sent.getJSONObject("location").getLong("timestamp"))
        assertEquals(12.0, sent.getJSONObject("location").getDouble("accuracy"), 1e-6)
        assertEquals("visit", sent.getString("syncTrigger"))
    }

    @Test
    fun `a departure carries the accuracy too, and an unknown environment is an explicit null`() {
        val sent = payload(VisitEvent(VisitEventKind.DEPARTURE, fix.copy(timestamp = wall), environmentId = null))

        val visit = sent.getJSONObject("visit")
        assertEquals("departure", visit.getString("kind"))
        assertTrue(visit.has("environmentId"))
        assertTrue(visit.isNull("environmentId"))
        assertEquals(wall, visit.getLong("at"))
        assertEquals(12.0, sent.getJSONObject("location").getDouble("accuracy"), 1e-6)
    }

    @Test
    fun `a Wi-Fi arrival without GPS is dated by its first sighting`() {
        val firstSeen = wall - 7 * 60_000L
        val wifis = listOf(WifiObservation(apId = "9f3a1c02b7d4e688", timestamp = firstSeen))
        val sent = payload(VisitEvent(VisitEventKind.ARRIVAL, fix = null, ENV_ID, wifis, VisitEventSource.WIFI))

        val visit = sent.getJSONObject("visit")
        assertEquals("wifi", visit.getString("source"))
        assertEquals(firstSeen, visit.getLong("at"))
        assertFalse(sent.has("location"))
    }

    @Test
    fun `location off drops the visit, Wi-Fi off drops only a Wi-Fi visit`() {
        val fence = payload(VisitEvent(VisitEventKind.ARRIVAL, fix, ENV_ID))
        val wifi = payload(VisitEvent(VisitEventKind.ARRIVAL, fix, ENV_ID,
            listOf(WifiObservation(apId = "9f3a1c02b7d4e688", timestamp = fix.timestamp)), VisitEventSource.WIFI))

        policy = DataCollectionPolicy(advertisingId = true, location = true, wifi = false)
        assertTrue(client.filterPayload(fence).has("visit"))
        assertFalse(client.filterPayload(wifi).has("visit"))

        policy = DataCollectionPolicy(advertisingId = true, location = false, wifi = true)
        assertFalse(client.filterPayload(fence).has("visit"))
        assertFalse(client.filterPayload(fence).has("location"))
    }

    @Test
    fun `each detector path names its source, and the queued payload keeps the block`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = VisitStateStore(context).also { it.clear() }
        val storage = OfflineBatchStorage(context).also { it.currentTenantId = "test-tenant"; it.clearAllBatches() }
        val captured = mutableListOf<JSONObject>()
        val queue = OfflineBatchVisitEventQueue(context, storage, capturePayload = { event ->
            payload(event).also { captured += it }.toString()
        }, drain = {})
        var now = wall
        val tracker = VisitStopTracker(store, queue) { now }
        val ap = WifiObservation(apId = "9f3a1c02b7d4e688", timestamp = wall - 20 * 60_000L)

        tracker.arrive(ENV_ID, fix.copy(timestamp = wall - 30 * 60_000L))
        tracker.depart(ENV_ID, fix.copy(timestamp = wall - 25 * 60_000L))
        tracker.arriveWifi(ENV_ID, ap.timestamp, listOf(ap))
        now += 1
        tracker.departWifi(ENV_ID, wall - 10 * 60_000L, listOf(ap.copy(timestamp = wall - 10 * 60_000L)))

        assertEquals(
            listOf("arrival/fence", "departure/fence", "arrival/wifi", "departure/wifi"),
            captured.map { it.getJSONObject("visit").let { v -> "${v.getString("kind")}/${v.getString("source")}" } }
        )
        assertEquals(4, storage.getBatchCount())
    }
}
