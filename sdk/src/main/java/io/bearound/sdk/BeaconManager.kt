package io.bearound.sdk

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import io.bearound.sdk.models.Beacon
import java.util.Date
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.pow

/**
 * Manages beacon scanning using Android's Bluetooth LE APIs
 * Similar to iOS BeaconManager
 */
class BeaconManager(private val context: Context) {
    companion object {
        private const val TAG = "BeAroundSDK-BeaconM"
        private val BEACON_UUID = UUID.fromString("E25B8D3C-947A-452F-A13F-589CB706D2E5")
        private const val BEACON_TIMEOUT_FOREGROUND = 5000L
        private const val BEACON_TIMEOUT_BACKGROUND = 10000L
        private const val WATCHDOG_INTERVAL = 30000L
        private const val RANGING_REFRESH_INTERVAL = 120000L
        private const val MAX_RESTARTS_PER_MINUTE = 3
    }

    private var bluetoothLeScanner: BluetoothLeScanner? = null
    var isScanning = false
    private var isRanging = false
    private var isInForeground = true
    private var isInBeaconRegion = false
    
    var enablePeriodicScanning = false
    var lastLocation: Location? = null

    // Callbacks
    var onBeaconsUpdated: ((List<Beacon>) -> Unit)? = null
    var onError: ((Exception) -> Unit)? = null
    var onScanningStateChanged: ((Boolean) -> Unit)? = null
    var onBackgroundRangingComplete: (() -> Unit)? = null

    // Beacon tracking
    private val detectedBeacons = mutableMapOf<String, Beacon>()
    private val beaconLastSeen = mutableMapOf<String, Long>()
    private val beaconLock = ReentrantLock()
    
    private var lastBeaconUpdate: Long? = null
    private var emptyBeaconCount = 0
    private var rangingRestartCount = 0
    private var lastRangingRestartTime: Long? = null

    // Timers
    private val handler = Handler(Looper.getMainLooper())
    private var watchdogRunnable: Runnable? = null
    private var rangingRefreshRunnable: Runnable? = null
    private var backgroundRangingRunnable: Runnable? = null

    private val beaconTimeout: Long
        get() = if (isInForeground) BEACON_TIMEOUT_FOREGROUND else BEACON_TIMEOUT_BACKGROUND

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.d(TAG, "onScanResult called (callbackType: $callbackType)")
            processScanResult(result)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            Log.d(TAG, "onBatchScanResults called with ${results.size} result(s)")
            results.forEach { processScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "========================================")
            Log.e(TAG, "SCAN FAILED!")
            Log.e(TAG, "Error code: $errorCode")
            Log.e(TAG, "========================================")
            val error = Exception("Beacon scan failed with error code: $errorCode")
            onError?.invoke(error)
        }
    }

    fun setForegroundState(inForeground: Boolean) {
        isInForeground = inForeground
        Log.d(TAG, "App ${if (inForeground) "entered foreground" else "entered background"}")
        
        if (!inForeground && isScanning) {
            startRangingRefreshTimer()
        } else if (inForeground) {
            stopRangingRefreshTimer()
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (isScanning) {
            Log.d(TAG, "Already scanning")
            return
        }

        if (!checkPermissions()) {
            val error = Exception("Location or Bluetooth permissions not granted")
            onError?.invoke(error)
            return
        }

        Log.d(TAG, "Starting beacon scanning")
        
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
            bluetoothLeScanner = bluetoothManager.adapter?.bluetoothLeScanner
            
            if (bluetoothLeScanner == null) {
                throw Exception("BluetoothLeScanner not available")
            }

            startMonitoring()
            isScanning = true
            onScanningStateChanged?.invoke(true)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scanning: ${e.message}")
            onError?.invoke(e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (!isScanning) {
            Log.d(TAG, "Not scanning")
            return
        }

        Log.d(TAG, "Stopping beacon scanning")
        stopWatchdog()
        stopRangingRefreshTimer()
        
        if (isRanging) {
            bluetoothLeScanner?.stopScan(scanCallback)
            isRanging = false
        }

        beaconLock.withLock {
            detectedBeacons.clear()
            beaconLastSeen.clear()
            lastLocation = null
        }

        isInBeaconRegion = false
        emptyBeaconCount = 0
        isScanning = false
        onScanningStateChanged?.invoke(false)
    }

    @SuppressLint("MissingPermission")
    fun startRanging() {
        if (isRanging) {
            Log.d(TAG, "Already ranging")
            return
        }

        Log.d(TAG, "========================================")
        Log.d(TAG, "STARTING RANGING")
        Log.d(TAG, "isInForeground: $isInForeground")
        Log.d(TAG, "Target UUID: $BEACON_UUID")
        Log.d(TAG, "========================================")

        // iBeacons don't advertise service UUIDs, they use manufacturer data
        // Apple manufacturer ID is 0x004C
        val filters = listOf(
            ScanFilter.Builder()
                .setManufacturerData(
                    0x004C, // Apple manufacturer ID
                    byteArrayOf(0x02, 0x15), // iBeacon identifier
                    byteArrayOf(0xFF.toByte(), 0xFF.toByte()) // Mask to match first 2 bytes
                )
                .build()
        )

        val scanMode = if (isInForeground) ScanSettings.SCAN_MODE_LOW_LATENCY
                       else ScanSettings.SCAN_MODE_LOW_POWER

        Log.d(TAG, "Scan mode: ${if (isInForeground) "LOW_LATENCY" else "LOW_POWER"}")
        Log.d(TAG, "Filters: ${filters.size} filter(s) - Using manufacturer data filter for iBeacon")
        Log.d(TAG, "Filter: Manufacturer ID 0x004C (Apple), iBeacon prefix 0x02 0x15")

        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .setReportDelay(0)
            .build()

        try {
            bluetoothLeScanner?.startScan(filters, settings, scanCallback)
            isRanging = true
            startWatchdog()
            
            Log.d(TAG, "Ranging started successfully")

            if (!isInForeground) {
                startRangingRefreshTimer()
            }
        } catch (e: Exception) {
            Log.e(TAG, "========================================")
            Log.e(TAG, "Failed to start ranging: ${e.message}")
            Log.e(TAG, "========================================")
            onError?.invoke(e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopRanging() {
        if (!isRanging) return

        if (!isInForeground) {
            Log.d(TAG, "Ignoring stopRanging in background to maintain continuous operation")
            return
        }

        Log.d(TAG, "Stopping ranging (monitoring continues)")
        bluetoothLeScanner?.stopScan(scanCallback)
        isRanging = false
        stopWatchdog()
        stopRangingRefreshTimer()

        beaconLock.withLock {
            detectedBeacons.clear()
            beaconLastSeen.clear()
        }

        onBeaconsUpdated?.invoke(emptyList())
    }

    private fun startMonitoring() {
        // On Android, we start ranging immediately since there's no separate monitoring phase
        if (!enablePeriodicScanning) {
            startRanging()
            if (!isInForeground) {
                startRangingRefreshTimer()
            }
        } else {
            Log.d(TAG, "Periodic scanning enabled - ranging will be controlled by sync timer")
        }
    }

    private fun processScanResult(result: ScanResult) {
        Log.d(TAG, "========================================")
        Log.d(TAG, "processScanResult called")

        val scanRecord = result.scanRecord
        if (scanRecord == null) {
            Log.d(TAG, "scanRecord is null, skipping")
            Log.d(TAG, "========================================")
            return
        }

        Log.d(TAG, "scanRecord found, checking manufacturer data")

        // Parse iBeacon data from manufacturer data
        val manufacturerData = scanRecord.manufacturerSpecificData
        Log.d(TAG, "Manufacturer data size: ${manufacturerData.size()}")

        if (manufacturerData.size() == 0) {
            Log.d(TAG, "No manufacturer data found, skipping")
            Log.d(TAG, "========================================")
            return
        }

        // Apple's manufacturer ID is 0x004C
        val appleData = manufacturerData.get(0x004C)
        if (appleData == null) {
            Log.d(TAG, "No Apple manufacturer data (0x004C) found")
            Log.d(TAG, "Available manufacturer IDs:")
            for (i in 0 until manufacturerData.size()) {
                val key = manufacturerData.keyAt(i)
                Log.d(TAG, "  - 0x${key.toString(16).uppercase()}")
            }
            Log.d(TAG, "========================================")
            return
        }

        Log.d(TAG, "Apple data found, size: ${appleData.size}")

        if (appleData.size < 23) {
            Log.d(TAG, "Apple data too short (< 23 bytes), skipping")
            Log.d(TAG, "========================================")
            return
        }

        // Verify iBeacon identifier
        val byte0 = appleData[0]
        val byte1 = appleData[1]
        Log.d(TAG, "iBeacon identifier bytes: 0x${byte0.toString(16).uppercase()} 0x${byte1.toString(16).uppercase()}")

        if (byte0 != 0x02.toByte() || byte1 != 0x15.toByte()) {
            Log.d(TAG, "Not an iBeacon (expected 0x02 0x15), skipping")
            Log.d(TAG, "========================================")
            return
        }

        // Parse UUID
        val uuidBytes = appleData.copyOfRange(2, 18)
        val uuid = bytesToUUID(uuidBytes)
        Log.d(TAG, "Parsed UUID: $uuid")
        Log.d(TAG, "Expected UUID: $BEACON_UUID")

        if (uuid != BEACON_UUID) {
            Log.d(TAG, "UUID mismatch, skipping")
            Log.d(TAG, "========================================")
            return
        }

        // Parse major and minor
        val major = ((appleData[18].toInt() and 0xFF) shl 8) or (appleData[19].toInt() and 0xFF)
        val minor = ((appleData[20].toInt() and 0xFF) shl 8) or (appleData[21].toInt() and 0xFF)
        val txPower = appleData[22].toInt()
        val rssi = result.rssi

        Log.d(TAG, "✅ iBeacon detected!")
        Log.d(TAG, "Major: $major, Minor: $minor")
        Log.d(TAG, "RSSI: $rssi, TxPower: $txPower")
        Log.d(TAG, "========================================")

        // Calculate accuracy and proximity
        val accuracy = calculateAccuracy(txPower, rssi)
        val proximity = calculateProximity(accuracy)

        val beacon = Beacon(
            uuid = uuid,
            major = major,
            minor = minor,
            rssi = rssi,
            proximity = proximity,
            accuracy = accuracy,
            timestamp = Date(),
            metadata = null,
            txPower = txPower
        )

        processBeacon(beacon)
    }

    private fun processBeacon(beacon: Beacon) {
        lastBeaconUpdate = System.currentTimeMillis()
        emptyBeaconCount = 0
        isInBeaconRegion = true

        beaconLock.withLock {
            val identifier = beacon.identifier
            detectedBeacons[identifier] = beacon
            beaconLastSeen[identifier] = System.currentTimeMillis()
        }

        // Clean up expired beacons
        cleanupExpiredBeacons()

        // Notify with current beacons
        val currentBeacons = beaconLock.withLock {
            detectedBeacons.values.toList()
        }
        
        Log.d(TAG, "========================================")
        Log.d(TAG, "BEACON MANAGER - processBeacon")
        Log.d(TAG, "Beacon detected: ${beacon.identifier}, rssi=${beacon.rssi}, proximity=${beacon.proximity}")
        Log.d(TAG, "Total beacons in cache: ${currentBeacons.size}")

        if (currentBeacons.isNotEmpty()) {
            Log.d(TAG, "Calling onBeaconsUpdated callback with ${currentBeacons.size} beacon(s)")
            currentBeacons.forEach { b ->
                Log.d(TAG, "  - ${b.identifier}: rssi=${b.rssi}, proximity=${b.proximity}")
            }
            Log.d(TAG, "onBeaconsUpdated callback: ${onBeaconsUpdated != null}")
            Log.d(TAG, "========================================")
            onBeaconsUpdated?.invoke(currentBeacons)
        } else {
            Log.d(TAG, "No beacons to report (list is empty)")
            Log.d(TAG, "========================================")
        }

        startWatchdog()
    }

    private fun cleanupExpiredBeacons() {
        beaconLock.withLock {
            val now = System.currentTimeMillis()
            val expiredKeys = beaconLastSeen.filter { (_, lastSeen) ->
                now - lastSeen > beaconTimeout
            }.keys

            expiredKeys.forEach { key ->
                Log.d(TAG, "Beacon $key expired (timeout: ${beaconTimeout}ms)")
                detectedBeacons.remove(key)
                beaconLastSeen.remove(key)
            }
        }
    }

    private fun calculateAccuracy(txPower: Int, rssi: Int): Double {
        if (rssi == 0) return -1.0
        
        val ratio = rssi * 1.0 / txPower
        return if (ratio < 1.0) {
            ratio.pow(10.0)
        } else {
            0.89976 * ratio.pow(7.7095) + 0.111
        }
    }

    private fun calculateProximity(accuracy: Double): Beacon.Proximity {
        return when {
            accuracy < 0 -> Beacon.Proximity.UNKNOWN
            accuracy < 0.5 -> Beacon.Proximity.IMMEDIATE
            accuracy < 3.0 -> Beacon.Proximity.NEAR
            else -> Beacon.Proximity.FAR
        }
    }

    private fun bytesToUUID(bytes: ByteArray): UUID {
        val msb = bytes.take(8).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
        val lsb = bytes.drop(8).take(8).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
        return UUID(msb, lsb)
    }

    private fun startWatchdog() {
        stopWatchdog()
        watchdogRunnable = Runnable {
            checkRangingHealth()
        }
        handler.postDelayed(watchdogRunnable!!, WATCHDOG_INTERVAL)
    }

    private fun stopWatchdog() {
        watchdogRunnable?.let { handler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    private fun startRangingRefreshTimer() {
        stopRangingRefreshTimer()
        rangingRefreshRunnable = object : Runnable {
            override fun run() {
                refreshRanging()
                handler.postDelayed(this, RANGING_REFRESH_INTERVAL)
            }
        }
        handler.postDelayed(rangingRefreshRunnable!!, RANGING_REFRESH_INTERVAL)
    }

    private fun stopRangingRefreshTimer() {
        rangingRefreshRunnable?.let { handler.removeCallbacks(it) }
        rangingRefreshRunnable = null
    }

    private fun checkRangingHealth() {
        if (!isScanning || !isInBeaconRegion) return

        val lastUpdate = lastBeaconUpdate
        if (lastUpdate != null) {
            val timeSinceLastUpdate = System.currentTimeMillis() - lastUpdate
            if (timeSinceLastUpdate > WATCHDOG_INTERVAL) {
                Log.w(TAG, "Ranging stuck detected: No beacon updates for ${timeSinceLastUpdate}ms")
                restartRanging()
            }
        } else if (isRanging) {
            Log.w(TAG, "Ranging stuck detected: Active but no updates ever received")
            restartRanging()
        }
    }

    private fun refreshRanging() {
        if (isScanning && isRanging && !isInForeground) {
            Log.d(TAG, "Background refresh: restarting ranging to prevent throttling...")
            restartRanging()
        }
    }

    @SuppressLint("MissingPermission")
    private fun restartRanging() {
        val now = System.currentTimeMillis()
        val lastRestart = lastRangingRestartTime
        
        if (lastRestart != null) {
            val timeSinceLastRestart = now - lastRestart
            
            if (timeSinceLastRestart > 60000L) {
                rangingRestartCount = 0
            }

            if (timeSinceLastRestart < 60000L && rangingRestartCount >= MAX_RESTARTS_PER_MINUTE) {
                Log.w(TAG, "Too many restarts ($rangingRestartCount in last minute), applying backoff")
                rangingRestartCount = 0
                lastRangingRestartTime = now
                return
            }
        }

        rangingRestartCount++
        lastRangingRestartTime = now

        if (isRanging) {
            bluetoothLeScanner?.stopScan(scanCallback)
        }

        val backoffDelay = minOf(500L * rangingRestartCount, 5000L)
        Log.d(TAG, "Restarting ranging in ${backoffDelay}ms (restart #$rangingRestartCount)")

        handler.postDelayed({
            startRanging()
        }, backoffDelay)
    }

    private fun checkPermissions(): Boolean {
        val locationPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val bluetoothScanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return locationPermission && bluetoothScanPermission
    }
}

