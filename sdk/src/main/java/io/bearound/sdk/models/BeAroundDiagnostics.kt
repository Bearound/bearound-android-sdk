package io.bearound.sdk.models

/**
 * Point-in-time snapshot of SDK state, produced by `BeAroundSDK.diagnostics()`.
 *
 * Lets integrators and support inspect what the SDK is doing — scanning state, the
 * masked push token and when it was last delivered, pending upload backlog, and the
 * most recent scan/sync outcomes — without pulling files off the device.
 *
 * @property pushTokenMasked current push token masked for display, or null when unset.
 * @property pushTokenLastSentAt epoch millis of the last successful token send, or null.
 * @property pendingBatches number of failed batches awaiting retry.
 * @property recentErrors most recent errors, oldest first, each "<epochMillis> | <message>".
 * @property sdkVersion the Android OS API level the SDK is running on (Build.VERSION.SDK_INT).
 */
data class BeAroundDiagnostics(
    val deviceId: String,
    val pushTokenMasked: String?,
    val pushTokenLastSentAt: Long?,
    val isScanning: Boolean,
    val pendingBatches: Int,
    val lastScanAt: Long?,
    val lastScanBeaconCount: Int?,
    val lastSyncAt: Long?,
    val lastSyncSuccess: Boolean?,
    val lastSyncBeaconCount: Int?,
    val recentErrors: List<String>,
    val sdkVersion: Int
) {
    /**
     * Readable multi-line rendering of the snapshot, suitable for logs or a support ticket.
     */
    fun summary(): String {
        val errorsBlock = if (recentErrors.isEmpty()) {
            "  (none)"
        } else {
            recentErrors.joinToString("\n") { "  - $it" }
        }

        return buildString {
            appendLine("BeAround Diagnostics")
            appendLine("  deviceId: $deviceId")
            appendLine("  sdkVersion (OS API): $sdkVersion")
            appendLine("  isScanning: $isScanning")
            appendLine("  pendingBatches: $pendingBatches")
            appendLine("  pushTokenMasked: ${pushTokenMasked ?: "none"}")
            appendLine("  pushTokenLastSentAt: ${pushTokenLastSentAt ?: "never"}")
            appendLine("  lastScanAt: ${lastScanAt ?: "never"}")
            appendLine("  lastScanBeaconCount: ${lastScanBeaconCount ?: "n/a"}")
            appendLine("  lastSyncAt: ${lastSyncAt ?: "never"}")
            appendLine("  lastSyncSuccess: ${lastSyncSuccess ?: "n/a"}")
            appendLine("  lastSyncBeaconCount: ${lastSyncBeaconCount ?: "n/a"}")
            appendLine("  recentErrors:")
            append(errorsBlock)
        }
    }
}
