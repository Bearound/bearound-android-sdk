package io.bearound.sdk

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

    @Test
    fun `a device that saw nobody still declares its own identity`() {
        val mesh = manager()
        mesh.start()

        assertTrue(mesh.isActive)
        assertTrue(mesh.snapshotEncounters().isEmpty())
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

    @Test
    fun `implausible rssi is dropped`() {
        val mesh = manager()
        mesh.start()

        mesh.handleVirtualBeacon(minor = 24284, rssi = 127) // "no reading" sentinel
        mesh.handleVirtualBeacon(minor = 24284, rssi = 0)

        assertTrue(mesh.drainVirtualBeacons().isEmpty())
    }
}
