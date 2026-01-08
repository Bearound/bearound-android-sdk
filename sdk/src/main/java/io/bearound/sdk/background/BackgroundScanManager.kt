package io.bearound.sdk.background

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Manages background beacon scanning using:
 * - Android 14+: Bluetooth Scan Broadcast (real-time, no notification)
 * - Android <14: Not supported (requires foreground service or manual implementation)
 */
class BackgroundScanManager(private val context: Context) {
    
    companion object {
        private const val TAG = "BeAroundSDK-BgScan"
        private const val APPLE_MANUFACTURER_ID = 0x004C
        private val IBEACON_PREFIX = byteArrayOf(0x02, 0x15)
        private val IBEACON_MASK = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
    }
    
    private var pendingIntent: PendingIntent? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    
    /**
     * Enable background scanning
     * Only works on Android 14+ (API 34+) using Bluetooth Scan Broadcast
     */
    @SuppressLint("MissingPermission")
    fun enableBackgroundScanning() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            enableBluetoothScanBroadcast()
        } else {
            Log.w(TAG, "Background scanning requires Android 14+")
        }
    }
    
    /**
     * Disable background scanning
     */
    @SuppressLint("MissingPermission")
    fun disableBackgroundScanning() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            disableBluetoothScanBroadcast()
        }
    }
    
    /**
     * Android 14+ - Register Bluetooth Scan Broadcast
     * System will wake up the app when beacon is detected
     */
    @SuppressLint("MissingPermission")
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun enableBluetoothScanBroadcast() {
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) 
                as android.bluetooth.BluetoothManager
            bluetoothLeScanner = bluetoothManager.adapter?.bluetoothLeScanner
            
            if (bluetoothLeScanner == null) {
                Log.e(TAG, "BluetoothLeScanner not available")
                return
            }
            
            val intent = Intent(context, BluetoothScanReceiver::class.java).apply {
                action = BluetoothScanReceiver.ACTION_BLUETOOTH_SCAN
            }
            
            pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            
            val filters = listOf(
                ScanFilter.Builder()
                    .setManufacturerData(
                        APPLE_MANUFACTURER_ID,
                        IBEACON_PREFIX,
                        IBEACON_MASK
                    )
                    .build()
            )
            
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .build()
            
            pendingIntent?.let { pi ->
                bluetoothLeScanner?.startScan(filters, settings, pi)
            }
            
            Log.d(TAG, "Bluetooth Scan Broadcast registered")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup background scanning: ${e.message}")
        }
    }
    
    @SuppressLint("MissingPermission")
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun disableBluetoothScanBroadcast() {
        try {
            pendingIntent?.let { pi ->
                bluetoothLeScanner?.stopScan(pi)
                pi.cancel()
                pendingIntent = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable background scanning: ${e.message}")
        }
    }
    
}

