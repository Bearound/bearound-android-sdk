package io.bearound.sdk.background

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import io.bearound.sdk.BeAroundSDK

/**
 * BroadcastReceiver for PendingIntent-based BLE scan (API 26+)
 * System wakes up the app when beacon is detected
 */
class BluetoothScanReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BeAroundSDK-BTReceiver"
        const val ACTION_BLUETOOTH_SCAN = "io.bearound.sdk.ACTION_BLUETOOTH_SCAN"

        /** Safety cap for goAsync — broadcast execution budget is ~10s. */
        private const val FINISH_TIMEOUT_MS = 8_000L
    }

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        if (!hasRequiredPermissions(context)) {
            Log.w(TAG, "Missing required permissions")
            return
        }

        try {
            val sdk = BeAroundSDK.getInstance(context.applicationContext)

            if (!sdk.isConfigured) {
                sdk.attemptConfigRestore()
                if (!sdk.isConfigured) {
                    Log.e(TAG, "SDK not configured - skipping scan")
                    return
                }
            }

            // The PendingIntent pipeline reports failures through EXTRA_ERROR_CODE (e.g.
            // the stack tore the registration down, or scan-start throttling kicked in).
            // Ignoring it kept the "background scan registered" state alive on a client
            // the OS had already rejected — the out-of-region detector was silently dead.
            val errorCode = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
            if (errorCode != 0) {
                Log.w(TAG, "PendingIntent scan delivered error code $errorCode")
                if (errorCode == 6 /* SCAN_FAILED_SCANNING_TOO_FREQUENTLY, API 30+ */) {
                    // Freeze the budget and let the 15-min watchdog re-register once the
                    // recovery window passes — an immediate re-start would be one more
                    // start against the very quota that just tripped.
                    io.bearound.sdk.utilities.ScanStartBudget.freeze()
                } else {
                    // Other failures (registration dropped, internal error): one
                    // budget-guarded re-registration now.
                    sdk.refreshBackgroundScanRegistration()
                }
                return
            }

            val scanResults = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(
                    BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
                    ScanResult::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)
            }

            if (!scanResults.isNullOrEmpty()) {
                // goAsync(): keep the broadcast's process-priority window open until
                // the immediate flush settles, instead of returning with the POST
                // still in flight (the process became freezable the moment onReceive
                // returned — top-5 fix #1). Guarded by a hard timeout well under the
                // ~10s broadcast budget; double-finish is a crash, hence the flag.
                val pending = goAsync()
                val finished = java.util.concurrent.atomic.AtomicBoolean(false)
                fun finishOnce() {
                    if (finished.compareAndSet(false, true)) {
                        try {
                            pending.finish()
                        } catch (_: Exception) {
                        }
                    }
                }
                android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed({ finishOnce() }, FINISH_TIMEOUT_MS)

                sdk.processBroadcastResults(scanResults) { finishOnce() }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing scan: ${e.message}")
            io.bearound.sdk.telemetry.ErrorReporter.report(e, "BluetoothScanReceiver.onReceive")
        }
    }

    private fun hasRequiredPermissions(context: Context): Boolean {
        val hasLocation =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: Bluetooth-only detection via BLUETOOTH_SCAN + neverForLocation
            // (see the library manifest). Location additionally satisfies OEMs that still
            // gate on it — either eye is enough.
            val hasBluetoothScan = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            hasLocation || hasBluetoothScan
        } else {
            // Pre-Android-12: BLE scan results are location-gated by the platform — the
            // legacy BLUETOOTH/BLUETOOTH_ADMIN install-time permissions do NOT unlock
            // them. The old `hasLocation || true` accepted work here that could never
            // produce results, keeping "background scan registered" state alive on a
            // pipeline the OS had silently zeroed.
            hasLocation
        }
    }
}
