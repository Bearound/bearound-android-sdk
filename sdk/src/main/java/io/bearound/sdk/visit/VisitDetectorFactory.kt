package io.bearound.sdk.visit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import io.bearound.sdk.utilities.WifiCollector

/** What the host app was granted, read once per decision. */
internal data class VisitPermissions(
    val sdkInt: Int,
    val fineLocation: Boolean,
    val coarseLocation: Boolean,
    val backgroundLocation: Boolean,
    val playServicesAvailable: Boolean
) {
    val anyLocation: Boolean get() = fineLocation || coarseLocation
}

/**
 * Chooses the visit detector. The SDK never declares
 * `ACCESS_BACKGROUND_LOCATION`; the native geofence path is used only when the HOST declares
 * it and the user grants it.
 */
internal object VisitDetectorFactory {

    /** After a native registration failure, the soft fence runs for this long before retrying. */
    const val NATIVE_RETRY_AFTER_MS = 60L * 60 * 1000

    /**
     * Native geofences need location access in background: `ACCESS_BACKGROUND_LOCATION` on
     * API 29+, fine location alone below that (where it already covers background). They
     * also need fine location itself (`GeofencingClient` requires it) and Play Services.
     * Anything else, or a registration that failed within [NATIVE_RETRY_AFTER_MS], gets the
     * soft fence.
     */
    fun choose(permissions: VisitPermissions, nativeFailedAt: Long?, now: Long): VisitDetectionMode {
        val backgroundAccess = if (permissions.sdkInt >= Build.VERSION_CODES.Q) {
            permissions.backgroundLocation
        } else {
            permissions.fineLocation
        }
        val recentlyFailed = nativeFailedAt != null && now - nativeFailedAt < NATIVE_RETRY_AFTER_MS
        val native = backgroundAccess &&
            permissions.fineLocation &&
            permissions.playServicesAvailable &&
            !recentlyFailed
        return if (native) VisitDetectionMode.NATIVE_GEOFENCE else VisitDetectionMode.SOFT_FENCE
    }

    fun readPermissions(context: Context): VisitPermissions {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        return VisitPermissions(
            sdkInt = Build.VERSION.SDK_INT,
            fineLocation = granted(Manifest.permission.ACCESS_FINE_LOCATION),
            coarseLocation = granted(Manifest.permission.ACCESS_COARSE_LOCATION),
            backgroundLocation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            playServicesAvailable = isPlayServicesAvailable(context)
        )
    }

    private fun isPlayServicesAvailable(context: Context): Boolean = try {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    } catch (e: Throwable) {
        false
    }

    fun create(
        mode: VisitDetectionMode,
        context: Context,
        store: VisitStateStore,
        tracker: VisitStopTracker
    ): VisitDetector = when (mode) {
        VisitDetectionMode.NATIVE_GEOFENCE ->
            NativeGeofenceVisitDetector(PlayServicesGeofenceRegistrar(context), store, tracker)
        VisitDetectionMode.SOFT_FENCE ->
            SoftFenceVisitDetector(store, tracker, WifiVisitRunner(tracker, WifiCacheReader.of(WifiCollector(context))))
    }
}
