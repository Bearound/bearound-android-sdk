package io.bearound.sdk.sampling

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import java.util.concurrent.ConcurrentHashMap

internal object MotionTransitionDispatch {
    private val callbacks = ConcurrentHashMap<String, (NativeActivityObservation) -> Unit>()
    fun register(id: String, callback: (NativeActivityObservation) -> Unit) { callbacks[id] = callback }
    fun unregister(id: String) { callbacks.remove(id) }
    fun dispatch(id: String, observation: NativeActivityObservation): Boolean {
        val callback = callbacks[id] ?: return false
        callback(observation)
        return true
    }
    fun contains(id: String): Boolean = callbacks.containsKey(id)
}

/** Explicit, non-exported receiver. It never restores an SDK or starts collection. */
class MotionTransitionReceiver : BroadcastReceiver() {
    companion object {
        internal const val ACTION_TRANSITION = "io.bearound.sdk.ACTION_MOTION_TRANSITION"
        internal const val REQUEST_CODE = 19932
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != ACTION_TRANSITION) return
        val id = intent.data?.toString() ?: return
        if (!MotionTransitionDispatch.contains(id)) {
            removeOrphan(context, intent)
            return
        }
        try {
            val result = ActivityTransitionResult.extractResult(intent) ?: return
            result.transitionEvents.forEach { event ->
                val activity = when (event.activityType) {
                    DetectedActivity.STILL -> NativeMotionActivity.STILL
                    DetectedActivity.WALKING -> NativeMotionActivity.WALKING
                    DetectedActivity.RUNNING -> NativeMotionActivity.RUNNING
                    DetectedActivity.ON_BICYCLE -> NativeMotionActivity.CYCLING
                    DetectedActivity.IN_VEHICLE -> NativeMotionActivity.VEHICLE
                    else -> return@forEach
                }
                MotionTransitionDispatch.dispatch(id, NativeActivityObservation(activity,
                    event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER,
                    (event.elapsedRealTimeNanos / 1_000_000L).takeIf { event.elapsedRealTimeNanos > 0 }))
            }
        } catch (_: Exception) { }
    }

    @SuppressLint("MissingPermission")
    private fun removeOrphan(context: Context, original: Intent) {
        try {
            val flags = PendingIntent.FLAG_NO_CREATE or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val intent = Intent(context, MotionTransitionReceiver::class.java)
                .setAction(ACTION_TRANSITION).setData(original.data)
            val pending = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags) ?: return
            ActivityRecognition.getClient(context).removeActivityTransitionUpdates(pending)
            pending.cancel()
        } catch (_: Exception) { }
    }
}
