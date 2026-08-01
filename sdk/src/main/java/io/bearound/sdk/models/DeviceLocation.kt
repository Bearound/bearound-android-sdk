package io.bearound.sdk.models

/**
 * Where the device was, as context for the observations in the same payload.
 *
 * Always the platform's **last known** fix — the SDK never requests an active one,
 * so this costs no extra battery and no GPS wake-up.
 *
 * @property timestamp  epoch millis **of the fix**, not of the payload. A fix can be
 *                      minutes old; the backend needs to know that to weigh it.
 * @property source     which provider produced it: `gps`, `network`, `fused` or `passive`
 * @property isMocked   true when the fix came from a mock provider — anti-fraud signal
 *                      that only Android can offer
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
