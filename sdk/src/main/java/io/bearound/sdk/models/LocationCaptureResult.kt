package io.bearound.sdk.models

import android.location.Location
import java.util.Date

/**
 * Outcome of a beacon-triggered location capture window.
 *
 * v2.x: GPS is consulted only when a beacon is in range. When the SDK opens a
 * capture window, it stays open until the first acceptable fix arrives or until
 * the timeout elapses. This struct reports what happened.
 */
data class LocationCaptureResult(
    /** Why the capture window was opened (e.g. "beacon_rising_edge", "stale_refresh"). */
    val reason: String,
    /** The acquired location, if any. `null` if the window closed by timeout with no fix. */
    val location: Location?,
    /** Outcome label (e.g. "fix_acquired_acc=18m", "timeout", "beacons_lost"). */
    val outcome: String,
    /** When the capture window closed. */
    val timestamp: Date = Date()
) {
    val hasFix: Boolean get() = location != null
}
