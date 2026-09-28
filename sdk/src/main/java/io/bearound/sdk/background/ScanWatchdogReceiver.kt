package io.bearound.sdk.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.bearound.sdk.BeAroundSDK
import io.bearound.sdk.background.ImmediateSyncWorker

/**
 * AlarmManager BroadcastReceiver that acts as a watchdog
 * Ensures scanning is still active and syncs any pending data
 * 
 * Triggers:
 * - Every ~15 minutes via AlarmManager (setAndAllowWhileIdle, inexact)
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
            } else if (shouldBeScanning) {
                // Process alive and "scanning" — but the PendingIntent client (the only
                // out-of-region detector) can be silently dead (BT off→on, quota starvation,
                // stack restart) with the local flag still true. Re-register it from scratch:
                // 1 scan start per 15-min tick, a cheap self-heal.
                sdk.refreshBackgroundScanRegistration()
            }

            // Sync any pending beacons. The receiver has no execution window of its own
            // (the process is freezable the moment onReceive returns, and the old direct
            // performBackgroundSync() was fire-and-forget into that void) — hand the
            // upload to the expedited worker, which owns a WorkManager window + wakelock.
            if (sdk.hasPendingBeacons()) {
                Log.d(TAG, "Watchdog: pending beacons — enqueuing ImmediateSyncWorker")
                ImmediateSyncWorker.enqueue(context.applicationContext)
            }

            // Reschedule ONLY while scanning is still wanted: after stopScanning() the
            // old unconditional reschedule kept the alarm waking the process forever.
            if (shouldBeScanning) {
                BackgroundScheduler.getInstance(context.applicationContext).scheduleWatchdogAlarm()
            } else {
                Log.d(TAG, "Watchdog: scanning disabled — not rescheduling")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog error: ${e.message}")
            io.bearound.sdk.telemetry.ErrorReporter.report(e, "ScanWatchdogReceiver.handleWatchdog")
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
            io.bearound.sdk.telemetry.ErrorReporter.report(e, "ScanWatchdogReceiver.handleBootCompleted")
        }
    }
}
