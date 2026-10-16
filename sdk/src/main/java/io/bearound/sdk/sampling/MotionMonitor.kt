package io.bearound.sdk.sampling

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal enum class MotionAvailability { AUTHORIZED, PERMISSION_ABSENT, PERMISSION_DENIED, SERVICES_UNAVAILABLE }

internal interface MotionActivityRegistrar {
    fun availability(): MotionAvailability
    fun register(generation: Long, onEvent: (NativeActivityObservation) -> Unit, onResult: (Throwable?) -> Unit)
    fun unregister(generation: Long)
}

/** No permission requests. Every registration and delivery is checked against its lifecycle. */
internal class MotionMonitor(
    private val policy: AdaptiveSamplingPolicy,
    private val registrar: MotionActivityRegistrar,
    private val collectionAllowed: () -> Boolean = { true },
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime
) {
    companion object { private val generations = AtomicLong() }

    private var generation: Long? = null
    private var startedAt: Long = 0
    private var lastElapsed: Long = 0
    private var accepting = false

    @Synchronized
    fun start() {
        stop()
        policy.invalidateNative(MotionReason.STARTUP)
        startedAt = elapsedRealtime()
        lastElapsed = startedAt
        val current = generations.incrementAndGet()
        generation = current
        if (!authorized()) {
            policy.invalidateNative(MotionReason.PERMISSION_UNAVAILABLE)
            return
        }
        accepting = true
        try {
            registrar.register(current, { event -> onEvent(current, event) }, { error -> onRegistered(current, error) })
        } catch (error: Exception) {
            onRegistered(current, error)
        }
    }

    @Synchronized
    fun stop() {
        val previous = generation
        generation = null
        accepting = false
        if (previous != null) remove(previous)
        policy.invalidateNative(MotionReason.NO_EVIDENCE)
    }

    /** Call from existing SDK ticks; it does not add a wakeup or request permission. */
    @Synchronized
    fun refreshAuthorization() {
        val current = generation ?: return
        if (!authorized()) {
            accepting = false
            remove(current)
            policy.invalidateNative(MotionReason.PERMISSION_UNAVAILABLE)
        } else if (!accepting) {
            start()
        }
    }

    @Synchronized
    private fun onEvent(current: Long, event: NativeActivityObservation) {
        if (generation != current || !accepting) return
        if (!authorized()) {
            refreshAuthorization()
            return
        }
        val observed = event.observedAtElapsedMs ?: return
        val now = elapsedRealtime()
        if (now < lastElapsed) {
            stop()
            policy.reset(MotionReason.CLOCK_UNCERTAIN)
            return
        }
        lastElapsed = now
        // A delayed broadcast from before registration cannot start this process's window.
        if (observed < startedAt) return
        policy.onNativeActivity(event)
    }

    @Synchronized
    private fun onRegistered(current: Long, error: Throwable?) {
        if (generation != current || !accepting) {
            remove(current)
            return
        }
        if (error != null || !authorized()) {
            accepting = false
            remove(current)
            policy.invalidateNative(if (error is SecurityException) MotionReason.PERMISSION_UNAVAILABLE else MotionReason.UNSUPPORTED)
        }
    }

    private fun authorized(): Boolean = try {
        collectionAllowed() && registrar.availability() == MotionAvailability.AUTHORIZED
    } catch (_: Exception) {
        false
    }

    private fun remove(current: Long) {
        try { registrar.unregister(current) } catch (_: Exception) { }
    }
}

internal class PlayServicesMotionRegistrar(context: Context) : MotionActivityRegistrar {
    private val context = context.applicationContext
    private val instanceId = UUID.randomUUID().toString()
    private data class Registration(val pending: PendingIntent, var pendingResult: Boolean = true, var cancelled: Boolean = false)
    private val registrations = mutableMapOf<Long, Registration>()
    private val client by lazy { ActivityRecognition.getClient(this.context) }

    override fun availability(): MotionAvailability {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Manifest.permission.ACTIVITY_RECOGNITION
            else "com.google.android.gms.permission.ACTIVITY_RECOGNITION"
        @Suppress("DEPRECATION")
        val declared = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().contains(permission)
        if (!declared) return MotionAvailability.PERMISSION_ABSENT
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            return MotionAvailability.PERMISSION_DENIED
        }
        return if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS)
            MotionAvailability.AUTHORIZED else MotionAvailability.SERVICES_UNAVAILABLE
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    override fun register(generation: Long, onEvent: (NativeActivityObservation) -> Unit, onResult: (Throwable?) -> Unit) {
        if (availability() != MotionAvailability.AUTHORIZED) {
            onResult(SecurityException("Activity recognition is not already authorized"))
            return
        }
        val intent = Intent(context, MotionTransitionReceiver::class.java)
            .setAction(MotionTransitionReceiver.ACTION_TRANSITION)
            .setData(Uri.parse("bearound-motion://$instanceId/$generation"))
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(context, MotionTransitionReceiver.REQUEST_CODE, intent, flags)
        registrations[generation] = Registration(pending)
        MotionTransitionDispatch.register(intent.data.toString(), onEvent)
        val transitions = listOf(DetectedActivity.STILL, DetectedActivity.WALKING, DetectedActivity.RUNNING,
            DetectedActivity.ON_BICYCLE, DetectedActivity.IN_VEHICLE).flatMap { activity ->
            listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map { transition ->
                ActivityTransition.Builder().setActivityType(activity).setActivityTransition(transition).build()
            }
        }
        try {
            client.requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pending)
                .addOnSuccessListener { complete(generation, null, onResult) }
                .addOnFailureListener { complete(generation, it, onResult) }
        } catch (error: Exception) {
            complete(generation, error, onResult)
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    override fun unregister(generation: Long) {
        val registration = registrations[generation] ?: return
        registration.cancelled = true
        MotionTransitionDispatch.unregister("bearound-motion://$instanceId/$generation")
        remove(registration.pending, cancel = !registration.pendingResult)
        if (!registration.pendingResult) registrations.remove(generation)
    }

    private fun complete(generation: Long, error: Throwable?, onResult: (Throwable?) -> Unit) {
        val registration = synchronized(this) {
            val current = registrations[generation] ?: return
            current.pendingResult = false
            if (current.cancelled) registrations.remove(generation)
            current
        }
        if (registration.cancelled) {
            // Removal before registration completed is repeated after its final result.
            remove(registration.pending, cancel = true)
            return
        }
        onResult(error)
    }

    @SuppressLint("MissingPermission")
    private fun remove(pending: PendingIntent, cancel: Boolean) {
        try {
            client.removeActivityTransitionUpdates(pending).addOnCompleteListener {
                if (cancel) pending.cancel()
            }
        } catch (_: Exception) {
            if (cancel) pending.cancel()
        }
    }
}
