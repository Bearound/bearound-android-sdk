package io.bearound.sdk.models

/**
 * Additional metadata for beacons obtained via BLE scanning
 */
data class BeaconMetadata(
    val firmwareVersion: String,
    val batteryLevel: Int,
    val movements: Int,
    val temperature: Int,
    val txPower: Int? = null,
    val rssiFromBLE: Int? = null,
    val isConnectable: Boolean? = null
)

