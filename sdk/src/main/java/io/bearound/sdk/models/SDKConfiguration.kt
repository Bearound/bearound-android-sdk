package io.bearound.sdk.models

import android.util.Log

/**
 * Defaults and safety bounds for the periodic background reconciliation
 * ([androidx.work.PeriodicWorkRequest] layer). Public so hosts can reference the
 * same constants.
 *
 * The bounds are PRODUCT guard rails, not just input validation:
 * - **Interval floor (15 min)**: WorkManager's own hard minimum for periodic work
 *   ([androidx.work.PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS]). Note that
 *   WorkManager silently raises smaller values to 15 min on its own — the SDK clamps
 *   WITH a loud warning instead of letting that happen invisibly.
 * - **Interval ceiling (24 h)**: past this the layer is effectively off — disable it
 *   explicitly ([SDKConfiguration.periodicReconciliationEnabled]) instead.
 * - **Scan window (3–30 s)**: bounded so a misconfigured window can neither miss the
 *   advertising cadence (< 3 s) nor camp on the radio (> 30 s).
 */
object PeriodicReconciliationDefaults {
    private const val TAG = "BeAroundSDK-Config"

    const val DEFAULT_INTERVAL_MILLIS: Long = 20L * 60L * 1000L
    /** WorkManager's hard minimum for periodic work. */
    const val MINIMUM_INTERVAL_MILLIS: Long = 15L * 60L * 1000L
    const val MAXIMUM_INTERVAL_MILLIS: Long = 24L * 60L * 60L * 1000L
    const val DEFAULT_SCAN_DURATION_MILLIS: Long = 12L * 1000L
    const val MINIMUM_SCAN_DURATION_MILLIS: Long = 3L * 1000L
    const val MAXIMUM_SCAN_DURATION_MILLIS: Long = 30L * 1000L

    /**
     * Sanitizes a host-provided interval: non-positive values fall back to the default;
     * out-of-range values are clamped into [MINIMUM_INTERVAL_MILLIS,
     * MAXIMUM_INTERVAL_MILLIS]. Every adjustment logs an ERROR-level warning — never a
     * silent change, never a crash (never-crash-the-host doctrine).
     */
    fun sanitizedInterval(value: Long): Long {
        if (value <= 0L) {
            Log.e(TAG, "⚠️ periodicReconciliationIntervalMillis ($value) is invalid — using the ${DEFAULT_INTERVAL_MILLIS / 60000} min default. Accepted range: ${MINIMUM_INTERVAL_MILLIS / 60000} min – ${MAXIMUM_INTERVAL_MILLIS / 3600000} h.")
            return DEFAULT_INTERVAL_MILLIS
        }
        if (value < MINIMUM_INTERVAL_MILLIS) {
            Log.e(TAG, "⚠️ periodicReconciliationIntervalMillis ${value}ms is below WorkManager's 15-min minimum for periodic work — CLAMPED to 15 min. (WorkManager would silently raise it anyway; the SDK warns instead.)")
            return MINIMUM_INTERVAL_MILLIS
        }
        if (value > MAXIMUM_INTERVAL_MILLIS) {
            Log.e(TAG, "⚠️ periodicReconciliationIntervalMillis ${value}ms is above the 24h ceiling — CLAMPED. If you want the layer off, set periodicReconciliationEnabled = false instead.")
            return MAXIMUM_INTERVAL_MILLIS
        }
        return value
    }

    /** Sanitizes the scan-window duration into [MINIMUM_SCAN_DURATION_MILLIS, MAXIMUM_SCAN_DURATION_MILLIS]. */
    fun sanitizedScanDuration(value: Long): Long {
        if (value <= 0L) {
            Log.e(TAG, "⚠️ periodicScanDurationMillis ($value) is invalid — using the ${DEFAULT_SCAN_DURATION_MILLIS / 1000}s default. Accepted range: ${MINIMUM_SCAN_DURATION_MILLIS / 1000}–${MAXIMUM_SCAN_DURATION_MILLIS / 1000}s.")
            return DEFAULT_SCAN_DURATION_MILLIS
        }
        if (value < MINIMUM_SCAN_DURATION_MILLIS || value > MAXIMUM_SCAN_DURATION_MILLIS) {
            Log.e(TAG, "⚠️ periodicScanDurationMillis ${value}ms is outside the ${MINIMUM_SCAN_DURATION_MILLIS / 1000}–${MAXIMUM_SCAN_DURATION_MILLIS / 1000}s range — CLAMPED.")
        }
        return value.coerceIn(MINIMUM_SCAN_DURATION_MILLIS, MAXIMUM_SCAN_DURATION_MILLIS)
    }
}

/**
 * Configuration for the BeAround SDK
 */
data class SDKConfiguration(
    val businessToken: String,
    val appId: String,
    val scanPrecision: ScanPrecision = ScanPrecision.MEDIUM,
    val maxQueuedPayloads: MaxQueuedPayloads = MaxQueuedPayloads.MEDIUM,
    val technology: String = "android-native",
    /**
     * Enables the periodic background reconciliation (WorkManager layer).
     *
     * A best-effort safety net: a [androidx.work.PeriodicWorkRequest] that periodically
     * checks scan health, waits a short collection window when useful, and syncs pending
     * data through the existing pipeline. Complementary to the PendingIntent scan —
     * never a replacement. Subject to Doze/battery optimizations and OEM policies;
     * force-stop suspends it until the app is launched again.
     */
    val periodicReconciliationEnabled: Boolean = true,
    /**
     * Minimum interval requested between eligible executions — a floor, never a
     * guaranteed cadence (Android may run the worker much later).
     *
     * Accepted range: **15 minutes (WorkManager's hard minimum) … 24 hours**.
     * Out-of-range values are clamped with an ERROR-level log; non-positive values
     * fall back to the 20-minute default. Sanitize via
     * [PeriodicReconciliationDefaults.sanitizedInterval] before constructing directly.
     */
    val periodicReconciliationIntervalMillis: Long = PeriodicReconciliationDefaults.DEFAULT_INTERVAL_MILLIS,
    /**
     * Ceiling of the temporary collection window inside the worker, clamped to
     * **3–30 seconds**. Only used while waiting for the continuous scanners to deliver;
     * the worker never registers scanners of its own.
     */
    val periodicScanDurationMillis: Long = PeriodicReconciliationDefaults.DEFAULT_SCAN_DURATION_MILLIS
) {
    val apiBaseURL: String = "https://ingest.bearound.io"

    // NOTE: the old precisionScanDuration/PauseDuration/CycleCount/CycleInterval props
    // described a manual scan/pause duty cycle the SDK no longer runs — scanning is
    // continuous and the OS handles duty-cycling; precision now only drives the scan
    // mode (see BeaconManager.rangingScanMode) and the sync cadence below.

    /** Sync interval: HIGH=15s, MEDIUM/LOW=60s */
    val syncInterval: Long
        get() = when (scanPrecision) {
            ScanPrecision.HIGH -> 15_000L
            ScanPrecision.MEDIUM -> 60_000L
            ScanPrecision.LOW -> 60_000L
        }
}
