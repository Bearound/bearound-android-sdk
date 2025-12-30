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
import io.bearound.sdk.interfaces.BluetoothManagerDelegate
import io.bearound.sdk.models.BeaconMetadata
import java.util.UUID
import androidx.core.util.isEmpty

/**
 * Manages Bluetooth LE scanning for beacon metadata
 * Similar to iOS BluetoothManager
 */
class BluetoothManager(private val context: Context) {
    companion object {
        private const val TAG = "BeAroundSDK-BLEManager"
        private val TARGET_UUID = UUID.fromString("E25B8D3C-947A-452F-A13F-589CB706D2E5")
        private const val DEDUPLICATION_INTERVAL = 1000L
    }

    var delegate: BluetoothManagerDelegate? = null
    
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
            delegate?.didUpdateBluetoothState(false)
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

        // Parse iBeacon data from manufacturer data
        val manufacturerData = scanRecord.manufacturerSpecificData
        if (manufacturerData.isEmpty()) return

        // Apple's manufacturer ID is 0x004C
        val appleData = manufacturerData.get(0x004C) ?: return
        if (appleData.size < 23) return

        // Verify iBeacon identifier
        if (appleData[0] != 0x02.toByte() || appleData[1] != 0x15.toByte()) return

        // Parse UUID
        val uuidBytes = appleData.copyOfRange(2, 18)
        val uuid = bytesToUUID(uuidBytes)
        if (uuid != TARGET_UUID) return

        // Parse major and minor
        val major = ((appleData[18].toInt() and 0xFF) shl 8) or (appleData[19].toInt() and 0xFF)
        val minor = ((appleData[20].toInt() and 0xFF) shl 8) or (appleData[21].toInt() and 0xFF)
        val txPower = appleData[22].toInt()

        // Check deduplication
        if (!shouldProcessBeacon(uuid, major, minor)) return

        // Check if connectable
        val isConnectable = scanRecord.advertiseFlags and 0x02 != 0

        // Parse metadata from device name if available
        var metadata: BeaconMetadata? = null
        val deviceName = scanRecord.deviceName
        if (deviceName != null && deviceName.startsWith("B:")) {
            metadata = parseBeaconMetadata(deviceName, txPower, rssi, isConnectable)
        }

        // Notify delegate
        delegate?.didDiscoverBeacon(
            uuid = uuid,
            major = major,
            minor = minor,
            rssi = rssi,
            txPower = txPower,
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

    private fun shouldProcessBeacon(uuid: UUID, major: Int, minor: Int): Boolean {
        val key = "$uuid-$major-$minor"
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

    private fun bytesToUUID(bytes: ByteArray): UUID {
        val msb = bytes.take(8).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
        val lsb = bytes.drop(8).take(8).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
        return UUID(msb, lsb)
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

