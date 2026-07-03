package io.bearound.sdk.models

/**
 * One-call picture of how hostile this device is to background BLE scanning and which
 * user-actionable levers are available. Produced by `BeAroundSDK.reliabilityStatus()`.
 *
 * Hosts should gate their "allow background detection" onboarding on
 * [recommendsUserAction]: when true, walk the user through
 * `openManufacturerAutostartSettings()` (when [isAutostartManageable]) and
 * `openBatteryOptimizationSettings()` (when not [isIgnoringBatteryOptimizations]).
 */
data class ReliabilityStatus(
    /** Marketing name of the OEM ROM ("HyperOS", "MIUI", "One UI", "ColorOS"…); null on stock Android. */
    val oemRom: String?,
    /** ROM version string, when the ROM exposes one. */
    val oemRomVersion: String?,
    /** "standard", "moderate" or "aggressive" — how hard this ROM kills background work. */
    val oemAggressiveness: String,
    /** Whether the app is already exempt from battery optimizations (Doze allowlist). */
    val isIgnoringBatteryOptimizations: Boolean,
    /** Whether this device has a known, resolvable OEM autostart/protected-apps screen. */
    val isAutostartManageable: Boolean,
    /**
     * True when reliable background detection on this device likely REQUIRES user action —
     * an aggressive/moderate ROM without the battery exemption in place.
     */
    val recommendsUserAction: Boolean
)
