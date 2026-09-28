package io.bearound.sdk.visit

import android.util.Log

/**
 * "Soft fence": the no-extra-permission path. On every wakeup the SDK
 * already has (sync timer, scan broadcast, [io.bearound.sdk.background.ScanWatchdogReceiver],
 * [io.bearound.sdk.background.BeaconSyncWorker], app foreground) it compares the platform's
 * LAST KNOWN fix with the target circles. It never requests a fix and never schedules
 * anything of its own.
 *
 * **Measured limit (emulator, API 36):** without
 * `ACCESS_BACKGROUND_LOCATION`, `LocationManager.getLastKnownLocation` returns `null` for
 * every provider as soon as the app leaves the foreground, even with the SDK's
 * `connectedDevice` foreground service running and after a kill + watchdog wake (the process
 * loses the foreground-location capability; the FINE_LOCATION appop rejects the read). So
 * this detector only sees stops while the app is open. It stays cheap and honest: no fix,
 * no evaluation; a stop is only reported from fixes it actually saw.
 *
 * A stop is:
 * - arrival: inside a target for at least `minDwellMinutes`, reported with the FIRST inside
 *   fix (its real position and time);
 * - departure: the first fix outside, reported with the LAST inside fix, so the pair spans
 *   the time the device was actually seen inside.
 */
internal class SoftFenceVisitDetector(
    private val store: VisitStateStore,
    private val tracker: VisitStopTracker
) : VisitDetector {

    companion object {
        private const val TAG = "BeAroundSDK-Visit"

        /** Used when the API does not send `minDwellMinutes`. */
        const val DEFAULT_MIN_DWELL_MINUTES = 5

        /** Coarse (cell) fixes cannot place a device inside a store. */
        const val MAX_FIX_ACCURACY_METERS = 250f

        /** Environments are registered no smaller than this, like the OS geofence floor. */
        const val MIN_TARGET_RADIUS_METERS = 100.0

        /** Leaving needs this much margin beyond the radius, so a noisy fix does not flap. */
        const val EXIT_MARGIN_METERS = 50.0

        /** An unseen gap longer than this restarts the dwell count. */
        const val MAX_CANDIDATE_GAP_MS = 30L * 60 * 1000
    }

    override val mode = VisitDetectionMode.SOFT_FENCE

    private var config: PlacesConfig? = null

    override fun apply(config: PlacesConfig?, now: Long) {
        this.config = config?.takeIf { it.visitDetectionEnabled }
        if (this.config == null) store.softCandidate = null
    }

    override fun onTick(fix: VisitFix?, now: Long) {
        val config = config ?: return
        if (fix == null) return
        val accuracy = fix.accuracy
        if (accuracy != null && accuracy > MAX_FIX_ACCURACY_METERS) return
        // One fix is evaluated once, however many wakeups hand it out.
        val lastFixAt = store.softLastFixAt
        if (lastFixAt != null && fix.timestamp <= lastFixAt) return
        store.softLastFixAt = fix.timestamp

        val open = tracker.openStop()
        if (open != null) {
            val stillInside = config.places.any { place ->
                place.environmentId == open.environmentId &&
                    distanceTo(place, fix) <= radiusOf(place) + EXIT_MARGIN_METERS
            }
            if (stillInside) {
                tracker.touch(fix)
                return
            }
            if (tracker.depart(open.environmentId, open.lastInside)) {
                Log.i(TAG, "Soft fence: departure from ${open.environmentId} (last seen inside at ${open.lastInside.timestamp})")
            }
        }

        val target = config.places
            .filter { distanceTo(it, fix) <= radiusOf(it) }
            .minByOrNull { distanceTo(it, fix) }
        if (target == null) {
            store.softCandidate = null
            return
        }

        val candidate = store.softCandidate
        if (candidate == null ||
            candidate.environmentId != target.environmentId ||
            fix.timestamp - candidate.last.timestamp > MAX_CANDIDATE_GAP_MS
        ) {
            store.softCandidate = VisitStateStore.Candidate(target.environmentId, first = fix, last = fix)
            return
        }

        val dwellMs = (target.minDwellMinutes ?: DEFAULT_MIN_DWELL_MINUTES).coerceAtLeast(1) * 60_000L
        if (fix.timestamp - candidate.first.timestamp < dwellMs) {
            store.softCandidate = candidate.copy(last = fix)
            return
        }

        store.softCandidate = null
        if (tracker.arrive(target.environmentId, candidate.first)) {
            tracker.touch(fix)
            Log.i(TAG, "Soft fence: arrival at ${target.environmentId} (first seen inside at ${candidate.first.timestamp})")
        }
    }

    override fun tearDown() {
        config = null
        store.softCandidate = null
    }

    private fun radiusOf(place: PlacesConfig.Place) = maxOf(place.radiusMeters, MIN_TARGET_RADIUS_METERS)

    private fun distanceTo(place: PlacesConfig.Place, fix: VisitFix) =
        Geo.distanceMeters(fix.latitude, fix.longitude, place.center.lat, place.center.lng)
}
