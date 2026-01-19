package io.bearound.sdk.utilities

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import io.bearound.sdk.models.Beacon
import io.bearound.sdk.models.BeaconMetadata
import io.bearound.sdk.models.MaxQueuedPayloads
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
    }
    
    // region Codable Types for JSON Serialization
    
    private data class StoredBatch(
        @SerializedName("id") val id: String,
        @SerializedName("timestamp") val timestamp: Long,
        @SerializedName("beacons") val beacons: List<StoredBeacon>
    )
    
    private data class StoredBeacon(
        @SerializedName("uuid") val uuid: String,
        @SerializedName("major") val major: Int,
        @SerializedName("minor") val minor: Int,
        @SerializedName("rssi") val rssi: Int,
        @SerializedName("proximity") val proximity: String,  // Store as string for readability
        @SerializedName("accuracy") val accuracy: Double,
        @SerializedName("timestamp") val timestamp: Long,
        @SerializedName("metadata") val metadata: StoredBeaconMetadata?,
        @SerializedName("txPower") val txPower: Int?
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
                    txPower = beacon.txPower
                )
            }
        }
        
        fun toBeacon(): Beacon {
            val beaconProximity = try {
                Beacon.Proximity.valueOf(proximity)
            } catch (e: IllegalArgumentException) {
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
                metadata = metadata?.toBeaconMetadata(),
                txPower = txPower
            )
        }
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
     * Saves a batch of beacons to persistent storage
     * @param beacons Array of beacons to store
     * @return true if saved successfully
     */
    fun saveBatch(beacons: List<Beacon>): Boolean {
        if (beacons.isEmpty()) {
            Log.w(TAG, "Cannot save empty batch")
            return false
        }
        
        return lock.withLock {
            try {
                val batchId = UUID.randomUUID().toString()
                val timestamp = System.currentTimeMillis()
                
                val storedBeacons = beacons.map { StoredBeacon.fromBeacon(it) }
                val batch = StoredBatch(
                    id = batchId,
                    timestamp = timestamp,
                    beacons = storedBeacons
                )
                
                // Filename format: timestamp_uuid.json for sorting
                val filename = "${timestamp}_${batchId}.json"
                val file = File(storageDirectory, filename)
                
                val json = gson.toJson(batch)
                file.writeText(json)
                
                Log.d(TAG, "Saved batch with ${beacons.size} beacons to $filename")
                
                // Enforce max batch count (remove oldest if exceeded)
                enforceMaxBatchCount()
                
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save batch: ${e.message}", e)
                false
            }
        }
    }
    
    /**
     * Loads the oldest batch from storage (FIFO)
     * @return List of beacons or null if no batches available
     */
    fun loadOldestBatch(): List<Beacon>? {
        return lock.withLock {
            try {
                val files = storageDirectory.listFiles()
                    ?.filter { it.extension == "json" }
                    ?.sortedBy { it.name }
                
                val oldestFile = files?.firstOrNull() ?: return@withLock null
                
                val json = oldestFile.readText()
                val batch = gson.fromJson(json, StoredBatch::class.java)
                
                val beacons = batch.beacons.map { it.toBeacon() }
                Log.d(TAG, "Loaded oldest batch with ${beacons.size} beacons from ${oldestFile.name}")
                
                beacons
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load oldest batch: ${e.message}", e)
                // Try to remove corrupted file
                try {
                    val files = storageDirectory.listFiles()
                        ?.filter { it.extension == "json" }
                        ?.sortedBy { it.name }
                    files?.firstOrNull()?.delete()
                } catch (cleanupError: Exception) {
                    Log.e(TAG, "Failed to cleanup corrupted batch: ${cleanupError.message}")
                }
                null
            }
        }
    }
    
    /**
     * Removes the oldest batch from storage (call after successful sync)
     * @return true if removed successfully
     */
    fun removeOldestBatch(): Boolean {
        return lock.withLock {
            try {
                val files = storageDirectory.listFiles()
                    ?.filter { it.extension == "json" }
                    ?.sortedBy { it.name }
                
                val oldestFile = files?.firstOrNull() ?: return@withLock false
                
                val deleted = oldestFile.delete()
                if (deleted) {
                    Log.d(TAG, "Removed batch file: ${oldestFile.name}")
                }
                deleted
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove oldest batch: ${e.message}", e)
                false
            }
        }
    }
    
    /**
     * Loads all batches from storage (for migration or debugging)
     * @return List of beacon lists, ordered oldest first
     */
    fun loadAllBatches(): List<List<Beacon>> {
        return lock.withLock {
            try {
                val files = storageDirectory.listFiles()
                    ?.filter { it.extension == "json" }
                    ?.sortedBy { it.name }
                    ?: return@withLock emptyList()
                
                val allBatches = mutableListOf<List<Beacon>>()
                
                for (file in files) {
                    try {
                        val json = file.readText()
                        val batch = gson.fromJson(json, StoredBatch::class.java)
                        val beacons = batch.beacons.map { it.toBeacon() }
                        allBatches.add(beacons)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load batch ${file.name}: ${e.message}")
                        // Remove corrupted file
                        file.delete()
                    }
                }
                
                Log.d(TAG, "Loaded ${allBatches.size} batches from storage")
                allBatches
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load all batches: ${e.message}", e)
                emptyList()
            }
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
            val files = storageDirectory.listFiles()
                ?.filter { it.extension == "json" }
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
    
    private fun enforceMaxBatchCount() {
        try {
            val files = storageDirectory.listFiles()
                ?.filter { it.extension == "json" }
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
