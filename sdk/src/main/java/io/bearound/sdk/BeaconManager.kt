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
import io.bearound.sdk.utilities.IBeaconParser
import java.util.Date
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.pow

/**
 * Manages beacon scanning using Android's Bluetooth LE APIs
 */
class BeaconManager(private val context: Context) {
    companion object {
        private const val TAG = "BeAroundSDK-BeaconM"
        private const val BEACON_TIMEOUT_FOREGROUND = 5000L
        private const val BEACON_TIMEOUT_BACKGROUND = 10000L
        private const val WATCHDOG_INTERVAL = 30000L
        private const val RANGING_REFRESH_INTERVAL = 120000L
        private const val MAX_RESTARTS_PER_MINUTE = 3
    }

    private var bluetoothLeScanner: BluetoothLeScanner? = null
    var isScanning = false
    var isRanging = false
        private set
    private var isInForeground = true
    private var isInBeaconRegion = false
    
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
                    IBeaconParser.APPLE_MANUFACTURER_ID,
                    IBeaconParser.IBEACON_PREFIX,
                    IBeaconParser.IBEACON_MASK
                )
                .build(),
            ScanFilter.Builder()
                .setServiceData(IBeaconParser.BEAD_SERVICE_UUID, byteArrayOf(), byteArrayOf())
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

    /**
     * Pause ranging without changing isScanning lifecycle.
     * Used for duty cycle pause periods.
     */
    @SuppressLint("MissingPermission")
    fun pauseRanging() {
        if (!isScanning || !isRanging) return
        Log.d(TAG, "pauseRanging() - pausing for duty cycle")
        stopRanging()
    }

    /**
     * Resume ranging without changing isScanning lifecycle.
     * Used for duty cycle scan periods.
     */
    fun resumeRanging() {
        if (!isScanning || isRanging) return
        Log.d(TAG, "resumeRanging() - resuming for duty cycle")
        startRanging()
    }

    private fun startMonitoring() {
        // Periodic scanning in foreground (controlled by external timer in BeAroundSDK)
        // Continuous scanning in background (always ranging)
        if (!isInForeground) {
            startRanging()
            startRangingRefreshTimer()
        }
        // In foreground: ranging is controlled by BeAroundSDK's sync timer
    }

    private fun processScanResult(result: ScanResult) {
        val scanRecord = result.scanRecord ?: return

        // PRIORITY 1: BEAD Service Data — has major, minor AND full metadata
        val serviceData = IBeaconParser.parseServiceData(scanRecord, result.rssi)
        if (serviceData != null) {
            val beacon = Beacon(
                uuid = IBeaconParser.BEAROUND_UUID,
                major = serviceData.major,
                minor = serviceData.minor,
                rssi = serviceData.rssi,
                proximity = Beacon.Proximity.BT,
                accuracy = -1.0,
                timestamp = Date(),
                metadata = serviceData.metadata,
                txPower = null
            )
            processBeacon(beacon)
            return
        }

        // PRIORITY 2: iBeacon manufacturer data — major, minor only, no metadata
        val beaconData = IBeaconParser.parse(scanRecord, result.rssi) ?: return
        if (!IBeaconParser.isBeAroundBeacon(beaconData)) return

        val accuracy = calculateAccuracy(beaconData.txPower, beaconData.rssi)
        val proximity = calculateProximity(accuracy)

        val beacon = Beacon(
            uuid = beaconData.uuid,
            major = beaconData.major,
            minor = beaconData.minor,
            rssi = beaconData.rssi,
            proximity = proximity,
            accuracy = accuracy,
            timestamp = Date(),
            metadata = null,
            txPower = beaconData.txPower
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
