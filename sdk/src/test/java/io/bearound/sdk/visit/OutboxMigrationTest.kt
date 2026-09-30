package io.bearound.sdk.visit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.bearound.sdk.utilities.OfflineBatchStorage
import java.io.File
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** One queue for visit events, and the retired outbox migrates on upgrade. */
@RunWith(RobolectricTestRunner::class)
class OutboxMigrationTest {

    private lateinit var context: Context
    private lateinit var store: VisitStateStore
    private lateinit var storage: OfflineBatchStorage

    private val now = 1_800_000_000_000L
    private val arrival = VisitEvent(VisitEventKind.ARRIVAL, VisitFix(-23.561, -46.656, 10f, now - 600_000L), "env-1")
    private val departure = VisitEvent(VisitEventKind.DEPARTURE, VisitFix(-23.562, -46.657, 15f, now - 60_000L, isMocked = false), "env-1")

    private fun prefs() = context.getSharedPreferences(VisitStateStore.PREFS_NAME, Context.MODE_PRIVATE)

    /** What the previous SDK version left behind: a JSON array of events under "outbox". */
    private fun writeLegacyOutbox(vararg events: VisitEvent) {
        val array = JSONArray()
        events.forEach { array.put(it.toJson()) }
        prefs().edit().putString(OutboxMigration.LEGACY_KEY, array.toString()).commit()
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = VisitStateStore(context)
        store.clear()
        File(context.filesDir.parentFile, "app_com.bearound.sdk.batches").deleteRecursively()
        storage = OfflineBatchStorage(context)
    }

    @Test
    fun `pending outbox events move to the stored queue without loss and the key goes`() {
        writeLegacyOutbox(arrival, departure)

        assertEquals(2, OutboxMigration.migrate(context, storage))

        val records = storage.loadAllRecords()
        assertEquals(2, records.size)
        records.forEach {
            assertEquals("visit", it.syncTrigger)
            assertTrue(it.beacons.isEmpty())
        }
        assertEquals(
            setOf(arrival.fix!!.timestamp, departure.fix!!.timestamp),
            records.map { it.context?.location?.timestamp }.toSet()
        )
        assertEquals(
            setOf(arrival.toDeviceLocation(), departure.toDeviceLocation()),
            records.map { it.context?.location }.toSet()
        )
        assertFalse(prefs().contains(OutboxMigration.LEGACY_KEY))

        // Runs on every flush: once migrated it is a no-op, never a duplicate.
        assertEquals(0, OutboxMigration.migrate(context, storage))
        assertEquals(2, storage.getBatchCount())
    }

    @Test
    fun `migration leaves the rest of the visit state untouched`() {
        store.saveConfig(VisitTestFixtures.configBody(), "etag-1", now)
        store.openStop = VisitStateStore.OpenStop("env-1", arrival.fix!!, arrival.fix!!)
        writeLegacyOutbox(arrival)

        OutboxMigration.migrate(context, storage)

        val cached = store.loadConfig()
        assertNotNull(cached)
        assertEquals("etag-1", cached!!.etag)
        assertEquals(now, cached.fetchedAt)
        assertEquals("env-1", store.openStop?.environmentId)
        assertEquals(arrival.fix, store.openStop?.arrival)
    }

    @Test
    fun `an unreadable retired outbox is dropped without breaking anything`() {
        prefs().edit().putString(OutboxMigration.LEGACY_KEY, "{ not an array").commit()

        assertEquals(0, OutboxMigration.migrate(context, storage))
        assertFalse(prefs().contains(OutboxMigration.LEGACY_KEY))
        assertEquals(0, storage.getBatchCount())
    }

    @Test
    fun `visit events from the tracker go to the single stored queue`() {
        var drained = 0
        val queue = OfflineBatchVisitEventQueue(context, storage) { drained++ }
        val tracker = VisitStopTracker(store, queue) { now }

        assertTrue(tracker.arrive("env-1", arrival.fix!!))
        assertTrue(tracker.depart("env-1", departure.fix!!))

        val records = storage.loadAllRecords()
        assertEquals(listOf("visit", "visit"), records.map { it.syncTrigger })
        assertEquals(
            listOf(arrival.fix!!.timestamp, departure.fix!!.timestamp),
            records.mapNotNull { it.context?.location?.timestamp }.sorted()
        )
        assertFalse(prefs().contains(OutboxMigration.LEGACY_KEY))
        assertEquals(0, drained)
    }

    @Test
    fun `discarding pending visits also clears the retired outbox`() {
        writeLegacyOutbox(arrival)
        storage.saveVisitEvent(departure)
        val queue = OfflineBatchVisitEventQueue(context, storage) {}

        queue.discardPending()

        assertEquals(0, storage.getBatchCount())
        assertFalse(prefs().contains(OutboxMigration.LEGACY_KEY))
    }
}
