package io.bearound.sdk.models

import kotlin.math.max
import kotlin.math.min

/**
 * Scan interval enums are defined in ScanIntervalConfiguration.kt
 *
 * SDK configuration for beacon scanning and API communication
 */
data class SDKConfiguration(
    val businessToken: String,
    val appId: String,
    val foregroundScanInterval: ForegroundScanInterval,
    val backgroundScanInterval: BackgroundScanInterval,
    val maxQueuedPayloads: MaxQueuedPayloads,
    val enableBluetoothScanning: Boolean = false,
    val enablePeriodicScanning: Boolean = true
) {
    val apiBaseURL: String = "https://ingest.bearound.io"

    /**
     * Calculate scan duration based on sync interval (1/3 of sync interval)
     */
    fun scanDuration(interval: Long): Long {
        val calculatedDuration = interval / 3
        return max(5000L, min(calculatedDuration, 10000L))
    }

    fun syncInterval(isInBackground: Boolean): Long {
        return if (isInBackground) {
            backgroundScanInterval.timeIntervalSeconds * 1000L
        } else {
            foregroundScanInterval.timeIntervalSeconds * 1000L
        }
    }
}

