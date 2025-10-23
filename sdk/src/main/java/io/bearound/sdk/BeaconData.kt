package io.bearound.sdk

/**
 * Data class representing a detected beacon with all its relevant information.
 *
 * @property uuid The beacon's UUID identifier
 * @property major The major identifier
 * @property minor The minor identifier
 * @property rssi The received signal strength indicator
 * @property bluetoothName The Bluetooth device name
 * @property bluetoothAddress The Bluetooth MAC address
 * @property lastSeen Timestamp when the beacon was last detected
 */
data class BeaconData(
    val uuid: String,
    val major: Int,
    val minor: Int,
    val rssi: Int,
    val bluetoothName: String?,
    val bluetoothAddress: String,
    val lastSeen: Long
)