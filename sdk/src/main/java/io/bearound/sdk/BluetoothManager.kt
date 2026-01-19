package io.bearound.sdk

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager as AndroidBluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import io.bearound.sdk.interfaces.BluetoothManagerListener
import io.bearound.sdk.models.BeaconMetadata
import io.bearound.sdk.utilities.IBeaconParser

/**
 * Manages Bluetooth LE scanning for beacon metadata
 */
class BluetoothManager(private val context: Context) {
    companion object {
        private const val TAG = "BeAroundSDK-BLEManager"
        private const val DEDUPLICATION_INTERVAL = 1000L
    }

    var listener: BluetoothManagerListener? = null
    
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false
    
    private val lastSeenBeacons = mutableMapOf<String, Long>()

    val isPoweredOn: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    init {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as AndroidBluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            processScanResult(result)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { processScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with error code: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (!isPoweredOn) {
            Log.w(TAG, "Cannot start scanning - Bluetooth not powered on")
            listener?.onBluetoothStateChanged(false)
            return
        }

        if (isScanning) {
            Log.d(TAG, "Already scanning")
            return
        }

        if (!checkPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            return
        }

        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setReportDelay(0)
                .build()

            // Scan for all devices to capture manufacturer data and names
            bluetoothLeScanner?.startScan(null, settings, scanCallback)
            isScanning = true
            Log.d(TAG, "Started BLE scanning")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE scanning: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (!isScanning) return

        try {
            bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
            lastSeenBeacons.clear()
            Log.d(TAG, "Stopped BLE scanning")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop BLE scanning: ${e.message}")
        }
    }

    private fun processScanResult(result: ScanResult) {
        val scanRecord = result.scanRecord ?: return
        val rssi = result.rssi

        // Check if RSSI is valid
        if (rssi == 127 || rssi == 0) return

        // Parse iBeacon data using utility class
        val beaconData = IBeaconParser.parse(scanRecord, rssi) ?: return
        
        // Only process BeAround beacons
        if (!IBeaconParser.isBeAroundBeacon(beaconData)) return

        // Check deduplication
        if (!shouldProcessBeacon(beaconData)) return

        // Check if connectable
        val isConnectable = scanRecord.advertiseFlags and 0x02 != 0

        // Parse metadata from device name if available
        var metadata: BeaconMetadata? = null
        val deviceName = scanRecord.deviceName
        if (deviceName != null && deviceName.startsWith("B:")) {
            metadata = parseBeaconMetadata(deviceName, beaconData.txPower, rssi, isConnectable)
        }

        // Notify listener
        listener?.onBeaconDiscovered(
            uuid = beaconData.uuid,
            major = beaconData.major,
            minor = beaconData.minor,
            rssi = rssi,
            txPower = beaconData.txPower,
            metadata = metadata,
            isConnectable = isConnectable
        )
    }

    private fun parseBeaconMetadata(
        name: String,
        txPower: Int,
        rssi: Int,
        isConnectable: Boolean
    ): BeaconMetadata? {
        try {
            // Format: B:firmware_?_battery_movements_temperature
            // Example: B:3.2.1_0_85_120_22
            if (!name.startsWith("B:")) return null

            val parts = name.substring(2).split("_")
            if (parts.size < 5) return null

            val firmware = parts[0]
            val battery = parts[2].toIntOrNull() ?: return null
            val movements = parts[3].toIntOrNull() ?: return null
            val temperature = parts[4].toIntOrNull() ?: return null

            return BeaconMetadata(
                firmwareVersion = firmware,
                batteryLevel = battery,
                movements = movements,
                temperature = temperature,
                txPower = txPower,
                rssiFromBLE = rssi,
                isConnectable = isConnectable
            )
        } catch (_: Exception) {
            Log.w(TAG, "Failed to parse beacon metadata from name: $name")
            return null
        }
    }

    private fun shouldProcessBeacon(beaconData: IBeaconParser.IBeaconData): Boolean {
        val key = "${beaconData.uuid}-${beaconData.major}-${beaconData.minor}"
        val now = System.currentTimeMillis()
        
        val lastSeen = lastSeenBeacons[key]
        if (lastSeen != null) {
            val timeSinceLastSeen = now - lastSeen
            if (timeSinceLastSeen < DEDUPLICATION_INTERVAL) {
                return false
            }
        }

        lastSeenBeacons[key] = now
        return true
    }

    private fun checkPermissions(): Boolean {
        val bluetoothScanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val bluetoothConnectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return bluetoothScanPermission && bluetoothConnectPermission
    }
}
