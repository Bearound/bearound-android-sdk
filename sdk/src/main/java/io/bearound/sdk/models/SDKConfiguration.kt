package io.bearound.sdk.models

import kotlin.math.max
import kotlin.math.min

/**
 * Configuration for the BeAround SDK
 */
data class SDKConfiguration(
    val businessToken: String,
    val appId: String,
    val foregroundScanInterval: ForegroundScanInterval = ForegroundScanInterval.SECONDS_15,
    val backgroundScanInterval: BackgroundScanInterval = BackgroundScanInterval.SECONDS_30,
    val maxQueuedPayloads: MaxQueuedPayloads = MaxQueuedPayloads.MEDIUM,
    val enableBluetoothScanning: Boolean = false,
    val enablePeriodicScanning: Boolean = true
) {
    val apiBaseURL: String = "https://ingest.bearound.io"

    /**
     * Get the sync interval based on app state (foreground or background)
     * @param isInBackground Whether the app is in background
     * @return The interval in milliseconds
     */
    fun syncInterval(isInBackground: Boolean): Long {
        return if (isInBackground) {
            backgroundScanInterval.milliseconds
        } else {
            foregroundScanInterval.milliseconds
        }
    }

    /**
     * Calculate scan duration based on current sync interval (1/3 of sync interval)
     * @param isInBackground Whether the app is in background
     * @return The scan duration in milliseconds (min 5s, max 10s)
     */
    fun scanDuration(isInBackground: Boolean): Long {
        val interval = syncInterval(isInBackground)
        val calculatedDuration = interval / 3
        return max(5000L, min(calculatedDuration, 10000L))
    }

    /**
     * Validate and adjust sync interval between 5 and 60 seconds
     * @deprecated Use syncInterval(isInBackground) instead
     */
    @Deprecated("Use syncInterval(isInBackground) instead", ReplaceWith("syncInterval(false)"))
    val validatedSyncInterval: Long
        get() = foregroundScanInterval.milliseconds

    /**
     * Calculate scan duration based on sync interval (1/3 of sync interval)
     * @deprecated Use scanDuration(isInBackground) instead
     */
    @Deprecated("Use scanDuration(isInBackground) instead", ReplaceWith("scanDuration(false)"))
    val scanDuration: Long
        get() = scanDuration(false)
}

