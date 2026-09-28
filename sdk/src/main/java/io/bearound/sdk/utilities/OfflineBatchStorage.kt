package io.bearound.sdk.utilities

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import io.bearound.sdk.models.Beacon
import io.bearound.sdk.models.BeaconMetadata
import io.bearound.sdk.models.DeviceLocation
import io.bearound.sdk.models.MaxQueuedPayloads
import io.bearound.sdk.models.RssiStats
import io.bearound.sdk.models.WifiObservation
import java.io.File
import java.util.Date
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manages persistent storage of failed beacon batches
 * Stores batches as JSON files in app's private files directory
 * 
 * Features:
 * - FIFO ordering (oldest batch sent first)
 * - Auto-cleanup of batches older than 7 days
 * - Respects maximum queue size from configuration
 * - Thread-safe operations
 * - Survives app kill and device reboot
 */
class OfflineBatchStorage(private val context: Context) {
    
    companion object {
        private const val TAG = "BeAroundSDK-Storage"
        
        /** Maximum age for stored batches (7 days in milliseconds) */
        private const val MAX_BATCH_AGE_MS = 7L * 24 * 60 * 60 * 1000
        
        /** Directory name for batch storage */
        private const val DIRECTORY_NAME = "com.bearound.sdk.batches"

        /** `syncTrigger` of a visit event (same value as `VisitEvent.SYNC_TRIGGER`). */
        internal const val VISIT_SYNC_TRIGGER = "visit"

        /**
         * Filename marker of a batch persisted with `syncTrigger = "visit"`, written at save
         * time from that trigger: [enforceMaxBatchCount] skips these files without reading
         * them. Format: `timestamp_visit_uuid.json` (timestamp still first, id still last).
         */
        private const val VISIT_FILE_MARKER = "_visit_"
    }
    
    // region Codable Types for JSON Serialization
    
    private data class StoredBatch(
        @SerializedName("id") val id: String,
        @SerializedName("timestamp") val timestamp: Long,
        // Fingerprint of the business token the batch was collected under. Batches from
        // a DIFFERENT tenant are never sent with the current credential (multi-tenant
        // isolation); null = written before this field existed → treated as current.
        @SerializedName("tenantId") val tenantId: String? = null,
        // Nullable: Gson leaves a missing field null even in a non-null Kotlin field. Only a
        // batch with a syncTrigger (a visit) may carry no beacons.
        @SerializedName("beacons") val beacons: List<StoredBeacon>?,
        // Added in 3.12.0. Both OPTIONAL: a batch written before them
        // decodes with null here and is sent with the device context collected at send time.
        @SerializedName("syncTrigger") val syncTrigger: String? = null,
        @SerializedName("context") val context: StoredContext? = null
    )

    /**
     * What the device reported when the batch was captured. Replayed as-is by the retry
     * drain instead of a snapshot taken at retry time (which placed old beacons at the
     * device's CURRENT location).
     *
     * @property location the fix captured with the batch; null means "there was none".
     * @property wifis    the access points captured with the batch; null means "not
     *                    captured" and the send-time list is used (visit events).
     */
    data class CapturedContext(
        val location: DeviceLocation?,
        val wifis: List<WifiObservation>?
    )

    /**
     * Public read model: the batch id travels with the beacons so callers remove EXACTLY
     * the batches a successful upload represents — never "the N oldest at removal time",
     * which drifts when saves/cleanup run between load and remove.
     *
     * [syncTrigger] and [context] are what was persisted with the batch; both null on a
     * batch written before they existed (legacy).
     */
    data class StoredBatchRecord(
        val id: String,
        val beacons: List<Beacon>,
        val syncTrigger: String? = null,
        val context: CapturedContext? = null
    ) {
        /** Written before the batch carried its own context: sent with a fresh snapshot. */
        val isLegacy: Boolean get() = context == null && syncTrigger == null
    }

    // Every field nullable: an entry missing a field reads as absent, never as a crash.
    private data class StoredContext(
        @SerializedName("location") val location: StoredLocation? = null,
        @SerializedName("wifis") val wifis: List<StoredWifi>? = null
    ) {
        companion object {
            fun from(context: CapturedContext) = StoredContext(
                location = context.location?.let { StoredLocation.from(it) },
                wifis = context.wifis?.map { StoredWifi.from(it) }
            )
        }

        fun toCapturedContext() = CapturedContext(
            location = location?.toDeviceLocation(),
            wifis = wifis?.mapNotNull { it.toWifiObservation() }
        )
    }

    private data class StoredLocation(
        @SerializedName("latitude") val latitude: Double? = null,
        @SerializedName("longitude") val longitude: Double? = null,
        @SerializedName("accuracy") val accuracy: Float? = null,
        @SerializedName("altitude") val altitude: Double? = null,
        @SerializedName("timestamp") val timestamp: Long? = null,
        @SerializedName("source") val source: String? = null,
        @SerializedName("isMocked") val isMocked: Boolean? = null
    ) {
        companion object {
            fun from(location: DeviceLocation) = StoredLocation(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracy = location.accuracy,
                altitude = location.altitude,
                timestamp = location.timestamp,
                source = location.source,
                isMocked = location.isMocked
            )
        }

        fun toDeviceLocation(): DeviceLocation? {
            return DeviceLocation(
                latitude = latitude ?: return null,
                longitude = longitude ?: return null,
                accuracy = accuracy,
                altitude = altitude,
                timestamp = timestamp ?: return null,
                source = source ?: return null,
                isMocked = isMocked
            )
        }
    }

    private data class StoredWifi(
        @SerializedName("apId") val apId: String? = null,
        @SerializedName("ssid") val ssid: String? = null,
        @SerializedName("rssi") val rssi: Int? = null,
        @SerializedName("connected") val connected: Boolean? = null,
        @SerializedName("frequencyMhz") val frequencyMhz: Int? = null,
        @SerializedName("timestamp") val timestamp: Long? = null
    ) {
        companion object {
            fun from(wifi: WifiObservation) = StoredWifi(
                apId = wifi.apId,
                ssid = wifi.ssid,
                rssi = wifi.rssi,
                connected = wifi.connected,
                frequencyMhz = wifi.frequencyMhz,
                timestamp = wifi.timestamp
            )
        }

        fun toWifiObservation(): WifiObservation? {
            return WifiObservation(
                apId = apId ?: return null,
                ssid = ssid,
                rssi = rssi,
                connected = connected ?: false,
                frequencyMhz = frequencyMhz,
                timestamp = timestamp ?: return null
            )
        }
    }
    
    private data class StoredBeacon(
        @SerializedName("uuid") val uuid: String,
        @SerializedName("major") val major: Int,
        @SerializedName("minor") val minor: Int,
        @SerializedName("rssi") val rssi: Int,
        @SerializedName("proximity") val proximity: String,  // Store as string for readability
        @SerializedName("accuracy") val accuracy: Double,
        @SerializedName("timestamp") val timestamp: Long,
        @SerializedName("metadata") val metadata: StoredBeaconMetadata?,
        @SerializedName("txPower") val txPower: Int?,
        @SerializedName("rssiRaw") val rssiRaw: Int? = null,
        @SerializedName("rssiSamples") val rssiSamples: StoredRssiStats? = null
    ) {
        companion object {
            fun fromBeacon(beacon: Beacon): StoredBeacon {
                return StoredBeacon(
                    uuid = beacon.uuid.toString(),
                    major = beacon.major,
                    minor = beacon.minor,
                    rssi = beacon.rssi,
                    proximity = beacon.proximity.name,  // Store enum name
                    accuracy = beacon.accuracy,
                    timestamp = beacon.timestamp.time,
                    metadata = beacon.metadata?.let { StoredBeaconMetadata.fromBeaconMetadata(it) },
                    txPower = beacon.txPower,
                    rssiRaw = beacon.rssiRaw,
                    rssiSamples = beacon.rssiSamples?.let { StoredRssiStats.fromRssiStats(it) }
                )
            }
        }

        fun toBeacon(): Beacon {
            // Exception, not only IllegalArgumentException: an old file without the field
            // hands Gson's null to valueOf (NPE), and that must not quarantine the batch.
            val beaconProximity = try {
                Beacon.Proximity.valueOf(proximity)
            } catch (e: Exception) {
                Beacon.Proximity.UNKNOWN
            }

            return Beacon(
                uuid = UUID.fromString(uuid),
                major = major,
                minor = minor,
                rssi = rssi,
                proximity = beaconProximity,
                accuracy = accuracy,
                timestamp = Date(timestamp),
                // Metadata with a missing field loses only the metadata, never the beacon.
                metadata = metadata?.let { runCatching { it.toBeaconMetadata() }.getOrNull() },
                txPower = txPower,
                rssiRaw = rssiRaw,
                rssiSamples = rssiSamples?.toRssiStats()
            )
        }
    }

    private data class StoredRssiStats(
        @SerializedName("count") val count: Int,
        @SerializedName("min") val min: Int,
        @SerializedName("max") val max: Int,
        @SerializedName("avg") val avg: Double,
        @SerializedName("stdDev") val stdDev: Double,
        @SerializedName("firstSeen") val firstSeen: Long,
        @SerializedName("lastSeen") val lastSeen: Long
    ) {
        companion object {
            fun fromRssiStats(stats: RssiStats) = StoredRssiStats(
                count = stats.count,
                min = stats.min,
                max = stats.max,
                avg = stats.avg,
                stdDev = stats.stdDev,
                firstSeen = stats.firstSeen,
                lastSeen = stats.lastSeen
            )
        }

        fun toRssiStats() = RssiStats(
            count = count,
            min = min,
            max = max,
            avg = avg,
            stdDev = stdDev,
            firstSeen = firstSeen,
            lastSeen = lastSeen
        )
    }
    
    private data class StoredBeaconMetadata(
        @SerializedName("firmwareVersion") val firmwareVersion: String,
        @SerializedName("batteryLevel") val batteryLevel: Int,
        @SerializedName("movements") val movements: Int,
        @SerializedName("temperature") val temperature: Int,
        @SerializedName("txPower") val txPower: Int?,
        @SerializedName("rssiFromBLE") val rssiFromBLE: Int?,
        @SerializedName("isConnectable") val isConnectable: Boolean?
    ) {
        companion object {
            fun fromBeaconMetadata(metadata: BeaconMetadata): StoredBeaconMetadata {
                return StoredBeaconMetadata(
                    firmwareVersion = metadata.firmwareVersion,
                    batteryLevel = metadata.batteryLevel,
                    movements = metadata.movements,
                    temperature = metadata.temperature,
                    txPower = metadata.txPower,
                    rssiFromBLE = metadata.rssiFromBLE,
                    isConnectable = metadata.isConnectable
                )
            }
        }
        
        fun toBeaconMetadata(): BeaconMetadata {
            return BeaconMetadata(
                firmwareVersion = firmwareVersion,
                batteryLevel = batteryLevel,
                movements = movements,
                temperature = temperature,
                txPower = txPower,
                rssiFromBLE = rssiFromBLE,
                isConnectable = isConnectable
            )
        }
    }
    
    // endregion
    
    // region Properties
    
    private val gson: Gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        .create()
    
    private val lock = ReentrantLock()

    /** Maximum number of batches to store (default from MaxQueuedPayloads.medium) */
    var maxBatchCount: Int = MaxQueuedPayloads.MEDIUM.value

    /**
     * Fingerprint of the currently-configured business token (set by configure()).
     * Reads only return batches written under this tenant (or legacy batches with no
     * tenant recorded); foreign-tenant batches stay on disk until the 7-day expiry so
     * data collected for client A is never delivered with client B's credential.
     */
    var currentTenantId: String? = null
    
    /** Storage directory */
    private val storageDirectory: File by lazy {
        val dir = context.getDir(DIRECTORY_NAME, Context.MODE_PRIVATE)
        if (!dir.exists()) {
            dir.mkdirs()
            Log.d(TAG, "Created batch storage directory: ${dir.absolutePath}")
        }
        dir
    }
    
    // endregion
    
    // region Initialization
    
    init {
        // Ensure directory exists
        storageDirectory
        
        // Cleanup expired batches on init
        cleanupExpiredBatches()
    }
    
    // endregion
    
    // region Public Methods
    
    /**
     * Returns the number of stored batches
     */
    fun getBatchCount(): Int = lock.withLock {
        try {
            storageDirectory.listFiles()?.filter { it.extension == "json" }?.size ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get batch count: ${e.message}")
            0
        }
    }
    
    /**
     * Saves a batch of beacons to persistent storage.
     * @return true if saved successfully
     */
    fun saveBatch(beacons: List<Beacon>): Boolean = saveBatchReturningId(beacons) != null

    /**
     * Saves a batch and returns its id (persist-before-send: the caller uploads the
     * payload AND, on success, removes exactly this id). Null when the write failed.
     */
    fun saveBatchReturningId(beacons: List<Beacon>): String? = saveBatchReturningId(beacons, null, null)

    /**
     * Saves a batch together with WHY it is sent ([syncTrigger]) and what the device
     * reported when it was captured ([context]); the retry drain replays both instead of
     * recomputing them. A batch without beacons is accepted only with a [syncTrigger]
     * (a visit event carries no beacon: its location IS the payload).
     */
    fun saveBatchReturningId(
        beacons: List<Beacon>,
        syncTrigger: String?,
        context: CapturedContext?
    ): String? {
        if (beacons.isEmpty() && syncTrigger == null) {
            Log.w(TAG, "Cannot save empty batch")
            return null
        }

        return lock.withLock {
            try {
                val batchId = UUID.randomUUID().toString()
                val timestamp = System.currentTimeMillis()

                val storedBeacons = beacons.map { StoredBeacon.fromBeacon(it) }
                val batch = StoredBatch(
                    id = batchId,
                    timestamp = timestamp,
                    tenantId = currentTenantId,
                    beacons = storedBeacons,
                    syncTrigger = syncTrigger,
                    context = context?.let { StoredContext.from(it) }
                )

                // Filename format: timestamp_uuid.json for sorting (timestamp_visit_uuid.json
                // for a visit, so eviction can tell it apart without reading it)
                val marker = if (syncTrigger == VISIT_SYNC_TRIGGER) VISIT_FILE_MARKER else "_"
                val filename = "${timestamp}${marker}${batchId}.json"
                val json = gson.toJson(batch)

                // Atomic write: tmp + fsync + rename (same directory = same filesystem).
                // A process death mid-write leaves a .tmp the readers never pick up,
                // instead of a half-written .json that would be silently dropped as
                // corrupted on the next read.
                val tempFile = File(storageDirectory, "$filename.tmp")
                val finalFile = File(storageDirectory, filename)
                tempFile.outputStream().use { output ->
                    output.write(json.toByteArray(Charsets.UTF_8))
                    output.flush()
                    output.fd.sync()
                }
                if (!tempFile.renameTo(finalFile)) {
                    tempFile.delete()
                    Log.e(TAG, "Failed to rename temp batch file $filename")
                    return@withLock null
                }

                Log.d(TAG, "Saved batch with ${beacons.size} beacons to $filename")

                // Enforce max batch count (remove oldest if exceeded)
                enforceMaxBatchCount()

                batchId
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save batch: ${e.message}", e)
                null
            }
        }
    }

    /**
     * Loads up to [count] oldest batches (FIFO) for the CURRENT tenant, with their ids.
     * Corrupted files are quarantined (renamed to .corrupt — kept for diagnosis, swept
     * by the 7-day expiry) instead of silently deleted.
     */
    fun loadOldestRecords(count: Int = Int.MAX_VALUE): List<StoredBatchRecord> {
        return lock.withLock {
            try {
                val files = storageDirectory.listFiles()
                    ?.filter { it.extension == "json" }
                    ?.sortedBy { it.name }
                    ?: return@withLock emptyList()

                val records = mutableListOf<StoredBatchRecord>()
                for (file in files) {
                    if (records.size >= count) break
                    try {
                        val batch = gson.fromJson(file.readText(), StoredBatch::class.java)
                        // Tenant isolation: skip batches written under a different token.
                        // (null tenant = legacy file → treated as current.)
                        if (batch.tenantId != null && currentTenantId != null &&
                            batch.tenantId != currentTenantId
                        ) {
                            continue
                        }
                        val storedBeacons = batch.beacons
                            ?: if (batch.syncTrigger != null) emptyList() else error("batch has no beacons")
                        records.add(
                            StoredBatchRecord(
                                id = batch.id,
                                beacons = storedBeacons.map { it.toBeacon() },
                                syncTrigger = batch.syncTrigger,
                                context = batch.context?.toCapturedContext()
                            )
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Corrupted batch ${file.name} — quarantining: ${e.message}")
                        file.renameTo(File(storageDirectory, "${file.nameWithoutExtension}.corrupt"))
                    }
                }
                records
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load batch records: ${e.message}", e)
                emptyList()
            }
        }
    }

    /** All pending batches for the current tenant, oldest first. */
    fun loadAllRecords(): List<StoredBatchRecord> = loadOldestRecords()

    /** Removes the batch with exactly this id. */
    fun removeBatch(id: String): Boolean = lock.withLock {
        try {
            val file = storageDirectory.listFiles()
                ?.firstOrNull { it.extension == "json" && it.nameWithoutExtension.endsWith(id) }
                ?: return@withLock false
            val deleted = file.delete()
            if (deleted) Log.d(TAG, "Removed batch file: ${file.name}")
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove batch $id: ${e.message}", e)
            false
        }
    }

    /** Removes exactly these batch ids; returns how many were deleted. */
    fun removeBatches(ids: List<String>): Int = ids.count { removeBatch(it) }

    /**
     * Drops every pending visit batch (the host turned location off: a visit is a location).
     * @return how many were deleted.
     */
    internal fun removeVisitBatches(): Int = lock.withLock {
        try {
            storageDirectory.listFiles()
                ?.filter { it.extension == "json" && it.isVisitBatch() }
                ?.count { it.delete() }
                ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove visit batches: ${e.message}", e)
            0
        }
    }

    /**
     * Takes a backend-REJECTED batch out of the send queue (renamed to .rejected — kept
     * for diagnosis, swept by the 7-day expiry). Without this, one poison batch at the
     * head of the FIFO blocked every batch behind it until expiry.
     */
    fun quarantineBatch(id: String): Boolean = lock.withLock {
        try {
            val file = storageDirectory.listFiles()
                ?.firstOrNull { it.extension == "json" && it.nameWithoutExtension.endsWith(id) }
                ?: return@withLock false
            val moved = file.renameTo(File(storageDirectory, "${file.nameWithoutExtension}.rejected"))
            if (moved) Log.w(TAG, "Quarantined rejected batch: ${file.name}")
            moved
        } catch (e: Exception) {
            Log.e(TAG, "Failed to quarantine batch $id: ${e.message}", e)
            false
        }
    }
    
    /**
     * Clears all stored batches
     */
    fun clearAllBatches() {
        lock.withLock {
            try {
                val files = storageDirectory.listFiles()
                    ?.filter { it.extension == "json" }
                
                if (files == null) return@withLock
                
                var deletedCount = 0
                for (file in files) {
                    if (file.delete()) {
                        deletedCount++
                    }
                }
                
                Log.d(TAG, "Cleared $deletedCount stored batches")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear all batches: ${e.message}", e)
            }
        }
    }
    
    // endregion
    
    // region Private Methods
    
    private fun cleanupExpiredBatches() {
        try {
            // .json = pending; .corrupt = unreadable; .rejected = backend-refused;
            // .tmp = interrupted atomic write. All carry the timestamp in the filename
            // and all expire on the same 7-day clock.
            val files = storageDirectory.listFiles()
                ?.filter {
                    it.extension == "json" || it.extension == "corrupt" ||
                        it.extension == "rejected" || it.extension == "tmp"
                }
                ?: return
            
            val now = System.currentTimeMillis()
            var removedCount = 0
            
            for (file in files) {
                // Extract timestamp from filename (format: timestamp_uuid.json)
                val timestampString = file.nameWithoutExtension.split("_").firstOrNull()
                val timestamp = timestampString?.toLongOrNull()
                
                if (timestamp != null) {
                    val age = now - timestamp
                    if (age > MAX_BATCH_AGE_MS) {
                        if (file.delete()) {
                            removedCount++
                        }
                    }
                }
            }
            
            if (removedCount > 0) {
                Log.d(TAG, "Cleaned up $removedCount expired batches")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup expired batches: ${e.message}", e)
        }
    }
    
    private fun File.isVisitBatch(): Boolean = name.contains(VISIT_FILE_MARKER)

    /**
     * Evicts the oldest beacon batches beyond [maxBatchCount]. A visit batch is exempt and
     * does not count toward the cap: a stop yields two events a day at most, and
     * losing one loses the whole visit. The 7-day expiry and the drain's 24 h capture window
     * still bound them.
     */
    private fun enforceMaxBatchCount() {
        try {
            val files = storageDirectory.listFiles()
                ?.filter { it.extension == "json" && !it.isVisitBatch() }
                ?.sortedBy { it.name }
                ?.toMutableList()
                ?: return
            
            while (files.size > maxBatchCount) {
                // Remove oldest file (first in sorted list)
                val oldestFile = files.removeAt(0)
                if (oldestFile.delete()) {
                    Log.d(TAG, "Removed oldest batch due to max count exceeded: ${oldestFile.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enforce max batch count: ${e.message}", e)
        }
    }
    
    // endregion
}
