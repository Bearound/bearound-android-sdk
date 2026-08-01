package io.bearound.sdk.models

/**
 * Complete device information sent with each request
 */
data class UserDevice(
    val deviceId: String,
    val pushToken: String? = null,
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
    val appInForeground: Boolean,
    val appUptimeMs: Long,
    val coldStart: Boolean,
    val lowPowerMode: Boolean?,
    val locationAccuracy: String?,
    /** Hash of the connected access point's BSSID — the identity the backend uses. */
    val apId: String?,
    /**
     * Name of the connected network.
     *
     * **Temporary — kept for validating the collection while the access-point map is being
     * built.** [apId] is the field that matters; remove this one (and `WifiObservation.ssid`)
     * once the collection is trusted.
     */
    val wifiSSID: String?,
    val connectionMetered: Boolean?,
    val connectionExpensive: Boolean?,
    val os: String = "Android",
    val deviceName: String,
    val carrierName: String?,
    val availableStorageMb: Long?,
    val systemLanguage: String,
    val thermalState: String,
    val systemUptimeMs: Long,
    val sdkVersion: Int,
    /**
     * Access points visible at collection time. Empty when the host app lacks the
     * permissions — the SDK never asks the user for anything on its own.
     */
    val wifis: List<WifiObservation> = emptyList(),
    /**
     * Last known fix, as context for [wifis] and for the beacons in the same payload.
     * Null when unavailable; the SDK never requests an active fix.
     */
    val location: DeviceLocation? = null,
    /**
     * Google Advertising ID (AAID) — resettable, user-controlled identifier for advertising.
     * Null when the user opted out, when Play Services is absent, or before the first
     * async fetch lands.
     */
    val advertisingId: String? = null,
    /**
     * True when the user asked apps not to track them. Reported alongside [advertisingId]
     * so an opt-out is distinguishable from Play Services simply being unavailable.
     */
    val limitAdTracking: Boolean? = null
)

