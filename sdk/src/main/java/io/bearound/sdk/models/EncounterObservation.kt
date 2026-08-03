package io.bearound.sdk.models

/**
 * One nearby SDK-carrying device, as seen over BLE during the current window.
 *
 * Sightings are reported as observed — "saw [rpi] at [rssi]" — with no local matching.
 * [rpi] is the peer's rotating identifier: 16 random bytes renewed every
 * [io.bearound.sdk.EncounterMeshManager.RPI_ROTATION_MS], so nothing stable goes on
 * the air.
 */
data class EncounterObservation(
    /** Peer's rotating identifier (32 lowercase hex chars) read over GATT. */
    val rpi: String,
    /** Most recent signal strength, in dBm. */
    val rssi: Int,
    /** Number of advertisements aggregated into this observation. */
    val sampleCount: Int,
    val rssiMin: Int,
    val rssiMax: Int,
    val rssiAvg: Int,
    /** Epoch millis of the first advertisement in this aggregate. */
    val firstSeen: Long,
    /** Epoch millis of the most recent advertisement. */
    val lastSeen: Long,
)
