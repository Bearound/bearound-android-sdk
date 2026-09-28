package io.bearound.sdk.visit

import android.content.Context
import android.util.Log
import io.bearound.sdk.utilities.OfflineBatchStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns everything around the detectors: the places config and its refresh (REQ-021), the kill
 * switch (REQ-014, D-14), which detector runs ([VisitDetectorFactory]) and delivery of the
 * events the detectors produce.
 *
 * Driven only by wakeups that already exist; it schedules nothing. Every entry point is
 * serialized by one mutex, so a tick and a geofence broadcast never interleave.
 */
internal class VisitController(
    private val store: VisitStateStore,
    @Volatile var fetcher: PlacesConfigFetching,
    /** Where the tracker persists events and [flushQueue] sends them from. */
    private val queue: VisitEventQueue,
    private val permissions: () -> VisitPermissions,
    /** `DataCollectionPolicy.location`: off means no fetch (it sends coordinates) and no event. */
    private val locationAllowedByHost: () -> Boolean,
    /** The platform's last known fix. Never a request for a new one. */
    private val lastKnownFix: () -> VisitFix?,
    private val createDetector: (VisitDetectionMode, VisitStopTracker) -> VisitDetector,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * The host's persisted scanning intent, read again under the mutex: stopScanning() saves
     * it before it stops visits, so a tick queued before the stop cannot re-arm after it.
     */
    private val hostWantsVisits: () -> Boolean = { true },
    /** Removes geofences a previous process armed (see [NativeGeofenceVisitDetector.removeStaleRegistration]). */
    private val staleGeofenceRegistrar: () -> GeofenceRegistrar? = { null }
) {
    companion object {
        private const val TAG = "BeAroundSDK-Visit"

        /** Floor between two failed config fetches that were not forced by a fence exit. */
        const val FAILED_FETCH_RETRY_MS = 15L * 60 * 1000

        /** The sync timer can fire every 15 s; a visit decision needs nothing that fine. */
        const val MIN_TICK_INTERVAL_MS = 30_000L

        /** Floors for the server's refresh knobs, so a bad config cannot turn every tick into a fetch. */
        const val MIN_MAX_AGE_SECONDS = 900.0
        const val MIN_REFRESH_AFTER_METERS = 500.0

        /** `maxAgeSeconds` in ms, floored at [MIN_MAX_AGE_SECONDS]; NaN, infinite or negative read as the floor. */
        fun effectiveMaxAgeMs(maxAgeSeconds: Double): Long {
            val seconds = if (maxAgeSeconds.isFinite() && maxAgeSeconds >= 0) maxAgeSeconds else MIN_MAX_AGE_SECONDS
            return (seconds.coerceAtLeast(MIN_MAX_AGE_SECONDS) * 1000).toLong()
        }

        /** `refreshAfterMeters` floored at [MIN_REFRESH_AFTER_METERS]; NaN, infinite or negative read as the floor. */
        fun effectiveRefreshAfterMeters(refreshAfterMeters: Double): Double {
            val meters = if (refreshAfterMeters.isFinite() && refreshAfterMeters >= 0) refreshAfterMeters else MIN_REFRESH_AFTER_METERS
            return meters.coerceAtLeast(MIN_REFRESH_AFTER_METERS)
        }

        /**
         * Runs one visit step inside someone else's execution window (the sync worker, a
         * receiver's `goAsync()`): bounded by [timeoutMs], and a failure is logged, never
         * thrown, so visit code cannot take the caller's own work down with it. Cancellation
         * of the caller still propagates.
         * @return true when [block] finished in time without failing.
         */
        suspend fun runGuarded(trigger: String, timeoutMs: Long, block: suspend () -> Unit): Boolean {
            val outcome = runCatching { withTimeoutOrNull(timeoutMs) { block() } }
            outcome.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
                Log.w(TAG, "Visit step ($trigger) failed: ${error.message}")
                return false
            }
            if (outcome.getOrNull() == null) {
                Log.w(TAG, "Visit step ($trigger) timed out after ${timeoutMs}ms")
                return false
            }
            return true
        }
    }

    val tracker = VisitStopTracker(store, queue, clock)

    private val mutex = Mutex()

    @Volatile
    var isStarted = false
        private set

    private var detector: VisitDetector? = null
    private var applied = false
    private var appliedConfig: PlacesConfig? = null
    private var lastTickAt = 0L

    val currentMode: VisitDetectionMode? get() = detector?.mode

    suspend fun start() {
        val started = mutex.withLock {
            if (!hostWantsVisits()) return@withLock false
            isStarted = true
            true
        }
        if (started) tick("start", force = true)
    }

    /** Stops visits and removes only what visit detection armed, in this process or a previous one. */
    suspend fun stop() = mutex.withLock {
        isStarted = false
        disarm()
        removeStaleGeofences()
    }

    /** One existing wakeup. Cheap when nothing changed: no fix means no evaluation. */
    suspend fun tick(trigger: String, force: Boolean = false) = mutex.withLock {
        if (!isStarted) return@withLock
        if (!hostWantsVisits()) {
            // stopScanning() already saved the flag; its stop() may still be queued.
            isStarted = false
            disarm()
            return@withLock
        }
        val now = clock()
        if (!force && now - lastTickAt < MIN_TICK_INTERVAL_MS) return@withLock
        lastTickAt = now

        if (!isEligible()) {
            disarm()
            flushQueue()
            return@withLock
        }

        val fix = lastKnownFix()
        val active = detectorFor(VisitDetectorFactory.choose(permissions(), store.nativeFailedAt, now))
        refreshIfNeeded(fix, forced = false, now = now)
        applyConfig(active, now)
        active.onTick(fix, now)
        flushQueue()
        Log.d(TAG, "Visit tick ($trigger): mode=${active.mode} fix=${fix != null}")
    }

    /**
     * A geofence transition delivered to [VisitGeofenceReceiver], possibly into a process the
     * broadcast just revived. Handled only while the native detector is the one chosen.
     */
    suspend fun onGeofenceSignal(signal: GeofenceSignal) = mutex.withLock {
        if (!isStarted || !hostWantsVisits() || !isEligible()) {
            // A transition for geofences nobody should hold any more: remove them.
            disarm()
            removeStaleGeofences()
            return@withLock
        }
        val now = clock()
        val active = detectorFor(VisitDetectorFactory.choose(permissions(), store.nativeFailedAt, now))
        applyConfig(active, now)
        val native = active as? NativeGeofenceVisitDetector ?: run {
            // The soft fence runs now; geofences left by an earlier native run must go.
            removeStaleGeofences()
            return@withLock
        }
        if (store.loadConfig()?.config?.visitDetectionEnabled != true) return@withLock

        val fix = signal.fix ?: lastKnownFix()
        if (native.onTransition(signal.copy(fix = fix))) {
            // Left the refresh fence (REQ-021): fetch around the exit fix and re-arm.
            refreshIfNeeded(fix, forced = true, now = now)
            applyConfig(native, now)
        }
        flushQueue()
    }

    private fun isEligible(): Boolean = locationAllowedByHost() && permissions().anyLocation

    private fun detectorFor(mode: VisitDetectionMode): VisitDetector {
        val current = detector
        if (current != null && current.mode == mode) return current
        current?.tearDown()
        applied = false
        appliedConfig = null
        return createDetector(mode, tracker).also {
            detector = it
            Log.i(TAG, "Visit detection mode: $mode")
        }
    }

    private fun applyConfig(active: VisitDetector, now: Long) {
        val config = store.loadConfig()?.config
        if (applied && config == appliedConfig) return
        active.apply(config, now)
        applied = true
        appliedConfig = config
    }

    private fun removeStaleGeofences() {
        NativeGeofenceVisitDetector.removeStaleRegistration(store, staleGeofenceRegistrar)
    }

    private fun disarm() {
        detector?.tearDown()
        detector = null
        applied = false
        appliedConfig = null
    }

    /**
     * REQ-021: fetch again when the device is more than `refreshAfterMeters` from the origin
     * of the last fetch, or `maxAgeSeconds` passed. Needs a fix (the request carries it); a
     * failure keeps the last list and the last kill-switch value (D-22).
     */
    private suspend fun refreshIfNeeded(fix: VisitFix?, forced: Boolean, now: Long) {
        val cached = store.loadConfig()
        var due = forced || cached == null
        if (cached != null) {
            val config = cached.config
            if (now - cached.fetchedAt >= effectiveMaxAgeMs(config.maxAgeSeconds)) {
                due = true
            } else if (fix != null &&
                Geo.distanceMeters(fix.latitude, fix.longitude, config.origin.lat, config.origin.lng) >
                effectiveRefreshAfterMeters(config.refreshAfterMeters)
            ) {
                due = true
            }
        }
        if (!due) return
        if (!forced) {
            val lastFailure = store.lastFailedFetchAt
            if (lastFailure != null && now - lastFailure < FAILED_FETCH_RETRY_MS) return
        }
        if (fix == null) return

        when (val result = fetcher.fetch(fix.latitude, fix.longitude, cached?.etag)) {
            is PlacesFetchResult.Updated -> {
                store.saveConfig(result.body, result.etag, clock())
                store.lastFailedFetchAt = null
                Log.i(TAG, "Places config updated: ${result.config.places.size} environment(s), " +
                    "enabled=${result.config.visitDetectionEnabled}")
            }
            PlacesFetchResult.NotModified -> {
                store.touchConfig(clock())
                store.lastFailedFetchAt = null
            }
            is PlacesFetchResult.Failed -> {
                store.lastFailedFetchAt = now
                Log.w(TAG, "Places config fetch failed, keeping the last list: ${result.error.message}")
            }
        }
    }

    /**
     * Delivers pending visit events in order. Each event carries its own fix, so a retry never
     * rebuilds the location at send time; the drain drops events past the ingest's 24 h
     * capture window.
     */
    private suspend fun flushQueue() {
        if (!locationAllowedByHost()) {
            queue.discardPending()
            return
        }
        queue.flush()
    }
}

/**
 * The production [VisitEventQueue]: visit events are batches of the SDK's single queue,
 * [OfflineBatchStorage] (sdk-visit-cohesion REQ-011), sent as ordinary `/ingest` payloads
 * with no beacons, `syncTrigger: "visit"` and the event's own fix as `location` (real fix
 * time, `source: "gnss"`, D-26). [drain] sends the pending visit batches.
 *
 * Each flush first moves what an older SDK left in the retired outbox ([OutboxMigration]),
 * so the first flush after an upgrade delivers it.
 */
internal class OfflineBatchVisitEventQueue(
    private val context: Context,
    private val storage: OfflineBatchStorage,
    private val drain: suspend () -> Unit
) : VisitEventQueue {

    override fun persist(event: VisitEvent): Boolean = storage.saveVisitEvent(event) != null

    override suspend fun flush() {
        OutboxMigration.migrate(context, storage)
        drain()
    }

    override fun discardPending() {
        OutboxMigration.discard(context)
        storage.removeVisitBatches()
    }
}
