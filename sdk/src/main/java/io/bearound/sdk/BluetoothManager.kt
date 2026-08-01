package io.bearound.sdk

import android.Manifest
import io.bearound.sdk.utilities.ScanResultFreshness
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager as AndroidBluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import io.bearound.sdk.interfaces.BluetoothManagerListener
import io.bearound.sdk.utilities.IBeaconParser
import io.bearound.sdk.utilities.ScanStartBudget

/**
 * Manages Bluetooth LE scanning for beacon metadata
 */
class BluetoothManager(private val context: Context) {
    companion object {
        private const val TAG = "BeAroundSDK-BLEManager"
        private const val DEDUPLICATION_INTERVAL = 1000L
    }

    var listener: BluetoothManagerListener? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isScanning = false

    /** Host intent: true between startScanning() and stopScanning(), regardless of whether
     *  the scan is actually registered right now. Gate for every deferred self-heal restart. */
    private var desiredScanning = false

    /** The one cancellable self-heal restart in flight (see [healZombieScanRegistration]). */
    private var pendingRestart: Runnable? = null
    
    private val lastSeenBeacons = mutableMapOf<String, Long>()

    val isPoweredOn: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    init {
        // NEVER-CRASH-THE-HOST: on devices without a Bluetooth radio (some emulators,
        // Android TV/Auto, Wi-Fi-only tablets — the SDK declares bluetooth_le as
        // required=false on purpose) getSystemService(BLUETOOTH_SERVICE) returns null.
        // A non-null cast here would NPE synchronously inside the host's very first
        // call (getInstance() in onCreate). Degrade gracefully instead: no adapter →
        // no scanning, the rest of the class already null-guards both fields.
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? AndroidBluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter
            bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        } catch (t: Throwable) {
            Log.w(TAG, "Bluetooth unavailable on this device — SDK will stay idle: ${t.message}")
        }
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
            if (errorCode == 6 /* SCAN_FAILED_SCANNING_TOO_FREQUENTLY, API 30+ */) {
                ScanStartBudget.freeze()
            }
            if (errorCode == 1 /* SCAN_FAILED_ALREADY_STARTED */) {
                healZombieScanRegistration()
                return // heal resets isScanning itself
            }
            // Every failure code means the scan is NOT running — the flag must say so,
            // or the next startScanning() early-returns "Already scanning" forever.
            // Recovery is owned by the existing paths (region edges, BT restore, heal).
            isScanning = false
        }
    }

    /**
     * The stack drops every scan client when the radio powers off, but this object's
     * flag survived as a lie — the next startScanning() saw "Already scanning" and the
     * metadata scan stayed a zombie for the rest of the region stay.
     */
    fun onBluetoothPoweredOff() {
        if (isScanning) {
            Log.d(TAG, "Bluetooth powered off — metadata scan client is gone, clearing flag")
            isScanning = false
        }
    }

    /** Radio back on: re-arm the metadata scan if the host still wants it (in-region). */
    fun onBluetoothRestored() {
        isScanning = false
        if (desiredScanning) {
            Log.d(TAG, "Bluetooth restored — re-arming metadata scan")
            startScanning()
        }
    }

    /**
     * Self-heal for SCAN_FAILED_ALREADY_STARTED: stack/SDK state desync (failed stop,
     * BT cycle) leaves the controller thinking this callback is still registered while
     * [isScanning] says otherwise — the metadata scan stays a zombie until a full app
     * restart (field: Moto G35, code-1 loop). Clear the stale registration and re-arm
     * once; startScanning() re-checks the ScanStartBudget, which is also the natural
     * brake against a heal loop.
     */
    @SuppressLint("MissingPermission")
    private fun healZombieScanRegistration() {
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
            /* stale registration — nothing to stop */
        }
        isScanning = false
        // Cancellable + intent-guarded restart: stopScanning() during the 500ms window
        // removes the callback AND flips desiredScanning, so a heal scheduled before an
        // explicit stop can never resurrect the scan after it.
        pendingRestart?.let { handler.removeCallbacks(it) }
        val restart = Runnable {
            pendingRestart = null
            if (desiredScanning) startScanning()
        }
        pendingRestart = restart
        handler.postDelayed(restart, 500L)
    }

    /**
     * Bearound-only hardware filters. An UNFILTERED scan is suspended by the platform while
     * the screen is off (Android 8.1+), so the previous `startScan(null, …)` delivered
     * nothing exactly when the metadata matters most (backgrounded, in-region). Filtering is
     * also re-checked in [processScanResult] via the parser, so behaviour is unchanged.
     */
    private fun metadataScanFilters() = listOf(
        ScanFilter.Builder()
            .setManufacturerData(IBeaconParser.BEAROUND_MANUFACTURER_ID, byteArrayOf())
            .build(),
        ScanFilter.Builder()
            .setServiceData(IBeaconParser.BEAD_SERVICE_UUID, byteArrayOf(), byteArrayOf())
            .build(),
        ScanFilter.Builder()
            .setManufacturerData(
                IBeaconParser.APPLE_MANUFACTURER_ID,
                IBeaconParser.BEAROUND_IBEACON_PREFIX,
                IBeaconParser.BEAROUND_IBEACON_MASK
            )
            .build()
    )

    @SuppressLint("MissingPermission")
    fun startScanning() {
        // Intent flag: survives transient start failures (BT off, missing permission,
        // quota) so self-heal paths know the host still WANTS the scan; cleared only by
        // an explicit stopScanning().
        desiredScanning = true
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

        if (!ScanStartBudget.tryAcquire("metadata")) return

        try {
            val settings = ScanSettings.Builder()
                // Foreground service is active -> BALANCED (not LOW_POWER) for faster detection; Android throttles anyway without a FG service.
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .setReportDelay(0)
                .build()

            bluetoothLeScanner?.startScan(metadataScanFilters(), settings, scanCallback)
            isScanning = true
            Log.d(TAG, "Started BLE scanning")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE scanning: ${e.message}")
        }
    }

    /**
     * Re-registra o scan de metadata (stop+start) SEM mudar o estado do ciclo de vida.
     * Anti-downgrade: sessões de scan longas são rebaixadas pelo sistema (AOSP: >30 min
     * vira opportunistic; OEMs Android 13+ aplicam adaptive throttling antes disso —
     * sessões AMBIENT_DISCOVERY/OPPORTUNISTIC observadas em campo no Moto G35/realme C61).
     * Uma sessão recém-registrada volta ao duty pleno. Chamado pelo refresh periódico do
     * SDK (~20 min). Se o ScanStartBudget não tiver folga, mantém a sessão atual como
     * está (pular um refresh é inofensivo; matar o scan sem conseguir religar não é).
     */
    @SuppressLint("MissingPermission")
    fun restartScanning() {
        if (!isScanning) return
        if (!checkPermissions()) return
        if (!ScanStartBudget.tryAcquire("metadata-restart")) return
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
            /* sessão já morta no stack — o start abaixo recria */
        }
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .setReportDelay(0)
                .build()
            bluetoothLeScanner?.startScan(metadataScanFilters(), settings, scanCallback)
            Log.d(TAG, "Metadata scan re-registered (anti-downgrade refresh)")
        } catch (e: Exception) {
            isScanning = false
            Log.w(TAG, "Anti-downgrade restart failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        // Always clear the intent + any pending self-heal restart, even when isScanning
        // is already false — a heal Runnable may be in flight for a scan that failed.
        desiredScanning = false
        pendingRestart?.let { handler.removeCallbacks(it) }
        pendingRestart = null

        if (!isScanning) return

        try {
            bluetoothLeScanner?.stopScan(scanCallback)
            Log.d(TAG, "Stopped BLE scanning")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop BLE scanning: ${e.message}")
        } finally {
            // The local flag must drop even when stopScan throws (e.g. SecurityException
            // after a permission revoke) — a lying isScanning=true blocked every restart.
            isScanning = false
            lastSeenBeacons.clear()
        }
    }

    private fun processScanResult(result: ScanResult) {
        // Controller-fossil guard — see [ScanResultFreshness]. BeaconManager logs the drops.
        if (ScanResultFreshness.isStale(result)) return
        val scanRecord = result.scanRecord ?: return
        val rssi = result.rssi

        // Check if RSSI is valid
        if (rssi == 127 || rssi == 0) return

        val serviceData = IBeaconParser.parseServiceData(scanRecord, rssi) ?: return
        if (!shouldProcessBeacon(serviceData.major, serviceData.minor)) return

        // ScanResult.isConnectable() (API 26+) reports the actual PDU type; the old
        // `advertiseFlags and 0x02` read the "LE General Discoverable" flag, which is
        // discoverability — not whether the device accepts connections.
        val isConnectable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            result.isConnectable
        } else {
            scanRecord.advertiseFlags and 0x02 != 0
        }

        listener?.onBeaconDiscovered(
            uuid = IBeaconParser.BEAROUND_UUID,
            major = serviceData.major,
            minor = serviceData.minor,
            rssi = rssi,
            txPower = serviceData.metadata?.txPower ?: -59,
            metadata = serviceData.metadata,
            isConnectable = isConnectable
        )
    }

    private fun shouldProcessBeacon(major: Int, minor: Int): Boolean {
        val key = "$major.$minor"
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
        // Version-dependent BLE-scan gate (mirrors BeaconManager):
        // - Android 12+ (S+): BLUETOOTH_SCAN is the only gate — the manifest asserts
        //   neverForLocation, so results flow with Bluetooth alone (location optional).
        // - Android <12: legacy model — ACCESS_FINE/COARSE_LOCATION unlocks the BLE scan.
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
}
