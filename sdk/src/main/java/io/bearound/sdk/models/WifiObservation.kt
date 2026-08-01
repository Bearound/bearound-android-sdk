package io.bearound.sdk.models

/**
 * One access point seen by the device at a point in time.
 *
 * Carries no network name: [apId] is a one-way hash of the BSSID, so the payload
 * never reveals which network the user is on.
 *
 * @property apId          canonical hash of the BSSID (16 hex chars)
 * @property rssi          signal strength in dBm; null when the platform does not expose it
 * @property connected     true when this is the access point the device is joined to
 * @property frequencyMhz  channel frequency (2412–5825); null when unavailable
 * @property timestamp     epoch millis of the observation itself, not of the payload
 */
data class WifiObservation(
    val apId: String,
    val rssi: Int? = null,
    val connected: Boolean = false,
    val frequencyMhz: Int? = null,
    val timestamp: Long
)
