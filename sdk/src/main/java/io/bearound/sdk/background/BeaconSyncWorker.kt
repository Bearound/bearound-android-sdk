package io.bearound.sdk.background

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.bearound.sdk.BeAroundSDK
import io.bearound.sdk.utilities.SDKConfigStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Periodic beacon reconciliation (unique [androidx.work.PeriodicWorkRequest], see
 * [BackgroundScheduler.schedulePeriodicSync]). Best effort by design: Android decides
 * the actual timing (Doze, battery optimizations, OEM policies), and the PendingIntent
 * scan remains the PRIMARY out-of-process detection mechanism — this worker is a
 * complementary safety net that:
 *
 * 1. Self-heals the background scan registration when the host wants scanning;
 * 2. Waits a short, configurable collection window for the CONTINUOUS scanners to
 *    deliver (it never registers a scanner, PendingIntent, or foreground service of
 *    its own — so there is no scan ownership to hand back when it finishes);
 * 3. Drains pending data through the existing single-flight sync pipeline
 *    (persist-before-send, batch ids, poison-batch quarantine all apply).
 *
 * Battery policy: in Battery Saver or serious/critical thermal state the collection
 * window is skipped — at most the already-pending data is synchronized. When the host
 * called stopScanning() the worker NEVER touches scan state and only drains leftovers.
 */
class BeaconSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "BeAroundSDK-SyncWorker"
        const val WORK_NAME = "beacon_sync_work"

        /** Poll cadence of the collection window (the batch scan flushes every ~2s). */
        private const val COLLECTION_POLL_MS = 500L
    }

    private enum class SkipReason {
        FEATURE_DISABLED, SDK_NOT_CONFIGURED, BATTERY_SAVER, THERMAL_RESTRICTION, HOST_STOPPED_SCANNING
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "periodic_worker_started attempt=$runAttemptCount")

        try {
            val sdk = BeAroundSDK.getInstance(applicationContext)

            // Check if SDK is configured
            if (!sdk.isConfigured) {
                Log.d(TAG, "SDK not configured, attempting restore")
                sdk.attemptConfigRestore()

                if (!sdk.isConfigured) {
                    logSkip(SkipReason.SDK_NOT_CONFIGURED)
                    return@withContext Result.success()
                }
            }

            val config = SDKConfigStorage.loadConfiguration(applicationContext)
            if (config?.periodicReconciliationEnabled == false) {
                // Disabled between scheduling and execution (the unique work is also
                // cancelled on reconfigure — this covers the race). Skipping is success.
                logSkip(SkipReason.FEATURE_DISABLED)
                return@withContext Result.success()
            }

            // ── Reconciliation policy ────────────────────────────────────────
            // Host intent rules everything: after stopScanning() the worker never
            // touches scan state — it only drains data that already exists.
            val wantsScanning = sdk.wasScanningEnabled()
            val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val batterySaver = powerManager?.isPowerSaveMode == true
            val thermalRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                (powerManager?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_SEVERE

            val mayCollect = wantsScanning && !batterySaver && !thermalRestricted
            if (!mayCollect) {
                when {
                    !wantsScanning -> logSkip(SkipReason.HOST_STOPPED_SCANNING)
                    batterySaver -> logSkip(SkipReason.BATTERY_SAVER)
                    else -> logSkip(SkipReason.THERMAL_RESTRICTION)
                }
            } else {
                // Self-heal: the PendingIntent client (the only out-of-process detector)
                // can be silently dead with the local flag still true. One budget-guarded
                // re-registration per run — same cheap heal the 15-min watchdog performs.
                sdk.refreshBackgroundScanRegistration()

                // Collection window: WAIT (never scan ourselves) for the continuous
                // scanners to deliver, bounded by the configured window. Data already
                // present short-circuits immediately (recent detection → no wait).
                val windowMs = config?.periodicScanDurationMillis
                    ?: io.bearound.sdk.models.PeriodicReconciliationDefaults.DEFAULT_SCAN_DURATION_MILLIS
                val windowStart = System.currentTimeMillis()
                var waited = 0L
                while (!sdk.hasPendingBeacons() &&
                    System.currentTimeMillis() - windowStart < windowMs
                ) {
                    ensureActive() // respond to WorkManager cancellation promptly
                    delay(COLLECTION_POLL_MS)
                    waited += COLLECTION_POLL_MS
                }
                if (waited > 0) {
                    Log.i(TAG, "periodic_scan_duration waitedMs=$waited windowMs=$windowMs dataFound=${sdk.hasPendingBeacons()}")
                }
            }

            ensureActive()

            // Check if there are pending beacons or failed batches
            val hasPendingData = sdk.hasPendingBeacons()

            if (hasPendingData) {
                Log.i(TAG, "periodic_sync_requested")
                // AWAIT the upload: returning before it finishes released the
                // WorkManager window (and its wakelock) with the POST in flight.
                // Offline: the sync fails, data STAYS persisted (persist-before-send),
                // and retry/backoff owns the redelivery — nothing is lost.
                val ok = sdk.performBackgroundSyncAwait()
                Log.i(TAG, "periodic_sync_completed success=$ok")
                if (!ok) {
                    BackgroundScheduler.getInstance(applicationContext).scheduleWatchdogAlarm()
                    return@withContext if (runAttemptCount < 3) Result.retry() else Result.failure()
                }
            } else {
                Log.i(TAG, "periodic_worker_completed result=NOTHING_TO_DO")
            }

            // Reschedule watchdog alarm
            BackgroundScheduler.getInstance(applicationContext).scheduleWatchdogAlarm()

            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "periodic_worker_failed: ${e.message}")
            io.bearound.sdk.telemetry.ErrorReporter.report(e, "BeaconSyncWorker.doWork")

            // Retry if this is a transient failure
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private fun logSkip(reason: SkipReason) {
        Log.i(TAG, "periodic_worker_skipped reason=$reason")
    }
}
