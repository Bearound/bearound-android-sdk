package io.bearound.sdk.visit

import io.bearound.sdk.models.DeviceLocation
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GPS visit detection on Android (design 2.7): one interface, two strategies.
 *
 * - [SoftFenceVisitDetector]: compares the last known fix with the target environments on
 *   the wakeups the SDK already has. No permission beyond foreground location, and blind in
 *   background (measured, see `docs/specs/sdk-visit-intelligence/verification.md`, AND1-00).
 * - `NativeGeofenceVisitDetector`: `GeofencingClient` DWELL/EXIT, only when the host
 *   declares and the user grants `ACCESS_BACKGROUND_LOCATION` (D-08).
 *
 * [VisitDetectorFactory] picks one; [VisitController] owns the config (REQ-021), the kill
 * switch (REQ-014) and delivery. A detector only decides WHEN a stop starts and ends and
 * reports it through [VisitStopTracker].
 */
internal interface VisitDetector {
    val mode: VisitDetectionMode

    /**
     * Arms the detector for [config]. `null` means no list was ever fetched (D-22): nothing
     * native is registered. A config with `visitDetectionEnabled = false` disarms it (REQ-014).
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

/**
 * One `/ingest` visit event. Carries the REAL time of the fix, never the send time
 * (REQ-023, D-26): the ingest honours capture times in [-24 h, +5 min], and a qualified
 * visit needs two observations at least 30 s apart, so a stop always produces an arrival
 * AND a departure.
 */
internal data class VisitEvent(
    val kind: VisitEventKind,
    val fix: VisitFix,
    /** The environment that triggered the event. Diagnostic only: the ingest resolves it. */
    val environmentId: String?
) {
    fun toDeviceLocation() = DeviceLocation(
        latitude = fix.latitude,
        longitude = fix.longitude,
        accuracy = fix.accuracy,
        timestamp = fix.timestamp,
        source = LOCATION_SOURCE,
        isMocked = fix.isMocked
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind.wire)
        put("fix", fix.toJson())
        environmentId?.let { put("env", it) }
    }

    companion object {
        const val SYNC_TRIGGER = "visit"
        const val LOCATION_SOURCE = "gnss"

        fun fromJson(json: JSONObject) = VisitEvent(
            kind = if (json.getString("kind") == VisitEventKind.DEPARTURE.wire) VisitEventKind.DEPARTURE else VisitEventKind.ARRIVAL,
            fix = VisitFix.fromJson(json.getJSONObject("fix")),
            environmentId = if (json.has("env")) json.getString("env") else null
        )
    }
}

internal enum class VisitSendOutcome { DELIVERED, PERMANENT_FAILURE, RETRY }

/** Delivers one visit event to `/ingest`. The event's own fix is the payload location. */
internal fun interface VisitEventSink {
    suspend fun send(event: VisitEvent): VisitSendOutcome
}

/**
 * One arrival and one departure per stop, whatever the detector. State lives in
 * [VisitStateStore] so a departure seen by the next process still pairs with the arrival
 * sent by the previous one. Events go to the persisted outbox first (persist-before-send);
 * [VisitController] drains it.
 */
internal class VisitStopTracker(
    private val store: VisitStateStore,
    private val clock: () -> Long
) {
    companion object {
        /** The ingest only honours capture times within the last 24 h. */
        const val OPEN_STOP_MAX_AGE_MS = 24L * 60 * 60 * 1000
    }

    /** The stop whose arrival was sent and whose departure was not, unless it expired. */
    fun openStop(): VisitStateStore.OpenStop? {
        val open = store.openStop ?: return null
        if (clock() - open.arrival.timestamp > OPEN_STOP_MAX_AGE_MS) {
            store.openStop = null
            return null
        }
        return open
    }

    /** @return false when a stop is already open or [fix] predates the last departure. */
    fun arrive(environmentId: String?, fix: VisitFix): Boolean {
        if (openStop() != null) return false
        val lastDeparture = store.lastDepartureAt
        if (lastDeparture != null && fix.timestamp <= lastDeparture) return false
        store.openStop = VisitStateStore.OpenStop(environmentId, arrival = fix, lastInside = fix)
        store.enqueue(VisitEvent(VisitEventKind.ARRIVAL, fix, environmentId))
        return true
    }

    /** Records that the device was still inside the open stop at [fix]. */
    fun touch(fix: VisitFix) {
        val open = openStop() ?: return
        if (fix.timestamp > open.lastInside.timestamp) store.openStop = open.copy(lastInside = fix)
    }

    /**
     * Closes the open stop with [fix] as the departure observation.
     * @return false when no stop is open, it belongs to another environment, or [fix] is
     *         not after the arrival (a redelivered transition).
     */
    fun depart(environmentId: String?, fix: VisitFix): Boolean {
        val open = openStop() ?: return false
        if (environmentId != null && open.environmentId != null && environmentId != open.environmentId) return false
        if (fix.timestamp <= open.arrival.timestamp) return false
        store.openStop = null
        store.lastDepartureAt = fix.timestamp
        store.enqueue(VisitEvent(VisitEventKind.DEPARTURE, fix, open.environmentId))
        return true
    }
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
