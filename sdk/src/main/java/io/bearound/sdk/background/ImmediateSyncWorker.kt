package io.bearound.sdk.background

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.bearound.sdk.BeAroundSDK
import java.util.concurrent.TimeUnit

/**
 * One-shot flush of pending beacons, enqueued right after a background
 * detection (top-5 fix #1: "detects in seconds, delivers in minutes").
 *
 * Why a Worker: the broadcast window (goAsync) covers the fast path, but if the
 * process dies or the network is gated by Doze, this Worker re-runs the flush
 * inside a system-granted execution window, deferred until network is actually
 * available. Expedited on Android 12+ (typically starts in <1s); plain one-time
 * work below that (expedited would require a visible foreground notification).
 *
 * The work is a no-op when the fast path already delivered (nothing pending).
 */
class ImmediateSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sdk = BeAroundSDK.getInstance(applicationContext)

        if (!sdk.isConfigured) {
            sdk.attemptConfigRestore()
            if (!sdk.isConfigured) {
                Log.w(TAG, "SDK not configured — nothing to flush")
                return Result.success()
            }
        }

        if (!sdk.hasPendingBeacons()) {
            Log.d(TAG, "Nothing pending — fast path already delivered")
            return Result.success()
        }

        val ok = sdk.performBackgroundSyncAwait()
        return when {
            ok -> Result.success()
            runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            else -> {
                // Give up on the window; the offline batch storage + periodic
                // worker remain as the long-tail retry path.
                Log.w(TAG, "Flush failed after $runAttemptCount attempts — deferring to periodic sync")
                Result.failure()
            }
        }
    }

    companion object {
        private const val TAG = "BeAroundSDK-FlushWorker"
        private const val WORK_NAME = "bearound_immediate_flush"
        private const val MAX_ATTEMPTS = 3

        fun enqueue(context: Context) {
            try {
                val builder = OneTimeWorkRequestBuilder<ImmediateSyncWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }

                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    builder.build()
                )
            } catch (e: Exception) {
                // WorkManager not initialized in exotic hosts — the fast path and
                // the 15-min fallbacks still stand.
                Log.w(TAG, "Could not enqueue immediate flush: ${e.message}")
            }
        }
    }
}
