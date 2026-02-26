package io.bearound.sdk.models

/**
 * Configuration for the BeAround SDK
 */
data class SDKConfiguration(
    val businessToken: String,
    val appId: String,
    val scanPrecision: ScanPrecision = ScanPrecision.MEDIUM,
    val maxQueuedPayloads: MaxQueuedPayloads = MaxQueuedPayloads.MEDIUM
) {
    val apiBaseURL: String = "https://ingest.bearound.io"

    /** Scan duration is always 10s for all precision modes */
    val precisionScanDuration: Long = 10_000L

    /** Pause duration between scans: HIGH=0, MEDIUM=10s, LOW=50s */
    val precisionPauseDuration: Long
        get() = when (scanPrecision) {
            ScanPrecision.HIGH -> 0L
            ScanPrecision.MEDIUM -> 10_000L
            ScanPrecision.LOW -> 50_000L
        }

    /** Number of scan+pause cycles per window: HIGH=0 (continuous), MEDIUM=3, LOW=1 */
    val precisionCycleCount: Int
        get() = when (scanPrecision) {
            ScanPrecision.HIGH -> 0
            ScanPrecision.MEDIUM -> 3
            ScanPrecision.LOW -> 1
        }

    /** Cycle interval (window duration) is always 60s */
    val precisionCycleInterval: Long = 60_000L

    /** Sync interval: HIGH=15s, MEDIUM/LOW=60s */
    val syncInterval: Long
        get() = when (scanPrecision) {
            ScanPrecision.HIGH -> 15_000L
            ScanPrecision.MEDIUM -> 60_000L
            ScanPrecision.LOW -> 60_000L
        }
}
