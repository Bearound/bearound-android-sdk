package io.bearound.sdk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.bearound.sdk.models.SDKConfiguration
import io.bearound.sdk.utilities.SDKConfigStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A process revived by the scan PendingIntent must rejoin the mesh.
 *
 * The broadcast is the only wake-up most background hosts get; with the mesh stopped the
 * device receives frames, counts no encounter and advertises nothing, and only the 15-min
 * watchdog fixes it.
 */
@RunWith(RobolectricTestRunner::class)
class BroadcastMeshRejoinTest {

    private lateinit var context: Context
    private lateinit var sdk: BeAroundSDK

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sdk = BeAroundSDK.getInstance(context)
        // The SDK is a singleton: start from a stopped mesh whatever ran before.
        sdk.encounterMesh?.stop()
        SDKConfigStorage.clearConfiguration(context)
        SDKConfigStorage.saveConfiguration(
            context,
            SDKConfiguration(businessToken = "mesh-token", appId = "io.test")
        )
    }

    @After
    fun tearDown() {
        // The singleton outlives the test class, so leaving it configured would change
        // what the next class observes.
        sdk.encounterMesh?.stop()
        SDKConfigStorage.clearConfiguration(context)
        // By type, not by name: the release variant is minified and field names are gone.
        BeAroundSDK::class.java.declaredFields
            .filter { it.type == SDKConfiguration::class.java }
            .forEach { it.isAccessible = true; it.set(sdk, null) }
    }

    @Test
    fun `a broadcast on a revived process puts the device back on the mesh`() {
        SDKConfigStorage.saveScanningEnabled(context, true)
        assertFalse(sdk.encounterMesh!!.isActive)

        sdk.processBroadcastResults(emptyList())

        assertTrue("broadcast must rejoin the mesh", sdk.encounterMesh!!.isActive)
    }

    @Test
    fun `a broadcast does not rejoin the mesh after scanning was stopped`() {
        // Persisted state after stopScanning(); a stale PendingIntent can still deliver.
        SDKConfigStorage.saveScanningEnabled(context, false)

        sdk.processBroadcastResults(emptyList())

        assertFalse("stopped scanning must stay off the mesh", sdk.encounterMesh!!.isActive)
    }

    @Test
    fun `repeated broadcasts do not restart the mesh and lose the window`() {
        SDKConfigStorage.saveScanningEnabled(context, true)
        sdk.processBroadcastResults(emptyList())

        val mesh = sdk.encounterMesh!!
        mesh.handleVirtualBeacon(minor = 4242, rssi = -70)
        sdk.processBroadcastResults(emptyList())

        val sightings = mesh.drainVirtualBeacons()
        assertEquals(1, sightings.size)
        assertEquals(4242, sightings.first().minor)
    }

    @Test
    fun `a broadcast frame carrying only the 0xBEAD virtual identity is an encounter, not a beacon`() {
        SDKConfigStorage.saveScanningEnabled(context, true)
        sdk.processBroadcastResults(emptyList())
        val mesh = sdk.encounterMesh!!

        // Pure service-data frame: no iBeacon manufacturer data, no mesh service UUID.
        val payload = EncounterMeshManager.virtualServiceDataPayload(4242)
        val ad = byteArrayOf((3 + payload.size).toByte(), 0x16, 0xAD.toByte(), 0xBE.toByte()) + payload
        val record = android.bluetooth.le.ScanRecord::class.java
            .getDeclaredMethod("parseFromBytes", ByteArray::class.java)
            .apply { isAccessible = true }
            .invoke(null, ad) as android.bluetooth.le.ScanRecord
        val device = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            .getRemoteDevice("AA:BB:CC:DD:EE:01")
        val result = android.bluetooth.le.ScanResult(device, record, -66, System.nanoTime())

        sdk.processBroadcastResults(listOf(result))

        val sightings = mesh.drainVirtualBeacons()
        assertEquals(listOf(4242), sightings.map { it.minor })
        assertEquals(-66, sightings.single().rssiAvg)
        assertTrue("a peer frame must not count as a detected beacon",
            !sdk.hasPendingBeacons())
    }
}
