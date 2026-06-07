package io.bearound.sdk.models

/** Point-in-time snapshot of SDK state, produced by `BeAroundSDK.diagnostics()`. */
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
