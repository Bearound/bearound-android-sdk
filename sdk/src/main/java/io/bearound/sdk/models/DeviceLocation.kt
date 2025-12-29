package io.bearound.sdk.models

import java.util.Date

/**
 * Device location information
 */
data class DeviceLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double?,
    val altitude: Double?,
    val altitudeAccuracy: Double?,
    val heading: Double?,
    val speed: Double?,
    val speedAccuracy: Double?,
    val bearing: Double?,
    val bearingAccuracy: Double?,
    val timestamp: Date,
    val provider: String?
)

