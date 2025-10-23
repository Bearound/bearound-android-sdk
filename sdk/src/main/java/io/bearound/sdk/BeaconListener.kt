package io.bearound.sdk

/**
 * Interface for receiving callbacks when beacons are detected.
 */
interface BeaconListener {
    /**
     * Called when beacons are detected in the monitored region.
     *
     * @param beacons List of detected beacons with their information
     * @param eventType The type of event ("enter", "exit", or "failed")
     */
    fun onBeaconsDetected(beacons: List<BeaconData>, eventType: String)
}