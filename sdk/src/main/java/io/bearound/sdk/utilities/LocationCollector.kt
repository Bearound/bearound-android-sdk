package io.bearound.sdk.utilities

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import io.bearound.sdk.models.DeviceLocation

/**
 * Reads the device's **last known** position — never requests a new one.
 *
 * That restraint is the whole design: an active fix wakes the GPS, costs battery and
 * takes seconds. The last known fix is free, already in memory, and good enough to
 * give the observations in this payload a place. It carries its own timestamp, so a
 * stale fix is visible as stale to whoever consumes it rather than silently wrong.
 */
internal class LocationCollector(private val context: Context) {

    companion object {
        private const val TAG = "LocationCollector"

        /** Older than this and the device has probably moved somewhere else entirely. */
        private const val MAX_AGE_MS = 10 * 60 * 1000L
    }

    /**
     * @return the freshest usable fix, or `null` when the host app has no location
     *         permission, location is off, or every fix is too old. Never throws.
     */
    fun lastKnown(): DeviceLocation? {
        if (!hasLocationPermission()) return null

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        return try {
            val now = System.currentTimeMillis()
            manager.providers(enabledOnly = true)
                .mapNotNull { provider ->
                    @Suppress("MissingPermission")
                    manager.getLastKnownLocation(provider)?.let { provider to it }
                }
                .filter { (_, location) -> now - location.time <= MAX_AGE_MS }
                // Freshest wins; accuracy breaks ties.
                .maxWithOrNull(
                    compareBy<Pair<String, Location>> { it.second.time }
                        .thenByDescending { it.second.accuracy }
                )
                ?.let { (provider, location) -> location.toDeviceLocation(provider) }
        } catch (e: SecurityException) {
            Log.d(TAG, "location unavailable: ${e.message}")
            null
        } catch (e: Exception) {
            Log.d(TAG, "location failed: ${e.message}")
            null
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    private fun LocationManager.providers(enabledOnly: Boolean): List<String> = try {
        getProviders(enabledOnly)
    } catch (e: Exception) {
        emptyList()
    }

    private fun Location.toDeviceLocation(provider: String) = DeviceLocation(
        latitude = latitude,
        longitude = longitude,
        accuracy = accuracy.takeIf { it > 0f },
        altitude = if (hasAltitude()) altitude else null,
        timestamp = time,
        source = provider,
        isMocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else isFromMockProviderCompat()
    )

    @Suppress("DEPRECATION")
    private fun Location.isFromMockProviderCompat(): Boolean? = try {
        isFromMockProvider
    } catch (e: Exception) {
        null
    }
}
