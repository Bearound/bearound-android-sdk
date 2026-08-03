package io.bearound.sdk.background

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import io.bearound.sdk.utilities.IBeaconParser
import io.bearound.sdk.utilities.ScanStartBudget

/**
 * Manages background beacon scanning using:
 * - Android 8+ (API 26+): Bluetooth Scan Broadcast via PendingIntent (real-time, no notification)
 * - Android <8: Not supported
 */
class BackgroundScanManager(private val context: Context) {
    
    companion object {
        private const val TAG = "BeAroundSDK-BgScan"
    }
    
    /**
     * Set ONLY after the stack ACCEPTED the registration (startScan returned 0).
     * The old flow assigned it before the budget check and before startScan — a denied
     * quota then left the field non-null, [isRegistered] lied `true`, and the 35s retry
     * (gated on `pendingIntent == null`) never re-registered anything: the only
     * out-of-region detector stayed dead until the 15-min watchdog.
     */
    private var pendingIntent: PendingIntent? = null

    /** Host intent: true between enable and disable, regardless of registration success. */
    private var desiredEnabled = false

    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingRetry: Runnable? = null

    /** True while the low-power PendingIntent background scan is registered. For diagnostics. */
    val isRegistered: Boolean
        get() = pendingIntent != null
    
    /**
     * Enable background scanning
     * Works on Android 8+ (API 26+) using PendingIntent-based BLE scan
     */
    @SuppressLint("MissingPermission")
    fun enableBackgroundScanning() {
        desiredEnabled = true
        // On Android 12+ the PendingIntent scan requires BLUETOOTH_SCAN. Without it the OS
        // throws SecurityException (caught, but it floods the log on every retry). This path
        // is reached ungated via restartScanningFromBackground, so check here too — skip
        // silently, consistent with the managers' permission gate.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Skipping background scan — BLUETOOTH_SCAN not granted (Android 12+)")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enableBluetoothScanBroadcast()
        } else {
            Log.w(TAG, "Background scanning requires Android 8+")
        }
    }

    /**
     * Disable background scanning. Also drops the intent flag and cancels any delayed
     * retry, so a registration deferred before the disable can't fire after it.
     */
    @SuppressLint("MissingPermission")
    fun disableBackgroundScanning() {
        desiredEnabled = false
        pendingRetry?.let { retryHandler.removeCallbacks(it) }
        pendingRetry = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            disableBluetoothScanBroadcast()
        }
    }
    
    /**
     * Android 8+ (API 26+) - Register PendingIntent-based BLE scan
     * System will wake up the app when beacon is detected
     */
    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("MissingPermission")
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
            
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            // Created locally; published to the field ONLY after the stack accepts the
            // scan — see [pendingIntent] for why premature assignment broke the retry.
            val candidate = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                flags
            )

            val filters = listOf(
                ScanFilter.Builder()
                    .setManufacturerData(
                        IBeaconParser.BEAROUND_MANUFACTURER_ID,
                        byteArrayOf()
                    )
                    .build(),
                ScanFilter.Builder()
                    .setServiceData(
                        IBeaconParser.BEAD_SERVICE_UUID,
                        byteArrayOf(),
                        byteArrayOf()
                    )
                    .build(),
                // iBeacon Bearound (Apple 0x004C, UUID e25b8d3c). Some beacons (e.g. B:0.135)
                // advertise the 0xBEAD payload in the SCAN RESPONSE, not the primary PDU — the
                // offloaded filter only inspects the primary, so the 0xBEAD filters miss them.
                // Matching the iBeacon (present in the primary) wakes the PendingIntent for those.
                ScanFilter.Builder()
                    .setManufacturerData(
                        IBeaconParser.APPLE_MANUFACTURER_ID,
                        IBeaconParser.BEAROUND_IBEACON_PREFIX,
                        IBeaconParser.BEAROUND_IBEACON_MASK
                    )
                    .build(),
                // Encounter layer: nearby SDK hosts wake the PendingIntent too, so the
                // mesh keeps receiving while the app is backgrounded or process-dead.
                ScanFilter.Builder()
                    .setServiceUuid(io.bearound.sdk.EncounterMeshManager.SERVICE_PARCEL)
                    .build()
            )
            
            val settings = ScanSettings.Builder()
                // Foreground service is active -> BALANCED (not LOW_POWER) for faster detection; Android throttles anyway without a FG service.
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .build()
            
            if (!ScanStartBudget.tryAcquire("pending-intent")) {
                // The PendingIntent scan is the ONLY out-of-region detector — waiting for the
                // 15-min watchdog after a startup-burst deferral is too long. Retry shortly,
                // once the 30 s budget window has rolled over. The field stays null, so the
                // retry's `pendingIntent == null` gate actually fires (the old flow had
                // already assigned it, turning every deferral into a permanent no-op).
                candidate.cancel()
                scheduleRetry()
                return
            }

            // For PendingIntent scans the SCAN_FAILED_* code comes in the RETURN VALUE —
            // there is no callback. Ignoring it makes a failed registration (e.g. over
            // the scan-start quota during a fg↔bg flip) permanently invisible.
            val result = bluetoothLeScanner?.startScan(filters, settings, candidate) ?: -1
            if (result != 0) {
                Log.w(TAG, "PendingIntent scan registration FAILED (code $result) — retrying shortly")
                if (result == 6 /* SCAN_FAILED_SCANNING_TOO_FREQUENTLY */) ScanStartBudget.freeze()
                candidate.cancel()
                scheduleRetry()
                return
            }

            pendingIntent = candidate
            Log.d(TAG, "Bluetooth Scan Broadcast registered")

        } catch (e: Exception) {
            // No lying state on an exception mid-setup: the stack did NOT accept a scan.
            pendingIntent = null
            Log.e(TAG, "Failed to setup background scanning: ${e.message}")
            io.bearound.sdk.telemetry.ErrorReporter.report(e, "BackgroundScanManager.enableBluetoothScanBroadcast")
        }
    }

    /** Single-flight delayed retry for a budget-deferred (or quota-failed) registration. */
    private fun scheduleRetry(delayMs: Long = 35_000L) {
        if (pendingRetry != null) return
        val retry = Runnable {
            pendingRetry = null
            if (desiredEnabled && pendingIntent == null) {
                Log.d(TAG, "Retrying deferred PendingIntent scan registration")
                enableBackgroundScanning()
            }
        }
        pendingRetry = retry
        retryHandler.postDelayed(retry, delayMs)
    }

    /**
     * Re-registers the PendingIntent scan from scratch. The PendingIntent client is the ONLY
     * out-of-region detector, and it can die silently (Bluetooth off→on, quota starvation,
     * stack restart) with [isRegistered] still true — the 15-min watchdog calls this as a
     * cheap self-heal (1 scan start per watchdog tick at most).
     */
    fun refreshBackgroundScanning() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        disableBluetoothScanBroadcast()
        enableBluetoothScanBroadcast()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("MissingPermission")
    private fun disableBluetoothScanBroadcast() {
        try {
            pendingIntent?.let { pi ->
                bluetoothLeScanner?.stopScan(pi)
                pi.cancel()
                pendingIntent = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable background scanning: ${e.message}")
            io.bearound.sdk.telemetry.ErrorReporter.report(e, "BackgroundScanManager.disableBluetoothScanBroadcast")
        }
    }

}

