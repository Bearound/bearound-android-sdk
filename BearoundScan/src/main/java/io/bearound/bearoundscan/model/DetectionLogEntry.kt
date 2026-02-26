package io.bearound.bearoundscan.model

import io.bearound.sdk.models.Beacon
import java.util.Date
import java.util.UUID

data class DetectionLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Date = Date(),
    val major: Int,
    val minor: Int,
    val rssi: Int,
    val proximity: String,
    val isBackground: Boolean,
    val discoverySource: String,
    val beaconUUID: String
) {
    companion object {
        fun from(beacon: Beacon, isBackground: Boolean): DetectionLogEntry {
            val proximityText = when (beacon.proximity) {
                Beacon.Proximity.IMMEDIATE -> "Imediato"
                Beacon.Proximity.NEAR -> "Perto"
                Beacon.Proximity.FAR -> "Longe"
                Beacon.Proximity.BT -> "Bluetooth"
                Beacon.Proximity.UNKNOWN -> "Desconhecido"
            }

            val hasSU = beacon.proximity == Beacon.Proximity.BT || beacon.metadata != null
            val hasIB = beacon.txPower != null

            val sourceText = when {
                hasSU && hasIB -> "Both"
                hasSU -> "Service UUID"
                hasIB -> "iBeacon"
                else -> "Service UUID"
            }

            return DetectionLogEntry(
                timestamp = Date(),
                major = beacon.major,
                minor = beacon.minor,
                rssi = beacon.rssi,
                proximity = proximityText,
                isBackground = isBackground,
                discoverySource = sourceText,
                beaconUUID = beacon.uuid.toString()
            )
        }
    }
}
