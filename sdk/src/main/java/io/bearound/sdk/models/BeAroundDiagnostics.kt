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
    /** Real SDK version (e.g. "3.4.5"), from BuildConfig.SDK_VERSION. */
    val sdkVersion: String,
    /** Android OS API level (Build.VERSION.SDK_INT). */
    val osApiLevel: Int,
    /**
     * Whether BLUETOOTH_SCAN is granted — the permission that unlocks the BLE scan on
     * Android 12+. Always true on Android ≤11, where it is not a runtime permission.
     */
    val hasBluetoothScanPermission: Boolean,
    /** Whether the Bluetooth adapter is currently powered on. */
    val bluetoothEnabled: Boolean,
    /** Whether the foreground scan service is currently running. */
    val foregroundServiceActive: Boolean,
    /** Whether the low-power PendingIntent background scan is currently registered. */
    val backgroundScanRegistered: Boolean,
    /** Whether the app is exempt from battery optimizations (Doze allowlist). */
    val isIgnoringBatteryOptimizations: Boolean
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
            appendLine("  sdkVersion: $sdkVersion")
            appendLine("  osApiLevel: $osApiLevel")
            appendLine("  isScanning: $isScanning")
            appendLine("  hasBluetoothScanPermission: $hasBluetoothScanPermission")
            appendLine("  bluetoothEnabled: $bluetoothEnabled")
            appendLine("  foregroundServiceActive: $foregroundServiceActive")
            appendLine("  backgroundScanRegistered: $backgroundScanRegistered")
            appendLine("  isIgnoringBatteryOptimizations: $isIgnoringBatteryOptimizations")
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
