package io.bearound.sdk.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.bearound.sdk.BeAroundSDK

/**
 * AlarmManager BroadcastReceiver that acts as a watchdog
 * Ensures scanning is still active and syncs any pending data
 * 
 * Triggers:
 * - Every 15 minutes via AlarmManager (setExactAndAllowWhileIdle)
 * - On device boot (BOOT_COMPLETED)
 * 
 * This provides an additional safety net when:
 * - Bluetooth Scan Broadcast stopped working
 * - WorkManager was delayed
 * - App needs to restart scanning after being killed
 */
class ScanWatchdogReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BeAroundSDK-Watchdog"
        const val ACTION_WATCHDOG = "io.bearound.sdk.ACTION_WATCHDOG"
    }
    
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        
        val action = intent?.action ?: return
        
        Log.d(TAG, "ScanWatchdogReceiver triggered: $action")
        
        when (action) {
            ACTION_WATCHDOG -> handleWatchdog(context)
            Intent.ACTION_BOOT_COMPLETED -> handleBootCompleted(context)
        }
    }
    
    private fun handleWatchdog(context: Context) {
        try {
            val sdk = BeAroundSDK.getInstance(context.applicationContext)
            
            // Restore config if needed
            if (!sdk.isConfigured) {
                Log.d(TAG, "Watchdog: SDK not configured, attempting restore")
                sdk.attemptConfigRestore()
            }
            
            if (!sdk.isConfigured) {
                Log.w(TAG, "Watchdog: SDK still not configured")
                return
            }
            
            // Check if scanning should be active but isn't
            val shouldBeScanning = sdk.wasScanningEnabled()
            val isCurrentlyScanning = sdk.isScanning
            
            if (shouldBeScanning && !isCurrentlyScanning) {
                Log.w(TAG, "Watchdog: Scanning should be active but isn't - restarting")
                sdk.restartScanningFromBackground()
            }
            
            // Sync any pending beacons
            if (sdk.hasPendingBeacons()) {
                Log.d(TAG, "Watchdog: Syncing pending beacons")
                sdk.performBackgroundSync()
            }
            
            // Reschedule next watchdog alarm
            BackgroundScheduler.getInstance(context.applicationContext).scheduleWatchdogAlarm()
            
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog error: ${e.message}")
        }
    }
    
    private fun handleBootCompleted(context: Context) {
        Log.d(TAG, "Device boot completed - checking if scanning should restart")
        
        try {
            val sdk = BeAroundSDK.getInstance(context.applicationContext)
            
            // Restore config
            sdk.attemptConfigRestore()
            
            if (!sdk.isConfigured) {
                Log.d(TAG, "Boot: SDK not configured, nothing to restore")
                return
            }
            
            // Check if scanning was enabled before reboot
            if (sdk.wasScanningEnabled()) {
                Log.d(TAG, "Boot: Restarting scanning")
                sdk.restartScanningFromBackground()
                
                // Re-enable background mechanisms
                BackgroundScheduler.getInstance(context.applicationContext).enableAll()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Boot handler error: ${e.message}")
        }
    }
}
