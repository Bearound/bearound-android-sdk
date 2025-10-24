package io.bearound.sdk

/**
 * Interface for receiving callbacks about sync operations with the API.
 */
interface SyncListener {
    /**
     * Called when a sync operation completes successfully.
     *
     * @param eventType The type of event that was synced ("enter", "exit", or "failed")
     * @param beaconCount Number of beacons that were synced
     * @param message Response message from the server
     */
    fun onSyncSuccess(eventType: String, beaconCount: Int, message: String)

    /**
     * Called when a sync operation fails.
     *
     * @param eventType The type of event that failed to sync
     * @param beaconCount Number of beacons that failed to sync
     * @param errorCode HTTP error code (if available)
     * @param errorMessage Error description
     */
    fun onSyncError(eventType: String, beaconCount: Int, errorCode: Int?, errorMessage: String)
}