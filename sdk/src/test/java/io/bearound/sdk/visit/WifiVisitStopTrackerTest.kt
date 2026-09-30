package io.bearound.sdk.visit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.bearound.sdk.models.WifiObservation
import io.bearound.sdk.utilities.OfflineBatchStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class WifiVisitStopTrackerTest {

    private val minute = 60_000L
    private val t0 = 1_800_000_000_000L
    private val env = VisitTestFixtures.ENV_ID
    private var now = t0 + 60 * minute

    private lateinit var context: Context
    private lateinit var store: VisitStateStore
    private lateinit var storage: OfflineBatchStorage
    private lateinit var queue: RecordingVisitEventQueue
    private lateinit var tracker: VisitStopTracker

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = VisitStateStore(context)
        store.clear()
        storage = OfflineBatchStorage(context)
        storage.clearAllBatches()
        queue = RecordingVisitEventQueue()
        tracker = VisitStopTracker(store, queue) { now }
    }

    private fun ap(id: String, at: Long) = WifiObservation(apId = id, rssi = -60, timestamp = at)

    private fun fix(minutes: Long) = VisitFix(-23.561, -46.656, 10f, t0 + minutes * minute)

    @Test
    fun `a Wi-Fi arrival with an open GPS stop only adds the apId and the source`() {
        assertTrue(tracker.arrive(env, fix(0)))
        assertEquals(1, queue.persisted.size)

        assertFalse(tracker.arriveWifi(env, t0 + 6 * minute, listOf(ap("aa", t0 + 6 * minute))))

        assertEquals(1, queue.persisted.size)
        val open = tracker.openStop()!!
        assertEquals(setOf(VisitSource.GPS, VisitSource.WIFI), open.sources)
        assertEquals(listOf("aa"), open.apIds)
        assertEquals(t0, open.arrivedAt)
    }

    @Test
    fun `a Wi-Fi departure does not close a GPS stop`() {
        assertTrue(tracker.arrive(env, fix(0)))
        assertFalse(tracker.departWifi(env, t0 + 20 * minute, listOf(ap("aa", t0 + 20 * minute))))
        assertNotNull(tracker.openStop())

        // Shared stop (GPS also sees it): still not closed by Wi-Fi.
        tracker.arriveWifi(env, t0 + 6 * minute, listOf(ap("aa", t0 + 6 * minute)))
        assertFalse(tracker.departWifi(env, t0 + 20 * minute, listOf(ap("aa", t0 + 20 * minute))))
        assertNotNull(tracker.openStop())
        assertEquals(1, queue.persisted.size)
    }

    @Test
    fun `a GPS departure closes the shared stop and carries fix and matched access points`() {
        tracker.arriveWifi(env, t0, listOf(ap("aa", t0)))
        // GPS arrival is refused for the same environment and joins the stop.
        assertFalse(tracker.arrive(env, fix(6)))
        assertEquals(setOf(VisitSource.GPS, VisitSource.WIFI), tracker.openStop()!!.sources)

        assertTrue(tracker.depart(env, fix(30), listOf(ap("zz", t0 + 29 * minute))))

        assertEquals(2, queue.persisted.size)
        val departure = queue.persisted[1]
        assertEquals(VisitEventKind.DEPARTURE, departure.kind)
        assertEquals(fix(30), departure.fix)
        assertEquals(listOf("aa", "zz"), departure.wifis!!.map { it.apId })
        assertNull(tracker.openStop())
    }

    @Test
    fun `a Wi-Fi stop goes to the queue with matched wifis first and no location`() {
        val queue = OfflineBatchVisitEventQueue(context, storage) {}
        val tracker = VisitStopTracker(store, queue) { now }
        val others = (1..40).map { ap("o%02d".format(it), t0 + 1_000L * it) }

        assertTrue(tracker.arriveWifi(env, t0, listOf(ap("aa", t0 + 5_000L)), others))
        assertTrue(tracker.departWifi(env, t0 + 30 * minute, listOf(ap("aa", t0 + 29 * minute))))

        val records = storage.loadAllRecords()
        assertEquals(listOf("visit", "visit"), records.map { it.syncTrigger })
        records.forEach { assertNull(it.context?.location) }

        val arrival = records[0].context!!.wifis!!
        assertEquals("aa", arrival[0].apId)
        // The event is dated by wifis[0]: the first sighting.
        assertEquals(t0, arrival[0].timestamp)
        assertEquals(25, arrival.size)
        assertEquals(1, arrival.count { it.apId == "aa" })

        val departure = records[1].context!!.wifis!!
        assertEquals("aa", departure[0].apId)
        assertEquals(t0 + 30 * minute, departure[0].timestamp)
        assertNull(tracker.openStop())
    }

    @Test
    fun `a Wi-Fi arrival older than the last departure is refused`() {
        assertTrue(tracker.arriveWifi(env, t0, listOf(ap("aa", t0))))
        assertTrue(tracker.departWifi(env, t0 + 10 * minute, listOf(ap("aa", t0 + 10 * minute))))

        assertFalse(tracker.arriveWifi(env, t0 + 10 * minute, listOf(ap("aa", t0 + 10 * minute))))
        assertEquals(2, queue.persisted.size)
    }

    @Test
    fun `discarding a Wi-Fi-only stop sends no departure and keeps a GPS stop`() {
        tracker.arriveWifi(env, t0, listOf(ap("aa", t0)))
        tracker.discardWifiStop()
        assertNull(tracker.openStop())
        assertEquals(1, queue.persisted.size)

        tracker.arrive(env, fix(40))
        tracker.discardWifiStop()
        assertNotNull(tracker.openStop())
    }

    @Test
    fun `an open stop persisted before Wi-Fi stops still loads as a GPS stop`() {
        val legacy = """{"env":"env-1","arrival":{"lat":-23.561,"lng":-46.656,"t":$t0},"lastInside":{"lat":-23.561,"lng":-46.656,"t":$t0}}"""
        context.getSharedPreferences(VisitStateStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("open_stop", legacy).commit()

        val open = store.openStop!!
        assertEquals(setOf(VisitSource.GPS), open.sources)
        assertEquals(emptyList<String>(), open.apIds)
        assertEquals(t0, open.arrivedAt)
    }

    @Test
    fun `a legacy persisted batch with a location and no wifis still decodes`() {
        val dir = context.getDir("com.bearound.sdk.batches", Context.MODE_PRIVATE)
        val legacy = """
            {"id":"legacy-1","timestamp":$t0,"beacons":[],"syncTrigger":"visit",
             "context":{"location":{"latitude":-23.561,"longitude":-46.656,"accuracy":12.0,
             "timestamp":$t0,"source":"gnss"}}}
        """.trimIndent()
        File(dir, "${t0}_visit_legacy-1.json").writeText(legacy)

        val record = storage.loadAllRecords().single { it.id == "legacy-1" }

        assertEquals(t0, record.context?.location?.timestamp)
        assertNull(record.context?.wifis)
        // And the new shape: no location, wifis present.
        storage.saveVisitEvent(
            VisitEvent(VisitEventKind.ARRIVAL, null, env, listOf(ap("aa", t0)))
        )
        val created = storage.loadAllRecords().single { it.id != "legacy-1" }
        assertNull(created.context?.location)
        assertEquals("aa", created.context?.wifis?.single()?.apId)
    }
}
