package io.bearound.sdk.models

/**
 * One access point seen by the device at a point in time.
 *
 * @property apId          canonical hash of the BSSID (16 hex chars) — the identity the
 *                         backend actually uses
 * @property ssid          TEMPORARY, see below
 * @property rssi          signal strength in dBm; null when the platform does not expose it
 * @property connected     true when this is the access point the device is joined to
 * @property frequencyMhz  channel frequency (2412–5825); null when unavailable
 * @property timestamp     epoch millis of the observation itself, not of the payload
 */
data class WifiObservation(
    val apId: String,
    /**
     * Human-readable network name.
     *
     * **Temporary — for validating the collection against real networks while the map is
     * being built.** Nothing downstream consumes it: [apId] is the identity. Remove this
     * property, its sibling `UserDevice.wifiSSID`, and both payload fields once the
     * collection is trusted — a network name identifies a household, so it should not
     * outlive its debugging purpose.
     */
    val ssid: String? = null,
    val rssi: Int? = null,
    val connected: Boolean = false,
    val frequencyMhz: Int? = null,
    val timestamp: Long
)
