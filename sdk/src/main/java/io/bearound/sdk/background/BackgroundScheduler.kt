package io.bearound.sdk.background

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.bearound.sdk.models.PeriodicReconciliationDefaults
import io.bearound.sdk.models.SDKConfiguration
import io.bearound.sdk.utilities.SDKConfigStorage
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
        
        // WorkManager — interval now comes from the persisted SDKConfiguration
        private const val WORK_FLEX_MINUTES = 5L
        
        // AlarmManager
        private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
        private const val PENDING_INTENT_REQUEST_CODE = 19921
        
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: BackgroundScheduler? = null
        
        fun getInstance(context: Context): BackgroundScheduler {
            return instance ?: synchronized(this) {
                instance ?: BackgroundScheduler(context.applicationContext).also {
                    instance = it
                }
            }
        }

        /**
         * Drops the singleton so the next getInstance() rebinds to a fresh context.
         * Robolectric gives every test its own Application (and test WorkManager);
         * without this the lazy WorkManager handle keeps pointing at the previous
         * test's orphaned instance. Same test-only pattern as RegisterStore.
         */
        internal fun _resetForTesting() {
            synchronized(this) { instance = null }
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
     * Schedules the periodic reconciliation as UNIQUE periodic work using the
     * configured interval (default 20 min; floor = WorkManager's 15-min minimum).
     *
     * `ExistingPeriodicWorkPolicy.UPDATE` swaps the spec in place: repeated calls
     * never accumulate requests, an unchanged interval is effectively a no-op, and a
     * running worker is never interrupted. The system decides actual timing — this
     * is best effort by design (Doze, battery optimizations, OEM policies).
     *
     * NO network constraint on purpose: the worker must be able to run its collection
     * window and PERSIST results offline; the sync step inside it degrades gracefully
     * without connectivity (data stays in the outbox for the next attempt).
     */
    fun schedulePeriodicSync() {
        val config = SDKConfigStorage.loadConfiguration(context)
        if (config != null && !config.periodicReconciliationEnabled) {
            Log.i(TAG, "periodic_work_cancelled reason=FEATURE_DISABLED")
            workManager.cancelUniqueWork(BeaconSyncWorker.WORK_NAME)
            return
        }

        val intervalMillis = config?.periodicReconciliationIntervalMillis
            ?: PeriodicReconciliationDefaults.DEFAULT_INTERVAL_MILLIS

        val syncRequest = PeriodicWorkRequestBuilder<BeaconSyncWorker>(
            intervalMillis, TimeUnit.MILLISECONDS,
            WORK_FLEX_MINUTES, TimeUnit.MINUTES
        )
            .addTag("bearound_sync")
            .build()

        workManager.enqueueUniquePeriodicWork(
            BeaconSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            syncRequest
        )

        Log.i(TAG, "periodic_work_scheduled intervalMs=$intervalMillis (earliest bound only — execution at Android's discretion)")
    }

    /**
     * Applies a (re)configuration to the periodic reconciliation: enabled → schedule
     * or update the unique work with the configured interval; disabled → cancel the
     * pending periodic work. Never touches a worker that is already executing.
     */
    fun refreshPeriodicReconciliation(config: SDKConfiguration) {
        if (config.periodicReconciliationEnabled) {
            schedulePeriodicSync()
        } else {
            cancelPeriodicSync()
        }
    }

    /**
     * Cancel periodic sync
     */
    fun cancelPeriodicSync() {
        Log.i(TAG, "periodic_work_cancelled reason=REQUESTED")
        workManager.cancelUniqueWork(BeaconSyncWorker.WORK_NAME)
    }
    
    // =========================================================================
    // ALARMMANAGER - Watchdog
    // =========================================================================
    
    /**
     * Schedule watchdog alarm using AlarmManager.
     * Uses setAndAllowWhileIdle (INEXACT) — runs in Doze without USE_EXACT_ALARM.
     * The SDK is not an alarm/calendar app, so it does not qualify for exact
     * alarms on Google Play. A few minutes of jitter is fine for a watchdog that
     * is just a safety net for WorkManager.
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
        
        // Alarme INEXATO: setAndAllowWhileIdle roda em Doze SEM exigir
        // USE_EXACT_ALARM. Para um watchdog (rede de segurança do WorkManager),
        // a janela de alguns minutos não afeta a detecção de presença.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
            Log.d(TAG, "Watchdog (inexact) scheduled for ${WATCHDOG_INTERVAL_MS / 1000 / 60} minutes from now")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule watchdog alarm: ${e.message}")
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
