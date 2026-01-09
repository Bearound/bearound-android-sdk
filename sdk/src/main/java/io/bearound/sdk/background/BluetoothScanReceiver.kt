package io.bearound.sdk.background

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import io.bearound.sdk.BeAroundSDK
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for Android 14+ Bluetooth Scan Broadcast
 * System wakes up the app when beacon is detected
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class BluetoothScanReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BeAroundSDK-BTReceiver"
        const val ACTION_BLUETOOTH_SCAN = "io.bearound.sdk.ACTION_BLUETOOTH_SCAN"
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
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
            
            val scanResults = intent.getParcelableArrayListExtra(
                android.bluetooth.le.BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
                ScanResult::class.java
            )

            if (!scanResults.isNullOrEmpty()) {
                sdk.processBroadcastResults(scanResults)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing scan: ${e.message}")
        }
    }
    
    private fun hasRequiredPermissions(context: Context): Boolean {
        val bluetoothScan =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED

        val location = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        return bluetoothScan && location
    }
}

