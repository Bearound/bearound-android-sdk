package io.bearound.sdk.background

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Unified manager for Android background mechanisms
 * Coordinates WorkManager and AlarmManager for reliable background sync
 * 
 * Architecture:
 * - WorkManager: Periodic sync every 15 minutes (OS optimized)
 * - AlarmManager: Watchdog every 15 minutes (more exact, survives Doze)
 * - BluetoothScanBroadcast: Real-time wakeup on Android 14+ (handled separately)
 */
class BackgroundScheduler private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "BeAroundSDK-Scheduler"
        
        // WorkManager
        private const val WORK_INTERVAL_MINUTES = 15L
        private const val WORK_FLEX_MINUTES = 5L
        
        // AlarmManager
        private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
        private const val PENDING_INTENT_REQUEST_CODE = 19921
        
        @Volatile
        private var instance: BackgroundScheduler? = null
        
        fun getInstance(context: Context): BackgroundScheduler {
            return instance ?: synchronized(this) {
                instance ?: BackgroundScheduler(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
    
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }
    private val alarmManager: AlarmManager by lazy { 
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager 
    }
    
    /**
     * Enable all background mechanisms
     * Call this when SDK is configured and scanning starts
     */
    fun enableAll() {
        Log.d(TAG, "Enabling all background mechanisms")
        schedulePeriodicSync()
        scheduleWatchdogAlarm()
    }
    
    /**
     * Disable all background mechanisms
     * Call this when scanning stops or SDK is deconfigured
     */
    fun disableAll() {
        Log.d(TAG, "Disabling all background mechanisms")
        cancelPeriodicSync()
        cancelWatchdogAlarm()
    }
    
    // =========================================================================
    // WORKMANAGER - Periodic Sync
    // =========================================================================
    
    /**
     * Schedule periodic sync using WorkManager
     * Runs every 15 minutes (minimum interval allowed)
     * System will optimize timing based on battery and network conditions
     */
    fun schedulePeriodicSync() {
        Log.d(TAG, "Scheduling periodic sync with WorkManager")
        
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        
        val syncRequest = PeriodicWorkRequestBuilder<BeaconSyncWorker>(
            WORK_INTERVAL_MINUTES, TimeUnit.MINUTES,
            WORK_FLEX_MINUTES, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .addTag("bearound_sync")
            .build()
        
        workManager.enqueueUniquePeriodicWork(
            BeaconSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            syncRequest
        )
        
        Log.d(TAG, "WorkManager periodic sync scheduled")
    }
    
    /**
     * Cancel periodic sync
     */
    fun cancelPeriodicSync() {
        Log.d(TAG, "Cancelling WorkManager periodic sync")
        workManager.cancelUniqueWork(BeaconSyncWorker.WORK_NAME)
    }
    
    // =========================================================================
    // ALARMMANAGER - Watchdog
    // =========================================================================
    
    /**
     * Schedule watchdog alarm using AlarmManager
     * Uses setExactAndAllowWhileIdle for more precise timing
     * This acts as a safety net when WorkManager is delayed
     */
    fun scheduleWatchdogAlarm() {
        val intent = Intent(context, ScanWatchdogReceiver::class.java).apply {
            action = ScanWatchdogReceiver.ACTION_WATCHDOG
        }
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            PENDING_INTENT_REQUEST_CODE,
            intent,
            flags
        )
        
        val triggerTime = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Use setExactAndAllowWhileIdle for Doze mode compatibility
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
            
            Log.d(TAG, "Watchdog alarm scheduled for ${WATCHDOG_INTERVAL_MS / 1000 / 60} minutes from now")
            
        } catch (e: SecurityException) {
            // On Android 12+ may need SCHEDULE_EXACT_ALARM permission
            Log.w(TAG, "Cannot schedule exact alarm: ${e.message}")
            
            // Fallback to inexact alarm
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
            Log.d(TAG, "Fallback: Inexact alarm scheduled")
        }
    }
    
    /**
     * Cancel watchdog alarm
     */
    fun cancelWatchdogAlarm() {
        val intent = Intent(context, ScanWatchdogReceiver::class.java).apply {
            action = ScanWatchdogReceiver.ACTION_WATCHDOG
        }
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            PENDING_INTENT_REQUEST_CODE,
            intent,
            flags
        )
        
        alarmManager.cancel(pendingIntent)
        Log.d(TAG, "Watchdog alarm cancelled")
    }
    
    /**
     * Check if device can schedule exact alarms (Android 12+)
     */
    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }
}
