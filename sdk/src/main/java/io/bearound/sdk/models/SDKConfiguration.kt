package io.bearound.sdk.models

import kotlin.math.max
import kotlin.math.min

/**
 * Configuration for the BeAround SDK
 */
data class SDKConfiguration(
    val appId: String,
    val syncInterval: Long,
    val enableBluetoothScanning: Boolean = false,
    val enablePeriodicScanning: Boolean = true
) {
    val apiBaseURL: String = "https://ingest.bearound.io"

    /**
     * Validate and adjust sync interval between 5 and 60 seconds
     */
    val validatedSyncInterval: Long
        get() = min(max(syncInterval, 5000L), 60000L)

    /**
     * Calculate scan duration based on sync interval (1/3 of sync interval)
     */
    val scanDuration: Long
        get() {
            val calculatedDuration = validatedSyncInterval / 3
            return max(5000L, min(calculatedDuration, 10000L))
        }
}

