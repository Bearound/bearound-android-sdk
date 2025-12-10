package io.bearound.sdk

import org.altbeacon.beacon.Beacon
import org.json.JSONObject
import java.util.UUID
import kotlin.math.pow

/**
 * Collects scan context information for the BeAround SDK payload.
 */
class ScanContextCollector {

    /**
     * Collects scan context information and returns it as a JSONObject.
     * Note: rssi, txPower, and approxDistanceMeters are now included in each beacon object.
     */
    fun collectScanContext(scanSessionId: String): JSONObject {
        return JSONObject().apply {
            put("scanSessionId", scanSessionId)
            put("detectedAt", System.currentTimeMillis())
        }
    }

    /**
     * Calculates approximate distance in meters based on RSSI and TX power.
     * Uses the log-distance path loss model.
     */
    fun calculateDistance(rssi: Int, txPower: Int): Double {
        if (txPower == 0) {
            return -1.0 // Unable to calculate
        }

        val ratio = rssi.toDouble() / txPower.toDouble()
        return if (ratio < 1.0) {
            ratio.pow(10.0)
        } else {
            val accuracy = 0.89976 * ratio.pow(7.7095) + 0.111
            accuracy
        }
    }

    /**
     * Generates a unique scan session ID.
     */
    fun generateScanSessionId(): String {
        return "scan_${UUID.randomUUID().toString().substring(0, 6).uppercase()}"
    }
}

