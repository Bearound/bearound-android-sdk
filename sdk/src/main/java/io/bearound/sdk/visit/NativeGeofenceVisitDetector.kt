package io.bearound.sdk.visit

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import io.bearound.sdk.BeAroundSDK
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** A geofence to register, in plain values so the plan is testable without Play Services. */
internal data class VisitGeofence(
    val requestId: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    /** `Geofence.GEOFENCE_TRANSITION_*` bitmask. */
    val transitions: Int,
    val loiteringDelayMs: Int
)

/** A geofence broadcast reduced to plain values. */
internal data class GeofenceSignal(
    val transition: Int,
    val requestIds: List<String>,
    /** The fix that triggered the transition, with its real time. */
    val fix: VisitFix?,
    /** Set when Play Services reports an error instead of a transition. */
    val errorCode: Int? = null
)

/** The slice of `GeofencingClient` the detector uses. */
internal interface GeofenceRegistrar {
    /** Replaces every SDK visit geofence with [fences]; [onResult] gets `null` on success. */
    fun replaceAll(fences: List<VisitGeofence>, onResult: (Throwable?) -> Unit)

    /** Removes only the SDK visit geofences (they all share one PendingIntent). */
    fun removeAll()
}

/**
 * Native path: `GeofencingClient` DWELL around the nearest target
 * environments, used only when the host declares and the user grants
 * `ACCESS_BACKGROUND_LOCATION` (see [VisitDetectorFactory]). Unlike the soft fence it works
 * with the app killed: the geofence broadcast goes to [VisitGeofenceReceiver], declared in
 * the SDK manifest, which revives the process.
 *
 * - DWELL (loitering delay = `minDwellMinutes`) sends the arrival with the triggering fix;
 * - EXIT of the same environment sends the departure: the stop position with the real time
 *   of the exit fix (the exit fix itself lies outside the environment by definition, and the
 *   ingest resolves the environment from the coordinates);
 * - EXIT of the refresh fence (circle at `origin`, radius `refreshAfterMeters`) makes the
 *   controller fetch the config again.
 *
 * A registration failure is recorded; the factory then picks the soft fence on the next tick.
 */
internal class NativeGeofenceVisitDetector(
    private val registrar: GeofenceRegistrar,
    private val store: VisitStateStore,
    private val tracker: VisitStopTracker,
    private val clock: () -> Long = System::currentTimeMillis,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime
) : VisitDetector {

    companion object {
        private const val TAG = "BeAroundSDK-Visit"

        /**
         * The SDK's own geofence budget: the refresh fence plus the 19 nearest targets.
         * Android allows 100 active geofences per app and the host shares that ceiling, so
         * the SDK never takes more than a fifth of it.
         */
        const val MAX_GEOFENCES = 20
        const val REFRESH_FENCE_ID = "bearound.visit.refresh"
        const val TARGET_PREFIX = "bearound.visit.env:"

        /** A registration older than this is redone even when nothing changed (self-heal). */
        const val REREGISTER_AFTER_MS = 6L * 60 * 60 * 1000

        /** Tolerance when comparing boot times derived from two clocks. */
        private const val BOOT_TOLERANCE_MS = 60_000L

        /** The platform does not honour circles much smaller than this. */
        const val MIN_TARGET_RADIUS_METERS = 100.0

        /**
         * The geofences for [config]: the refresh fence plus the nearest targets, up to
         * [MAX_GEOFENCES] in total.
         */
        fun plan(config: PlacesConfig): List<VisitGeofence> {
            val fences = mutableListOf<VisitGeofence>()
            // Same floored radius the controller's distance check uses.
            fences += VisitGeofence(
                requestId = REFRESH_FENCE_ID,
                latitude = config.origin.lat,
                longitude = config.origin.lng,
                radiusMeters = VisitController.effectiveRefreshAfterMeters(config.refreshAfterMeters).toFloat(),
                transitions = Geofence.GEOFENCE_TRANSITION_EXIT,
                loiteringDelayMs = 0
            )
            config.places
                .sortedBy { it.distanceMeters }
                .take(MAX_GEOFENCES - fences.size)
                .forEach { place ->
                    val dwellMinutes = (place.minDwellMinutes ?: SoftFenceVisitDetector.DEFAULT_MIN_DWELL_MINUTES)
                        .coerceAtLeast(1)
                    fences += VisitGeofence(
                        requestId = TARGET_PREFIX + place.environmentId,
                        latitude = place.center.lat,
                        longitude = place.center.lng,
                        radiusMeters = maxOf(place.radiusMeters, MIN_TARGET_RADIUS_METERS).toFloat(),
                        transitions = Geofence.GEOFENCE_TRANSITION_DWELL or Geofence.GEOFENCE_TRANSITION_EXIT,
                        loiteringDelayMs = dwellMinutes * 60_000
                    )
                }
            return fences
        }

        /**
         * The smaller set tried once after `GEOFENCE_TOO_MANY_GEOFENCES` (the host already
         * holds most of the per-app ceiling): the first half of [fences], which keeps the
         * refresh fence and the nearest targets because [plan] puts them first.
         */
        fun halved(fences: List<VisitGeofence>): List<VisitGeofence> = fences.take(fences.size / 2)

        fun isTooManyGeofences(error: Throwable?): Boolean =
            (error as? ApiException)?.statusCode == GeofenceStatusCodes.GEOFENCE_TOO_MANY_GEOFENCES

        /**
         * Removes a registration a previous process left in Play Services. Geofences outlive
         * the process that armed them, so an in-memory "nothing armed" proves nothing: the
         * persisted [VisitStateStore.nativeRegistration] is the only record of them.
         * @return true when there was one to remove.
         */
        fun removeStaleRegistration(store: VisitStateStore, registrar: () -> GeofenceRegistrar?): Boolean {
            if (store.nativeRegistration == null) return false
            val target = registrar() ?: return false
            target.removeAll()
            store.nativeRegistration = null
            Log.i(TAG, "Removed visit geofences armed by a previous process")
            return true
        }

        fun environmentIdOf(requestId: String): String? =
            if (requestId.startsWith(TARGET_PREFIX)) requestId.removePrefix(TARGET_PREFIX) else null
    }

    override val mode = VisitDetectionMode.NATIVE_GEOFENCE

    override fun apply(config: PlacesConfig?, now: Long) {
        when {
            // No list yet (first run without a successful fetch): nothing native.
            config == null -> Unit
            // Kill switch: only the visit geofences go.
            !config.visitDetectionEnabled -> tearDown()
            else -> register(plan(config), now)
        }
    }

    /**
     * Registers [fences] unless Play Services already holds exactly this set from this boot.
     * Every revived process applies the config again, and re-registering would restart the
     * DWELL loitering timer each time: a process revived more often than `minDwellMinutes`
     * (scan broadcasts near beacons, the watchdog) would never see a DWELL.
     */
    private fun register(fences: List<VisitGeofence>, now: Long) {
        val signature = fences.hashCode().toString(16)
        val bootAt = now - elapsedRealtime()
        val current = store.nativeRegistration
        if (current != null &&
            current.signature == signature &&
            kotlin.math.abs(current.bootAt - bootAt) < BOOT_TOLERANCE_MS &&
            now - current.registeredAt < REREGISTER_AFTER_MS
        ) {
            return
        }
        registrar.replaceAll(fences) { error ->
            when {
                error == null -> onRegistered(fences, signature, now, bootAt)
                isTooManyGeofences(error) && fences.size > 1 -> {
                    // The host holds most of the per-app ceiling: one retry with half the set.
                    val smaller = halved(fences)
                    Log.w(TAG, "Too many geofences for the app, retrying with ${smaller.size} of ${fences.size}")
                    registrar.replaceAll(smaller) { retryError ->
                        if (retryError == null) onRegistered(smaller, signature, now, bootAt) else onFailed(retryError)
                    }
                }
                else -> onFailed(error)
            }
        }
    }

    /**
     * [signature] is the one of the full plan, not of what was registered: the next apply of
     * the same plan must not register again (it would restart the DWELL timers).
     */
    private fun onRegistered(registered: List<VisitGeofence>, signature: String, now: Long, bootAt: Long) {
        store.nativeFailedAt = null
        store.nativeRegistration = VisitStateStore.NativeRegistration(signature, now, bootAt)
        Log.i(TAG, "Visit geofences registered: ${registered.size}")
    }

    private fun onFailed(error: Throwable) {
        store.nativeFailedAt = clock()
        store.nativeRegistration = null
        Log.w(TAG, "Visit geofence registration failed, soft fence on the next tick: ${error.message}")
    }

    /** Stops come from DWELL/EXIT; the controller still refreshes by age and distance on ticks. */
    override fun onTick(fix: VisitFix?, now: Long) = Unit

    override fun tearDown() {
        registrar.removeAll()
        store.nativeRegistration = null
    }

    /** @return true when the refresh fence was exited and the config must be fetched again. */
    fun onTransition(signal: GeofenceSignal): Boolean {
        signal.errorCode?.let { code ->
            // GEOFENCE_NOT_AVAILABLE: location turned off, every geofence is gone.
            store.nativeFailedAt = clock()
            store.nativeRegistration = null
            Log.w(TAG, "Geofence error ${GeofenceStatusCodes.getStatusCodeString(code)}, soft fence on the next tick")
            return false
        }
        var refresh = false
        for (requestId in signal.requestIds) {
            if (requestId == REFRESH_FENCE_ID) {
                if (signal.transition == Geofence.GEOFENCE_TRANSITION_EXIT) refresh = true
                continue
            }
            val environmentId = environmentIdOf(requestId) ?: continue
            val fix = signal.fix ?: continue
            when (signal.transition) {
                Geofence.GEOFENCE_TRANSITION_DWELL -> if (tracker.arrive(environmentId, fix)) {
                    Log.i(TAG, "Geofence DWELL: arrival at $environmentId (fix at ${fix.timestamp})")
                }
                Geofence.GEOFENCE_TRANSITION_EXIT -> {
                    val open = tracker.openStop() ?: continue
                    if (open.environmentId != environmentId) continue
                    val departure = open.arrival?.copy(timestamp = fix.timestamp, isMocked = fix.isMocked) ?: fix
                    if (tracker.depart(environmentId, departure)) {
                        Log.i(TAG, "Geofence EXIT: departure from $environmentId (fix at ${fix.timestamp})")
                    }
                }
            }
        }
        return refresh
    }
}

/** [GeofenceRegistrar] over Play Services `GeofencingClient`. */
internal class PlayServicesGeofenceRegistrar(context: Context) : GeofenceRegistrar {

    companion object {
        private const val TAG = "BeAroundSDK-Visit"
        private const val REQUEST_CODE = 19931

        /**
         * Lets Play Services batch transitions; well inside the 15 min budget for sending both
         * visit events after a detection.
         */
        private const val NOTIFICATION_RESPONSIVENESS_MS = 60_000

        /**
         * Task listeners run here, not on the main thread (Play Services' default): the
         * result writes [VisitStateStore] with `commit()`, and a retry registers again.
         */
        private val callbackExecutor: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "bearound-visit-geofence").apply { isDaemon = true }
        }

        fun toPlayGeofence(fence: VisitGeofence): Geofence = Geofence.Builder()
            .setRequestId(fence.requestId)
            .setCircularRegion(fence.latitude, fence.longitude, fence.radiusMeters)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(fence.transitions)
            .setLoiteringDelay(fence.loiteringDelayMs)
            .setNotificationResponsiveness(NOTIFICATION_RESPONSIVENESS_MS)
            .build()
    }

    private val context = context.applicationContext
    private val client by lazy { LocationServices.getGeofencingClient(this.context) }

    private val pendingIntent: PendingIntent by lazy {
        val intent = Intent(this.context, VisitGeofenceReceiver::class.java)
            .setAction(VisitGeofenceReceiver.ACTION_GEOFENCE)
        // Mutable on purpose: Play Services fills in the transition extras.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        PendingIntent.getBroadcast(this.context, REQUEST_CODE, intent, flags)
    }

    @SuppressLint("MissingPermission")
    override fun replaceAll(fences: List<VisitGeofence>, onResult: (Throwable?) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            onResult(SecurityException("ACCESS_FINE_LOCATION not granted"))
            return
        }
        try {
            // Remove-then-add: ids from a previous list that are no longer near must go too.
            client.removeGeofences(pendingIntent).addOnCompleteListener(callbackExecutor) {
                if (fences.isEmpty()) {
                    onResult(null)
                    return@addOnCompleteListener
                }
                try {
                    val request = GeofencingRequest.Builder()
                        // Already inside when registered: the dwell still counts.
                        .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_DWELL)
                        .addGeofences(fences.map(::toPlayGeofence))
                        .build()
                    client.addGeofences(request, pendingIntent)
                        .addOnSuccessListener(callbackExecutor) { onResult(null) }
                        .addOnFailureListener(callbackExecutor) { onResult(it) }
                } catch (e: Exception) {
                    onResult(e)
                }
            }
        } catch (e: Exception) {
            onResult(e)
        }
    }

    /** Removes only the geofences behind the SDK's own PendingIntent; the host's stay. */
    override fun removeAll() {
        try {
            client.removeGeofences(pendingIntent)
        } catch (e: Exception) {
            Log.d(TAG, "Visit geofence removal failed: ${e.message}")
        }
    }
}

/**
 * Receives the visit geofence transitions. Declared in the SDK manifest (not exported), so
 * Play Services can deliver a transition with the process dead; the SDK restores its
 * configuration from storage and handles it inside a `goAsync()` window.
 */
class VisitGeofenceReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_GEOFENCE = "io.bearound.sdk.ACTION_VISIT_GEOFENCE"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val event = try {
            GeofencingEvent.fromIntent(intent)
        } catch (e: Exception) {
            null
        } ?: return
        val signal = if (event.hasError()) {
            GeofenceSignal(transition = -1, requestIds = emptyList(), fix = null, errorCode = event.errorCode)
        } else {
            GeofenceSignal(
                transition = event.geofenceTransition,
                requestIds = event.triggeringGeofences?.map { it.requestId }.orEmpty(),
                fix = event.triggeringLocation?.toVisitFix()
            )
        }
        val pending = goAsync()
        BeAroundSDK.getInstance(context.applicationContext).handleVisitGeofenceSignal(signal) {
            pending.finish()
        }
    }

    private fun Location.toVisitFix() = VisitFix(
        latitude = latitude,
        longitude = longitude,
        accuracy = if (hasAccuracy()) accuracy else null,
        timestamp = time,
        isMocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else isFromMockProviderCompat()
    )

    @Suppress("DEPRECATION")
    private fun Location.isFromMockProviderCompat(): Boolean? = try {
        isFromMockProvider
    } catch (e: Exception) {
        null
    }
}
