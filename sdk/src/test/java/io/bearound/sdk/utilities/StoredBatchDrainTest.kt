package io.bearound.sdk.utilities

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.bearound.sdk.BeAroundSDK
import io.bearound.sdk.models.Beacon
import io.bearound.sdk.models.DataCollectionPolicy
import io.bearound.sdk.models.DeviceLocation
import io.bearound.sdk.models.UserDevice
import io.bearound.sdk.network.HttpException
import io.bearound.sdk.visit.VisitEvent
import io.bearound.sdk.visit.VisitEventKind
import io.bearound.sdk.visit.VisitFix
import io.bearound.sdk.visit.VisitTestFixtures
import io.bearound.sdk.visit.saveVisitEvent
import java.io.File
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StoredBatchDrainTest {

    private data class Sent(val beacons: List<Beacon>, val device: UserDevice, val syncTrigger: String?)

    private lateinit var storage: OfflineBatchStorage
    private val sent = mutableListOf<Sent>()

    /** Where the device is at DRAIN time: must never be reported for a batch that captured its own. */
    private val freshLocation = DeviceLocation(latitude = 10.0, longitude = 10.0, timestamp = 9_000L, source = "fused")
    private val fresh = VisitTestFixtures.device().copy(location = freshLocation)

    private fun location(lat: Double, at: Long) =
        DeviceLocation(latitude = lat, longitude = -46.656, accuracy = 10f, timestamp = at, source = "fused")

    private fun beacon(minor: Int) = Beacon(
        uuid = UUID.fromString("11111111-2222-3333-4444-555555555555"),
        major = 1,
        minor = minor,
        rssi = -60,
        proximity = Beacon.Proximity.NEAR,
        accuracy = 1.0,
        timestamp = Date()
    )

    private fun drain(respond: (Sent) -> Result<Unit> = { Result.success(Unit) }) = StoredBatchDrain(
        storage = storage,
        permanentHttpCodes = BeAroundSDK.PERMANENT_HTTP_CODES,
        send = { beacons, device, trigger ->
            val request = Sent(beacons, device, trigger)
            sent += request
            respond(request)
        }
    )

    private fun storageDir(context: Context) = File(context.filesDir.parentFile, "app_com.bearound.sdk.batches")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        storageDir(context).deleteRecursively()
        storage = OfflineBatchStorage(context)
    }

    @Test
    fun `each batch goes in its own request with the context captured with it`() = runBlocking {
        val first = location(-23.1, 1_000L)
        val second = location(-23.2, 2_000L)
        storage.saveBatchReturningId(listOf(beacon(1)), null, OfflineBatchStorage.CapturedContext(first, emptyList()))
        Thread.sleep(2)
        storage.saveBatchReturningId(listOf(beacon(2)), null, OfflineBatchStorage.CapturedContext(second, emptyList()))

        assertTrue(drain().drain(storage.loadAllRecords(), fresh, DataCollectionPolicy.ALL_ENABLED))

        assertEquals(2, sent.size)
        assertEquals(listOf(1), sent[0].beacons.map { it.minor })
        assertEquals(first, sent[0].device.location)
        assertEquals(listOf(2), sent[1].beacons.map { it.minor })
        assertEquals(second, sent[1].device.location)
        assertEquals(0, storage.getBatchCount())
    }

    @Test
    fun `a batch captured without a location is not given the drain-time location`() = runBlocking {
        storage.saveBatchReturningId(listOf(beacon(1)), null, OfflineBatchStorage.CapturedContext(null, emptyList()))

        drain().drain(storage.loadAllRecords(), fresh, DataCollectionPolicy.ALL_ENABLED)

        assertNull(sent.single().device.location)
    }

    @Test
    fun `a retried visit keeps syncTrigger visit and its own fix`() = runBlocking {
        val fixAt = System.currentTimeMillis() - 60_000L
        val event = VisitEvent(VisitEventKind.ARRIVAL, VisitFix(-23.561, -46.656, 12f, fixAt), "env-1")
        storage.saveVisitEvent(event)

        // First attempt: network down. The visit stays queued.
        assertFalse(drain { Result.failure(java.io.IOException("offline")) }.drain(storage.loadAllRecords(), fresh, DataCollectionPolicy.ALL_ENABLED))
        assertEquals(1, storage.getBatchCount())

        // The retry.
        assertTrue(drain().drain(storage.loadAllRecords(), fresh, DataCollectionPolicy.ALL_ENABLED))
        val retry = sent.last()
        assertEquals("visit", retry.syncTrigger)
        assertTrue(retry.beacons.isEmpty())
        assertEquals(fixAt, retry.device.location?.timestamp)
        assertEquals("gnss", retry.device.location?.source)
        assertEquals(fresh.wifis, retry.device.wifis)
        assertEquals(0, storage.getBatchCount())
    }

    @Test
    fun `a visit past the 24 h capture window is dropped unsent`() = runBlocking {
        val fixAt = System.currentTimeMillis() - StoredBatchDrain.VISIT_MAX_AGE_MS - 60_000L
        storage.saveVisitEvent(VisitEvent(VisitEventKind.DEPARTURE, VisitFix(-23.561, -46.656, 12f, fixAt), null))

        assertTrue(drain().drain(storage.loadAllRecords(), fresh, DataCollectionPolicy.ALL_ENABLED))

        assertTrue(sent.isEmpty())
        assertEquals(0, storage.getBatchCount())
    }

    @Test
    fun `legacy batches fall back to the drain-time snapshot, up to five per request`() = runBlocking {
        repeat(6) { index ->
            storage.saveBatchReturningId(listOf(beacon(index)))
            Thread.sleep(2)
        }

        drain().drain(storage.loadAllRecords(), fresh, DataCollectionPolicy.ALL_ENABLED)

        assertEquals(listOf(5, 1), sent.map { it.beacons.size })
        sent.forEach {
            assertEquals(freshLocation, it.device.location)
            assertNull(it.syncTrigger)
        }
    }

    @Test
    fun `a corrupt file is quarantined and the drain still sends the rest`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        storage.saveBatchReturningId(listOf(beacon(1)), null, OfflineBatchStorage.CapturedContext(location(-23.1, 1_000L), emptyList()))
        File(storageDir(context), "${System.currentTimeMillis()}_broken.json").writeText("{ not json")

        assertTrue(drain().drain(storage.loadAllRecords(), fresh, DataCollectionPolicy.ALL_ENABLED))

        assertEquals(1, sent.size)
        assertTrue(storageDir(context).listFiles()!!.any { it.extension == "corrupt" })
        assertEquals(0, storage.getBatchCount())
    }

    @Test
    fun `a permanently rejected batch is quarantined and the next one still goes`() = runBlocking {
        storage.saveBatchReturningId(listOf(beacon(1)), null, OfflineBatchStorage.CapturedContext(null, emptyList()))
        Thread.sleep(2)
        storage.saveBatchReturningId(listOf(beacon(2)), null, OfflineBatchStorage.CapturedContext(null, emptyList()))

        val ok = drain { request ->
            if (request.beacons.single().minor == 1) Result.failure(HttpException(422, "bad")) else Result.success(Unit)
        }.drain(storage.loadAllRecords(), fresh, DataCollectionPolicy.ALL_ENABLED)

        assertTrue(ok)
        assertEquals(listOf(1, 2), sent.map { it.beacons.single().minor })
        assertEquals(0, storage.getBatchCount())
    }
}
