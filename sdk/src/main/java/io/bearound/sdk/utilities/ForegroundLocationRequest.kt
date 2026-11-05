package io.bearound.sdk.utilities

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationListenerCompat
import android.location.Location
import io.bearound.sdk.models.DeviceLocation

/** Uses existing grants only. Never starts a service or asks for permission. */
internal class ForegroundLocationRequest(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager get() = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    fun eligible(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        manager?.let { LocationManagerCompat.isLocationEnabled(it) } == true &&
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.let {
            !it.isPowerSaveMode && (Build.VERSION.SDK_INT < 29 || it.currentThermalStatus < PowerManager.THERMAL_STATUS_SEVERE)
        } != false

    fun schedule(delay: Long, action: () -> Unit): () -> Unit {
        val runnable = Runnable { action() }
        handler.postDelayed(runnable, delay)
        return { handler.removeCallbacks(runnable) }
    }

    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("MissingPermission")
    fun request(callback: (DeviceLocation?) -> Unit): () -> Unit {
        val locationManager = manager
        if (!eligible() || locationManager == null) { callback(null); return {} }
        val requestedAt = System.currentTimeMillis()
        var listener: LocationListenerCompat? = null
        var delivered = false
        try {
            val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .firstOrNull { locationManager.isProviderEnabled(it) }
            if (provider == null) { callback(null); return {} }
            val receiver = object : LocationListenerCompat {
                override fun onLocationChanged(fix: Location) {
                    if (delivered || fix.time < requestedAt || fix.time > System.currentTimeMillis() ||
                        !fix.hasAccuracy() || fix.accuracy <= 0 || fix.accuracy > 50) return
                    delivered = true
                    runCatching { locationManager.removeUpdates(this) }
                    callback(DeviceLocation(fix.latitude, fix.longitude, fix.accuracy,
                        if (fix.hasAltitude()) fix.altitude else null, fix.time, provider,
                        if (Build.VERSION.SDK_INT >= 31) fix.isMock else fix.isFromMockProvider))
                }
                override fun onProviderDisabled(provider: String) {
                    if (delivered) return
                    delivered = true
                    runCatching { locationManager.removeUpdates(this) }
                    callback(null)
                }
            }
            listener = receiver
            // Unlike getCurrentLocation, this explicitly requests new platform updates.
            locationManager.requestLocationUpdates(provider, 1_000L, 0f, receiver, Looper.getMainLooper())
        } catch (_: SecurityException) { callback(null) }
        catch (_: IllegalArgumentException) { callback(null) }
        return { delivered = true; listener?.let { runCatching { locationManager.removeUpdates(it) } } }
    }
}
