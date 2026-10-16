package io.bearound.sdk.sampling

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import io.bearound.sdk.EncounterMeshManager
import io.bearound.sdk.utilities.IBeaconParser
import io.bearound.sdk.withCachedMetadata
import io.bearound.sdk.BeaconManager
import io.bearound.sdk.models.Beacon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class BeaconManagerSamplingTest {
    private fun scan(timestampNanos: Long): ScanResult {
        val payload = byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x87.toByte(), 0x00, 0x5C, 0x00, 0x1B, 0x94.toByte(), 0x0C)
        val bytes = byteArrayOf((1 + 2 + payload.size).toByte(), 0x16, 0xAD.toByte(), 0xBE.toByte()) + payload
        val record = ScanRecord::class.java.getDeclaredMethod("parseFromBytes", ByteArray::class.java)
            .apply { isAccessible = true }.invoke(null, bytes) as ScanRecord
        @Suppress("DEPRECATION")
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("AA:BB:CC:DD:EE:01")
        @Suppress("DEPRECATION")
        return ScanResult(device, record, -60, timestampNanos)
    }

    @Test
    fun `physical handoff preserves source age while legacy UI timing remains unchanged`() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
        val manager = BeaconManager(ApplicationProvider.getApplicationContext())
        val source = SystemClock.elapsedRealtime() - 12_000L
        val physical = mutableListOf<Pair<Beacon, Long?>>()
        var legacy: Beacon? = null
        manager.onPhysicalBeacon = { beacon, elapsed -> physical += beacon to elapsed }
        manager.onBeaconsUpdated = { legacy = it.single() }

        manager.processExternalScanResult(scan(source * 1_000_000L))

        assertEquals(source, physical.single().second)
        assertEquals(12_000L, legacy!!.timestamp.time - physical.single().first.timestamp.time)
        assertEquals(135, physical.single().first.minor)
        assertTrue(manager.isInBeaconRegion)
    }

    @Test
    fun `stale physical frames are rejected before the sampling handoff`() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
        val manager = BeaconManager(ApplicationProvider.getApplicationContext())
        var deliveries = 0
        manager.onPhysicalBeacon = { _, _ -> deliveries++ }
        manager.processExternalScanResult(scan((SystemClock.elapsedRealtime() - 30_001L) * 1_000_000L))
        assertEquals(0, deliveries)
        assertTrue(!manager.isInBeaconRegion)
    }

    @Test
    fun `missing source clock is explicit rather than manufactured from callback time`() {
        val manager = BeaconManager(ApplicationProvider.getApplicationContext())
        var source: Long? = 1L
        manager.onPhysicalBeacon = { _, elapsed -> source = elapsed }
        manager.processExternalScanResult(scan(0L))
        assertNull(source)
        assertTrue(manager.isInBeaconRegion)
    }
    @Test
    fun `accepted virtual peer carries original source clock without entering a beacon region`() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = BeaconManager(context)
        val mesh = EncounterMeshManager(context)
        manager.encounterMesh = mesh
        mesh.start()
        val source = SystemClock.elapsedRealtime() - 4_000L
        val frame = IBeaconParser.BEAROUND_IBEACON_PREFIX + byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x00, 0x17, 0xC5.toByte())
        val bytes = byteArrayOf((frame.size + 3).toByte(), 0xFF.toByte(), 0x4C, 0x00) + frame
        val record = ScanRecord::class.java.getDeclaredMethod("parseFromBytes", ByteArray::class.java)
            .apply { isAccessible = true }.invoke(null, bytes) as ScanRecord
        @Suppress("DEPRECATION")
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("AA:BB:CC:DD:EE:02")
        var elapsed: Long? = null
        var deliveries = 0
        mesh.onPhysicalVirtualSighting = { minor, _, at -> assertEquals(23, minor); elapsed = at; deliveries++ }
        @Suppress("DEPRECATION")
        val scan = ScanResult(device, record, -60, source * 1_000_000L)
        manager.processExternalScanResult(scan)
        assertEquals(source, elapsed); assertEquals(1, deliveries); assertTrue(!manager.isInBeaconRegion)
        manager.processExternalScanResult(ScanResult(device, record, -60, (SystemClock.elapsedRealtime() - 30_001L) * 1_000_000L))
        assertEquals(1, deliveries)
        mesh.stop(); manager.processExternalScanResult(scan)
        assertEquals(1, deliveries)
    }

    @Test fun `cached sensor metadata enriches a later raw iBeacon without changing capture timing`() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
        val manager = BeaconManager(ApplicationProvider.getApplicationContext())
        var parsed: Beacon? = null
        manager.onPhysicalBeacon = { beacon, _ -> parsed = beacon }
        manager.processExternalScanResult(scan(SystemClock.elapsedRealtime() * 1_000_000L))
        val sensor = parsed!!
        val cache = mapOf(sensor.identifier to sensor.metadata!!)
        val raw = sensor.copy(metadata = null, txPower = -59, timestamp = java.util.Date(sensor.timestamp.time + 1000))
        val enriched = raw.withCachedMetadata(cache[raw.identifier])
        assertEquals(sensor.metadata, enriched.metadata)
        assertEquals(sensor.metadata!!.txPower ?: raw.txPower, enriched.txPower)
        assertEquals(raw.timestamp, enriched.timestamp)
        assertEquals(raw.rssiRaw, enriched.rssiRaw)
        assertEquals(raw.identifier, enriched.identifier)
    }

}
