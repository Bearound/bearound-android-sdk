package io.bearound.sdk.utilities

import android.bluetooth.le.ScanRecord
import android.os.ParcelUuid
import io.bearound.sdk.models.BeaconMetadata
import java.util.UUID

/**
 * Utility class for parsing Bearound BEAD beacon data from BLE scan results
 */
object IBeaconParser {

    /** Bearound's Bluetooth SIG manufacturer ID */
    const val BEAROUND_MANUFACTURER_ID = 0xBEAD

    /** BeAround beacon UUID */
    val BEAROUND_UUID: UUID = UUID.fromString("E25B8D3C-947A-452F-A13F-589CB706D2E5")

    /** BEAD Service Data UUID (16-bit 0xBEAD in 128-bit form) */
    val BEAD_SERVICE_UUID: ParcelUuid = ParcelUuid.fromString("0000BEAD-0000-1000-8000-00805F9B34FB")

    /** Apple's Bluetooth SIG manufacturer ID — iBeacon frames are advertised under it (0x004C). */
    const val APPLE_MANUFACTURER_ID = 0x004C

    /**
     * iBeacon manufacturer-data prefix for a Bearound beacon: `[0x02, 0x15]` (iBeacon type +
     * length) followed by the 16-byte [BEAROUND_UUID]. Used as a ScanFilter to match beacons
     * that carry the 0xBEAD payload in the SCAN RESPONSE rather than the primary PDU (e.g.
     * B:0.135) — the offloaded 0xBEAD filter only inspects the primary advertisement, so those
     * beacons are matched via their iBeacon frame (which is in the primary).
     */
    val BEAROUND_IBEACON_PREFIX: ByteArray = byteArrayOf(0x02, 0x15) + uuidToBytes(BEAROUND_UUID)

    /** Full-match (0xFF) mask covering every byte of [BEAROUND_IBEACON_PREFIX]. */
    val BEAROUND_IBEACON_MASK: ByteArray = ByteArray(BEAROUND_IBEACON_PREFIX.size) { 0xFF.toByte() }

    private fun uuidToBytes(uuid: UUID): ByteArray =
        java.nio.ByteBuffer.allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()

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
}
