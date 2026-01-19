package io.bearound.sdk.interfaces

import io.bearound.sdk.models.Beacon

/**
 * Listener interface for SDK events and updates
 * v2.2: Added sync lifecycle and background detection callbacks
 */
interface BeAroundSDKListener {
    /**
     * Called when beacons are detected and updated
     */
    fun onBeaconsUpdated(beacons: List<Beacon>)

    /**
     * Called when an error occurs in the SDK
     */
    fun onError(error: Exception) {}

    /**
     * Called when scanning state changes
     */
    fun onScanningStateChanged(isScanning: Boolean) {}

    /**
     * Called periodically to update sync status
     */
    fun onSyncStatusUpdated(secondsUntilNextSync: Int, isRanging: Boolean) {}
    
    /**
     * Called when app state changes between foreground and background
     * @param isInBackground true if app entered background, false if entered foreground
     */
    fun onAppStateChanged(isInBackground: Boolean) {}
    
    // region Sync Lifecycle Callbacks (v2.2)
    
    /**
     * Called before starting a sync operation
     * @param beaconCount Number of beacons to be synced
     */
    fun onSyncStarted(beaconCount: Int) {}
    
    /**
     * Called after a sync operation completes
     * @param beaconCount Number of beacons that were synced
     * @param success Whether the sync was successful
     * @param error The error if sync failed, null otherwise
     */
    fun onSyncCompleted(beaconCount: Int, success: Boolean, error: Exception?) {}
    
    // endregion
    
    // region Background Events (v2.2)
    
    /**
     * Called when beacons are detected while app is in background
     * @param beaconCount Number of beacons detected
     */
    fun onBeaconDetectedInBackground(beaconCount: Int) {}
    
    // endregion
}
