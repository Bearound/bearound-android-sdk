package io.bearound.sdk.utilities

import android.bluetooth.le.ScanRecord
import androidx.core.util.isEmpty
import java.util.UUID

/**
 * Utility class for parsing iBeacon data from BLE scan results
 */
object IBeaconParser {
    
    /** Apple's manufacturer ID for iBeacon */
    const val APPLE_MANUFACTURER_ID = 0x004C
    
    /** BeAround beacon UUID */
    val BEAROUND_UUID: UUID = UUID.fromString("E25B8D3C-947A-452F-A13F-589CB706D2E5")
    
    /** iBeacon prefix bytes */
    val IBEACON_PREFIX = byteArrayOf(0x02, 0x15)
    
    /** iBeacon prefix mask */
    val IBEACON_MASK = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
    
    /**
     * Data class representing parsed iBeacon data
     */
    data class IBeaconData(
        val uuid: UUID,
        val major: Int,
        val minor: Int,
        val txPower: Int,
        val rssi: Int
    )
    
    /**
     * Parse iBeacon data from a ScanRecord
     * @param scanRecord The BLE scan record
     * @param rssi The RSSI value from the scan result
     * @return IBeaconData if valid iBeacon found, null otherwise
     */
    fun parse(scanRecord: ScanRecord, rssi: Int): IBeaconData? {
        val manufacturerData = scanRecord.manufacturerSpecificData
        if (manufacturerData.isEmpty()) return null
        
        val appleData = manufacturerData.get(APPLE_MANUFACTURER_ID) ?: return null
        if (appleData.size < 23) return null
        
        // Verify iBeacon identifier
        if (appleData[0] != 0x02.toByte() || appleData[1] != 0x15.toByte()) return null
        
        // Parse UUID
        val uuidBytes = appleData.copyOfRange(2, 18)
        val uuid = bytesToUUID(uuidBytes)
        
        // Parse major and minor
        val major = ((appleData[18].toInt() and 0xFF) shl 8) or (appleData[19].toInt() and 0xFF)
        val minor = ((appleData[20].toInt() and 0xFF) shl 8) or (appleData[21].toInt() and 0xFF)
        val txPower = appleData[22].toInt()
        
        return IBeaconData(
            uuid = uuid,
            major = major,
            minor = minor,
            txPower = txPower,
            rssi = rssi
        )
    }
    
    /**
     * Check if the iBeacon data matches BeAround UUID
     */
    fun isBeAroundBeacon(data: IBeaconData): Boolean {
        return data.uuid == BEAROUND_UUID
    }
    
    /**
     * Convert bytes to UUID
     */
    fun bytesToUUID(bytes: ByteArray): UUID {
        val msb = bytes.take(8).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
        val lsb = bytes.drop(8).take(8).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
        return UUID(msb, lsb)
    }
}
