package io.bearound.sdk

import android.annotation.SuppressLint
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import io.bearound.sdk.models.EncounterObservation
import java.security.SecureRandom
import java.util.UUID

/**
 * Device-to-device encounter layer: the host both **transmits** (advertises a fixed
 * service UUID with its rotating identifier in the scan response) and **receives**
 * (recognises other hosts in the shared BLE scan and aggregates their signal strength).
 *
 * Identity travels ON AIR — no GATT, no connections, no BLUETOOTH_CONNECT:
 * - Android peers carry the identifier as 16-bit service data (`0xBEA1`, 16 raw bytes)
 *   in the scan response.
 * - iOS peers (foreground) carry it as the advertised local name (22-char base64url of
 *   the same 16 bytes). Backgrounded iOS advertising is invisible to Android scanners
 *   by platform design, so no pair is lost by not connecting.
 *
 * - RSSI comes from advertisements; aggregates keep four running integers per peer
 *   (no sample buffers); tracked peers are capped and stale entries evicted.
 * - Requires BLUETOOTH_ADVERTISE on API 31+ to be seen; the layer degrades to
 *   receive-only when it is missing — never throws, never prompts.
 */
internal class EncounterMeshManager(private val context: Context) {

    companion object {
        private const val TAG = "EncounterMesh"
        val SERVICE_UUID: UUID = UUID.fromString("B3A20001-0000-4000-8000-BEA0BEA0BEA0")
        val SERVICE_PARCEL: ParcelUuid = ParcelUuid(SERVICE_UUID)

        /** 16-bit service-data key carrying the rotating identifier (16 raw bytes) in
         * the scan response — identity travels on air, no GATT connection needed. */
        val RPI_DATA_PARCEL: ParcelUuid = ParcelUuid.fromString("0000BEA1-0000-1000-8000-00805F9B34FB")

        const val RPI_ROTATION_MS: Long = 15 * 60 * 1000

        /** iBeacon major reserved for hosts advertising as virtual beacons. Must match
         * [io.bearound.sdk.utilities.IBeaconParser.VIRTUAL_ENCOUNTER_MAJOR] — every
         * receive path filters it out of detection. */
        private const val VIRTUAL_BEACON_MAJOR = 0xFFFF

        private const val MAX_TRACKED_PEERS = 64
        private const val PEER_STALE_EVICTION_MS: Long = 10 * 60 * 1000

        private const val PREFS = "io.bearound.sdk.mesh"
        private const val KEY_CURRENT = "rpi.current"
        private const val KEY_PREVIOUS = "rpi.previous"
        private const val KEY_ROTATED_AT = "rpi.rotatedAt"
    }

    private class PeerAggregate {
        var rpi: String? = null
        var lastRssi = 0
        var sampleCount = 0
        var rssiMin = 0
        var rssiMax = Int.MIN_VALUE
        var rssiSum = 0L
        var firstSeen = 0L
        var lastSeen = 0L

        fun addSample(rssi: Int, now: Long) {
            if (sampleCount == 0) { firstSeen = now; rssiMin = rssi; rssiMax = rssi }
            lastRssi = rssi
            sampleCount++
            rssiMin = minOf(rssiMin, rssi)
            rssiMax = maxOf(rssiMax, rssi)
            rssiSum += rssi
            lastSeen = now
        }

        val rssiAvg: Int get() = if (sampleCount == 0) 0 else (rssiSum.toDouble() / sampleCount).toInt()
    }

    private val lock = Any()
    private val peers = HashMap<String, PeerAggregate>() // key: device address
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private var started = false
    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null
    private var virtualBeaconMinor = -1
    private var advertisedRpi: String? = null

    // ── Rotating identifier ──────────────────────────────────────────────────

    /** Current identifier (32 lowercase hex), rotating lazily on read. */
    fun currentRpi(now: Long = System.currentTimeMillis()): String = synchronized(lock) {
        val rotatedAt = prefs.getLong(KEY_ROTATED_AT, 0)
        val existing = prefs.getString(KEY_CURRENT, null)
        if (existing != null && rotatedAt > 0 && now - rotatedAt < RPI_ROTATION_MS) return existing
        val fresh = ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        prefs.edit()
            .putString(KEY_PREVIOUS, existing)
            .putString(KEY_CURRENT, fresh)
            .putLong(KEY_ROTATED_AT, now)
            .apply()
        // Advertised identity (scan response + virtual-beacon minor) must follow rotation.
        if (advertisedRpi != null && advertisedRpi != fresh) {
            refreshAdvertisingAfterRotation()
        }
        return fresh
    }

    /** `[current, previous]` for the sync payload. */
    fun currentEncounterIds(): List<String> {
        val current = currentRpi()
        val previous = prefs.getString(KEY_PREVIOUS, null)
        return if (previous != null) listOf(current, previous) else listOf(current)
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    private fun hasPermission(name: String): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth unavailable — mesh idle until restart")
            return
        }
        if (!hasPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)) {
            Log.w(TAG, "BLUETOOTH_ADVERTISE not granted — mesh is receive-only")
        } else {
            startAdvertising(adapter)
        }
        Log.i(TAG, "Encounter mesh started (rpi=${currentRpi().take(8)}…)")
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
            peers.clear()
        }
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        runCatching { advertiser?.stopAdvertising(virtualBeaconCallback) }
        advertiser = null
        virtualBeaconMinor = -1
        advertisedRpi = null
        Log.i(TAG, "Encounter mesh stopped")
    }

    // ── TX ───────────────────────────────────────────────────────────────────

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "Advertising encounter service (identity in scan response)")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "Advertise failed: $errorCode")
        }
    }

    private val virtualBeaconCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "Advertising virtual beacon (iBeacon frame, reserved major)")
        }
        override fun onStartFailure(errorCode: Int) {
            // Some chips cap concurrent advertisements — the mesh service UUID
            // advertisement has priority; the virtual beacon is best-effort.
            Log.w(TAG, "Virtual beacon advertise failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(adapter: android.bluetooth.BluetoothAdapter) {
        val leAdvertiser = adapter.bluetoothLeAdvertiser ?: run {
            Log.w(TAG, "No LE advertiser on this device")
            return
        }
        advertiser = leAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(SERVICE_PARCEL)
            .build()
        // Identity rides the scan response: 2 (hdr) + 2 (uuid16) + 16 (rpi) = 20 bytes.
        val rpiHex = currentRpi()
        val rpiBytes = rpiHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        advertisedRpi = rpiHex
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(RPI_DATA_PARCEL, rpiBytes)
            .build()
        runCatching { leAdvertiser.startAdvertising(settings, data, scanResponse, advertiseCallback) }
            .onFailure { Log.w(TAG, "startAdvertising threw: ${it.message}") }
        startVirtualBeacon(leAdvertiser)
    }

    /** iBeacon frame identical to the physical Bearound beacon's (same UUID, Apple
     * 0x004C 02-15 layout), with reserved major and minor derived from the rotating
     * identifier. Unlike iOS (foreground-only), Android can emit this in background
     * for as long as the process lives — a host device can (re)launch terminated iOS
     * apps nearby through their CoreLocation region monitoring. */
    private fun virtualBeaconPayload(minor: Int): ByteArray {
        val uuid = io.bearound.sdk.utilities.IBeaconParser.BEAROUND_IBEACON_PREFIX
        return ByteArray(2 + uuid.size - 2 + 5).also { out ->
            // BEAROUND_IBEACON_PREFIX already starts with 02 15 + 16 UUID bytes.
            uuid.copyInto(out, 0)
            val base = uuid.size
            out[base] = ((VIRTUAL_BEACON_MAJOR shr 8) and 0xFF).toByte()
            out[base + 1] = (VIRTUAL_BEACON_MAJOR and 0xFF).toByte()
            out[base + 2] = ((minor shr 8) and 0xFF).toByte()
            out[base + 3] = (minor and 0xFF).toByte()
            out[base + 4] = (-59).toByte() // calibrated RSSI @ 1 m, same as our beacons
        }
    }

    @SuppressLint("MissingPermission")
    private fun startVirtualBeacon(leAdvertiser: android.bluetooth.le.BluetoothLeAdvertiser) {
        val minor = currentRpi().take(4).toInt(16)
        virtualBeaconMinor = minor
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(0x004C, virtualBeaconPayload(minor))
            .build()
        runCatching { leAdvertiser.startAdvertising(settings, data, virtualBeaconCallback) }
            .onFailure { Log.w(TAG, "Virtual beacon startAdvertising threw: ${it.message}") }
    }

    /** Rotation moved the identifier → re-advertise both sets (scan-response RPI and
     * virtual-beacon minor). Called from the rotation path; safe from any thread. */
    @SuppressLint("MissingPermission")
    private fun refreshAdvertisingAfterRotation() {
        val leAdvertiser = advertiser ?: return
        if (!synchronized(lock) { started }) return
        handler.post {
            runCatching { leAdvertiser.stopAdvertising(advertiseCallback) }
            runCatching { leAdvertiser.stopAdvertising(virtualBeaconCallback) }
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
                as? android.bluetooth.BluetoothManager)?.adapter ?: return@post
            startAdvertising(adapter)
        }
    }

    // ── RX (fed by the existing scan) ────────────────────────────────────────

    /** True when [result] advertises the encounter service (cheap pre-check). */
    fun isEncounterFrame(result: ScanResult): Boolean =
        result.scanRecord?.serviceUuids?.contains(SERVICE_PARCEL) == true

    /** Extracts the peer's identity from the frame itself — Android peers put it in
     * service data, iOS foreground peers in the advertised name (base64url). */
    private fun identityFromFrame(result: ScanResult): String? {
        val record = result.scanRecord ?: return null
        record.getServiceData(RPI_DATA_PARCEL)?.let { data ->
            if (data.size == 16) return data.joinToString("") { "%02x".format(it) }
        }
        val name = record.deviceName ?: return null
        if (name.length != 22) return null
        return runCatching {
            val bytes = Base64.decode(name, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            if (bytes.size == 16) bytes.joinToString("") { "%02x".format(it) } else null
        }.getOrNull()
    }

    fun handleScanResult(result: ScanResult) {
        if (!synchronized(lock) { started }) return
        val rssi = result.rssi
        if (rssi >= 0 || rssi == 127) return
        val address = result.device.address ?: return
        val now = System.currentTimeMillis()
        val frameRpi = identityFromFrame(result)

        synchronized(lock) {
            var peer = peers[address]
            if (peer == null) {
                if (peers.size >= MAX_TRACKED_PEERS) {
                    peers.entries.removeAll { now - it.value.lastSeen > PEER_STALE_EVICTION_MS }
                    if (peers.size >= MAX_TRACKED_PEERS) return
                }
                peer = PeerAggregate()
                peers[address] = peer
            }
            if (frameRpi != null && peer.rpi != null && peer.rpi != frameRpi) {
                // Rotated identity = new logical presence: restart the aggregate.
                peer = PeerAggregate()
                peers[address] = peer
            }
            if (frameRpi != null) peer.rpi = frameRpi
            peer.addSample(rssi, now)
        }
    }

    // ── Reporting ────────────────────────────────────────────────────────────

    /** Any identified peer seen after [sinceMs]? Cheap gate for encounters-only syncs. */
    fun hasFreshEncounters(sinceMs: Long): Boolean = synchronized(lock) {
        peers.values.any { it.rpi != null && it.lastSeen > sinceMs }
    }

    /** Non-destructive snapshot of every identified peer, for the sync payload. */
    fun snapshotEncounters(): List<EncounterObservation> = synchronized(lock) {
        peers.values.mapNotNull { peer ->
            val rpi = peer.rpi ?: return@mapNotNull null
            if (peer.sampleCount == 0) return@mapNotNull null
            EncounterObservation(
                rpi = rpi,
                rssi = peer.lastRssi,
                sampleCount = peer.sampleCount,
                rssiMin = peer.rssiMin,
                rssiMax = peer.rssiMax,
                rssiAvg = peer.rssiAvg,
                firstSeen = peer.firstSeen,
                lastSeen = peer.lastSeen,
            )
        }
    }
}
