package io.bearound.scan

import android.content.Context
import android.util.Log
import io.bearound.sdk.interfaces.BeAroundSDKListener
import io.bearound.sdk.models.Beacon

/**
 * Background listener for SDK callbacks.
 * Persists even when Activity is not active (app closed in background).
 * Handles notifications and logging when app wakes up via WorkManager/Broadcast.
 */
class SDKBackgroundListener(
    private val context: Context
) : BeAroundSDKListener {
    
    companion object {
        private const val TAG = "SDKBackgroundListener"
    }
    
    private val notificationManager = NotificationManager(context)
    
    override fun onBeaconsUpdated(beacons: List<Beacon>) {
        // Log only - UI updates handled by ViewModel when active
        Log.d(TAG, "Beacons updated: ${beacons.size}")
    }
    
    override fun onError(error: Exception) {
        Log.e(TAG, "SDK error: ${error.message}")
    }
    
    override fun onScanningStateChanged(isScanning: Boolean) {
        Log.d(TAG, "Scanning: $isScanning")
    }
    
    override fun onAppStateChanged(isInBackground: Boolean) {
        Log.d(TAG, "App state changed - Background: $isInBackground")
    }
    
    // region Sync Lifecycle (always active, even in background)
    
    override fun onSyncStarted(beaconCount: Int) {
        Log.d(TAG, "Sync starting with $beaconCount beacons")
        notificationManager.notifyAPISyncStarted(beaconCount)
    }
    
    override fun onSyncCompleted(beaconCount: Int, success: Boolean, error: Exception?) {
        if (success) {
            Log.d(TAG, "Sync completed successfully: $beaconCount beacons")
        } else {
            Log.e(TAG, "Sync failed: ${error?.message ?: "unknown error"}")
        }
        notificationManager.notifyAPISyncCompleted(beaconCount, success)
    }
    
    // endregion
    
    // region Background Events (critical for closed app)
    
    override fun onBeaconDetectedInBackground(beaconCount: Int) {
        Log.d(TAG, "Beacon detected in background: $beaconCount beacons")
        notificationManager.notifyBeaconDetected(beaconCount, isBackground = true)
    }
    
    // endregion
}
