package io.bearound.sdk.utilities

import android.util.Log
import java.util.ArrayDeque

/**
 * Preventive guard for the OS BLE scan-start quota (5 starts per 30 s per app — exceeding it
 * leaves the scan client registered but permanently SILENT, with no error on most versions).
 *
 * The SDK's duty cycle alone runs at 4 starts/30 s in MEDIUM precision; its own side events
 * (watchdog restarts, batch revive, foreground↔background flips, region enter) consume the
 * remaining headroom and silently starve scanners in perfectly normal usage. Every internal
 * `startScan` call must pass through [tryAcquire]; callers that get `false` should SKIP this
 * start and let their natural retry (duty cycle tick / watchdog / liveness) re-attempt —
 * skipping one cycle is recoverable, a starved client is not.
 *
 * [freeze] implements the cool-off demanded by `SCAN_FAILED_SCANNING_TOO_FREQUENTLY`
 * (Android 11+): all starts are denied until the freeze elapses.
 */
object ScanStartBudget {

    private const val TAG = "BeAroundSDK-ScanBudget"

    /** One below the OS limit of 5, leaving headroom for the host app's own scans. */
    private const val MAX_STARTS = 4
    private const val WINDOW_MS = 30_000L

    private val startTimes = ArrayDeque<Long>()

    @Volatile
    private var frozenUntil = 0L

    /**
     * Requests permission for one `startScan` call. Returns true and records the start, or
     * false when the budget is exhausted (or frozen) — the caller must skip this attempt.
     */
    @Synchronized
    fun tryAcquire(tag: String): Boolean {
        val now = System.currentTimeMillis()
        if (now < frozenUntil) {
            Log.w(TAG, "scan start [$tag] denied — frozen for ${frozenUntil - now}ms (TOO_FREQUENTLY cool-off)")
            return false
        }
        while (startTimes.isNotEmpty() && now - startTimes.first() > WINDOW_MS) {
            startTimes.removeFirst()
        }
        if (startTimes.size >= MAX_STARTS) {
            Log.w(TAG, "scan start [$tag] deferred — ${startTimes.size} starts in the last 30s (OS quota is 5)")
            return false
        }
        startTimes.addLast(now)
        return true
    }

    /**
     * Denies every start for [durationMs]. Called on `SCAN_FAILED_SCANNING_TOO_FREQUENTLY` —
     * continuing to call startScan while throttled extends the OS penalty.
     */
    @Synchronized
    fun freeze(durationMs: Long = WINDOW_MS) {
        frozenUntil = System.currentTimeMillis() + durationMs
        Log.w(TAG, "all scan starts frozen for ${durationMs}ms")
    }

    /** Test hook. */
    @Synchronized
    internal fun reset() {
        startTimes.clear()
        frozenUntil = 0L
    }
}
