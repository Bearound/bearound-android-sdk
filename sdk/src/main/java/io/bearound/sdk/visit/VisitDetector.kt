package io.bearound.sdk.visit

import android.util.Log
import io.bearound.sdk.models.DataCollectionPolicyStore
import io.bearound.sdk.models.DeviceLocation
import io.bearound.sdk.models.WifiObservation
import io.bearound.sdk.utilities.OfflineBatchStorage
import io.bearound.sdk.utilities.WifiCollector
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GPS visit detection on Android: one interface, two strategies.
 *
 * - [SoftFenceVisitDetector]: compares the last known fix with the target environments on
 *   the wakeups the SDK already has. No permission beyond foreground location, and blind in
 *   background (measured on the emulator, API 36).
 * - `NativeGeofenceVisitDetector`: `GeofencingClient` DWELL/EXIT, only when the host
 *   declares and the user grants `ACCESS_BACKGROUND_LOCATION`.
 *
 * [VisitDetectorFactory] picks one; [VisitController] owns the config, the kill
 * switch and delivery. A detector only decides WHEN a stop starts and ends and
 * reports it through [VisitStopTracker].
 */
internal interface VisitDetector {
    val mode: VisitDetectionMode

    /**
     * Arms the detector for [config]. `null` means no list was ever fetched: nothing
     * native is registered. A config with `visitDetectionEnabled = false` disarms it.
     */
    fun apply(config: PlacesConfig?, now: Long)

    /** One existing wakeup, with the last known fix when the platform hands one out. */
    fun onTick(fix: VisitFix?, now: Long)

    /** Removes only what visit detection armed; beacon scanning and the mesh are untouched. */
    fun tearDown()
}

internal enum class VisitDetectionMode { NATIVE_GEOFENCE, SOFT_FENCE }

/** A location fix reduced to what the visit logic needs. [timestamp] is the fix time. */
internal data class VisitFix(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    val timestamp: Long,
    val isMocked: Boolean? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("lat", latitude)
        put("lng", longitude)
        accuracy?.let { put("acc", it.toDouble()) }
        put("t", timestamp)
        isMocked?.let { put("mock", it) }
    }

    companion object {
        fun fromDeviceLocation(location: DeviceLocation) = VisitFix(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            timestamp = location.timestamp,
            isMocked = location.isMocked
        )

        fun fromJson(json: JSONObject) = VisitFix(
            latitude = json.getDouble("lat"),
            longitude = json.getDouble("lng"),
            accuracy = if (json.has("acc")) json.getDouble("acc").toFloat() else null,
            timestamp = json.getLong("t"),
            isMocked = if (json.has("mock")) json.getBoolean("mock") else null
        )
    }
}

internal enum class VisitEventKind(val wire: String) { ARRIVAL("arrival"), DEPARTURE("departure") }

/** Who has seen an open stop: the GPS detectors, the Wi-Fi matcher, or both. */
internal enum class VisitSource(val wire: String) {
    GPS("gps"), WIFI("wifi");

    companion object {
        fun fromWire(wire: String): VisitSource? = values().firstOrNull { it.wire == wire }
    }
}

/**
 * One `/ingest` visit event. Carries the REAL time of the fix, never the send time:
 * the ingest honours capture times in [-24 h, +5 min], and a qualified
 * visit needs two observations at least 30 s apart, so a stop always produces an arrival
 * AND a departure.
 *
 * [fix] is null for a stop the Wi-Fi matcher opened without GPS: the event then goes with no
 * location and [wifis] (matched access points first) dates it. [wifis] null means "not
 * captured": the send-time list rides along, as before.
 */
internal data class VisitEvent(
    val kind: VisitEventKind,
    val fix: VisitFix?,
    /** The environment that triggered the event. Diagnostic only: the ingest resolves it. */
    val environmentId: String?,
    val wifis: List<WifiObservation>? = null
) {
    fun toDeviceLocation() = fix?.let {
        DeviceLocation(
            latitude = it.latitude,
            longitude = it.longitude,
            accuracy = it.accuracy,
            timestamp = it.timestamp,
            source = LOCATION_SOURCE,
            isMocked = it.isMocked
        )
    }

    /** The time the event is about: the fix time, or the first Wi-Fi sighting when there is no fix. */
    val timestamp: Long? get() = fix?.timestamp ?: wifis?.firstOrNull()?.timestamp

    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind.wire)
        fix?.let { put("fix", it.toJson()) }
        environmentId?.let { put("env", it) }
    }

    companion object {
        const val SYNC_TRIGGER = OfflineBatchStorage.VISIT_SYNC_TRIGGER
        const val LOCATION_SOURCE = "gnss"

        fun fromJson(json: JSONObject) = VisitEvent(
            kind = if (json.getString("kind") == VisitEventKind.DEPARTURE.wire) VisitEventKind.DEPARTURE else VisitEventKind.ARRIVAL,
            fix = json.optJSONObject("fix")?.let(VisitFix::fromJson),
            environmentId = if (json.has("env")) json.getString("env") else null
        )
    }
}

/**
 * The `wifis[]` of a Wi-Fi visit event: [matched] first, then [others], up to
 * [WifiCollector.MAX_OBSERVATIONS]. The ingest dates a location-less event by `wifis[0]`, so
 * the lead entry carries [leadAt], the time the matcher decided the event is about.
 */
internal fun visitWifis(
    matched: List<WifiObservation>,
    others: List<WifiObservation>,
    leadAt: Long
): List<WifiObservation> {
    val lead = matched.mapIndexed { index, it -> if (index == 0) it.copy(timestamp = leadAt) else it }
    val seen = matched.map { it.apId }.toSet()
    return (lead + others.filter { it.apId !in seen }.distinctBy { it.apId })
        .take(WifiCollector.MAX_OBSERVATIONS)
}

/**
 * Where visit events wait for `/ingest`: the SDK's single queue (`OfflineBatchStorage`),
 * never a queue of its own.
 */
internal interface VisitEventQueue {
    /** Writes [event] to disk before anything tries to send it (persist-before-send). */
    fun persist(event: VisitEvent): Boolean

    /** Sends what is pending, in order; a transient failure leaves the rest queued. */
    suspend fun flush()

    /** Drops every pending visit event (the host no longer allows location). */
    fun discardPending()
}

/**
 * A visit event as a stored batch: no beacons, `syncTrigger = "visit"`, its own fix as location
 * (none for a Wi-Fi stop without GPS) and its own Wi-Fi observations when it has them.
 */
internal fun OfflineBatchStorage.saveVisitEvent(event: VisitEvent): String? =
    saveBatchReturningId(
        beacons = emptyList(),
        syncTrigger = VisitEvent.SYNC_TRIGGER,
        // wifis null = not captured with the event: the send-time list rides along, as before.
        context = OfflineBatchStorage.CapturedContext(location = event.toDeviceLocation(), wifis = event.wifis)
    )

/**
 * One arrival and one departure per stop, whatever the detector. State lives in
 * [VisitStateStore] so a departure seen by the next process still pairs with the arrival
 * sent by the previous one. Events go to [queue] first (persist-before-send);
 * [VisitController] flushes it.
 */
internal class VisitStopTracker(
    private val store: VisitStateStore,
    private val queue: VisitEventQueue,
    private val clock: () -> Long
) {
    companion object {
        private const val TAG = "BeAroundSDK-Visit"

        /** The ingest only honours capture times within the last 24 h. */
        const val OPEN_STOP_MAX_AGE_MS = 24L * 60 * 60 * 1000
    }

    /** The stop whose arrival was sent and whose departure was not, unless it expired. */
    fun openStop(): VisitStateStore.OpenStop? {
        val open = store.openStop ?: return null
        if (clock() - open.arrivedAt > OPEN_STOP_MAX_AGE_MS) {
            store.openStop = null
            return null
        }
        return open
    }

    /**
     * GPS arrival. A stop the Wi-Fi matcher already opened for the same environment is shared,
     * not duplicated: it gains the GPS source and the fix goes in its departure event.
     * @return false when a stop is already open or [fix] predates the last departure.
     */
    fun arrive(environmentId: String?, fix: VisitFix): Boolean {
        val open = openStop()
        if (open != null) {
            if (environmentId != null && open.environmentId == environmentId && VisitSource.GPS !in open.sources) {
                store.openStop = open.copy(sources = open.sources + VisitSource.GPS, lastInside = fix)
            }
            return false
        }
        val lastDeparture = store.lastDepartureAt
        if (lastDeparture != null && fix.timestamp <= lastDeparture) return false
        store.openStop = VisitStateStore.OpenStop(environmentId, arrival = fix, lastInside = fix)
        persist(VisitEvent(VisitEventKind.ARRIVAL, fix, environmentId))
        return true
    }

    /** Records that the device was still inside the open stop at [fix]. */
    fun touch(fix: VisitFix) {
        val open = openStop() ?: return
        val last = open.lastInside
        if (last == null || fix.timestamp > last.timestamp) store.openStop = open.copy(lastInside = fix)
    }

    /**
     * Closes the open stop with [fix] as the departure observation. A GPS departure closes any
     * stop of the environment, including one the Wi-Fi matcher opened, and carries its matched
     * access points (taken from [observations] when they are there, else named by id at the
     * fix time).
     * @return false when no stop is open, it belongs to another environment, or [fix] is
     *         not after the arrival (a redelivered transition).
     */
    fun depart(environmentId: String?, fix: VisitFix, observations: List<WifiObservation> = emptyList()): Boolean {
        val open = openStop() ?: return false
        if (environmentId != null && open.environmentId != null && environmentId != open.environmentId) return false
        if (fix.timestamp <= open.arrivedAt) return false
        store.openStop = null
        store.lastDepartureAt = fix.timestamp
        val wifis = if (open.apIds.isEmpty()) {
            null
        } else {
            val matched = open.apIds.map { id ->
                observations.firstOrNull { it.apId == id } ?: WifiObservation(apId = id, timestamp = fix.timestamp)
            }
            visitWifis(matched, observations, fix.timestamp)
        }
        persist(VisitEvent(VisitEventKind.DEPARTURE, fix, open.environmentId, wifis))
        return true
    }

    /**
     * Wi-Fi arrival at [at] (the first sighting). With no stop open it opens one and sends an
     * arrival with no location unless the same stop event has a [fix]. With a stop already
     * open in the same environment it only adds the access points and the Wi-Fi source: no event.
     * @return true when an arrival event was produced.
     */
    fun arriveWifi(
        environmentId: String,
        at: Long,
        matched: List<WifiObservation>,
        others: List<WifiObservation> = emptyList(),
        fix: VisitFix? = null
    ): Boolean {
        val apIds = matched.map { it.apId }.distinct()
        val open = openStop()
        if (open != null) {
            if (open.environmentId == environmentId) {
                store.openStop = open.copy(
                    sources = open.sources + VisitSource.WIFI,
                    apIds = (open.apIds + apIds).distinct()
                )
            }
            return false
        }
        val lastDeparture = store.lastDepartureAt
        if (lastDeparture != null && at <= lastDeparture) return false
        store.openStop = VisitStateStore.OpenStop(
            environmentId = environmentId,
            arrival = fix,
            lastInside = fix,
            arrivedAt = at,
            sources = if (fix != null) setOf(VisitSource.GPS, VisitSource.WIFI) else setOf(VisitSource.WIFI),
            apIds = apIds
        )
        persist(VisitEvent(VisitEventKind.ARRIVAL, fix, environmentId, visitWifis(matched, others, at)))
        return true
    }

    /**
     * Wi-Fi departure at [at] (the last sighting). Closes only a stop the Wi-Fi matcher alone
     * holds: a stop the GPS detector also sees is left for its own departure.
     * @return true when a departure event was produced.
     */
    fun departWifi(
        environmentId: String,
        at: Long,
        matched: List<WifiObservation>,
        fix: VisitFix? = null
    ): Boolean {
        val open = openStop() ?: return false
        if (open.environmentId != environmentId || open.sources != setOf(VisitSource.WIFI)) return false
        if (at <= open.arrivedAt) return false
        store.openStop = null
        store.lastDepartureAt = at
        persist(VisitEvent(VisitEventKind.DEPARTURE, fix, environmentId, visitWifis(matched, emptyList(), at)))
        return true
    }

    /**
     * Drops a stop only the Wi-Fi matcher holds, with no departure event: the matcher is off
     * and nothing will ever close it (REQ-019). A stop the GPS detector also sees stays.
     */
    fun discardWifiStop() {
        val open = store.openStop ?: return
        if (open.sources == setOf(VisitSource.WIFI)) store.openStop = null
    }

    private fun persist(event: VisitEvent) {
        if (!queue.persist(event)) Log.e(TAG, "Could not persist visit ${event.kind.wire} (at ${event.timestamp})")
    }
}

/** The platform's cached Wi-Fi scan results. Reading it never asks the radio for a new scan. */
internal fun interface WifiCacheReader {
    fun read(): List<WifiObservation>

    companion object {
        fun of(collector: WifiCollector) = WifiCacheReader { collector.collectCached() }
    }
}

/**
 * Runs the [WifiVisitMatcher] on the wakeups a detector already has and applies its actions
 * through the [VisitStopTracker], so a Wi-Fi stop and a GPS stop are the same stop.
 *
 * Active only while the places config has `visit_detection_enabled` and the host allows both
 * location and Wi-Fi collection (REQ-019); otherwise the matcher state and a Wi-Fi-only open
 * stop are discarded, with no invented departure. Reads only [reader] (cached scan results):
 * it never starts a scan.
 */
internal class WifiVisitRunner(
    private val tracker: VisitStopTracker,
    private val reader: WifiCacheReader,
    private val allowedByHost: () -> Boolean = {
        val policy = DataCollectionPolicyStore.current
        policy.location && policy.wifi
    },
    private val matcher: WifiVisitMatcher = WifiVisitMatcher()
) {
    companion object {
        private const val TAG = "BeAroundSDK-Visit"
    }

    /** One existing wakeup: builds the round from the cached scan results and runs it. */
    fun onWakeup(config: PlacesConfig?, now: Long) {
        if (!isActive(config)) {
            reset(discardStop = true)
            return
        }
        if (config!!.places.none { it.knownApIds.isNotEmpty() }) {
            reset(discardStop = false)
            return
        }
        val observations = reader.read()
        // Conclusive = the platform answered with a fresh, non-empty list (the reader drops old results).
        val round = WifiRound(
            at = observations.maxOfOrNull { it.timestamp } ?: now,
            observations = observations,
            conclusive = observations.isNotEmpty()
        )
        onRound(round, config)
    }

    /** Runs an already built [round] (for callers that take their own round, e.g. a geofence transition). */
    fun onRound(round: WifiRound, config: PlacesConfig?) {
        if (!isActive(config)) {
            reset(discardStop = true)
            return
        }
        for (action in matcher.onRound(round, config!!.places)) {
            when (action) {
                is WifiVisitAction.Arrive -> if (tracker.arriveWifi(action.environmentId, action.at, action.observations, round.observations)) {
                    Log.i(TAG, "Wi-Fi: arrival at ${action.environmentId} (first seen at ${action.at})")
                }
                is WifiVisitAction.Depart -> if (tracker.departWifi(action.environmentId, action.at, action.observations)) {
                    Log.i(TAG, "Wi-Fi: departure from ${action.environmentId} (last seen at ${action.at})")
                }
            }
        }
    }

    /** Forgets the candidate and, with [discardStop], the Wi-Fi-only open stop. No event. */
    fun reset(discardStop: Boolean = true) {
        matcher.reset()
        if (discardStop) tracker.discardWifiStop()
    }

    private fun isActive(config: PlacesConfig?) =
        config != null && config.visitDetectionEnabled && allowedByHost()
}

internal object Geo {
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val radius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * radius * atan2(sqrt(a), sqrt(1 - a))
    }
}
