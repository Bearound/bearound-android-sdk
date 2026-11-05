package io.bearound.sdk.models

/**
 * Where the device was, as context for the observations in the same payload.
 *
 * Cached context normally; explicit foreground acquisition can attach a fresh fix.
 * The timestamp always belongs to the original platform fix.
 *
 * @property timestamp  epoch millis of the fix, not of the payload (a fix can be minutes old)
 * @property source     which provider produced it: `gps`, `network`, `fused` or `passive`
 * @property isMocked   true when the fix came from a mock provider
 */
data class DeviceLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float? = null,
    val altitude: Double? = null,
    val timestamp: Long,
    val source: String,
    val isMocked: Boolean? = null
)
