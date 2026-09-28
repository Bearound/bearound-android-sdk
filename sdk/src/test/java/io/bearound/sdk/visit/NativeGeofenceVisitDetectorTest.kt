package io.bearound.sdk.visit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.location.Geofence
import io.bearound.sdk.visit.VisitTestFixtures.ENV_ID
import io.bearound.sdk.visit.VisitTestFixtures.ORIGIN_LAT
import io.bearound.sdk.visit.VisitTestFixtures.ORIGIN_LNG
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NativeGeofenceVisitDetectorTest {

    private class FakeRegistrar(private val failure: Throwable? = null) : GeofenceRegistrar {
        val registrations = mutableListOf<List<VisitGeofence>>()
        var removals = 0

        override fun replaceAll(fences: List<VisitGeofence>, onResult: (Throwable?) -> Unit) {
            registrations += fences
            onResult(failure)
        }

        override fun removeAll() {
            removals++
        }
    }

    private lateinit var store: VisitStateStore
    private var now = 1_800_000_000_000L
    private val sent = mutableListOf<VisitEvent>()

    @Before
    fun setUp() {
        store = VisitStateStore(ApplicationProvider.getApplicationContext<Context>())
        store.clear()
    }

    private fun controller(registrar: FakeRegistrar) = VisitController(
        store = store,
        fetcher = PlacesConfigFetching { _, _, _ -> PlacesFetchResult.NotModified },
        sink = VisitEventSink { event -> sent += event; VisitSendOutcome.DELIVERED },
        permissions = { VisitPermissions(34, true, true, true, true) },
        locationAllowedByHost = { true },
        lastKnownFix = { null },
        createDetector = { mode, tracker ->
            when (mode) {
                VisitDetectionMode.NATIVE_GEOFENCE ->
                    NativeGeofenceVisitDetector(registrar, store, tracker, clock = { now }, elapsedRealtime = { 5_000L })
                VisitDetectionMode.SOFT_FENCE -> SoftFenceVisitDetector(store, tracker)
            }
        },
        clock = { now }
    )

    private fun insideFix(timestamp: Long) =
        VisitFix(ORIGIN_LAT + 0.0001, ORIGIN_LNG, accuracy = 10f, timestamp = timestamp)

    @Test
    fun `targets register with DWELL and the minDwellMinutes loitering delay`() = runBlocking {
        store.saveConfig(VisitTestFixtures.configBody(minDwellMinutes = 7), "etag-1", now)
        val registrar = FakeRegistrar()
        controller(registrar).start()

        assertEquals(1, registrar.registrations.size)
        val fences = registrar.registrations.single().associateBy { it.requestId }

        val target = fences.getValue(NativeGeofenceVisitDetector.TARGET_PREFIX + ENV_ID)
        assertEquals(Geofence.GEOFENCE_TRANSITION_DWELL or Geofence.GEOFENCE_TRANSITION_EXIT, target.transitions)
        assertEquals(7 * 60_000, target.loiteringDelayMs)
        assertEquals(100f, target.radiusMeters) // 60 m floored to the platform minimum

        // No minDwellMinutes from the API: the default dwell.
        val other = fences.getValue(NativeGeofenceVisitDetector.TARGET_PREFIX + "env-2")
        assertEquals(SoftFenceVisitDetector.DEFAULT_MIN_DWELL_MINUTES * 60_000, other.loiteringDelayMs)

        val refresh = fences.getValue(NativeGeofenceVisitDetector.REFRESH_FENCE_ID)
        assertEquals(Geofence.GEOFENCE_TRANSITION_EXIT, refresh.transitions)
        assertEquals(2500f, refresh.radiusMeters)

        // The same values reach the Play Services object.
        val play = PlayServicesGeofenceRegistrar.toPlayGeofence(target)
        assertEquals(7 * 60_000, play.loiteringDelay)
        assertEquals(Geofence.GEOFENCE_TRANSITION_DWELL or Geofence.GEOFENCE_TRANSITION_EXIT, play.transitionTypes)
    }

    @Test
    fun `kill switch false registers nothing`() = runBlocking {
        store.saveConfig(VisitTestFixtures.configBody(enabled = false), "etag-1", now)
        val registrar = FakeRegistrar()
        val controller = controller(registrar)
        controller.start()
        controller.onGeofenceSignal(
            GeofenceSignal(Geofence.GEOFENCE_TRANSITION_DWELL, listOf(NativeGeofenceVisitDetector.TARGET_PREFIX + ENV_ID), insideFix(now))
        )

        assertTrue(registrar.registrations.isEmpty())
        assertTrue(registrar.removals >= 1)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `no more than 100 geofences, nearest first`() {
        val places = (0 until 150).map { index ->
            PlacesConfig.Place("env-$index", distanceMeters = (150 - index) * 10.0,
                center = PlacesConfig.Coordinate(ORIGIN_LAT, ORIGIN_LNG), radiusMeters = 150.0, minDwellMinutes = 5)
        }
        val config = PlacesConfig(PlacesConfig.Coordinate(ORIGIN_LAT, ORIGIN_LNG), 2500.0, 21600.0, true, places)

        val fences = NativeGeofenceVisitDetector.plan(config)

        assertEquals(NativeGeofenceVisitDetector.MAX_GEOFENCES, fences.size)
        val targets = fences.mapNotNull { NativeGeofenceVisitDetector.environmentIdOf(it.requestId) }
        assertEquals(99, targets.size)
        assertEquals("env-149", targets.first()) // distance 10 m
        assertTrue("env-50" !in targets) // distance 1000 m, the 100th nearest
    }

    @Test
    fun `DWELL then EXIT sends one arrival and one departure with the real fix times`() = runBlocking {
        store.saveConfig(VisitTestFixtures.configBody(minDwellMinutes = 5), "etag-1", now)
        val controller = controller(FakeRegistrar())
        controller.start()
        val id = NativeGeofenceVisitDetector.TARGET_PREFIX + ENV_ID

        val dwellFix = insideFix(now - 30_000L)
        now += 60_000L
        controller.onGeofenceSignal(GeofenceSignal(Geofence.GEOFENCE_TRANSITION_DWELL, listOf(id), dwellFix))
        // Redelivered DWELL: still one arrival.
        controller.onGeofenceSignal(GeofenceSignal(Geofence.GEOFENCE_TRANSITION_DWELL, listOf(id), dwellFix))

        val exitFix = VisitFix(ORIGIN_LAT + 0.01, ORIGIN_LNG, accuracy = 15f, timestamp = now + 20 * 60_000L)
        now += 25 * 60_000L
        controller.onGeofenceSignal(GeofenceSignal(Geofence.GEOFENCE_TRANSITION_EXIT, listOf(id), exitFix))
        controller.onGeofenceSignal(GeofenceSignal(Geofence.GEOFENCE_TRANSITION_EXIT, listOf(id), exitFix))

        assertEquals(listOf(VisitEventKind.ARRIVAL, VisitEventKind.DEPARTURE), sent.map { it.kind })
        assertEquals(dwellFix.timestamp, sent[0].fix.timestamp)
        assertEquals(exitFix.timestamp, sent[1].fix.timestamp)
        // The departure is placed at the stop, not at the exit fix outside the environment.
        assertEquals(dwellFix.latitude, sent[1].fix.latitude, 1e-9)
        assertEquals("gnss", sent[1].toDeviceLocation().source)
    }

    @Test
    fun `a failed registration falls back to the soft fence on the next tick`() = runBlocking {
        store.saveConfig(VisitTestFixtures.configBody(), "etag-1", now)
        val controller = controller(FakeRegistrar(failure = IllegalStateException("1000")))
        controller.start()
        assertEquals(VisitDetectionMode.NATIVE_GEOFENCE, controller.currentMode)

        now += VisitController.MIN_TICK_INTERVAL_MS
        controller.tick("test")

        assertEquals(VisitDetectionMode.SOFT_FENCE, controller.currentMode)
    }
}
