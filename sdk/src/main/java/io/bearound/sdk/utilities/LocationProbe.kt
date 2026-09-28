package io.bearound.sdk.utilities

import android.app.ActivityManager
import android.content.Context
import android.location.LocationManager
import android.util.Log

/**
 * AND1-00 background-location probe (spec sdk-visit-intelligence, D-28).
 *
 * Inert unless the tag is enabled on the device:
 * `adb shell setprop log.tag.BeAroundLocProbe DEBUG`
 *
 * When enabled, logs the process importance, the raw per-provider
 * `getLastKnownLocation` result (with fix age) and the outcome of
 * [LocationCollector.lastKnown], so scripts/probe-background-location.sh can tell
 * "blind in background" apart from "stale fix".
 */
internal object LocationProbe {

    private const val TAG = "BeAroundLocProbe"

    fun log(context: Context, origin: String) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        try {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            val now = System.currentTimeMillis()
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val raw = manager?.getProviders(true).orEmpty().joinToString(",") { provider ->
                val fix = try {
                    @Suppress("MissingPermission")
                    manager?.getLastKnownLocation(provider)
                } catch (e: SecurityException) {
                    null
                }
                if (fix == null) "$provider=null"
                else "$provider=${fix.latitude},${fix.longitude}@age=${(now - fix.time) / 1000}s"
            }
            val sdkFix = LocationCollector(context.applicationContext).lastKnown()
            val sdkResult = if (sdkFix == null) "null"
            else "${sdkFix.latitude},${sdkFix.longitude} src=${sdkFix.source} age=${(now - sdkFix.timestamp) / 1000}s"
            Log.d(
                TAG,
                "probe origin=$origin importance=${info.importance} raw=[$raw] lastKnown=$sdkResult"
            )
        } catch (e: Exception) {
            Log.d(TAG, "probe origin=$origin failed: ${e.message}")
        }
    }
}
