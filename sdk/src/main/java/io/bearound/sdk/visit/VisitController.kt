package io.bearound.sdk.visit

import android.util.Log
import io.bearound.sdk.models.UserDevice
import io.bearound.sdk.network.HttpException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val sink: VisitEventSink,
    private val permissions: () -> VisitPermissions,
    /** `DataCollectionPolicy.location`: off means no fetch (it sends coordinates) and no event. */
    private val locationAllowedByHost: () -> Boolean,
    /** The platform's last known fix. Never a request for a new one. */
    private val lastKnownFix: () -> VisitFix?,
    private val createDetector: (VisitDetectionMode, VisitStopTracker) -> VisitDetector,
    private val clock: () -> Long = System::currentTimeMillis
) {
    companion object {
        private const val TAG = "BeAroundSDK-Visit"

        /** Floor between two failed config fetches that were not forced by a fence exit. */
        const val FAILED_FETCH_RETRY_MS = 15L * 60 * 1000

        /** The sync timer can fire every 15 s; a visit decision needs nothing that fine. */
        const val MIN_TICK_INTERVAL_MS = 30_000L
    }

    val tracker = VisitStopTracker(store, clock)

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
        mutex.withLock { isStarted = true }
        tick("start", force = true)
    }

    /** Stops visits and removes only what visit detection armed. */
    suspend fun stop() = mutex.withLock {
        isStarted = false
        disarm()
    }

    /** One existing wakeup. Cheap when nothing changed: no fix means no evaluation. */
    suspend fun tick(trigger: String, force: Boolean = false) = mutex.withLock {
        if (!isStarted) return@withLock
        val now = clock()
        if (!force && now - lastTickAt < MIN_TICK_INTERVAL_MS) return@withLock
        lastTickAt = now

        if (!isEligible()) {
            disarm()
            flushOutbox()
            return@withLock
        }

        val fix = lastKnownFix()
        val active = detectorFor(VisitDetectorFactory.choose(permissions(), store.nativeFailedAt, now))
        refreshIfNeeded(fix, forced = false, now = now)
        applyConfig(active, now)
        active.onTick(fix, now)
        flushOutbox()
        Log.d(TAG, "Visit tick ($trigger): mode=${active.mode} fix=${fix != null}")
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
            if (now - cached.fetchedAt >= (config.maxAgeSeconds * 1000).toLong()) {
                due = true
            } else if (fix != null &&
                Geo.distanceMeters(fix.latitude, fix.longitude, config.origin.lat, config.origin.lng) >
                config.refreshAfterMeters
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
     * Delivers the outbox in order. Each event carries its own fix, so a retry never rebuilds
     * the location at send time. Events past the ingest's 24 h capture window are dropped.
     */
    private suspend fun flushOutbox() {
        if (!locationAllowedByHost()) {
            store.clearOutbox()
            return
        }
        val now = clock()
        for (event in store.outbox()) {
            if (now - event.fix.timestamp > VisitStopTracker.OPEN_STOP_MAX_AGE_MS) {
                store.remove(event)
                continue
            }
            when (sink.send(event)) {
                VisitSendOutcome.DELIVERED, VisitSendOutcome.PERMANENT_FAILURE -> store.remove(event)
                VisitSendOutcome.RETRY -> return
            }
        }
    }
}

/**
 * Sends a visit event as an ordinary `/ingest` payload: no beacons, `syncTrigger: "visit"`,
 * and the event's own fix as `location` (real fix time, `source: "gnss"`, D-26). Everything
 * else in the device block is the current snapshot.
 */
internal class IngestVisitEventSink(
    private val deviceSnapshot: () -> UserDevice?,
    private val post: suspend (UserDevice, String) -> Result<Unit>,
    private val permanentHttpCodes: Set<Int>
) : VisitEventSink {

    override suspend fun send(event: VisitEvent): VisitSendOutcome {
        val device = deviceSnapshot() ?: return VisitSendOutcome.RETRY
        val result = post(device.copy(location = event.toDeviceLocation()), VisitEvent.SYNC_TRIGGER)
        return result.fold(
            onSuccess = { VisitSendOutcome.DELIVERED },
            onFailure = { error ->
                val status = (error as? HttpException)?.statusCode
                if (status != null && status in permanentHttpCodes) {
                    VisitSendOutcome.PERMANENT_FAILURE
                } else {
                    VisitSendOutcome.RETRY
                }
            }
        )
    }
}
