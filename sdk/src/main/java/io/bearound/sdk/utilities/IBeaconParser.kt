package io.bearound.sdk.utilities

import android.bluetooth.le.ScanRecord
import android.os.ParcelUuid
import androidx.core.util.isEmpty
import io.bearound.sdk.models.BeaconMetadata
import java.util.UUID

/**
 * Utility class for parsing iBeacon data from BLE scan results
 */
object IBeaconParser {

    /** Apple's manufacturer ID for iBeacon */
    const val APPLE_MANUFACTURER_ID = 0x004C

    /** BeAround beacon UUID */
    val BEAROUND_UUID: UUID = UUID.fromString("E25B8D3C-947A-452F-A13F-589CB706D2E5")

    /** BEAD Service Data UUID (16-bit 0xBEAD in 128-bit form) */
    val BEAD_SERVICE_UUID: ParcelUuid = ParcelUuid.fromString("0000BEAD-0000-1000-8000-00805F9B34FB")

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
     * Data class representing parsed BEAD Service Data
     */
    data class BeadServiceData(
        val major: Int,
        val minor: Int,
        val metadata: BeaconMetadata,
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
     * Parse BEAD Service Data (11 bytes LE) from a ScanRecord
     * @param scanRecord The BLE scan record
     * @param rssi The RSSI value from the scan result
     * @return BeadServiceData if valid BEAD service data found, null otherwise
     */
    fun parseServiceData(scanRecord: ScanRecord, rssi: Int): BeadServiceData? {
        val data = scanRecord.getServiceData(BEAD_SERVICE_UUID) ?: return null
        if (data.size < 11) return null

        val firmware = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val major = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
        val minor = (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8)
        val motion = (data[6].toInt() and 0xFF) or ((data[7].toInt() and 0xFF) shl 8)
        val temperature = data[8].toInt() // sign-extended int8
        val battery = (data[9].toInt() and 0xFF) or ((data[10].toInt() and 0xFF) shl 8)

        val metadata = BeaconMetadata(
            firmwareVersion = firmware.toString(),
            batteryLevel = battery,
            movements = motion,
            temperature = temperature,
            rssiFromBLE = rssi
        )

        return BeadServiceData(
            major = major,
            minor = minor,
            metadata = metadata,
            rssi = rssi
        )
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
