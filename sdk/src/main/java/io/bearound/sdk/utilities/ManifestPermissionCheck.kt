package io.bearound.sdk.utilities

import android.Manifest
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Guardrail: verifies at configure() time that the merged manifest still carries
 * `neverForLocation` on BLUETOOTH_SCAN.
 *
 * This SDK (and the companion Bearound Telemetry SDK) declare the flag; the manifest
 * merger silently DROPS it when any third-party library declares BLUETOOTH_SCAN
 * without it. In that regime Android withholds every scan result unless fine location
 * is granted — background detection for users without location dies silently, and the
 * companion telemetry goes blind for them too. Surfacing it beats debugging silence.
 */
internal object ManifestPermissionCheck {

    private const val TAG = "BeAroundSDK-Manifest"

    fun verify(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val pi = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS
            )
            val idx = pi.requestedPermissions?.indexOf(Manifest.permission.BLUETOOTH_SCAN) ?: -1
            if (idx < 0) return
            val flags = pi.requestedPermissionsFlags?.getOrNull(idx) ?: 0
            if ((flags and PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION) == 0) {
                Log.e(
                    TAG,
                    "neverForLocation was DROPPED from the merged manifest (some library " +
                        "declares BLUETOOTH_SCAN without it). Scan results are now withheld " +
                        "for every user without fine location — background detection and " +
                        "companion telemetry go blind for them. Fix in the APP manifest: " +
                        "<uses-permission android:name=\"android.permission.BLUETOOTH_SCAN\" " +
                        "android:usesPermissionFlags=\"neverForLocation\" " +
                        "tools:replace=\"android:usesPermissionFlags\" />"
                )
                io.bearound.sdk.telemetry.ErrorReporter.report(
                    IllegalStateException("neverForLocation dropped from merged manifest"),
                    "ManifestPermissionCheck"
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Manifest permission check failed: ${t.message}")
        }
    }
}
