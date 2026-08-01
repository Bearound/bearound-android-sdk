package io.bearound.sdk.utilities

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.bearound.sdk.models.Beacon
import java.io.File
import java.util.Date
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OfflineBatchStorageTest {

    private lateinit var context: Context
    private lateinit var storage: OfflineBatchStorage

    private fun beacon(minor: Int = 1) = Beacon(
        uuid = UUID.fromString("11111111-2222-3333-4444-555555555555"),
        major = 1,
        minor = minor,
        rssi = -60,
        proximity = Beacon.Proximity.NEAR,
        accuracy = 1.0,
        timestamp = Date()
    )

    private fun storageDir(): File {
        // Mirrors the production path: context.getDir(name, MODE_PRIVATE) prefixes "app_".
        return File(context.filesDir.parentFile, "app_com.bearound.sdk.batches")
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storageDir().deleteRecursively()
        storage = OfflineBatchStorage(context)
    }

    @Test
    fun `saveBatchReturningId returns an id and the batch is readable back`() {
        val id = storage.saveBatchReturningId(listOf(beacon()))
        assertNotNull(id)
        val records = storage.loadAllRecords()
        assertEquals(1, records.size)
        assertEquals(id, records[0].id)
        assertEquals(1, records[0].beacons.size)
    }

    @Test
    fun `removeBatch removes exactly the given id even when a newer batch exists`() {
        val first = storage.saveBatchReturningId(listOf(beacon(1)))!!
        val second = storage.saveBatchReturningId(listOf(beacon(2)))!!

        // Removal by id is immune to ordering — the scenario the positional
        // removeOldestBatch() got wrong when saves ran between load and remove.
        assertTrue(storage.removeBatch(second))
        val remaining = storage.loadAllRecords()
        assertEquals(1, remaining.size)
        assertEquals(first, remaining[0].id)
    }

    @Test
    fun `removeBatches removes only the requested ids`() {
        val a = storage.saveBatchReturningId(listOf(beacon(1)))!!
        val b = storage.saveBatchReturningId(listOf(beacon(2)))!!
        val c = storage.saveBatchReturningId(listOf(beacon(3)))!!

        assertEquals(2, storage.removeBatches(listOf(a, c)))
        val remaining = storage.loadAllRecords()
        assertEquals(1, remaining.size)
        assertEquals(b, remaining[0].id)
    }

    @Test
    fun `batches from another tenant are not returned nor counted`() {
        storage.currentTenantId = "tenant-a"
        val idA = storage.saveBatchReturningId(listOf(beacon(1)))!!

        storage.currentTenantId = "tenant-b"
        assertTrue(storage.loadAllRecords().isEmpty())

        // Switching back exposes tenant A's queue again — nothing was destroyed.
        storage.currentTenantId = "tenant-a"
        assertEquals(listOf(idA), storage.loadAllRecords().map { it.id })
    }

    @Test
    fun `legacy batches without tenant are treated as current`() {
        // Written with no tenant configured (pre-upgrade file).
        val legacyId = storage.saveBatchReturningId(listOf(beacon(1)))!!

        storage.currentTenantId = "tenant-a"
        assertEquals(listOf(legacyId), storage.loadAllRecords().map { it.id })
    }

    @Test
    fun `corrupted file is quarantined not silently deleted`() {
        storage.saveBatchReturningId(listOf(beacon(1)))
        val dir = storageDir()
        val corrupted = File(dir, "${System.currentTimeMillis()}_zzz.json")
        corrupted.writeText("{ not json !!")

        val records = storage.loadAllRecords()
        assertEquals(1, records.size) // the good one still loads

        assertFalse(corrupted.exists())
        assertTrue(
            "expected a .corrupt quarantine file",
            dir.listFiles()!!.any { it.extension == "corrupt" }
        )
    }

    @Test
    fun `quarantineBatch removes the batch from the send queue but keeps the file`() {
        val poison = storage.saveBatchReturningId(listOf(beacon(1)))!!
        val healthy = storage.saveBatchReturningId(listOf(beacon(2)))!!

        assertTrue(storage.quarantineBatch(poison))

        // The queue moves on without the rejected batch (head-of-line unblocked)...
        assertEquals(listOf(healthy), storage.loadAllRecords().map { it.id })
        // ...and the evidence file survives as .rejected for diagnosis.
        assertTrue(storageDir().listFiles()!!.any { it.extension == "rejected" })
    }

    @Test
    fun `interrupted atomic write (tmp file) is invisible to readers`() {
        val dir = storageDir()
        File(dir, "${System.currentTimeMillis()}_abc.json.tmp").writeText("{ partial")

        assertTrue(storage.loadAllRecords().isEmpty())
        assertEquals(0, storage.getBatchCount())
    }
}
