package io.bearound.sdk.visit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingRequest
import io.bearound.sdk.models.WifiObservation
import io.bearound.sdk.visit.VisitTestFixtures.ENV_ID
import io.bearound.sdk.visit.VisitTestFixtures.ORIGIN_LAT
import io.bearound.sdk.visit.VisitTestFixtures.ORIGIN_LNG
import io.bearound.sdk.visit.VisitTestFixtures.WIFI_ENV_ID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    private val queue = RecordingVisitEventQueue()
    private val sent get() = queue.persisted

    @Before
    fun setUp() {
        store = VisitStateStore(ApplicationProvider.getApplicationContext<Context>())
        store.clear()
    }

    /** Answers each registration with the next scripted result (null is success). */
    private class ScriptedRegistrar(private val results: List<Throwable?>) : GeofenceRegistrar {
        val registrations = mutableListOf<List<VisitGeofence>>()

        override fun replaceAll(fences: List<VisitGeofence>, onResult: (Throwable?) -> Unit) {
            registrations += fences
            onResult(results.getOrNull(registrations.size - 1))
        }

        override fun removeAll() = Unit
    }

    private fun tooMany() = ApiException(Status(GeofenceStatusCodes.GEOFENCE_TOO_MANY_GEOFENCES))

    private fun bigConfig(): PlacesConfig {
        val places = (0 until 150).map { index ->
            PlacesConfig.Place("env-$index", distanceMeters = (150 - index) * 10.0,
                center = PlacesConfig.Coordinate(ORIGIN_LAT, ORIGIN_LNG), radiusMeters = 150.0, minDwellMinutes = 5)
        }
        return PlacesConfig(PlacesConfig.Coordinate(ORIGIN_LAT, ORIGIN_LNG), 2500.0, 21600.0, true, places)
    }

    /** [elapsedRealtime] fixed: every process started after `now` moved is a new boot. */
    private fun controller(
        registrar: GeofenceRegistrar,
        hostWants: () -> Boolean = { true },
        staleRegistrar: GeofenceRegistrar? = null,
        elapsedRealtime: () -> Long = { 5_000L }
    ) = VisitController(
        store = store,
        fetcher = PlacesConfigFetching { _, _, _ -> PlacesFetchResult.NotModified },
        queue = queue,
        permissions = { VisitPermissions(34, true, true, true, true) },
        locationAllowedByHost = { true },
        lastKnownFix = { null },
        createDetector = { mode, tracker ->
            when (mode) {
                VisitDetectionMode.NATIVE_GEOFENCE ->
                    NativeGeofenceVisitDetector(registrar, store, tracker, clock = { now }, elapsedRealtime = elapsedRealtime)
                VisitDetectionMode.SOFT_FENCE -> SoftFenceVisitDetector(store, tracker)
            }
        },
        clock = { now },
        hostWantsVisits = hostWants,
        staleGeofenceRegistrar = { staleRegistrar }
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

        // No minDwellMinutes from the API: the default dwell, two minutes inside the zone.
        val other = fences.getValue(NativeGeofenceVisitDetector.TARGET_PREFIX + "env-2")
        assertEquals(2 * 60_000, other.loiteringDelayMs)

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
    fun `no more than 20 geofences, the refresh fence plus the 19 nearest`() {
        val fences = NativeGeofenceVisitDetector.plan(bigConfig())

        assertEquals(20, NativeGeofenceVisitDetector.MAX_GEOFENCES)
        assertEquals(NativeGeofenceVisitDetector.MAX_GEOFENCES, fences.size)
        assertEquals(NativeGeofenceVisitDetector.REFRESH_FENCE_ID, fences.first().requestId)
        val targets = fences.mapNotNull { NativeGeofenceVisitDetector.environmentIdOf(it.requestId) }
        assertEquals(19, targets.size)
        assertEquals("env-149", targets.first()) // distance 10 m
        assertEquals("env-131", targets.last()) // distance 190 m, the 19th nearest
        assertTrue("env-130" !in targets) // distance 200 m, the 20th nearest
    }

    @Test
    fun `too many geofences halves the set and retries once`() {
        val registrar = ScriptedRegistrar(listOf(tooMany(), null))
        NativeGeofenceVisitDetector(registrar, store, VisitStopTracker(store, RecordingVisitEventQueue()) { now }, clock = { now }, elapsedRealtime = { 5_000L })
            .apply(bigConfig(), now)

        assertEquals(listOf(20, 10), registrar.registrations.map { it.size })
        val retried = registrar.registrations[1]
        assertEquals(NativeGeofenceVisitDetector.REFRESH_FENCE_ID, retried.first().requestId)
        assertEquals(NativeGeofenceVisitDetector.TARGET_PREFIX + "env-149", retried[1].requestId)
        assertNotNull(store.nativeRegistration)
        assertNull(store.nativeFailedAt)
    }

    @Test
    fun `too many geofences twice falls back to the soft fence`() = runBlocking {
        store.saveConfig(VisitTestFixtures.configBody(), "etag-1", now)
        val registrar = ScriptedRegistrar(listOf(tooMany(), tooMany()))
        val controller = controller(registrar)
        controller.start()

        assertEquals(2, registrar.registrations.size) // one retry, never a third attempt
        assertEquals(now, store.nativeFailedAt)
        assertNull(store.nativeRegistration)

        now += VisitController.MIN_TICK_INTERVAL_MS
        controller.tick("test")
        assertEquals(VisitDetectionMode.SOFT_FENCE, controller.currentMode)
    }

    @Test
    fun `any other registration error does not retry`() {
        val registrar = ScriptedRegistrar(listOf(IllegalStateException("1000"), null))
        NativeGeofenceVisitDetector(registrar, store, VisitStopTracker(store, RecordingVisitEventQueue()) { now }, clock = { now })
            .apply(bigConfig(), now)

        assertEquals(1, registrar.registrations.size)
        assertEquals(now, store.nativeFailedAt)
    }

    @Test
    fun `stop removes geofences a previous process armed`() = runBlocking {
        // A previous process registered; this one never ticked, so nothing is in memory.
        store.nativeRegistration = VisitStateStore.NativeRegistration("abc", now - 60_000L, now - 5_000L)
        val stale = FakeRegistrar()
        val controller = controller(FakeRegistrar(), staleRegistrar = stale)

        controller.stop()

        assertEquals(1, stale.removals)
        assertNull(store.nativeRegistration)
    }

    @Test
    fun `a geofence broadcast after the host stopped removes the stale geofences`() = runBlocking {
        store.saveConfig(VisitTestFixtures.configBody(), "etag-1", now)
        store.nativeRegistration = VisitStateStore.NativeRegistration("abc", now - 60_000L, now - 5_000L)
        val registrar = FakeRegistrar()
        val stale = FakeRegistrar()
        val controller = controller(registrar, hostWants = { false }, staleRegistrar = stale)

        controller.start() // the host stopped: start does not arm
        controller.onGeofenceSignal(
            GeofenceSignal(Geofence.GEOFENCE_TRANSITION_DWELL, listOf(NativeGeofenceVisitDetector.TARGET_PREFIX + ENV_ID), insideFix(now))
        )

        assertTrue(registrar.registrations.isEmpty())
        assertEquals(1, stale.removals)
        assertNull(store.nativeRegistration)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `nothing to remove when no registration was persisted`() = runBlocking {
        val stale = FakeRegistrar()
        controller(FakeRegistrar(), staleRegistrar = stale).stop()

        assertEquals(0, stale.removals)
    }

    @Test
    fun `a tick queued before stopScanning cannot re-arm after it`() = runBlocking {
        store.saveConfig(VisitTestFixtures.configBody(), "etag-1", now)
        var hostWants = true
        val registrar = FakeRegistrar()
        val controller = controller(registrar, hostWants = { hostWants })
        controller.start()
        assertEquals(1, registrar.registrations.size)

        // stopScanning(): the flag is saved first, its stop() is still queued behind the tick.
        hostWants = false
        now += VisitController.MIN_TICK_INTERVAL_MS
        controller.tick("sync")
        controller.start() // the queued tickVisitDetectionAwait path on a stopped controller
        controller.stop()

        assertEquals(1, registrar.registrations.size)
        assertTrue(registrar.removals >= 1)
        assertFalse(controller.isStarted)
        assertNull(controller.currentMode)
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
        assertEquals(dwellFix.timestamp, sent[0].fix!!.timestamp)
        assertEquals(exitFix.timestamp, sent[1].fix!!.timestamp)
        // The departure is placed at the stop, not at the exit fix outside the environment.
        assertEquals(dwellFix.latitude, sent[1].fix!!.latitude, 1e-9)
        assertEquals("gnss", sent[1].toDeviceLocation()!!.source)
        // With the accuracy of that position, never a missing one.
        assertEquals(dwellFix.accuracy, sent[1].toDeviceLocation()!!.accuracy)
    }

    @Test
    fun `geofences arm with an initial DWELL and EXIT, never an initial ENTER`() {
        val fences = NativeGeofenceVisitDetector.plan(wifiConfig())
        val request = PlayServicesGeofenceRegistrar.request(fences)

        // Inside: the arrival by DWELL. Outside: the EXIT that closes a stop left while unarmed.
        assertEquals(GeofencingRequest.INITIAL_TRIGGER_DWELL or GeofencingRequest.INITIAL_TRIGGER_EXIT, request.initialTrigger)
        assertEquals(fences.size, request.geofences.size)
    }

    private fun outsideFix(timestamp: Long) =
        VisitFix(ORIGIN_LAT + 0.01, ORIGIN_LNG, accuracy = 12f, timestamp = timestamp)

    @Test
    fun `geofences re-armed with the device already outside close the stop with no departure`() = runBlocking {
        store.saveConfig(VisitTestFixtures.configBody(), "etag-1", now)
        val first = controller(FakeRegistrar())
        first.start()
        val arrival = insideFix(now)
        first.onGeofenceSignal(GeofenceSignal(Geofence.GEOFENCE_TRANSITION_DWELL, listOf(targetId), arrival))

        // Reboot inside the zone, leave, and the next process arms the geofences from outside.
        now += 3 * 60 * 60_000L
        val registrar = FakeRegistrar()
        val rebooted = controller(registrar)
        rebooted.start()
        assertEquals(1, registrar.registrations.size)
        // The initial EXIT of the re-arm, for every fence the device is outside of.
        val other = NativeGeofenceVisitDetector.TARGET_PREFIX + "env-2"
        rebooted.onGeofenceSignal(GeofenceSignal(Geofence.GEOFENCE_TRANSITION_EXIT, listOf(targetId, other), outsideFix(now)))

        // No departure dated at the re-arm, hours after the stay: only the stop is gone.
        assertEquals(listOf(VisitEventKind.ARRIVAL), sent.map { it.kind })
        assertNull(store.openStop)
        assertEquals(arrival.timestamp, store.lastDepartureAt)

        // A redelivered DWELL of that stay does not open it again.
        rebooted.onGeofenceSignal(GeofenceSignal(Geofence.GEOFENCE_TRANSITION_DWELL, listOf(targetId), arrival))
        assertEquals(listOf(VisitEventKind.ARRIVAL), sent.map { it.kind })

        // The next place arrives instead of waiting a day for the stop to expire.
        now += 20 * 60_000L
        rebooted.onGeofenceSignal(GeofenceSignal(Geofence.GEOFENCE_TRANSITION_DWELL, listOf(other), insideFix(now)))
        assertEquals(listOf(VisitEventKind.ARRIVAL, VisitEventKind.ARRIVAL), sent.map { it.kind })
        assertEquals("env-2", sent.last().environmentId)
    }

    @Test
    fun `a stay that outlives a re-arm departs on its EXIT once a DWELL confirms it`() = runBlocking {
        store.saveConfig(VisitTestFixtures.configBody(), "etag-1", now)
        val first = controller(FakeRegistrar())
        first.start()
        val arrival = insideFix(now)
        first.onGeofenceSignal(GeofenceSignal(Geofence.GEOFENCE_TRANSITION_DWELL, listOf(targetId), arrival))

        // Reboot with the device still inside: the re-armed fence counts the dwell again.
        now += 2 * 60 * 60_000L
        val rebooted = controller(FakeRegistrar())
        rebooted.start()
        now += 5 * 60_000L
        // No location with it: the DWELL alone proves the device is inside.
        rebooted.onGeofenceSignal(GeofenceSignal(Geofence.GEOFENCE_TRANSITION_DWELL, listOf(targetId), fix = null))

        now += 40 * 60_000L
        val exitFix = outsideFix(now - 30_000L)
        rebooted.onGeofenceSignal(GeofenceSignal(Geofence.GEOFENCE_TRANSITION_EXIT, listOf(targetId), exitFix))

        assertEquals(listOf(VisitEventKind.ARRIVAL, VisitEventKind.DEPARTURE), sent.map { it.kind })
        assertEquals(exitFix.timestamp, sent[1].fix!!.timestamp)
        assertEquals(arrival.latitude, sent[1].fix!!.latitude, 1e-9)
    }

    @Test
    fun `an EXIT that revives the process with the same geofences armed still departs`() = runBlocking {
        store.saveConfig(VisitTestFixtures.configBody(), "etag-1", now)
        val bootAt = now - 5_000L
        val sameBoot = { now - bootAt }
        val first = controller(FakeRegistrar(), elapsedRealtime = sameBoot)
        first.start()
        val arrival = insideFix(now)
        first.onGeofenceSignal(GeofenceSignal(Geofence.GEOFENCE_TRANSITION_DWELL, listOf(targetId), arrival))

        // The process died; the EXIT broadcast starts a new one in the same boot.
        now += 40 * 60_000L
        val registrar = FakeRegistrar()
        val revived = controller(registrar, elapsedRealtime = sameBoot)
        revived.start()
        val exitFix = outsideFix(now - 30_000L)
        revived.onGeofenceSignal(GeofenceSignal(Geofence.GEOFENCE_TRANSITION_EXIT, listOf(targetId), exitFix))

        assertTrue(registrar.registrations.isEmpty()) // Play Services still holds this set
        assertEquals(listOf(VisitEventKind.ARRIVAL, VisitEventKind.DEPARTURE), sent.map { it.kind })
        assertEquals(exitFix.timestamp, sent[1].fix!!.timestamp)
    }

    @Test
    fun `a stop only Wi-Fi saw is left to the Wi-Fi matcher by the EXIT of a re-arm`() {
        val tracker = VisitStopTracker(store, queue) { now }
        tracker.arriveWifi(ENV_ID, now, listOf(WifiObservation(apId = "9f3a1c02b7d4e688", timestamp = now)))
        store.saveConfig(VisitTestFixtures.configBody(), "etag-1", now)

        // The native detector takes over (background location granted) with the device outside.
        now += 30 * 60_000L
        val native = detector(tracker).also { it.apply(store.loadConfig()!!.config, now) }
        native.onTransition(GeofenceSignal(Geofence.GEOFENCE_TRANSITION_EXIT, listOf(targetId), outsideFix(now)))

        // No departure at the exit fix, dated at the re-arm; the matcher still holds the stop.
        assertEquals(listOf(VisitEventKind.ARRIVAL), sent.map { it.kind })
        assertEquals(setOf(VisitSource.WIFI), store.openStop!!.sources)
    }

    private fun detector(tracker: VisitStopTracker) =
        NativeGeofenceVisitDetector(FakeRegistrar(), store, tracker, clock = { now }, elapsedRealtime = { 5_000L })

    private val targetId = NativeGeofenceVisitDetector.TARGET_PREFIX + ENV_ID

    @Test
    fun `an EXIT with no fix at all still departs, at the delivery time and the stop position`() = runBlocking {
        store.saveConfig(VisitTestFixtures.configBody(), "etag-1", now)
        val controller = controller(FakeRegistrar())
        controller.start()
        val dwellFix = insideFix(now)
        controller.onGeofenceSignal(GeofenceSignal(Geofence.GEOFENCE_TRANSITION_DWELL, listOf(targetId), dwellFix))

        now += 12 * 60_000L
        // No triggering location and no last known fix.
        controller.onGeofenceSignal(GeofenceSignal(Geofence.GEOFENCE_TRANSITION_EXIT, listOf(targetId), fix = null))

        assertEquals(listOf(VisitEventKind.ARRIVAL, VisitEventKind.DEPARTURE), sent.map { it.kind })
        val departure = sent[1].fix!!
        assertEquals(now, departure.timestamp)
        assertEquals(dwellFix.latitude, departure.latitude, 1e-9)
        assertEquals(dwellFix.accuracy, departure.accuracy)
        assertNull(store.openStop)

        // The stop is closed, so the next place arrives instead of waiting a day for it to expire.
        now += 20 * 60_000L
        val other = NativeGeofenceVisitDetector.TARGET_PREFIX + "env-2"
        controller.onGeofenceSignal(GeofenceSignal(Geofence.GEOFENCE_TRANSITION_DWELL, listOf(other), insideFix(now)))
        assertEquals(VisitEventKind.ARRIVAL, sent.last().kind)
        assertEquals("env-2", sent.last().environmentId)
    }

    @Test
    fun `an EXIT whose only fix predates the arrival departs at the delivery time`() {
        val tracker = VisitStopTracker(store, queue) { now }
        val arrival = insideFix(now)
        tracker.arrive(ENV_ID, arrival)
        now += 9 * 60_000L
        val stale = VisitFix(ORIGIN_LAT + 0.01, ORIGIN_LNG, accuracy = 30f, timestamp = arrival.timestamp - 60 * 60_000L)

        detector(tracker).onTransition(GeofenceSignal(Geofence.GEOFENCE_TRANSITION_EXIT, listOf(targetId), stale))

        assertEquals(VisitEventKind.DEPARTURE, sent.last().kind)
        assertEquals(now, sent.last().fix!!.timestamp)
        assertEquals(arrival.latitude, sent.last().fix!!.latitude, 1e-9)
        assertNull(store.openStop)
    }

    @Test
    fun `a stop Wi-Fi opened and GPS joined departs at the last fix inside, not at the exit fix`() {
        val tracker = VisitStopTracker(store, queue) { now }
        tracker.arriveWifi(ENV_ID, now, listOf(WifiObservation(apId = "9f3a1c02b7d4e688", timestamp = now)))
        now += 6 * 60_000L
        val inside = insideFix(now)
        tracker.arrive(ENV_ID, inside) // joins the Wi-Fi stop: no event, the fix becomes its last inside
        assertNull(store.openStop!!.arrival)

        now += 10 * 60_000L
        val exitFix = VisitFix(ORIGIN_LAT + 0.01, ORIGIN_LNG, accuracy = 40f, timestamp = now - 30_000L)
        detector(tracker).onTransition(GeofenceSignal(Geofence.GEOFENCE_TRANSITION_EXIT, listOf(targetId), exitFix))

        val departure = sent.last()
        assertEquals(VisitEventKind.DEPARTURE, departure.kind)
        assertEquals(inside.latitude, departure.fix!!.latitude, 1e-9)
        assertEquals(inside.accuracy, departure.fix!!.accuracy)
        assertEquals(exitFix.timestamp, departure.fix!!.timestamp)
    }

    @Test
    fun `a stop only Wi-Fi saw is left to the Wi-Fi matcher when the EXIT has no fix`() {
        val tracker = VisitStopTracker(store, queue) { now }
        tracker.arriveWifi(ENV_ID, now, listOf(WifiObservation(apId = "9f3a1c02b7d4e688", timestamp = now)))
        now += 10 * 60_000L

        detector(tracker).onTransition(GeofenceSignal(Geofence.GEOFENCE_TRANSITION_EXIT, listOf(targetId), fix = null))

        assertEquals(listOf(VisitEventKind.ARRIVAL), sent.map { it.kind })
        assertNotNull(store.openStop)
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

    private fun wifiConfig(): PlacesConfig {
        store.saveConfig(VisitTestFixtures.configBody(withWifiPlace = true), "etag-1", now)
        return store.loadConfig()!!.config
    }

    @Test
    fun `ENTER is registered only for the place with known APs, DWELL and EXIT for the others`() {
        val fences = NativeGeofenceVisitDetector.plan(wifiConfig()).associateBy { it.requestId }
        val dwellExit = Geofence.GEOFENCE_TRANSITION_DWELL or Geofence.GEOFENCE_TRANSITION_EXIT

        assertEquals(
            Geofence.GEOFENCE_TRANSITION_ENTER or dwellExit,
            fences.getValue(NativeGeofenceVisitDetector.TARGET_PREFIX + WIFI_ENV_ID).transitions
        )
        assertEquals(dwellExit, fences.getValue(NativeGeofenceVisitDetector.TARGET_PREFIX + ENV_ID).transitions)
        assertEquals(dwellExit, fences.getValue(NativeGeofenceVisitDetector.TARGET_PREFIX + "env-2").transitions)
        assertEquals(Geofence.GEOFENCE_TRANSITION_EXIT, fences.getValue(NativeGeofenceVisitDetector.REFRESH_FENCE_ID).transitions)
        // The budget is untouched.
        assertTrue(NativeGeofenceVisitDetector.plan(bigConfig()).size <= NativeGeofenceVisitDetector.MAX_GEOFENCES)
    }

    private inner class WifiHarness {
        val known = "9f3a1c02b7d4e688"
        var nudges = 0
        var cache: List<WifiObservation> = emptyList()
        val tracker = VisitStopTracker(store, queue) { now }
        val detector = NativeGeofenceVisitDetector(
            FakeRegistrar(), store, tracker, clock = { now }, elapsedRealtime = { 5_000L },
            wifi = WifiVisitRunner(tracker, { cache }, allowedByHost = { true }),
            nudgeScan = { nudges++ },
            wifiCache = WifiCacheReader { cache }
        ).also { it.apply(wifiConfig(), now) }
        val id = NativeGeofenceVisitDetector.TARGET_PREFIX + WIFI_ENV_ID

        fun see(apId: String) {
            cache = listOf(WifiObservation(apId = apId, timestamp = now))
        }

        fun signal(transition: Int) = detector.onTransition(GeofenceSignal(transition, listOf(id), fix = null))
    }

    @Test
    fun `ENTER nudges one scan and is never an arrival by itself`() {
        val h = WifiHarness()
        h.see(h.known)

        h.signal(Geofence.GEOFENCE_TRANSITION_ENTER)

        assertEquals(1, h.nudges)
        assertNull(store.openStop)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `ENTER for a place without known APs does not scan`() {
        val h = WifiHarness()
        val other = NativeGeofenceVisitDetector.TARGET_PREFIX + ENV_ID

        h.detector.onTransition(GeofenceSignal(Geofence.GEOFENCE_TRANSITION_ENTER, listOf(other), fix = null))

        assertEquals(0, h.nudges)
    }

    @Test
    fun `DWELL and EXIT are Wi-Fi rounds that open and close the stop`() {
        val h = WifiHarness()
        h.see(h.known)
        h.signal(Geofence.GEOFENCE_TRANSITION_ENTER)
        val enterAt = now

        now += 5 * 60_000L
        h.see(h.known)
        h.signal(Geofence.GEOFENCE_TRANSITION_DWELL)
        assertEquals(listOf(VisitEventKind.ARRIVAL), sent.map { it.kind })
        assertEquals(enterAt, sent[0].timestamp)

        now += 7 * 60_000L
        h.see("ffffffffffffffff")
        h.signal(Geofence.GEOFENCE_TRANSITION_EXIT)
        assertEquals(listOf(VisitEventKind.ARRIVAL, VisitEventKind.DEPARTURE), sent.map { it.kind })
        assertEquals(1, h.nudges) // only ENTER scans
    }

    @Test
    fun `without the background grant the cache is empty and the round is inconclusive`() {
        val h = WifiHarness()
        h.signal(Geofence.GEOFENCE_TRANSITION_ENTER)
        now += 5 * 60_000L
        h.signal(Geofence.GEOFENCE_TRANSITION_DWELL)

        assertNull(store.openStop)
        assertTrue(sent.isEmpty())
    }
}
