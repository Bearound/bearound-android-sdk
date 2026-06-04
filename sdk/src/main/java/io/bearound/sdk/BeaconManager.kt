package io.bearound.sdk

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.le.*
import android.bluetooth.le.ScanCallback.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import io.bearound.sdk.models.Beacon
import io.bearound.sdk.models.RssiStats
import io.bearound.sdk.utilities.IBeaconParser
import io.bearound.sdk.utilities.RssiFilterRegistry
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
        // Grace period before a beacon is considered "gone". Long enough to
        // absorb BLE radio dropouts while the device is stationary inside the
        // zone — short values caused enter/exit flicker (5s → 30s).
        private const val BEACON_TIMEOUT_DEFAULT = 30000L
        private const val WATCHDOG_INTERVAL = 30000L
        private const val RANGING_REFRESH_INTERVAL = 120000L
        private const val MAX_RESTARTS_PER_MINUTE = 3
        private const val DEFAULT_TX_POWER = -59
        /** Past this gap with no packet, the beacon is rendered as stale (faded) but kept. */
        private const val STALE_THRESHOLD_MS = 5000L
    }

    private var bluetoothLeScanner: BluetoothLeScanner? = null
    var isScanning = false
    var isRanging = false
        private set
    private var isInForeground = true
    /**
     * True while at least one beacon is currently detected (after rising edge in [processBeacon]
     * and before falling edge in [cleanupExpiredBeacons]). Active scanning is gated by this
     * flag — outside the region only [io.bearound.sdk.background.BackgroundScanManager]'s
     * PendingIntent-based scan runs (kernel-managed, low power).
     */
    var isInBeaconRegion: Boolean = false
        private set

    // Callbacks
    var onBeaconsUpdated: ((List<Beacon>) -> Unit)? = null
    var onError: ((Exception) -> Unit)? = null
    var onScanningStateChanged: ((Boolean) -> Unit)? = null
    var onBackgroundRangingComplete: (() -> Unit)? = null

    // v2.5 — Region transition + active-scan gating callbacks
    /** Fired when the first beacon is detected (rising edge: empty → ≥1). */
    var onRegionEnter: (() -> Unit)? = null
    /** Fired when the last beacon expires (falling edge: ≥1 → empty). */
    var onRegionExit: (() -> Unit)? = null
    /** Fired alongside [onRegionEnter] — host should start BLE scan + duty cycle. */
    var onActiveScanShouldStart: (() -> Unit)? = null
    /** Fired alongside [onRegionExit] — host should stop BLE scan + duty cycle. */
    var onActiveScanShouldStop: (() -> Unit)? = null

    // Beacon tracking
    private val detectedBeacons = mutableMapOf<String, Beacon>()
    private val beaconLastSeen = mutableMapOf<String, Long>()
    private val beaconLock = ReentrantLock()

    // RSSI smoothing + per-window sample accumulation
    private val rssiFilter = RssiFilterRegistry()
    private val rssiAccumulators = mutableMapOf<String, RssiStats.Accumulator>()

    /**
     * Effective beacon removal timeout (ms). Should be set by the SDK to cover
     * scan + pause + buffer for the active precision mode (5s HIGH, 25s MEDIUM, 65s LOW).
     */
    private var beaconTimeoutMs: Long = BEACON_TIMEOUT_DEFAULT
    
    private var lastBeaconUpdate: Long? = null
    private var emptyBeaconCount = 0
    private var rangingRestartCount = 0
    private var lastRangingRestartTime: Long? = null

    // Timers
    private val handler = Handler(Looper.getMainLooper())
    private var watchdogRunnable: Runnable? = null
    private var rangingRefreshRunnable: Runnable? = null
    private var backgroundRangingRunnable: Runnable? = null
    /** Periodically cleans expired beacons so the falling-edge fires when the last beacon goes stale. */
    private var regionCleanupRunnable: Runnable? = null
    private val REGION_CLEANUP_INTERVAL_MS = 2_000L

    private val beaconTimeout: Long
        get() = beaconTimeoutMs

    /**
     * Set how long a beacon stays in the detected map after the last packet.
     * Should be set to cover the worst-case gap between packets for the active
     * scan precision (scan + pause + buffer).
     */
    fun setBeaconTimeout(timeoutMs: Long) {
        beaconTimeoutMs = timeoutMs.coerceAtLeast(BEACON_TIMEOUT_DEFAULT)
    }

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
            val error = Exception("Neither Location nor Bluetooth permission granted — at least one is required to scan")
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
        stopRegionCleanupTimer()

        if (isRanging) {
            bluetoothLeScanner?.stopScan(scanCallback)
            isRanging = false
        }

        beaconLock.withLock {
            detectedBeacons.clear()
            beaconLastSeen.clear()
            rssiAccumulators.clear()
        }
        rssiFilter.clear()

        isInBeaconRegion = false
        emptyBeaconCount = 0
        isScanning = false
        onScanningStateChanged?.invoke(false)
    }

    @SuppressLint("MissingPermission")
    fun startRanging() {
        if (!isScanning) return
        if (isRanging) return
        // Doctrine: active ranging only runs inside a beacon region. Outside, only
        // BackgroundScanManager's PendingIntent-based filter scan is active (low-power,
        // kernel-managed). The first beacon match wakes us via the broadcast receiver and
        // processBeacon will fire the rising edge → onActiveScanShouldStart → resumeRanging.
        if (!isInBeaconRegion) {
            Log.d(TAG, "startRanging skipped — not inside beacon region")
            return
        }

        val filters = listOf(
            ScanFilter.Builder()
                .setManufacturerData(
                    IBeaconParser.BEAROUND_MANUFACTURER_ID,
                    byteArrayOf()
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
        if (!isInBeaconRegion) {
            Log.d(TAG, "resumeRanging skipped — not inside beacon region")
            return
        }
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

        val serviceData = IBeaconParser.parseServiceData(scanRecord, result.rssi) ?: return

        val txPower = serviceData.metadata.txPower ?: DEFAULT_TX_POWER
        val identifier = "${serviceData.major}.${serviceData.minor}"
        val rawRssi = serviceData.rssi
        val nowMs = System.currentTimeMillis()

        val smoothedRssi = rssiFilter.smooth(identifier, rawRssi)

        val statsSnapshot = beaconLock.withLock {
            val accumulator = rssiAccumulators.getOrPut(identifier) { RssiStats.Accumulator() }
            accumulator.add(rawRssi, nowMs)
            accumulator.snapshot()
        }

        val accuracy = calculateAccuracy(txPower, smoothedRssi)
        val proximity = calculateProximity(accuracy)

        val beacon = Beacon(
            uuid = IBeaconParser.BEAROUND_UUID,
            major = serviceData.major,
            minor = serviceData.minor,
            rssi = smoothedRssi,
            proximity = proximity,
            accuracy = accuracy,
            timestamp = Date(nowMs),
            metadata = serviceData.metadata,
            txPower = txPower,
            rssiRaw = rawRssi,
            rssiSamples = statsSnapshot,
            isStale = false
        )
        processBeacon(beacon)
    }

    /**
     * Snapshot per-beacon stats accumulated since the last reset, then reset accumulators.
     * Used by the SDK right before a sync to attach window stats to outgoing payloads and
     * start a fresh accumulation window.
     */
    fun consumeRssiStats(identifiers: Collection<String>): Map<String, RssiStats> {
        return beaconLock.withLock {
            val result = mutableMapOf<String, RssiStats>()
            for (id in identifiers) {
                val acc = rssiAccumulators[id] ?: continue
                acc.snapshot()?.let { result[id] = it }
                acc.reset()
            }
            result
        }
    }

    private fun processBeacon(beacon: Beacon) {
        val now = System.currentTimeMillis()
        lastBeaconUpdate = now
        emptyBeaconCount = 0

        // Rising-edge detection: were we OUT of region before this beacon arrived?
        val wasOutOfRegion = !isInBeaconRegion
        isInBeaconRegion = true

        beaconLock.withLock {
            val identifier = beacon.identifier
            detectedBeacons[identifier] = beacon
            beaconLastSeen[identifier] = now
        }

        if (wasOutOfRegion) {
            Log.d(TAG, "REGION ENTER — first beacon detected (${beacon.identifier})")
            onRegionEnter?.invoke()
            onActiveScanShouldStart?.invoke()
        }

        // Ensure the periodic cleanup timer is running so we eventually detect the falling edge.
        startRegionCleanupTimer()

        cleanupExpiredBeacons()

        // Refresh stale flag for every beacon in the snapshot — emit reflects current freshness
        val currentBeacons = beaconLock.withLock {
            detectedBeacons.values.map { b ->
                val lastSeen = beaconLastSeen[b.identifier] ?: now
                val staleNow = (now - lastSeen) > STALE_THRESHOLD_MS
                if (staleNow != b.isStale) b.copy(isStale = staleNow) else b
            }
        }

        if (currentBeacons.isNotEmpty()) {
            onBeaconsUpdated?.invoke(currentBeacons)
        }

        startWatchdog()
    }

    private fun cleanupExpiredBeacons() {
        val (hadBeaconsBefore, hasBeaconsAfter) = beaconLock.withLock {
            val before = detectedBeacons.isNotEmpty()
            val now = System.currentTimeMillis()
            val expiredKeys = beaconLastSeen.filter { (_, lastSeen) ->
                now - lastSeen > beaconTimeout
            }.keys.toList()

            expiredKeys.forEach { key ->
                Log.d(TAG, "Beacon $key expired (timeout: ${beaconTimeout}ms)")
                detectedBeacons.remove(key)
                beaconLastSeen.remove(key)
                rssiFilter.remove(key)
                rssiAccumulators.remove(key)
            }
            Pair(before, detectedBeacons.isNotEmpty())
        }

        // Falling-edge detection: last beacon expired → fire region exit and stop active scan.
        if (hadBeaconsBefore && !hasBeaconsAfter && isInBeaconRegion) {
            isInBeaconRegion = false
            Log.d(TAG, "REGION EXIT — last beacon expired, falling back to background broadcast scan")
            onRegionExit?.invoke()
            onActiveScanShouldStop?.invoke()
            stopRegionCleanupTimer()
        }
    }

    private fun startRegionCleanupTimer() {
        if (regionCleanupRunnable != null) return
        regionCleanupRunnable = object : Runnable {
            override fun run() {
                if (!isScanning) {
                    stopRegionCleanupTimer()
                    return
                }
                cleanupExpiredBeacons()
                if (regionCleanupRunnable != null) {
                    handler.postDelayed(this, REGION_CLEANUP_INTERVAL_MS)
                }
            }
        }
        handler.postDelayed(regionCleanupRunnable!!, REGION_CLEANUP_INTERVAL_MS)
    }

    private fun stopRegionCleanupTimer() {
        regionCleanupRunnable?.let { handler.removeCallbacks(it) }
        regionCleanupRunnable = null
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
        // Two "eyes": Location and Bluetooth. Scan should proceed if AT LEAST ONE is granted.
        // - Android 12+: BLUETOOTH_SCAN alone is sufficient (manifest uses neverForLocation).
        // - Android <12: ACCESS_FINE/COARSE_LOCATION alone is sufficient (legacy BLE scan model;
        //   BLUETOOTH/BLUETOOTH_ADMIN are normal permissions, auto-granted at install).
        val locationPermission =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val bluetoothScanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Pre-Android-12, BLUETOOTH/BLUETOOTH_ADMIN are install-time normal permissions.
            // Treat as available so that location-only grants still unlock the scan.
            true
        }

        return locationPermission || bluetoothScanPermission
    }
}
