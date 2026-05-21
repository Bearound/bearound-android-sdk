package io.bearound.sdk

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Captures device location on demand, gated by external triggers.
 *
 * Doctrine (mirrors the iOS SDK v2.4 model): GPS is OFF by default. The SDK
 * opens a one-shot capture window only when a beacon is detected. The window
 * closes on:
 *   - first acceptable fix (accuracy ≤ [ACCEPT_ACCURACY_M])
 *   - timeout ([WINDOW_FOREGROUND_MS] / [WINDOW_BACKGROUND_MS])
 *   - explicit external stop (beacons lost, scan stopped)
 *
 * The result is delivered via [onCaptureCompleted] with the location acquired
 * during *this* window (never stale data from a previous window).
 */
class LocationCaptureManager(private val context: Context) {

    companion object {
        private const val TAG = "BeAroundSDK-LocCap"
        /** Max capture window in foreground (ms). */
        const val WINDOW_FOREGROUND_MS = 30_000L
        /** Max capture window in background (ms). Shorter to conserve battery. */
        const val WINDOW_BACKGROUND_MS = 15_000L
        /** Accept the fix and close the window when accuracy is ≤ this many meters. */
        const val ACCEPT_ACCURACY_M = 30.0f
        /**
         * Re-trigger capture if [lastLocation] is older than this (ms).
         * v2.5: 2 min during initial rollout so the UI shows a fresh fix often
         * enough to be useful for debugging. Production tuning later.
         */
        const val STALE_THRESHOLD_MS = 2 * 60_000L
        /** Maximum acceptable horizontalAccuracy to store as lastLocation (filter junk). */
        private const val MAX_ACCURACY_M = 100.0f
        /** Maximum acceptable age of a delivered fix when first received (ms). */
        private const val MAX_FIX_AGE_MS = 15_000L
    }

    /** Last location seen across any window. Null if never captured. */
    var lastLocation: Location? = null
        private set

    /** True while a capture window is currently open. */
    var isCapturing: Boolean = false
        private set

    /** Reason the current capture window was opened (null when not capturing). */
    private var currentReason: String? = null

    /** Location acquired during the current window only. Reset on each [start]. */
    private var capturedInWindow: Location? = null

    /** True while host app is in foreground (controls window length + provider availability). */
    private var isInForeground = true

    // Callbacks
    var onCaptureStarted: ((String) -> Unit)? = null
    var onCaptureCompleted: ((Location?, String, String) -> Unit)? = null

    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleLocationUpdate(location)
        }

        // Pre-Q method — older runtimes still invoke it.
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun setForegroundState(inForeground: Boolean) {
        isInForeground = inForeground
    }

    /** True if [lastLocation] is missing or older than [STALE_THRESHOLD_MS]. */
    fun isLocationStale(): Boolean {
        val loc = lastLocation ?: return true
        val ageMs = System.currentTimeMillis() - loc.time
        return ageMs > STALE_THRESHOLD_MS
    }

    /** Open a one-shot capture window. Idempotent — no-op if already capturing. */
    @SuppressLint("MissingPermission")
    fun start(reason: String) {
        if (isCapturing) {
            Log.d(TAG, "start($reason) skipped — already capturing")
            return
        }
        if (!hasLocationPermission()) {
            Log.w(TAG, "start($reason) skipped — missing ACCESS_FINE_LOCATION")
            return
        }

        currentReason = reason
        capturedInWindow = null
        isCapturing = true

        val provider = pickProvider()
        if (provider == null) {
            Log.w(TAG, "start($reason) — no enabled provider")
            // Surface a synthetic "no provider" completion immediately
            finish(outcome = "no_provider_available")
            return
        }

        val windowMs = if (isInForeground) WINDOW_FOREGROUND_MS else WINDOW_BACKGROUND_MS
        Log.d(TAG, "Capture STARTED (reason=$reason, provider=$provider, window=${windowMs}ms, fg=$isInForeground)")

        try {
            locationManager.requestLocationUpdates(
                provider,
                0L,
                0f,
                locationListener,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            Log.e(TAG, "requestLocationUpdates failed: ${e.message}")
            finish(outcome = "request_failed")
            return
        }

        onCaptureStarted?.invoke(reason)

        timeoutRunnable = Runnable { finish(outcome = "timeout") }
        handler.postDelayed(timeoutRunnable!!, windowMs)
    }

    /** Close the capture window. Idempotent. */
    @SuppressLint("MissingPermission")
    fun stop(outcome: String) {
        if (!isCapturing) return
        finish(outcome = outcome)
    }

    @SuppressLint("MissingPermission")
    private fun finish(outcome: String) {
        val openingReason = currentReason ?: "unknown"
        val captured = capturedInWindow

        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
        try {
            locationManager.removeUpdates(locationListener)
        } catch (e: Exception) {
            Log.w(TAG, "removeUpdates threw: ${e.message}")
        }

        isCapturing = false
        currentReason = null
        capturedInWindow = null

        Log.d(TAG, "Capture STOPPED (outcome=$outcome, gotFixInWindow=${captured != null})")
        onCaptureCompleted?.invoke(captured, openingReason, outcome)
    }

    private fun handleLocationUpdate(location: Location) {
        if (!isCapturing) return

        val ageMs = System.currentTimeMillis() - location.time
        if (ageMs > MAX_FIX_AGE_MS) return
        if (!location.hasAccuracy()) return
        if (location.accuracy <= 0f || location.accuracy >= MAX_ACCURACY_M) return

        // Always update lastLocation with the freshest usable fix
        lastLocation = location
        capturedInWindow = location

        // If accuracy is good enough, close the window early
        if (location.accuracy <= ACCEPT_ACCURACY_M) {
            finish(outcome = "fix_acquired_acc=${location.accuracy.toInt()}m")
        }
    }

    private fun pickProvider(): String? {
        // Prefer fused/GPS when available, fall back to network.
        val fused = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "fused" else null
        val candidates = listOfNotNull(
            fused,
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )
        for (provider in candidates) {
            try {
                if (locationManager.isProviderEnabled(provider)) return provider
            } catch (_: IllegalArgumentException) {
                // Provider not present on this device (e.g. "fused" pre-S) — try next
            }
        }
        return null
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
