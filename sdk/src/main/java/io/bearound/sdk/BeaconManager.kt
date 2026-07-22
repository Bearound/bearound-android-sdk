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
import io.bearound.sdk.utilities.ScanStartBudget
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
        // Default/floor for the beacon eviction timeout. Region enter/exit flicker is
        // no longer a concern here — the zone falling edge has its own 5-min grace
        // (ZONE_EXIT_GRACE_MS), so this only controls how fast the host LIST drops a
        // vanished beacon. With the continuous scan modes (3.5.2) a present beacon
        // delivers every ~1-5 s, so the SDK sets 15-25 s per precision; 10 s is the
        // hard floor for callers of setBeaconTimeout.
        private const val BEACON_TIMEOUT_DEFAULT = 15000L
        private const val BEACON_TIMEOUT_FLOOR = 10000L
        private const val WATCHDOG_INTERVAL = 30000L
        private const val RANGING_REFRESH_INTERVAL = 120000L
        private const val MAX_RESTARTS_PER_MINUTE = 3
        private const val DEFAULT_TX_POWER = -59

        /** Batch report delay for the slow-beacon iBeacon scan (see [startSlowBeaconBatchScan]). */
        private const val SLOW_BEACON_BATCH_DELAY_MS = 2000L

        /**
         * Window during which a batch-delivered sample is dropped when the regular scan has
         * already sampled the same beacon. Beacons matched by BOTH scanners (0xBEAD in the
         * primary PDU and the iBeacon frame) would otherwise feed [rssiAccumulators] twice
         * and skew the RSSI statistics synced to the backend. Scan-response-only beacons
         * (never matched by the regular scan) are unaffected — their samples always land.
         */
        private const val SLOW_BEACON_DEDUP_MS = 5000L

        /**
         * No batch delivery for this long while the batch scanner claims to be running →
         * assume the client was starved by the OS scan-start quota and revive it. Restarting
         * at most once per window keeps the revive itself far below the quota (5/30 s).
         */
        private const val BATCH_LIVENESS_TIMEOUT_MS = 120_000L
        /** Past this gap with no packet, the beacon is rendered as stale (faded) but kept. */
        private const val STALE_THRESHOLD_MS = 5000L
        /**
         * How long the BLE eye waits without ANY beacon detection before firing
         * [onRegionExit]. Decoupled from [beaconTimeout] (which controls per-beacon
         * eviction from the detected map) because OS-level background scan
         * throttling (Doze, App Standby) can silence delivery for minutes while
         * the device is stationary inside the zone. Without this gate, the falling
         * edge in [cleanupExpiredBeacons] would fire as soon as the last beacon
         * timed out (≤ 65s), producing phantom exit→enter cycles seen in v3.3.0.
         * 300s (5 min) matches the iOS BLE zone exit grace.
         */
        private const val ZONE_EXIT_GRACE_MS = 300_000L
        /**
         * Persisted-zone-state staleness threshold. Restoration of `inZone=true` is only
         * trusted if the snapshot was written less than this long ago. Beyond this, the
         * snapshot is discarded and a real ENTER fires on the next detection. 1 hour is
         * well above any plausible Doze + App Standby cycle. Matches iOS.
         */
        private const val ZONE_STATE_MAX_AGE_MS = 3_600_000L
        private const val PREFS_NAME = "com.bearound.sdk.config"
        private const val PREF_KEY_ZONE_IN = "ble_zone_state_v1.inZone"
        private const val PREF_KEY_ZONE_WRITTEN_AT = "ble_zone_state_v1.writtenAt"
        private const val PREF_KEY_ZONE_LAST_SEEN = "ble_zone_state_v1.lastSeenAt"
    }

    private var bluetoothLeScanner: BluetoothLeScanner? = null
    var isScanning = false
    var isRanging = false
    private var isBatchScanning = false

    /**
     * Last time the batch scanner delivered anything. The OS scan-start quota (5 starts per
     * 30 s per app) silently starves a scan client that registered over quota — "started"
     * with no error, zero deliveries, forever. The watchdog uses this timestamp to detect
     * the starved state and revive the batch (see [checkRangingHealth]).
     */
    @Volatile
    private var lastBatchDeliveryAt = 0L
    private var isInForeground = true

    /**
     * Last time the REGULAR scan (ranging / PendingIntent) fed a sample for each beacon
     * identifier. Used to drop batch-delivered duplicates (see [SLOW_BEACON_DEDUP_MS]).
     */
    private val lastRegularSampleAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /**
     * True while at least one beacon is currently detected (after rising edge in [processBeacon]
     * and before falling edge in [cleanupExpiredBeacons]). Active scanning is gated by this
     * flag — outside the region only [io.bearound.sdk.background.BackgroundScanManager]'s
     * PendingIntent-based scan runs (kernel-managed, low power).
     */
    var isInBeaconRegion: Boolean = false
        private set

    /**
     * Scan mode for the ranging client, set by the SDK from the active precision.
     * MEDIUM → BALANCED (~20% hardware duty, listen window every ~5 s);
     * LOW → LOW_POWER (~10%, every ~5 s); null (HIGH) → legacy behavior
     * (LOW_LATENCY in foreground, BALANCED in background). Continuous registration:
     * the controller manages the duty in hardware, so the client is registered ONCE —
     * unlike the old manual start/stop duty cycle, which spent 3-4 of the 5
     * scan-starts/30 s the OS allows and starved every scanner when anything else
     * (watchdog, batch revive, refresh) needed a start (field: Moto G35).
     */
    @Volatile
    var rangingScanMode: Int? = null

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

    /**
     * Last time ANY beacon ad was received, independent of [detectedBeacons] cleanup.
     * Used by [cleanupExpiredBeacons]'s falling-edge logic so the region exit grace is
     * decoupled from the per-beacon eviction timeout (which can be as low as 5s in HIGH
     * precision mode). Reset on [stopScanning] and after a real [onRegionExit].
     */
    private var lastBeaconSeenAt: Long? = null

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

    init {
        // Restore persisted zone state — see [restorePersistedZoneState]. Must run before
        // the first beacon is processed by [processBeacon], otherwise the default
        // `isInBeaconRegion = false` would let the rising edge fire a phantom ENTER on the
        // post-restoration first delivery (e.g. when AlarmManager / PendingIntent scan
        // wakes the app after Doze + termination, while the device never left the zone).
        restorePersistedZoneState()
    }

    private fun zoneStatePrefs() =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Saves `(isInBeaconRegion, lastBeaconSeenAt)` to SharedPreferences. Called from the
     * 3 transitions: rising edge in [processBeacon], falling edge in [cleanupExpiredBeacons],
     * and [stopScanning] (clean wipe). `apply()` is async so this is cheap to call from
     * the scan/lock paths.
     */
    private fun persistZoneState() {
        val editor = zoneStatePrefs().edit()
        editor.putBoolean(PREF_KEY_ZONE_IN, isInBeaconRegion)
        editor.putLong(PREF_KEY_ZONE_WRITTEN_AT, System.currentTimeMillis())
        val last = lastBeaconSeenAt
        if (last != null) {
            editor.putLong(PREF_KEY_ZONE_LAST_SEEN, last)
        } else {
            editor.remove(PREF_KEY_ZONE_LAST_SEEN)
        }
        editor.apply()
    }

    /**
     * Restores the persisted snapshot at construction. Only honors `inZone=true` snapshots
     * younger than [ZONE_STATE_MAX_AGE_MS] — older or absent snapshots are ignored so a
     * genuine ENTER can still fire on the next detection.
     */
    private fun restorePersistedZoneState() {
        val prefs = zoneStatePrefs()
        if (!prefs.contains(PREF_KEY_ZONE_IN)) {
            Log.d(TAG, "No persisted zone state found — starting fresh")
            return
        }
        val inZone = prefs.getBoolean(PREF_KEY_ZONE_IN, false)
        val writtenAt = prefs.getLong(PREF_KEY_ZONE_WRITTEN_AT, 0L)
        val age = System.currentTimeMillis() - writtenAt
        if (age > ZONE_STATE_MAX_AGE_MS) {
            Log.d(TAG, "Persisted zone state stale (age=${age / 1000}s > max=${ZONE_STATE_MAX_AGE_MS / 1000}s) — ignoring")
            return
        }
        if (!inZone) {
            Log.d(TAG, "Persisted zone state was OUT-of-region — nothing to restore")
            return
        }
        isInBeaconRegion = true
        val lastSeen = if (prefs.contains(PREF_KEY_ZONE_LAST_SEEN)) {
            prefs.getLong(PREF_KEY_ZONE_LAST_SEEN, writtenAt)
        } else {
            writtenAt
        }
        lastBeaconSeenAt = lastSeen
        Log.d(TAG, "Restored persisted zone state: inRegion=true age=${age / 1000}s — suppressing phantom ENTER")
    }

    /**
     * Set how long a beacon stays in the detected map after the last packet.
     * Should be set to cover the worst-case gap between packets for the active
     * scan precision (scan + pause + buffer).
     */
    fun setBeaconTimeout(timeoutMs: Long) {
        beaconTimeoutMs = timeoutMs.coerceAtLeast(BEACON_TIMEOUT_FLOOR)
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
            handleScanFailure(errorCode)
            // The client did NOT register — reflect it so the duty cycle/watchdog retry.
            isRanging = false
            val error = Exception("Beacon scan failed with error code: $errorCode")
            onError?.invoke(error)
        }
    }

    /**
     * Shared reaction to `onScanFailed` codes: SCANNING_TOO_FREQUENTLY (6, Android 11+) means
     * the app hit the OS scan-start quota — calling startScan again only extends the penalty,
     * so freeze every start for a cool-off window.
     */
    private fun handleScanFailure(errorCode: Int) {
        if (errorCode == 6 /* ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY, API 30+ */) {
            ScanStartBudget.freeze()
        }
    }

    /**
     * Dedicated callback for the batched iBeacon scan (see [startSlowBeaconBatchScan]).
     * Kept separate from [scanCallback] so the batch scanner can be started/stopped
     * independently of the regular 0xBEAD scan. Results are parsed by the same
     * [processScanResult] — the batched ScanRecord carries the 0xBEAD payload (scan response),
     * so the existing BEAD parser handles them unchanged. `fromBatch = true` lets
     * [processScanResult] drop samples for beacons the regular scan is already feeding,
     * so RSSI statistics don't get double-counted (see [SLOW_BEACON_DEDUP_MS]).
     */
    private val batchScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            lastBatchDeliveryAt = System.currentTimeMillis()
            processScanResult(result, fromBatch = true)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            lastBatchDeliveryAt = System.currentTimeMillis()
            results.forEach { processScanResult(it, fromBatch = true) }
        }

        override fun onScanFailed(errorCode: Int) {
            // Batch scanning isn't supported on every device; the regular scan still runs.
            Log.w(TAG, "Slow-beacon batch scan failed (code $errorCode) — regular scan unaffected")
            handleScanFailure(errorCode)
            // Client didn't register — clear the flag so the liveness check can re-arm it.
            isBatchScanning = false
        }
    }

    fun setForegroundState(inForeground: Boolean) {
        val changed = isInForeground != inForeground
        isInForeground = inForeground

        if (!inForeground && isScanning) {
            startRangingRefreshTimer()
        } else if (inForeground) {
            stopRangingRefreshTimer()
        }

        // The effective scan mode depends on the app state (foreground → LOW_LATENCY,
        // background → precision mode), so a fg/bg flip must re-register the ranging
        // client with the new mode. Budget-aware via restartRanging(): when there is no
        // start headroom the current session is kept — never killed without a replacement.
        if (changed && isScanning && isRanging) {
            restartRangingForModeChange()
        }
    }

    /** Stop+start the ranging client so the scan mode matches the current app state. */
    @SuppressLint("MissingPermission")
    private fun restartRangingForModeChange() {
        if (!ScanStartBudget.tryAcquire("ranging-mode-flip")) return
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
            /* session already gone in the stack — the start below recreates it */
        }
        isRanging = false
        startRanging()
        Log.d(TAG, "Ranging re-registered for app-state change (foreground=$isInForeground)")
    }

    /**
     * Bluetooth was toggled off→on: the stack dropped every scan client while the local
     * flags stayed true. Re-arm the batch immediately (budget-guarded); the ranging watchdog
     * and the duty cycle recover the other scanners on their own ticks.
     */
    fun onBluetoothRestored() {
        if (!isScanning) return
        Log.i(TAG, "Bluetooth restored — re-arming batch scan")
        isBatchScanning = false
        startSlowBeaconBatchScan()
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
            // Watchdog/restart path: the regular ranging has its own health-check, but the
            // batch scanner doesn't — re-arm it here (idempotent via isBatchScanning) so a
            // batch client dropped by the controller comes back on the next watchdog tick.
            if (checkPermissions()) startSlowBeaconBatchScan()
            return
        }

        if (!checkPermissions()) {
            val error = Exception(missingPermissionMessage())
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
            startSlowBeaconBatchScan()
            isScanning = true
            onScanningStateChanged?.invoke(true)

            // Reconfig-safe zone restore: a stop()+start() cycle (settings apply) must not
            // lose a live in-zone state. Restores the snapshot persisted by stopScanning()
            // and — when the last advert is fresh (within the exit grace) — re-fires the
            // active-scan path the rising edge would normally fire, so HIGH precision
            // starts ranging immediately instead of waiting for the next broadcast.
            // If the zone is genuinely gone, the exit grace shuts active scanning down.
            restorePersistedZoneState()
            val last = lastBeaconSeenAt
            if (isInBeaconRegion && last != null &&
                System.currentTimeMillis() - last < ZONE_EXIT_GRACE_MS
            ) {
                Log.d(TAG, "Zone state fresh (${(System.currentTimeMillis() - last) / 1000}s since last ad) — re-arming active scan")
                startRegionCleanupTimer()
                onActiveScanShouldStart?.invoke()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scanning: ${e.message}")
            io.bearound.sdk.telemetry.ErrorReporter.report(e, "BeaconManager.startScanning")
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
        stopSlowBeaconBatchScan()

        // Snapshot the TRUE zone state BEFORE tearing it down. stop()+start() is also the
        // reconfigure path (settings apply): persisting `false` here made the next start
        // skip ranging ("startRanging skipped — not inside beacon region") until a
        // PendingIntent broadcast happened to revive it — in the field, precision changes
        // looked like they didn't apply (realme C61, 2026-07-22). The fresh snapshot lets
        // startScanning() restore the zone and re-arm active scanning immediately.
        persistZoneState()

        beaconLock.withLock {
            detectedBeacons.clear()
            beaconLastSeen.clear()
            rssiAccumulators.clear()
        }
        lastRegularSampleAt.clear()
        rssiFilter.clear()
        lastBeaconSeenAt = null

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

        // Adaptive: in FOREGROUND every precision gets LOW_LATENCY — the user is looking
        // at the screen, detection must feel fluid, and the display dwarfs the radio cost.
        // In BACKGROUND each precision pays its own price: HIGH/MEDIUM → BALANCED (~20%
        // hardware duty), LOW → LOW_POWER (~10%) — see [rangingScanMode]. This is what
        // makes MEDIUM a safe default: integrators get best-in-class foreground detection
        // automatically and a battery-honest background without configuring anything.
        val scanMode = if (isInForeground) {
            ScanSettings.SCAN_MODE_LOW_LATENCY
        } else {
            rangingScanMode ?: ScanSettings.SCAN_MODE_BALANCED
        }

        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .setReportDelay(0)
            .build()

        // Preventive quota guard: skipping one duty-cycle tick is recoverable (the next
        // tick retries in seconds); exceeding the OS quota silently starves the client.
        if (!ScanStartBudget.tryAcquire("ranging")) return

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

    /**
     * Dedicated batched scan for "slow" Bearound beacons. Some beacons advertise the 0xBEAD
     * payload in the SCAN RESPONSE (not the primary PDU) and/or at a long advertising interval
     * (~1 s). On some OEMs (notably Samsung) the regular scan runs at a reduced host duty cycle
     * and never catches them, even though the controller receives the packet. This scan:
     *  - filters on the Bearound iBeacon frame (present in the PRIMARY PDU, so the offloaded
     *    hardware filter matches it — the 0xBEAD service-data filter can't, being scan-response),
     *  - uses a non-zero reportDelay (batch), so — where the controller supports offloaded
     *    batching — matches are accumulated continuously and delivered via onBatchScanResults
     *    regardless of the host duty cycle. Devices without offloaded batching fall back to
     *    framework-emulated batching (still functional; the regular scan keeps running too).
     * Runs alongside the regular 0xBEAD scan; the delivered ScanRecord still carries the 0xBEAD
     * payload, so [processScanResult]/[IBeaconParser.parseServiceData] handle it unchanged.
     */
    @SuppressLint("MissingPermission")
    private fun startSlowBeaconBatchScan() {
        if (isBatchScanning) return
        val scanner = bluetoothLeScanner ?: return
        // Preventive quota guard — the liveness check in checkRangingHealth retries later.
        if (!ScanStartBudget.tryAcquire("batch")) return
        try {
            // Field observability: offloaded batching is the premise of this scan — log where
            // the device only emulates it in software, so "batch didn't help" is diagnosable.
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
                as? android.bluetooth.BluetoothManager)?.adapter
            if (adapter?.isOffloadedScanBatchingSupported == false) {
                Log.i(TAG, "Offloaded scan batching NOT supported — batch scan will be software-emulated")
            }
            val ibFilter = ScanFilter.Builder()
                .setManufacturerData(
                    IBeaconParser.APPLE_MANUFACTURER_ID,
                    IBeaconParser.BEAROUND_IBEACON_PREFIX,
                    IBeaconParser.BEAROUND_IBEACON_MASK
                )
                .build()
            // fw v4+ transmite o 0xBEAD como advertisement PRIMÁRIO (fase alternada) —
            // o filtro offloaded de service data agora casa. Sem ele o batch é CEGO pros
            // frames BEAD-puros (validado em campo 2026-07-21: o batch via filtro iBeacon
            // não enxergava a fase B do fw v4). Lista de filtros = OR: cobre beacons
            // antigos (iBeacon no primário) E novos (0xBEAD no primário).
            val beadFilter = ScanFilter.Builder()
                .setServiceData(IBeaconParser.BEAD_SERVICE_UUID, byteArrayOf(), byteArrayOf())
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setReportDelay(SLOW_BEACON_BATCH_DELAY_MS)
                .build()
            scanner.startScan(listOf(ibFilter, beadFilter), settings, batchScanCallback)
            isBatchScanning = true
            lastBatchDeliveryAt = System.currentTimeMillis() // liveness baseline
            Log.d(TAG, "Slow-beacon batch scan started (reportDelay=${SLOW_BEACON_BATCH_DELAY_MS}ms)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start slow-beacon batch scan: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopSlowBeaconBatchScan() {
        if (!isBatchScanning) return
        try {
            bluetoothLeScanner?.stopScan(batchScanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop slow-beacon batch scan: ${e.message}")
        }
        isBatchScanning = false
    }

    private fun processScanResult(result: ScanResult, fromBatch: Boolean = false) {
        val scanRecord = result.scanRecord ?: return

        // Prefer the 0xBEAD sensor payload; fall back to the iBeacon frame when the scan
        // response wasn't captured (observed on Xiaomi batched results) — detection still
        // works, metadata stays null until a 0xBEAD frame arrives ("first sighting" case).
        val serviceData = IBeaconParser.parseServiceData(scanRecord, result.rssi)
            ?: IBeaconParser.parseIBeaconFrame(scanRecord, result.rssi)
            ?: return

        val txPower = serviceData.metadata?.txPower ?: serviceData.txPower ?: DEFAULT_TX_POWER
        val identifier = "${serviceData.major}.${serviceData.minor}"
        val rawRssi = serviceData.rssi
        val nowMs = System.currentTimeMillis()

        // Beacons matched by BOTH scanners (0xBEAD in the primary + iBeacon frame) would be
        // processed twice — once by the regular scan, once by the batch — inflating the RSSI
        // statistics synced to the backend. Drop the batch copy while the regular scan is
        // actively feeding this beacon; scan-response-only beacons (e.g. B:0.135) are never
        // matched by the regular scan, so their batch samples always pass.
        if (fromBatch) {
            val lastRegular = lastRegularSampleAt[identifier] ?: 0L
            if (nowMs - lastRegular < SLOW_BEACON_DEDUP_MS) return
        } else {
            lastRegularSampleAt[identifier] = nowMs
        }

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
        // Cleanup-immune "any beacon seen at" timeline. See [lastBeaconSeenAt] kdoc.
        lastBeaconSeenAt = now

        if (wasOutOfRegion) {
            Log.d(TAG, "REGION ENTER — first beacon detected (${beacon.identifier})")
            persistZoneState()
            onRegionEnter?.invoke()
            onActiveScanShouldStart?.invoke()
        }

        // Ensure the periodic cleanup timer is running so we eventually detect the falling edge.
        startRegionCleanupTimer()

        cleanupExpiredBeacons()

        // Refresh stale flag for every beacon — emit reflects current freshness
        refreshStaleFlags(now)
        val currentBeacons = beaconLock.withLock { detectedBeacons.values.toList() }

        if (currentBeacons.isNotEmpty()) {
            onBeaconsUpdated?.invoke(currentBeacons)
        }

        startWatchdog()
    }

    /**
     * Recomputes the per-beacon staleness flag IN the detected map. Returns true when any
     * flag flipped since the last pass — the periodic cleanup uses this to know the host's
     * list needs a refresh (fade-in/fade-out) without spamming identical emissions.
     */
    private fun refreshStaleFlags(now: Long = System.currentTimeMillis()): Boolean =
        beaconLock.withLock {
            var changed = false
            for (id in detectedBeacons.keys.toList()) {
                val b = detectedBeacons[id] ?: continue
                val lastSeen = beaconLastSeen[id] ?: now
                val staleNow = (now - lastSeen) > STALE_THRESHOLD_MS
                if (staleNow != b.isStale) {
                    detectedBeacons[id] = b.copy(isStale = staleNow)
                    changed = true
                }
            }
            changed
        }

    /** Removes timed-out beacons from the detected map. Returns true when any was removed. */
    private fun cleanupExpiredBeacons(): Boolean {
        var removedAny = false
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
            removedAny = expiredKeys.isNotEmpty()
            Pair(before, detectedBeacons.isNotEmpty())
        }

        // Falling-edge detection: only fire region exit after the cleanup-immune grace
        // (ZONE_EXIT_GRACE_MS = 5 min) has elapsed since the LAST advert. Previously this
        // fired as soon as the per-beacon `beaconTimeout` (5-65s) drained the dict —
        // producing phantom EXIT→ENTER cycles when OS-level background scan throttling
        // silenced delivery briefly. Now the dict can empty without triggering exit;
        // exit fires only when no advert has arrived for ZONE_EXIT_GRACE_MS.
        if (hadBeaconsBefore && !hasBeaconsAfter && isInBeaconRegion) {
            val last = lastBeaconSeenAt
            val graceElapsed = last == null || (System.currentTimeMillis() - last) > ZONE_EXIT_GRACE_MS
            if (graceElapsed) {
                isInBeaconRegion = false
                lastBeaconSeenAt = null
                persistZoneState()
                Log.d(TAG, "REGION EXIT — no beacon ad for ${ZONE_EXIT_GRACE_MS / 1000}s, falling back to background broadcast scan")
                onRegionExit?.invoke()
                onActiveScanShouldStop?.invoke()
                stopRegionCleanupTimer()
            } else {
                val sinceLast = if (last != null) (System.currentTimeMillis() - last) / 1000 else -1
                Log.d(TAG, "Detected map drained but zone-exit grace not yet elapsed (${sinceLast}s since last ad, grace=${ZONE_EXIT_GRACE_MS / 1000}s) — staying in region")
            }
        }
        return removedAny
    }

    private fun startRegionCleanupTimer() {
        if (regionCleanupRunnable != null) return
        regionCleanupRunnable = object : Runnable {
            override fun run() {
                if (!isScanning) {
                    stopRegionCleanupTimer()
                    return
                }
                val removed = cleanupExpiredBeacons()
                val staleChanged = refreshStaleFlags()
                if (removed || staleChanged) {
                    // Push the post-expiry snapshot (possibly EMPTY) to the host. Without
                    // this emit, a beacon that left the air stayed frozen on the host's
                    // list until the next packet from any OTHER beacon arrived — the
                    // "ghost beacon" seen in the example app. An empty list is a valid
                    // emission: it is how the host learns the last beacon is gone.
                    val snapshot = beaconLock.withLock { detectedBeacons.values.toList() }
                    onBeaconsUpdated?.invoke(snapshot)
                }
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

    /**
     * Re-registra o batch scan (anti-downgrade) — par do BluetoothManager.restartScanning().
     * Se o ScanStartBudget negar o re-start, isBatchScanning fica false e o próprio
     * watchdog ([checkRangingHealth]) re-arma no próximo ciclo — sem estado perdido.
     */
    fun refreshBatchScan() {
        if (!isScanning || !isBatchScanning) return
        stopSlowBeaconBatchScan()
        startSlowBeaconBatchScan()
    }

    private fun checkRangingHealth() {
        if (!isScanning) return

        // Batch liveness — runs regardless of region (the batch IS the out-of-region
        // detector for scan-response beacons). A client starved by the OS scan-start quota
        // stays "started" with zero deliveries forever; revive it after a silent window.
        // Also re-arms a batch that died via onScanFailed (isBatchScanning == false).
        if (!isBatchScanning) {
            startSlowBeaconBatchScan()
        } else if (System.currentTimeMillis() - lastBatchDeliveryAt > BATCH_LIVENESS_TIMEOUT_MS) {
            Log.w(TAG, "Batch scan silent for ${BATCH_LIVENESS_TIMEOUT_MS / 1000}s — reviving")
            stopSlowBeaconBatchScan()
            startSlowBeaconBatchScan()
        }

        if (!isInBeaconRegion) return

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
        // The BLE-scan gate is version-dependent:
        // - Android 12+ (S+): BLUETOOTH_SCAN is the only gate. The manifest asserts
        //   neverForLocation, which is what makes Bluetooth-only detection work: without the
        //   flag Android silently withholds every scan result unless ACCESS_FINE_LOCATION is
        //   also granted (verified on-device — an unfiltered scan delivered 0 results).
        //   Location is still declared/recommended for OEM coverage, but not required here.
        // - Android <12: legacy model — ACCESS_FINE/COARSE_LOCATION unlocks the BLE scan
        //   (BLUETOOTH/BLUETOOTH_ADMIN are install-time normal permissions).
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Whether BLUETOOTH_SCAN is granted (the runtime permission that unlocks the BLE scan
     * on Android 12+). Always true on Android <12 where BLUETOOTH_SCAN is not a runtime
     * permission. Exposed for diagnostics.
     */
    fun hasBluetoothScanPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Version-accurate message for the missing-permission case: on Android 12+ BLUETOOTH_SCAN
     * ("Nearby devices") is the hard gate (location is recommended alongside it but not
     * required to start the scan); on Android ≤11 location is what unlocks the BLE scan.
     */
    private fun missingPermissionMessage(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "BLUETOOTH_SCAN (Nearby devices) required on Android 12+ to start the BLE scan; also grant location for full beacon coverage"
        } else {
            "ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION required on Android ≤11 to unlock the BLE scan"
        }
    }
}
