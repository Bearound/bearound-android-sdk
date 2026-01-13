package io.bearound.sdk

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.le.*
import android.bluetooth.le.ScanCallback.*
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import io.bearound.sdk.models.Beacon
import java.util.Date
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.pow
import androidx.core.util.size
import androidx.core.util.isEmpty

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
            processScanResult(result)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { processScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
            val error = Exception("Beacon scan failed with error code: $errorCode")
            onError?.invoke(error)
        }
    }

    fun setForegroundState(inForeground: Boolean) {
        isInForeground = inForeground
        
        if (!inForeground && isScanning) {
            startRangingRefreshTimer()
        } else if (inForeground) {
            stopRangingRefreshTimer()
        }
    }

    /**
     * Process scan result from external source (e.g. Bluetooth Scan Broadcast)
     * Public method to allow processing without starting another scan
     */
    fun processExternalScanResult(result: ScanResult) {
        Log.d(TAG, "Processing external scan result from Bluetooth Broadcast")
        processScanResult(result)
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
        if (!isScanning) return
        if (isRanging) return

        val filters = listOf(
            ScanFilter.Builder()
                .setManufacturerData(
                    0x004C,
                    byteArrayOf(0x02, 0x15),
                    byteArrayOf(0xFF.toByte(), 0xFF.toByte())
                )
                .build()
        )

        val scanMode = if (isInForeground) ScanSettings.SCAN_MODE_LOW_LATENCY
                       else ScanSettings.SCAN_MODE_LOW_POWER

        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .setReportDelay(0)
            .build()

        try {
            bluetoothLeScanner?.startScan(filters, settings, scanCallback)
            isRanging = true
            startWatchdog()

            if (!isInForeground) {
                startRangingRefreshTimer()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ranging: ${e.message}")
            onError?.invoke(e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopRanging() {
        Log.d(TAG, "stopRanging() called - isRanging: $isRanging, isInForeground: $isInForeground")
        if (!isRanging) return

        bluetoothLeScanner?.stopScan(scanCallback)
        isRanging = false
        stopWatchdog()
        
        if (isInForeground) {
            stopRangingRefreshTimer()
        }
    }

    private fun startMonitoring() {
        if (!enablePeriodicScanning) {
            startRanging()
            if (!isInForeground) {
                startRangingRefreshTimer()
            }
        }
    }

    private fun processScanResult(result: ScanResult) {
        val scanRecord = result.scanRecord ?: return
        val manufacturerData = scanRecord.manufacturerSpecificData
        if (manufacturerData.isEmpty()) return

        val appleData = manufacturerData.get(0x004C) ?: return
        if (appleData.size < 23) return

        if (appleData[0] != 0x02.toByte() || appleData[1] != 0x15.toByte()) return

        val uuidBytes = appleData.copyOfRange(2, 18)
        val uuid = bytesToUUID(uuidBytes)
        if (uuid != BEACON_UUID) return

        val major = ((appleData[18].toInt() and 0xFF) shl 8) or (appleData[19].toInt() and 0xFF)
        val minor = ((appleData[20].toInt() and 0xFF) shl 8) or (appleData[21].toInt() and 0xFF)
        val txPower = appleData[22].toInt()
        val rssi = result.rssi

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

        cleanupExpiredBeacons()

        val currentBeacons = beaconLock.withLock {
            detectedBeacons.values.toList()
        }
        
        if (currentBeacons.isNotEmpty()) {
            onBeaconsUpdated?.invoke(currentBeacons)
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
                restartRanging()
            }
        } else if (isRanging) {
            restartRanging()
        }
    }

    private fun refreshRanging() {
        if (isScanning && isRanging && !isInForeground) {
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

