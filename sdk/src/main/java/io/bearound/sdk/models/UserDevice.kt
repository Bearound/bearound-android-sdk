package io.bearound.sdk.models

/**
 * Complete device information sent with each request
 */
data class UserDevice(
    val deviceId: String,
    val manufacturer: String,
    val model: String,
    val osVersion: String,
    val timestamp: Long,
    val timezone: String,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val bluetoothState: String,
    val locationPermission: String,
    val notificationsPermission: String,
    val networkType: String,
    val cellularGeneration: String?,
    val ramTotalMb: Int,
    val ramAvailableMb: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val adTrackingEnabled: Boolean,
    val appInForeground: Boolean,
    val appUptimeMs: Long,
    val coldStart: Boolean,
    val advertisingId: String?,
    val lowPowerMode: Boolean?,
    val locationAccuracy: String?,
    val wifiSSID: String?,
    val connectionMetered: Boolean?,
    val connectionExpensive: Boolean?,
    val os: String = "Android",
    val deviceLocation: DeviceLocation?,
    val deviceName: String,
    val carrierName: String?,
    val availableStorageMb: Long?,
    val systemLanguage: String,
    val thermalState: String,
    val systemUptimeMs: Long,
    val sdkVersion: Int
)

