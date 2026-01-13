package io.bearound.sdk.interfaces

import io.bearound.sdk.models.Beacon

/**
 * Delegate interface for SDK events and updates
 */
interface BeAroundSDKDelegate {
    /**
     * Called when beacons are detected and updated
     */
    fun didUpdateBeacons(beacons: List<Beacon>)

    /**
     * Called when an error occurs in the SDK
     */
    fun didFailWithError(error: Exception) {}

    /**
     * Called when scanning state changes
     */
    fun didChangeScanning(isScanning: Boolean) {}

    /**
     * Called periodically to update sync status
     */
    fun didUpdateSyncStatus(secondsUntilNextSync: Int, isRanging: Boolean) {}
    
    /**
     * Called when app state changes between foreground and background
     * @param isInBackground true if app entered background, false if entered foreground
     */
    fun didChangeAppState(isInBackground: Boolean) {}
}

