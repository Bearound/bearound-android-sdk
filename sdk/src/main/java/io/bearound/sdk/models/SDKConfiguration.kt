package io.bearound.sdk.models

/**
 * Configuration for the BeAround SDK
 */
data class SDKConfiguration(
    val businessToken: String,
    val appId: String,
    val scanPrecision: ScanPrecision = ScanPrecision.MEDIUM,
    val maxQueuedPayloads: MaxQueuedPayloads = MaxQueuedPayloads.MEDIUM,
    val technology: String = "android-native"
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
