package io.bearound.sdk.apppresence

import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import io.bearound.sdk.BeAroundSDK
import io.bearound.sdk.background.BackgroundScheduler
import io.bearound.sdk.collectors.AppPresenceCollector
import io.bearound.sdk.collectors.AppPresencePackageLookup
import io.bearound.sdk.interfaces.BeAroundSDKListener
import io.bearound.sdk.models.AppPresenceConfiguration
import io.bearound.sdk.models.AppPresenceConfigurationException
import io.bearound.sdk.models.AppPresenceDeclarations
import io.bearound.sdk.models.AppPresenceDetectionMethod
import io.bearound.sdk.models.AppPresenceReason
import io.bearound.sdk.models.AppPresenceSnapshot
import io.bearound.sdk.models.AppPresenceState
import io.bearound.sdk.models.AppPresenceTarget
import io.bearound.sdk.models.Beacon
import io.bearound.sdk.models.SDKConfiguration
import io.bearound.sdk.models.SDKInfo
import io.bearound.sdk.network.APIClient
import io.bearound.sdk.utilities.AppPresenceFileSystem
import io.bearound.sdk.utilities.AppPresenceReadResult
import io.bearound.sdk.utilities.AppPresenceStorageException
import io.bearound.sdk.utilities.AppPresenceStore
import io.bearound.sdk.utilities.SDKConfigStorage
import io.bearound.sdk.visit.VisitTestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class AppPresenceCoordinatorTest {

    // region fakes

    private class FakeClock(var wall: Long = 1_760_000_000_000L, var elapsed: Long = 1_000_000L) : AppPresenceClock {
        override fun wallMillis(): Long = wall
        override fun elapsedRealtime(): Long = elapsed
        fun advance(millis: Long) {
            wall += millis
            elapsed += millis
        }
    }

    /** In-memory files shared across "processes", with injectable failures. */
    private class MemoryFileSystem : AppPresenceFileSystem {
        val files = HashMap<String, String>()
        var failWrites = false
        var failReads = false

        @Synchronized
        override fun read(name: String): String? {
            if (failReads) throw IOException("read failure")
            return files[name]
        }

        @Synchronized
        override fun writeAtomically(name: String, content: String) {
            if (failWrites) throw IOException("disk full")
            files[name] = content
        }

        override fun <T> withFileLock(block: () -> T): T = block()
    }

    private class RecordingListener : BeAroundSDKListener {
        val snapshots = mutableListOf<AppPresenceSnapshot>()
        val errors = mutableListOf<Exception>()
        override fun onBeaconsUpdated(beacons: List<io.bearound.sdk.models.Beacon>) = Unit
        override fun onAppPresenceUpdated(snapshot: AppPresenceSnapshot) {
            snapshots.add(snapshot)
        }
        override fun onError(error: Exception) {
            errors.add(error)
        }
    }

    private class QueuedPoster : (Runnable) -> Unit {
        val queue = ArrayDeque<Runnable>()
        override fun invoke(runnable: Runnable) {
            queue.addLast(runnable)
        }
        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    // endregion

    private val hostPackage = "io.bearound.host"
    private val clock = FakeClock()
    private val fileSystem = MemoryFileSystem()
    private val store = AppPresenceStore(fileSystem)
    private var bootCount: Int? = 7
    private val lookups = mutableListOf<String>()
    private val installed = mutableSetOf("io.bearound.fixture.presence")
    private var lookupHook: (String) -> Unit = {}
    private val errors = mutableListOf<Exception>()

    private val declaredPackages = listOf("io.bearound.fixture.absent", "io.bearound.fixture.denied", "io.bearound.fixture.presence")
    private fun evidenceJson(applicationId: String = hostPackage) =
        """{"schemaVersion":1,"applicationId":"$applicationId","packages":${declaredPackages.joinToString(",", "[", "]") { "\"$it\"" }}}"""

    private var evidence: String? = evidenceJson()

    private val lookup = AppPresencePackageLookup { packageName ->
        lookups.add(packageName)
        lookupHook(packageName)
        when {
            packageName == "io.bearound.fixture.denied" -> throw SecurityException("denied")
            packageName !in installed -> throw PackageManager.NameNotFoundException(packageName)
        }
    }

    private val config = AppPresenceConfiguration(
        enabled = true,
        targets = listOf(
            AppPresenceTarget("fixture", iosScheme = "bearound-presence-fixture", androidPackageName = "io.bearound.fixture.presence"),
            AppPresenceTarget("absent", androidPackageName = "io.bearound.fixture.absent"),
            AppPresenceTarget("ios-only", iosScheme = "bearound-presence-fixture")
        )
    )

    private fun coordinator(
        poster: (Runnable) -> Unit = { it.run() },
        mainProcess: Boolean = true
    ): AppPresenceCoordinator = AppPresenceCoordinator(
        packageName = hostPackage,
        isMainProcess = mainProcess,
        store = store,
        collector = AppPresenceCollector(
            declarationsLoader = { AppPresenceDeclarations.parse(evidence, hostPackage) },
            packageLookup = lookup,
            wallClock = { clock.wall }
        ),
        clock = clock,
        bootCount = { bootCount },
        executor = { it.run() },
        mainPoster = poster,
        errorSink = { errors.add(it) },
        idGenerator = { UUID.randomUUID().toString() }
    )

    /** A configured, active coordinator with a listener (first install: runs at once). */
    private fun started(
        token: String = "token-A",
        listener: RecordingListener = RecordingListener(),
        configuration: AppPresenceConfiguration = config,
        poster: (Runnable) -> Unit = { it.run() }
    ): Pair<AppPresenceCoordinator, RecordingListener> {
        val coordinator = coordinator(poster)
        coordinator.configure(configuration)
        coordinator.setBusinessToken(token)
        coordinator.setListener(listener)
        coordinator.activate()
        return coordinator to listener
    }

    private fun namespace(token: String) = AppPresenceStore.namespace(hostPackage, token)

    private fun storedRecord(token: String = "token-A") =
        (store.read(namespace(token)) as AppPresenceReadResult.Found).record

    @Before
    fun resetState() {
        lookups.clear()
        errors.clear()
    }

    // region declarations and package lookup

    @Test
    fun `missing evidence makes every Android target unknown without calling PackageManager`() {
        evidence = null
        val (_, listener) = started()

        val snapshot = listener.snapshots.single()
        assertTrue("PackageManager must not be called: $lookups", lookups.isEmpty())
        assertEquals(listOf("fixture", "absent", "ios-only"), snapshot.results.map { it.targetId })
        snapshot.results.take(2).forEach {
            assertEquals(AppPresenceState.UNKNOWN, it.state)
            assertNull(it.present)
            assertEquals(AppPresenceReason.DECLARATION_UNVERIFIED, it.reason)
        }
        assertEquals(AppPresenceReason.UNSUPPORTED_PLATFORM, snapshot.results[2].reason)
        assertEquals(AppPresenceDetectionMethod.NONE, snapshot.results[2].detectionMethod)
    }

    @Test
    fun `evidence for another applicationId is unverified and never yields false`() {
        evidence = evidenceJson(applicationId = "io.bearound.someone.else")
        val (_, listener) = started()

        assertTrue(lookups.isEmpty())
        val absent = listener.snapshots.single().results.first { it.targetId == "absent" }
        assertEquals(AppPresenceState.UNKNOWN, absent.state)
        assertEquals(AppPresenceReason.DECLARATION_UNVERIFIED, absent.reason)
    }

    @Test
    fun `with valid evidence installed is true, NameNotFound is false, SecurityException is unknown`() {
        val configuration = AppPresenceConfiguration(
            enabled = true,
            targets = listOf(
                AppPresenceTarget("denied", androidPackageName = "io.bearound.fixture.denied"),
                AppPresenceTarget("fixture", androidPackageName = "io.bearound.fixture.presence"),
                AppPresenceTarget("undeclared", androidPackageName = "io.bearound.fixture.undeclared"),
                AppPresenceTarget("absent", androidPackageName = "io.bearound.fixture.absent"),
                AppPresenceTarget("fixture-alias", androidPackageName = "io.bearound.fixture.presence")
            )
        )
        val (_, listener) = started(configuration = configuration)

        val snapshot = listener.snapshots.single()
        assertEquals(listOf("denied", "fixture", "undeclared", "absent", "fixture-alias"), snapshot.results.map { it.targetId })
        val byId = snapshot.results.associateBy { it.targetId }
        assertEquals(AppPresenceState.UNKNOWN, byId.getValue("denied").state)
        assertEquals(AppPresenceReason.QUERY_FAILED, byId.getValue("denied").reason)
        assertEquals(true, byId.getValue("fixture").present)
        assertEquals(AppPresenceState.PRESENT, byId.getValue("fixture").state)
        assertEquals(AppPresenceReason.NOT_DECLARED, byId.getValue("undeclared").reason)
        assertEquals(false, byId.getValue("absent").present)
        assertEquals(AppPresenceState.ABSENT, byId.getValue("absent").state)
        assertEquals(true, byId.getValue("fixture-alias").present)
        // Undeclared package never queried; duplicated package queried once.
        assertEquals(listOf("io.bearound.fixture.denied", "io.bearound.fixture.presence", "io.bearound.fixture.absent"), lookups)
        assertEquals(AppPresenceSnapshot.SCHEMA_VERSION, snapshot.schemaVersion)
        assertFalse(snapshot.cached)
        assertTrue(snapshot.checkedAt.endsWith("Z"))
    }

    // endregion

    // region cooldown, restart and clocks

    @Test
    fun `same process cooldown boundary is 3599 s closed and 3600 s open`() {
        val (coordinator, listener) = started()
        assertEquals(1, listener.snapshots.size)

        clock.advance(3_599_000L)
        coordinator.tryRun()
        assertEquals(1, listener.snapshots.size)

        clock.advance(1_000L)
        coordinator.tryRun()
        assertEquals(2, listener.snapshots.size)
        assertTrue("every round is a new snapshot", listener.snapshots[0].snapshotId != listener.snapshots[1].snapshotId)
    }

    @Test
    fun `restart in the same boot reuses the persisted interval`() {
        started()
        val reservedAt = clock.elapsed

        clock.advance(100_000L) // process dies and relaunches 100 s later, same boot
        val (relaunched, listener) = started()
        assertTrue(listener.snapshots.none { !it.cached })

        clock.elapsed = reservedAt + 3_599_000L
        relaunched.tryRun()
        assertTrue(listener.snapshots.none { !it.cached })

        clock.elapsed = reservedAt + 3_600_000L
        relaunched.tryRun()
        assertEquals(1, listener.snapshots.count { !it.cached })
    }

    @Test
    fun `unavailable boot count (API 23) quarantines each new process for one interval`() {
        bootCount = null
        started()

        clock.advance(5 * 3_600_000L) // far past the interval, but nothing proves the same boot
        val launch = clock.elapsed
        val (relaunched, listener) = started()
        assertTrue(listener.snapshots.none { !it.cached })

        clock.elapsed = launch + 3_599_000L
        relaunched.tryRun()
        assertTrue(listener.snapshots.none { !it.cached })

        clock.elapsed = launch + 3_600_000L
        relaunched.tryRun()
        assertEquals(1, listener.snapshots.count { !it.cached })
    }

    @Test
    fun `reboot (new boot count, regressed elapsed) quarantines from launch`() {
        started()

        bootCount = 8
        clock.elapsed = 5_000L
        val (relaunched, listener) = started()
        assertTrue(listener.snapshots.none { !it.cached })

        clock.elapsed = 5_000L + 3_600_000L
        relaunched.tryRun()
        assertEquals(1, listener.snapshots.count { !it.cached })
    }

    @Test
    fun `quarantine after a reboot is persisted so a later relaunch in that boot does not restart it`() {
        started()

        bootCount = 8
        clock.elapsed = 5_000L
        val (_, firstListener) = started() // short session after reboot: quarantined
        assertTrue(firstListener.snapshots.none { !it.cached })
        assertEquals(8, storedRecord().bootCount)
        assertEquals(5_000L, storedRecord().reservedElapsedRealtime)

        clock.elapsed = 5_000L + 3_599_000L // relaunch in the same boot, before the marker ends
        val (early, earlyListener) = started()
        assertTrue(earlyListener.snapshots.none { !it.cached })

        clock.elapsed = 5_000L + 3_600_000L // measured from the first launch, not this one
        early.tryRun()
        assertEquals(1, earlyListener.snapshots.count { !it.cached })
    }

    @Test
    fun `reservation is durable before the first lookup and a crash after it consumes the window`() {
        class SimulatedCrash : Error()
        lookupHook = {
            val record = storedRecord()
            assertTrue("reservation must be persisted before PackageManager", record.hasReservation)
            throw SimulatedCrash()
        }
        try {
            started()
            fail("expected the simulated crash")
        } catch (_: SimulatedCrash) {
            // the process dies here
        }
        lookupHook = {}
        assertNull("no snapshot was completed", storedRecord().lastSnapshot)

        clock.advance(10_000L)
        val (relaunched, listener) = started()
        assertTrue(listener.snapshots.isEmpty())

        clock.advance(3_590_000L)
        relaunched.tryRun()
        assertEquals(1, listener.snapshots.size)
    }

    @Test
    fun `wall clock jumps neither release nor delay the window`() {
        val (coordinator, listener) = started()

        clock.wall += 48 * 3_600_000L
        clock.elapsed += 3_599_000L
        coordinator.tryRun()
        assertEquals(1, listener.snapshots.size)

        clock.wall -= 96 * 3_600_000L
        clock.elapsed += 1_000L
        coordinator.tryRun()
        assertEquals(2, listener.snapshots.size)
    }

    @Test
    fun `corrupt record quarantines and is rewritten as a cooldown marker`() {
        fileSystem.files[AppPresenceStore.fileName(namespace("token-A"))] = "{garbage"
        val launch = clock.elapsed
        val (coordinator, listener) = started()

        assertTrue(listener.snapshots.isEmpty())
        assertTrue(lookups.isEmpty())
        assertTrue(storedRecord().hasReservation)

        clock.elapsed = launch + 3_600_000L
        coordinator.tryRun()
        assertEquals(1, listener.snapshots.size)
    }

    @Test
    fun `storage write failure cancels the round before any lookup and reports once`() {
        fileSystem.failWrites = true
        val (coordinator, listener) = started()
        coordinator.tryRun()

        assertTrue(lookups.isEmpty())
        assertTrue(listener.snapshots.isEmpty())
        val error = errors.single() as AppPresenceStorageException
        assertEquals("app_presence_storage_unavailable", error.code)

        fileSystem.failWrites = false
        coordinator.tryRun()
        assertEquals("recovers once storage is writable", 1, listener.snapshots.size)
    }

    // endregion

    // region isolation, reconfiguration, stop

    @Test
    fun `switching clients A to B to A keeps the reservation of A`() {
        val (coordinator, listener) = started(token = "token-A")
        assertEquals(1, listener.snapshots.size)

        clock.advance(60_000L)
        coordinator.setBusinessToken("token-B")
        assertEquals("B is a separate namespace", 2, listener.snapshots.size)

        clock.advance(60_000L)
        coordinator.setBusinessToken("token-A")
        assertEquals("back to A: still inside A's window", 2, listener.snapshots.size)
        assertTrue(storedRecord("token-A").hasReservation)

        clock.advance(3_600_000L - 120_000L)
        coordinator.tryRun()
        assertEquals(3, listener.snapshots.size)
        fileSystem.files.keys.forEach { assertFalse(it.contains("token")) }
    }

    @Test
    fun `disabling deletes the snapshot but keeps the cooldown`() {
        val (coordinator, _) = started()
        assertNotNull(coordinator.lastSnapshot())

        coordinator.configure(AppPresenceConfiguration(enabled = false, targets = config.targets))
        assertNull(coordinator.lastSnapshot())
        assertNull(storedRecord().lastSnapshot)
        assertTrue(storedRecord().hasReservation)

        coordinator.configure(config)
        assertEquals("re-enabling does not reopen the window", 2, lookups.size)
        assertNull("the deleted snapshot does not come back", coordinator.lastSnapshot())
    }

    @Test
    fun `empty targets behave as disabled`() {
        val (coordinator, _) = started()
        coordinator.configure(AppPresenceConfiguration(enabled = true, targets = emptyList()))

        assertNull(coordinator.lastSnapshot())
        assertNull(storedRecord().lastSnapshot)
        assertTrue(storedRecord().hasReservation)
    }

    @Test
    fun `invalid configuration disables the feature, clears the cache and returns the error`() {
        val (coordinator, _) = started()
        val invalid = AppPresenceConfiguration(
            enabled = true,
            targets = listOf(AppPresenceTarget("dup", androidPackageName = "a.b"), AppPresenceTarget("dup", androidPackageName = "c.d"))
        )

        val error = coordinator.configure(invalid)

        assertEquals("app_presence_invalid_configuration", error?.code)
        assertNull(coordinator.lastSnapshot())
        assertNull(storedRecord().lastSnapshot)
        clock.advance(3_600_000L)
        coordinator.tryRun()
        assertEquals("disabled feature does not query", 2, lookups.size)
    }

    @Test
    fun `new fingerprint hides the incompatible snapshot without resetting the cooldown`() {
        val (coordinator, listener) = started()
        val other = AppPresenceConfiguration(enabled = true, targets = listOf(AppPresenceTarget("absent", androidPackageName = "io.bearound.fixture.absent")))

        coordinator.configure(other)

        assertNull(coordinator.lastSnapshot())
        val late = RecordingListener()
        coordinator.setListener(late)
        assertTrue("no stale replay", late.snapshots.isEmpty())
        assertEquals(1, listener.snapshots.size)

        clock.advance(3_599_000L)
        coordinator.tryRun()
        assertTrue("reconfiguration does not reset the cooldown", late.snapshots.isEmpty())
        clock.advance(1_000L)
        coordinator.tryRun()
        assertEquals(other.targets.map { it.id }, late.snapshots.single().results.map { it.targetId })
        assertEquals(other.targets.map { it.id }, coordinator.lastSnapshot()!!.results.map { it.targetId })
    }

    @Test
    fun `reconfiguration during a round discards it and keeps the reservation consumed`() {
        lateinit var coordinator: AppPresenceCoordinator
        lookupHook = { coordinator.configure(config.copy(targets = config.targets.dropLast(1))) }
        coordinator = coordinator()
        val listener = RecordingListener()
        coordinator.configure(config)
        coordinator.setBusinessToken("token-A")
        coordinator.setListener(listener)
        coordinator.activate()
        lookupHook = {}

        assertTrue("stale round is not delivered", listener.snapshots.isEmpty())
        assertNull(storedRecord().lastSnapshot)
        coordinator.tryRun()
        assertTrue(listener.snapshots.isEmpty())
        clock.advance(3_600_000L)
        coordinator.tryRun()
        assertEquals(1, listener.snapshots.size)
    }

    @Test
    fun `stop cancels pending deliveries and restart respects the reservation`() {
        val poster = QueuedPoster()
        val (coordinator, listener) = started(poster = poster)

        coordinator.pause()
        poster.drain()
        assertTrue("pending live delivery and replay dropped by stop", listener.snapshots.isEmpty())
        assertNotNull("the cache itself survives stop", coordinator.lastSnapshot())

        clock.advance(60_000L)
        coordinator.activate()
        poster.drain()
        assertTrue(listener.snapshots.isEmpty())
        assertEquals("only the first round queried", 2, lookups.size)
    }

    @Test
    fun `configure alone and an inactive session never query`() {
        val coordinator = coordinator()
        coordinator.configure(config)
        coordinator.setBusinessToken("token-A")
        coordinator.tryRun()

        assertTrue(lookups.isEmpty())
        assertTrue(fileSystem.files.isEmpty())
    }

    @Test
    fun `secondary process never runs the feature`() {
        val coordinator = coordinator(mainProcess = false)
        coordinator.configure(config)
        coordinator.setBusinessToken("token-A")
        coordinator.activate()

        assertTrue(lookups.isEmpty())
        assertTrue(fileSystem.files.isEmpty())
    }

    // endregion

    // region cache and replay

    @Test
    fun `new listener gets a cached replay with the original ids and times`() {
        val (coordinator, first) = started()
        val live = first.snapshots.single()

        val late = RecordingListener()
        coordinator.setListener(late)

        val replay = late.snapshots.single()
        assertTrue(replay.cached)
        assertEquals(live.snapshotId, replay.snapshotId)
        assertEquals(live.checkedAt, replay.checkedAt)
        assertEquals(live.configurationFingerprint, replay.configurationFingerprint)
        assertEquals(live.results, replay.results)
        assertEquals(live.copy(cached = true), coordinator.lastSnapshot())
        assertEquals("getter never queries", 2, lookups.size)
    }

    @Test
    fun `event and replay racing for the same listener deliver the snapshot once`() {
        val poster = QueuedPoster()
        val listener = RecordingListener()
        val (coordinator, _) = started(poster = poster, listener = RecordingListener())
        // Live delivery is queued; a new listener assignment queues its replay too.
        coordinator.setListener(listener)
        poster.drain()

        assertEquals(1, listener.snapshots.size)

        // A further assignment of the same listener is a new subscription: one replay.
        coordinator.setListener(listener)
        poster.drain()
        assertEquals(2, listener.snapshots.size)
        assertTrue(listener.snapshots[1].cached)
    }

    @Test
    fun `persisted snapshot replays after relaunch without querying`() {
        started()
        val original = storedRecord().lastSnapshot!!
        lookups.clear()

        clock.advance(30_000L)
        val relaunched = coordinator()
        relaunched.configure(config)
        relaunched.setBusinessToken("token-A")
        val listener = RecordingListener()
        relaunched.setListener(listener)

        assertEquals(original.copy(cached = true), listener.snapshots.single())
        assertEquals(original.snapshotId, relaunched.lastSnapshot()?.snapshotId)
        assertTrue(lookups.isEmpty())
    }

    @Test
    fun `a throwing listener does not break the round or later deliveries`() {
        val throwing = object : BeAroundSDKListener {
            override fun onBeaconsUpdated(beacons: List<io.bearound.sdk.models.Beacon>) = Unit
            override fun onAppPresenceUpdated(snapshot: AppPresenceSnapshot) = throw IllegalStateException("host bug")
        }
        val coordinator = coordinator()
        coordinator.configure(config)
        coordinator.setBusinessToken("token-A")
        coordinator.setListener(throwing)
        coordinator.activate()

        assertNotNull(storedRecord().lastSnapshot)
        val next = RecordingListener()
        coordinator.setListener(next)
        assertEquals(1, next.snapshots.size)
    }

    // endregion

    // region SDK integration

    private var originalCoordinator: AppPresenceCoordinator? = null

    private fun sdkWithTestCoordinator(): Pair<BeAroundSDK, Context> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        BackgroundScheduler._resetForTesting()
        val sdk = BeAroundSDK.getInstance(context)
        sdk.resetNotificationOpenStateForTest(context)
        SDKConfigStorage.clearConfiguration(context)
        originalCoordinator = sdk.appPresenceCoordinator
        sdk.appPresenceCoordinator = coordinator()
        return sdk to context
    }

    private fun tearDownSdk(sdk: BeAroundSDK, context: Context) {
        sdk.stopScanning()
        sdk.listener = null
        SDKConfigStorage.clearConfiguration(context)
        BeAroundSDK::class.java.declaredFields
            .filter { it.type == SDKConfiguration::class.java }
            .forEach { it.isAccessible = true; it.set(sdk, null) }
        originalCoordinator?.let { sdk.appPresenceCoordinator = it }
    }

    @Test
    fun `startScanning runs app presence without Bluetooth permission and nothing reaches the upload payloads`() {
        val (sdk, context) = sdkWithTestCoordinator()
        try {
            val listener = RecordingListener()
            sdk.listener = listener
            sdk.configure(businessToken = "token-sdk")
            sdk.configureAppPresence(config)
            assertTrue("configure alone never queries", lookups.isEmpty())

            sdk.startScanning() // Robolectric grants no BLE/location permission
            shadowOf(Looper.getMainLooper()).idle()

            val snapshot = listener.snapshots.single { !it.cached }
            assertEquals(config.targets.map { it.id }, snapshot.results.map { it.targetId })
            assertEquals(snapshot.copy(cached = true), sdk.getLastAppPresenceSnapshot())

            // The configuration carried by the SDK (and its APIClient) now holds app presence;
            // the ingestion payloads built from it must not carry targets or results.
            val sdkConfig = BeAroundSDK::class.java.declaredFields
                .first { it.type == SDKConfiguration::class.java }
                .apply { isAccessible = true }
                .get(sdk) as SDKConfiguration
            assertTrue(sdkConfig.appPresence.enabled)
            val client = APIClient(sdkConfig)
            val beacon = Beacon(UUID.randomUUID(), 1, 2, -60, Beacon.Proximity.NEAR, 1.0)
            val payloads = listOf(
                client.buildPayload(listOf(beacon), SDKInfo(appId = context.packageName, build = 1), VisitTestFixtures.device(), null),
                client.buildPayload(emptyList(), SDKInfo(appId = context.packageName, build = 1), VisitTestFixtures.device(), null, "register"),
                client.buildDevicePayload(VisitTestFixtures.device())
            ).map { it.toString() }
            val markers = listOf("io.bearound.fixture", "bearound-presence-fixture", "appPresence", "ios-only", snapshot.snapshotId, snapshot.configurationFingerprint)
            payloads.forEach { payload ->
                markers.forEach { marker -> assertFalse("payload leaked $marker", payload.contains(marker)) }
            }
        } finally {
            tearDownSdk(sdk, context)
        }
    }

    @Test
    fun `invalid configuration reaches onError with its code and keeps the SDK configured`() {
        val (sdk, context) = sdkWithTestCoordinator()
        try {
            val listener = RecordingListener()
            sdk.listener = listener
            sdk.configure(businessToken = "token-sdk-invalid")

            sdk.configureAppPresence(AppPresenceConfiguration(enabled = true, targets = listOf(AppPresenceTarget("bad id!"))))
            shadowOf(Looper.getMainLooper()).idle()

            val error = listener.errors.filterIsInstance<AppPresenceConfigurationException>().single()
            assertEquals("app_presence_invalid_configuration", error.code)
            assertTrue(sdk.isConfigured)
            assertNull(sdk.getLastAppPresenceSnapshot())
            assertEquals(AppPresenceConfiguration.DISABLED, SDKConfigStorage.loadAppPresenceConfiguration(context))
        } finally {
            tearDownSdk(sdk, context)
        }
    }

    @Test
    fun `configure keeps the app presence opt-in instead of resetting it`() {
        val (sdk, context) = sdkWithTestCoordinator()
        try {
            sdk.configureAppPresence(config)
            sdk.configure(businessToken = "token-sdk-carry")

            assertEquals(true, SDKConfigStorage.loadConfiguration(context)?.appPresence?.enabled)
            assertEquals(config.targets.size, SDKConfigStorage.loadAppPresenceConfiguration(context).targets.size)
        } finally {
            tearDownSdk(sdk, context)
        }
    }

    // endregion
}
