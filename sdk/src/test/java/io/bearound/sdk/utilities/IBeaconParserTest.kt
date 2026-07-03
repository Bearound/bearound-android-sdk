package io.bearound.sdk.utilities

import android.bluetooth.le.ScanRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests the BEAD service-data parsing and the iBeacon ScanFilter constants added for
 * slow / scan-response beacons (e.g. B:0.135). The filter bytes are the only guard-rail
 * against a silent endianness regression: a wrong byte order would simply never match any
 * beacon in the field ("0 beacons") without any error surfacing.
 */
@RunWith(RobolectricTestRunner::class)
class IBeaconParserTest {

    // ── iBeacon filter constants (3.4.5 slow-beacon fix) ──

    @Test
    fun `iBeacon prefix is type+length followed by the Bearound UUID in network byte order`() {
        val expected = byteArrayOf(
            0x02, 0x15, // iBeacon type + payload length — fixed by Apple's frame format
            0xE2.toByte(), 0x5B, 0x8D.toByte(), 0x3C, 0x94.toByte(), 0x7A,
            0x45, 0x2F, 0xA1.toByte(), 0x3F, 0x58, 0x9C.toByte(), 0xB7.toByte(), 0x06,
            0xD2.toByte(), 0xE5.toByte() // E25B8D3C-947A-452F-A13F-589CB706D2E5, MSB first
        )
        assertArrayEquals(expected, IBeaconParser.BEAROUND_IBEACON_PREFIX)
    }

    @Test
    fun `iBeacon mask fully matches every prefix byte`() {
        assertEquals(IBeaconParser.BEAROUND_IBEACON_PREFIX.size, IBeaconParser.BEAROUND_IBEACON_MASK.size)
        assertArrayEquals(ByteArray(18) { 0xFF.toByte() }, IBeaconParser.BEAROUND_IBEACON_MASK)
    }

    @Test
    fun `apple manufacturer id is 0x004C`() {
        assertEquals(0x004C, IBeaconParser.APPLE_MANUFACTURER_ID)
    }

    // ── BEAD service-data parsing ──

    @Test
    fun `parses the real B0135 payload captured on device`() {
        // Payload observed via nRF/Mac on the physical beacon: 0100000087005c001b940c
        val record = scanRecordWithBeadServiceData(
            byteArrayOf(
                0x01, 0x00, // firmware 1 (LE)
                0x00, 0x00, // major 0
                0x87.toByte(), 0x00, // minor 135
                0x5C, 0x00, // movements 92
                0x1B, // temperature 27 °C (int8)
                0x94.toByte(), 0x0C // battery 3220 mV
            )
        )

        val parsed = IBeaconParser.parseServiceData(record, rssi = -35)!!

        assertEquals(0, parsed.major)
        assertEquals(135, parsed.minor)
        assertEquals(-35, parsed.rssi)
        val metadata = parsed.metadata!!
        assertEquals("1", metadata.firmwareVersion)
        assertEquals(92, metadata.movements)
        assertEquals(27, metadata.temperature)
        assertEquals(3220, metadata.batteryLevel)
        assertEquals(-35, metadata.rssiFromBLE)
    }

    @Test
    fun `temperature is sign-extended from int8`() {
        val record = scanRecordWithBeadServiceData(
            byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0xF6.toByte(), 0x00, 0x00)
        )

        assertEquals(-10, IBeaconParser.parseServiceData(record, rssi = -50)!!.metadata!!.temperature)
    }

    @Test
    fun `returns null for payload shorter than 11 bytes`() {
        val record = scanRecordWithBeadServiceData(byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x87.toByte()))

        assertNull(IBeaconParser.parseServiceData(record, rssi = -50))
    }

    @Test
    fun `returns null when the record carries only an iBeacon frame (no BEAD service data)`() {
        assertNull(IBeaconParser.parseServiceData(iBeaconOnlyRecord(), rssi = -40))
    }

    // ── iBeacon frame fallback parsing ──

    @Test
    fun `parses identity and txPower from an iBeacon-only frame with null metadata`() {
        // What Xiaomi batched results deliver for B:0.135 when the scan response is missing.
        val parsed = IBeaconParser.parseIBeaconFrame(iBeaconOnlyRecord(), rssi = -62)!!

        assertEquals(0, parsed.major)
        assertEquals(135, parsed.minor)
        assertEquals(-61, parsed.txPower) // 0xC3 sign-extended
        assertEquals(-62, parsed.rssi)
        assertNull(parsed.metadata) // "first sighting without data" — fills in via 0xBEAD later
    }

    @Test
    fun `rejects iBeacon frames from foreign UUIDs`() {
        val foreign = IBeaconParser.BEAROUND_IBEACON_PREFIX.clone()
        foreign[2] = 0x00 // corrupt the first UUID byte
        val manufacturerData = foreign + byteArrayOf(0x00, 0x00, 0x00, 0x87.toByte(), 0xC3.toByte())
        val advBytes = byteArrayOf(
            (3 + manufacturerData.size).toByte(), 0xFF.toByte(), 0x4C, 0x00
        ) + manufacturerData

        assertNull(IBeaconParser.parseIBeaconFrame(parseScanRecord(advBytes), rssi = -40))
    }

    // ── helpers ──

    /** iBeacon-only primary PDU — B:0.135's advertisement without the scan response. */
    private fun iBeaconOnlyRecord(): ScanRecord {
        val manufacturerData = IBeaconParser.BEAROUND_IBEACON_PREFIX +
            byteArrayOf(0x00, 0x00, 0x00, 0x87.toByte(), 0xC3.toByte()) // major 0, minor 135, txPower -61
        val advBytes = byteArrayOf(
            (3 + manufacturerData.size).toByte(), 0xFF.toByte(), 0x4C, 0x00
        ) + manufacturerData
        return parseScanRecord(advBytes)
    }

    /** Builds a ScanRecord whose 16-bit service data (0xBEAD) is [payload]. */
    private fun scanRecordWithBeadServiceData(payload: ByteArray): ScanRecord {
        val advBytes = byteArrayOf(
            (3 + payload.size).toByte(), // AD length: type + UUID16 + payload
            0x16, // AD type: Service Data — 16-bit UUID
            0xAD.toByte(), 0xBE.toByte() // 0xBEAD little-endian
        ) + payload
        return parseScanRecord(advBytes)
    }

    /** ScanRecord has no public constructor; parse raw advertising bytes like the stack does. */
    private fun parseScanRecord(advBytes: ByteArray): ScanRecord {
        val method = ScanRecord::class.java.getDeclaredMethod("parseFromBytes", ByteArray::class.java)
        method.isAccessible = true
        return method.invoke(null, advBytes) as ScanRecord
    }
}
