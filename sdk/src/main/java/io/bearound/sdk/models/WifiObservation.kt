package io.bearound.sdk.models

/**
 * One access point seen by the device at a point in time.
 *
 * @property apId          canonical hash of the BSSID (16 hex chars) — the identity the
 *                         backend actually uses
 * @property ssid          human-readable network name, see below
 * @property rssi          signal strength in dBm; null when the platform does not expose it
 * @property connected     true when this is the access point the device is joined to
 * @property frequencyMhz  channel frequency (2412–5825); null when unavailable
 * @property timestamp     epoch millis of the observation itself, not of the payload
 */
data class WifiObservation(
    val apId: String,
    /**
     * Human-readable network name, reported alongside [apId].
     *
     * **Consumed by the backend — keep it.** It is not a debugging leftover on its way out:
     * the name carries information the hashed [apId] cannot, so it is part of the payload
     * contract. (An earlier revision of this file marked it for removal; that is no longer
     * the plan, and deleting it would take a live signal down with it.)
     *
     * It is personal data all the same — a network name identifies a place, and at home a
     * household — so it ships only while the host allows Wi-Fi collection
     * (`configure(collectWifi = ...)`) and is dropped with the rest of the block otherwise.
     */
    val ssid: String? = null,
    val rssi: Int? = null,
    val connected: Boolean = false,
    val frequencyMhz: Int? = null,
    val timestamp: Long
)
