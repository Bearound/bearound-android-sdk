package io.bearound.sdk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.bearound.sdk.models.SDKConfiguration
import io.bearound.sdk.utilities.SDKConfigStorage
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock

/**
 * A process revived by the scan PendingIntent that only sees other phones (mesh frames, no
 * physical beacon) must upload those encounters from the broadcast window, and must arm
 * the periodic sync like any other revived process.
 */
@RunWith(RobolectricTestRunner::class)
class BroadcastEncounterFlushTest {

    private lateinit var context: Context
    private lateinit var sdk: BeAroundSDK
    private var server: ServerSocket? = null
    private val posts = AtomicInteger(0)
    private val firstPost = CountDownLatch(1)
    private val oldResolver = SDKConfiguration.endpointResolver

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sdk = BeAroundSDK.getInstance(context)
        // stopScanning() (used in tearDown to cancel the sync timer) needs WorkManager.
        try {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        } catch (_: IllegalStateException) {
            // already initialized by an earlier test in this JVM
        }
        sdk.encounterMesh?.stop()
        // Loopback server standing in for the ingest endpoint: no real network.
        val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        server = socket
        thread(isDaemon = true) {
            try {
                while (!socket.isClosed) socket.accept().use { conn ->
                    conn.soTimeout = 10_000
                    val input = conn.getInputStream().buffered()
                    fun line(): String {
                        val bytes = ByteArrayOutputStream()
                        while (true) {
                            val v = input.read()
                            if (v == -1 || v == 10) break
                            if (v != 13) bytes.write(v)
                        }
                        return bytes.toString("UTF-8")
                    }
                    val request = line()
                    var length = 0
                    while (true) {
                        val header = line()
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) {
                            length = header.substringAfter(':').trim().toInt()
                        }
                    }
                    var read = 0
                    while (read < length) { if (input.read() == -1) break; read++ }
                    if (request.startsWith("POST")) { posts.incrementAndGet(); firstPost.countDown() }
                    val body = "{}".toByteArray()
                    conn.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        write(body)
                        flush()
                    }
                }
            } catch (_: Exception) {
                // socket closed in tearDown
            }
        }
        val endpoint = "http://127.0.0.1:${socket.localPort}"
        SDKConfiguration.endpointResolver = { endpoint }
        // Clear the broadcast debounce: the singleton keeps it across tests.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
        SDKConfigStorage.clearConfiguration(context)
        SDKConfigStorage.saveConfiguration(
            context,
            SDKConfiguration(businessToken = "mesh-token", appId = "io.test")
        )
    }

    @After
    fun tearDown() {
        server?.close()
        SDKConfiguration.endpointResolver = oldResolver
        // Cancels the periodic sync timer and stops the mesh.
        sdk.stopScanning()
        sdk.encounterMesh?.stop()
        SDKConfigStorage.clearConfiguration(context)
        // By type, not by name: the release variant is minified and field names are gone.
        BeAroundSDK::class.java.declaredFields
            .filter { it.type == SDKConfiguration::class.java }
            .forEach { it.isAccessible = true; it.set(sdk, null) }
    }

    @Test
    fun `no pending encounters without a sighting`() {
        SDKConfigStorage.saveScanningEnabled(context, true)
        sdk.processBroadcastResults(emptyList())

        assertTrue(sdk.encounterMesh!!.isActive)
        assertFalse(sdk.hasPendingEncounters())
    }

    @Test
    fun `a fresh sighting on an active mesh is pending and peeking does not consume it`() {
        SDKConfigStorage.saveScanningEnabled(context, true)
        sdk.processBroadcastResults(emptyList())

        sdk.encounterMesh!!.handleVirtualBeacon(minor = 4242, rssi = -70)

        assertTrue(sdk.hasPendingEncounters())
        assertTrue("peek must not advance the throttle", sdk.hasPendingEncounters())
    }

    @Test
    fun `a stopped mesh has no pending encounters`() {
        SDKConfigStorage.saveScanningEnabled(context, true)
        sdk.processBroadcastResults(emptyList())
        sdk.encounterMesh!!.handleVirtualBeacon(minor = 4242, rssi = -70)
        assertTrue(sdk.hasPendingEncounters())

        sdk.encounterMesh!!.stop()

        assertFalse(sdk.hasPendingEncounters())
    }

    @Test
    fun `a mesh-only broadcast in background uploads the encounters`() {
        assumeTrue("Needs the debug endpoint override", BuildConfig.DEBUG)
        SDKConfigStorage.saveScanningEnabled(context, true)
        sdk.processBroadcastResults(emptyList())
        val mesh = sdk.encounterMesh!!
        mesh.handleVirtualBeacon(minor = 4242, rssi = -70)
        assertTrue(sdk.hasPendingEncounters())

        val settled = CountDownLatch(1)
        sdk.processBroadcastResults(emptyList()) { settled.countDown() }

        assertTrue("flush must settle", settled.await(20, TimeUnit.SECONDS))
        assertTrue("an upload must reach the endpoint", firstPost.await(5, TimeUnit.SECONDS))
        assertTrue(
            "the sighting must have been drained by the upload",
            mesh.drainVirtualBeacons().isEmpty()
        )
    }

    @Test
    fun `a broadcast without sightings does not upload`() {
        assumeTrue("Needs the debug endpoint override", BuildConfig.DEBUG)
        SDKConfigStorage.saveScanningEnabled(context, true)

        val settled = CountDownLatch(1)
        sdk.processBroadcastResults(emptyList()) { settled.countDown() }

        assertTrue(settled.await(5, TimeUnit.SECONDS))
        assertEquals(0, posts.get())
    }

    @Test
    fun `a broadcast on a revived process arms the periodic sync timer`() {
        SDKConfigStorage.saveScanningEnabled(context, true)
        assertFalse(sdk.isSyncTimerArmed)

        sdk.processBroadcastResults(emptyList())

        assertTrue(sdk.isSyncTimerArmed)
    }

    @Test
    fun `a broadcast does not arm the sync timer after scanning was stopped`() {
        SDKConfigStorage.saveScanningEnabled(context, false)

        sdk.processBroadcastResults(emptyList())

        assertFalse(sdk.isSyncTimerArmed)
    }
}
