package io.bearound.sdk.models

import java.util.Date
import java.util.UUID

/**
 * Represents a detected beacon with its properties
 */
data class Beacon(
    val uuid: UUID,
    val major: Int,
    val minor: Int,
    val rssi: Int,
    val proximity: Proximity,
    val accuracy: Double,
    val timestamp: Date = Date(),
    val metadata: BeaconMetadata? = null,
    val txPower: Int? = null,
    val alreadySynced: Boolean = false,
    val syncedAt: Date? = null
) {
    enum class Proximity {
        IMMEDIATE,
        NEAR,
        FAR,
        BT,
        UNKNOWN;

        fun toApiString(): String = when (this) {
            IMMEDIATE -> "immediate"
            NEAR -> "near"
            FAR -> "far"
            BT -> "bt"
            UNKNOWN -> "unknown"
        }
    }

    val identifier: String
        get() = "$major.$minor"
}

