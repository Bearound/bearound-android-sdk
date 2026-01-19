package io.bearound.sdk.background

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.bearound.sdk.BeAroundSDK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager Worker for periodic beacon sync
 * Runs every 15 minutes (minimum interval) to sync pending beacons
 * 
 * This provides a fallback mechanism when:
 * - Bluetooth Scan Broadcast doesn't fire (Android < 14)
 * - App was killed by system
 * - Network was unavailable during previous sync attempts
 */
class BeaconSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        private const val TAG = "BeAroundSDK-SyncWorker"
        const val WORK_NAME = "beacon_sync_work"
    }
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "BeaconSyncWorker started")
        
        try {
            val sdk = BeAroundSDK.getInstance(applicationContext)
            
            // Check if SDK is configured
            if (!sdk.isConfigured) {
                Log.d(TAG, "SDK not configured, attempting restore")
                sdk.attemptConfigRestore()
                
                if (!sdk.isConfigured) {
                    Log.w(TAG, "SDK still not configured, skipping sync")
                    return@withContext Result.success()
                }
            }
            
            // Check if there are pending beacons or failed batches
            val hasPendingData = sdk.hasPendingBeacons()
            
            if (hasPendingData) {
                Log.d(TAG, "Syncing pending beacons...")
                sdk.performBackgroundSync()
                Log.d(TAG, "Background sync completed")
            } else {
                Log.d(TAG, "No pending beacons to sync")
            }
            
            // Reschedule watchdog alarm
            BackgroundScheduler.getInstance(applicationContext).scheduleWatchdogAlarm()
            
            Result.success()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in BeaconSyncWorker: ${e.message}")
            
            // Retry if this is a transient failure
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
