package io.bearound.sdk

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The mesh's virtual-beacon port — the one that produced pairs in the field, and the one
 * a parser filter silently switched off in 3.8.0.
 *
 * Two properties are load-bearing and neither is obvious from reading the class:
 * the identity a device declares must NOT depend on that device having seen anybody
 * (otherwise no pair can ever be closed), and the sighting aggregate must be drained by
 * the sync (otherwise one hour beside another phone uploads the same window forever).
 */
@RunWith(RobolectricTestRunner::class)
class EncounterMeshManagerTest {

    private fun manager() = EncounterMeshManager(ApplicationProvider.getApplicationContext())

    /**
     * A real [ScanResult] carrying the peer's identifier the way an Android peer does:
     * 16-bit service data `0xBEA1` with 16 raw bytes, in the scan response.
     *
     * Built from raw AD bytes through the platform parser so the test exercises the same
     * extraction path production does — no stubbing of [ScanRecord].
     */
    private fun scanResult(address: String, rpiHex: String, rssi: Int): ScanResult {
        val rpi = rpiHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val ad = byteArrayOf(
            (1 + 2 + rpi.size).toByte(), // length
            0x16,                        // AD type: service data, 16-bit UUID
            0xA1.toByte(), 0xBE.toByte(), // 0xBEA1, little-endian
        ) + rpi
        val record = ScanRecord::class.java
            .getDeclaredMethod("parseFromBytes", ByteArray::class.java)
            .apply { isAccessible = true }
            .invoke(null, ad) as ScanRecord
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
        return ScanResult(device, record, rssi, System.nanoTime())
    }

    private val rpiA = "a0".repeat(16)
    private val rpiB = "b1".repeat(16)

    @Test
    fun `a device that saw nobody still declares its own identity`() {
        val mesh = manager()
        mesh.start()

        assertTrue(mesh.isActive)
        assertTrue(mesh.drainEncounters().isEmpty())
        assertEquals(32, mesh.currentEncounterIds().first().length)
    }

    @Test
    fun `virtual-beacon sightings aggregate per peer minor`() {
        val mesh = manager()
        mesh.start()

        mesh.handleVirtualBeacon(minor = 24284, rssi = -55)
        mesh.handleVirtualBeacon(minor = 24284, rssi = -65)
        mesh.handleVirtualBeacon(minor = 7, rssi = -40)

        val sightings = mesh.drainVirtualBeacons().associateBy { it.minor }
        assertEquals(2, sightings.size)
        val peer = sightings.getValue(24284)
        assertEquals(2, peer.sampleCount)
        assertEquals(-65, peer.rssiMin)
        assertEquals(-55, peer.rssiMax)
        assertEquals(-60, peer.rssiAvg)
        assertEquals(-65, peer.rssi) // last sample, not the best one
    }

    @Test
    fun `draining resets the window so a still device does not re-upload it`() {
        val mesh = manager()
        mesh.start()
        mesh.handleVirtualBeacon(minor = 24284, rssi = -55)

        assertEquals(1, mesh.drainVirtualBeacons().size)
        assertTrue(mesh.drainVirtualBeacons().isEmpty())
    }

    @Test
    fun `a virtual-beacon sighting alone opens the encounters-only sync gate`() {
        val mesh = manager()
        mesh.start()
        assertFalse(mesh.hasFreshEncounters(0L))

        mesh.handleVirtualBeacon(minor = 24284, rssi = -55)

        assertTrue(mesh.hasFreshEncounters(0L))
    }

    @Test
    fun `nothing is tracked before start or after stop`() {
        val mesh = manager()
        mesh.handleVirtualBeacon(minor = 24284, rssi = -55)
        assertTrue(mesh.drainVirtualBeacons().isEmpty())

        mesh.start()
        mesh.handleVirtualBeacon(minor = 24284, rssi = -55)
        mesh.stop()

        assertFalse(mesh.isActive)
        assertTrue(mesh.drainVirtualBeacons().isEmpty())
    }

    // ── The replay defect ────────────────────────────────────────────────────
    // A 4m42s encounter was uploaded ~30k times across two days because the peer
    // aggregate was snapshotted, never drained, and the age-based eviction only ran
    // inside the `peers.size >= MAX_TRACKED_PEERS` branch — dead code on a phone that
    // sees one peer. Every replay resolved to nobody: the identifier rotates every 15
    // minutes, so only the window it was seen in can ever be matched.

    @Test
    fun `a peer seen once is reported once, not on every later sync`() {
        val mesh = manager()
        mesh.start()
        mesh.handleScanResult(scanResult("AA:BB:CC:DD:EE:01", rpiA, -55))

        assertEquals(1, mesh.drainEncounters().size)

        // The encounter is over. Nothing new came in, so nothing more may go up — ever.
        assertTrue(mesh.drainEncounters().isEmpty())
        assertTrue(mesh.drainEncounters().isEmpty())
    }

    @Test
    fun `a peer still present keeps being reported, with a fresh window each time`() {
        val mesh = manager()
        mesh.start()

        mesh.handleScanResult(scanResult("AA:BB:CC:DD:EE:01", rpiA, -50))
        mesh.handleScanResult(scanResult("AA:BB:CC:DD:EE:01", rpiA, -60))
        val first = mesh.drainEncounters().single()
        assertEquals(2, first.sampleCount)

        // Still there in the next window: reported again, but only with what that
        // window actually saw.
        mesh.handleScanResult(scanResult("AA:BB:CC:DD:EE:01", rpiA, -70))
        val second = mesh.drainEncounters().single()
        assertEquals(rpiA, second.rpi)
        assertEquals(1, second.sampleCount)
        assertEquals(-70, second.rssiMin)
        assertEquals(-70, second.rssiMax)
    }

    @Test
    fun `the reported window does not grow without bound`() {
        val mesh = manager()
        mesh.start()

        var totalReported = 0
        repeat(20) {
            mesh.handleScanResult(scanResult("AA:BB:CC:DD:EE:01", rpiA, -55))
            val observation = mesh.drainEncounters().single()
            // One sample in, one sample out — never 1, 2, 3, … n.
            assertEquals(1, observation.sampleCount)
            assertEquals(observation.firstSeen, observation.lastSeen)
            totalReported += observation.sampleCount
        }
        assertEquals(20, totalReported)
    }

    @Test
    fun `a peer that walked away is evicted once stale`() {
        val mesh = manager()
        mesh.start()
        mesh.handleScanResult(scanResult("AA:BB:CC:DD:EE:01", rpiA, -55))
        assertEquals(1, mesh.drainEncounters().size)

        // Eviction must not depend on the peer table being full (MAX_TRACKED_PEERS = 64):
        // one peer never fills it, and that is exactly the field case.
        val wayLater = System.currentTimeMillis() + 60 * 60 * 1000
        assertEquals(1, mesh.trackedPeerCount) // drained, but still held
        assertTrue(mesh.drainEncounters(now = wayLater).isEmpty())
        assertEquals(0, mesh.trackedPeerCount) // and now reclaimed

        // Same address reappearing after eviction is a brand-new aggregate, not a resumed one.
        mesh.handleScanResult(scanResult("AA:BB:CC:DD:EE:01", rpiB, -40))
        val fresh = mesh.drainEncounters().single()
        assertEquals(rpiB, fresh.rpi)
        assertEquals(1, fresh.sampleCount)
    }

    @Test
    fun `a drained peer does not keep opening the encounters-only sync`() {
        val mesh = manager()
        mesh.start()
        mesh.handleScanResult(scanResult("AA:BB:CC:DD:EE:01", rpiA, -55))
        assertTrue(mesh.hasFreshEncounters(0L))

        mesh.drainEncounters()

        // The entry survives (identity + lastSeen are still useful), but it has nothing
        // left to upload, so it must not trigger a sync of its own.
        assertFalse(mesh.hasFreshEncounters(0L))
    }

    @Test
    fun `implausible rssi is dropped`() {
        val mesh = manager()
        mesh.start()

        mesh.handleVirtualBeacon(minor = 24284, rssi = 127) // "no reading" sentinel
        mesh.handleVirtualBeacon(minor = 24284, rssi = 0)

        assertTrue(mesh.drainVirtualBeacons().isEmpty())
    }
}
