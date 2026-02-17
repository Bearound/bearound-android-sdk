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
                sdk.processBroadcastResults(scanResults)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing scan: ${e.message}")
        }
    }

    private fun hasRequiredPermissions(context: Context): Boolean {
        val location = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val btScan = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            return btScan && location
        }

        return location
    }
}
