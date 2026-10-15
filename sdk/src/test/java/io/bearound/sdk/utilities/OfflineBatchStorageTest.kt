package io.bearound.sdk.utilities

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.bearound.sdk.models.Beacon
import io.bearound.sdk.models.DeviceLocation
import io.bearound.sdk.models.WifiObservation
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.Date
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        storage = OfflineBatchStorage(context).also { it.currentTenantId = "test-tenant" }
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
    fun `legacy batches without tenant are isolated instead of assigned to the current tenant`() {
        // Written with no tenant configured (pre-upgrade file).
        storage.currentTenantId = null
        storage.saveBatchReturningId(listOf(beacon(1)))!!
        storage.currentTenantId = "tenant-a"
        assertTrue(storage.loadAllRecords().isEmpty())
        assertTrue(storageDir().listFiles()!!.any { it.extension == "unbound" })
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

    // region captured context

    private val capturedLocation = DeviceLocation(
        latitude = -23.561, longitude = -46.656, accuracy = 12f, timestamp = 1_700_000_000_000L, source = "fused"
    )

    @Test
    fun `syncTrigger and captured context round-trip with the batch`() {
        val context = OfflineBatchStorage.CapturedContext(
            location = capturedLocation,
            wifis = listOf(WifiObservation(apId = "ap-1", rssi = -50, timestamp = 1_700_000_000_000L))
        )
        val id = storage.saveBatchReturningId(listOf(beacon(1)), "presence_heartbeat", context)!!

        val record = storage.loadAllRecords().single()
        assertEquals(id, record.id)
        assertEquals("presence_heartbeat", record.syncTrigger)
        assertEquals(context, record.context)
        assertFalse(record.isLegacy)
    }

    @Test
    fun `a batch written before the new fields decodes as legacy and is not quarantined`() {
        // A known-tenant legacy file has no syncTrigger, context or RSSI statistics; the
        // beacon also lacks proximity and its metadata lacks a field.
        val file = File(storageDir(), "${System.currentTimeMillis()}_legacy-1.json")
        file.writeText(
            """
            {"id":"legacy-1","tenantId":"test-tenant","timestamp":1700000000000,"beacons":[
              {"uuid":"11111111-2222-3333-4444-555555555555","major":1,"minor":7,"rssi":-61,
               "accuracy":1.5,"timestamp":1700000000000,"txPower":-59,
               "metadata":{"batteryLevel":90,"movements":1,"temperature":20}}
            ]}
            """.trimIndent()
        )

        val record = storage.loadAllRecords().single()
        assertEquals("legacy-1", record.id)
        assertTrue(record.isLegacy)
        assertNull(record.syncTrigger)
        assertNull(record.context)
        assertEquals(7, record.beacons.single().minor)
        assertEquals(Beacon.Proximity.UNKNOWN, record.beacons.single().proximity)
        assertTrue(file.exists())
        assertFalse(storageDir().listFiles()!!.any { it.extension == "corrupt" })
    }

    @Test
    fun `a visit batch without beacons is stored and a plain empty batch is not`() {
        assertNull(storage.saveBatchReturningId(emptyList()))
        val id = storage.saveBatchReturningId(
            emptyList(), "visit", OfflineBatchStorage.CapturedContext(capturedLocation, wifis = null)
        )
        assertNotNull(id)
        val record = storage.loadAllRecords().single()
        assertEquals("visit", record.syncTrigger)
        assertTrue(record.beacons.isEmpty())
        assertEquals(capturedLocation, record.context?.location)
        assertNull(record.context?.wifis)
    }

    @Test
    fun `a visit batch is exempt from max batch count eviction`() {
        storage.maxBatchCount = 2
        val visit = storage.saveBatchReturningId(
            emptyList(), "visit", OfflineBatchStorage.CapturedContext(capturedLocation, wifis = null)
        )!!
        Thread.sleep(2)
        val oldestBeacon = storage.saveBatchReturningId(listOf(beacon(1)))!!
        Thread.sleep(2)
        val second = storage.saveBatchReturningId(listOf(beacon(2)))!!
        Thread.sleep(2)
        val third = storage.saveBatchReturningId(listOf(beacon(3)))!!

        val ids = storage.loadAllRecords().map { it.id }
        // The visit was the oldest file, yet the oldest BEACON batch is the one evicted.
        assertEquals(listOf(visit, second, third), ids)
        assertFalse(ids.contains(oldestBeacon))
    }

    @Test
    fun `removeVisitBatches drops only visit batches`() {
        storage.saveBatchReturningId(emptyList(), "visit", OfflineBatchStorage.CapturedContext(capturedLocation, null))
        val beaconBatch = storage.saveBatchReturningId(listOf(beacon(1)))!!

        assertEquals(1, storage.removeVisitBatches())
        assertEquals(listOf(beaconBatch), storage.loadAllRecords().map { it.id })
    }

    // endregion
    private fun observation(id: String = UUID.randomUUID().toString(), timestamp: Long = 1_800_000_000_000L) = JSONObject().apply {
        put("sdk", JSONObject().put("appId", "fixture")); put("device", JSONObject().put("deviceId", "device-1").put("timestamp", timestamp))
        put("beacons", JSONArray().put(JSONObject().put("uuid", beacon().uuid.toString()).put("major", 1).put("minor", 1)
            .put("rssi", -60).put("accuracy", -1.0).put("timestamp", timestamp).put("sampleId", "physical-sample")))
        put("sampling", JSONObject().put("batchId", id).put("kind", "summary").put("sentAt", timestamp)
            .put("clock", JSONObject().put("wallTimeMs", timestamp).put("monotonicTimeMs", 1_000L)))
    }.toString()

    @Test
    fun `version two restart preserves complete original JSON and field absence`() {
        val body = observation()
        val id = storage.saveRequestReturningId(body, captureContextId = "context-1")!!
        val restored = OfflineBatchStorage(context).also { it.currentTenantId = "test-tenant" }.loadAllRecords().single()
        assertEquals(id, restored.id); assertEquals(2, restored.version); assertEquals(body, restored.requestJson)
        assertEquals(1, restored.beacons.size); assertEquals(1_800_000_000_000L, restored.beacons.single().timestamp.time)
        assertFalse(JSONObject(restored.requestJson!!).has("location")); assertNull(restored.context)
        assertFalse(restored.isLegacy)
    }

    @Test
    fun `same context windows share a stable manifest across volatile metadata and partial ACK restart`() {
        val first = storage.saveRequestReturningId(observation(), captureContextId = "context-1")!!
        val second = storage.saveRequestReturningId(observation(timestamp = 1_800_000_030_000L), captureContextId = "context-1")!!
        val sealed = storage.sealReplay(storage.loadAllRecords())
        assertEquals(sealed[first]!!.getString("drainId"), sealed[second]!!.getString("drainId"))
        assertEquals(listOf(first, second), (0 until 2).map { sealed[first]!!.getJSONArray("batchIds").getString(it) })
        val original = storage.loadAllRecords().last().requestJson
        storage.removeBatch(first)
        val restored = OfflineBatchStorage(context).also { it.currentTenantId = "test-tenant" }
        val retry = restored.sealReplay(restored.loadAllRecords())[second]!!
        assertEquals(sealed[second].toString(), retry.toString())
        assertEquals(original, restored.loadAllRecords().single().requestJson)
        assertEquals(1, retry.getInt("index")); assertEquals(2, retry.getInt("count"))
        restored.removeBatch(second)
        assertFalse(storageDir().listFiles()!!.any { it.extension == "replay" })
    }

    @Test
    fun `policy or tenant context changes cannot share a replay cohort`() {
        val first = storage.saveRequestReturningId(observation(), captureContextId = "context-1")!!
        val second = storage.saveRequestReturningId(observation(), captureContextId = "context-2")!!
        val manifests = storage.sealReplay(storage.loadAllRecords())
        assertFalse(manifests[first]!!.getString("drainId") == manifests[second]!!.getString("drainId"))
        storage.currentTenantId = "other-tenant"
        assertTrue(storage.loadAllRecords().isEmpty())
        assertNull(storage.saveRequestReturningId("{"))
    }

    @Test
    fun `captured persistence is idempotent bounded and requires tenant provenance`() {
        val body = observation()
        val first = storage.saveRequestReturningId(body)!!
        assertEquals(first, storage.saveRequestReturningId(body)); assertEquals(1, storage.loadAllRecords().size)
        assertNull(storage.saveRequestReturningId(observation().dropLast(1) + ",\"padding\":\"" + "x".repeat(524_288) + "\"}"))
        storage.currentTenantId = null
        assertNull(storage.saveRequestReturningId(observation()))
    }

    @Test
    fun `historical legacy segregation retains evidence without a sendable live row`() {
        val id = storage.saveBatchReturningId(listOf(beacon()))!!
        assertTrue(storage.segregateHistoricalBatch(id))
        assertTrue(storage.loadAllRecords().isEmpty())
        assertTrue(storageDir().listFiles()!!.any { it.extension == "historical" })
    }

    @Test
    fun `FIFO limits apply after persisted sequence ordering rather than filename clock ordering`() {
        val first = storage.saveRequestReturningId(observation())!!
        val second = storage.saveRequestReturningId(observation())!!
        val file = storageDir().listFiles()!!.single { it.name.endsWith("$second.json") }
        assertTrue(file.renameTo(File(storageDir(), "1_$second.json")))
        assertEquals(first, storage.loadOldestRecords(1).single().id)
    }

    @Test fun `pending original inertial metadata survives reload without adding policy or raw sensor fields`() {
        val body = JSONObject().put("sdk", JSONObject().put("appId", "fixture"))
            .put("device", JSONObject().put("deviceId", "fixture-device")).put("beacons", JSONArray())
            .put("sampling", JSONObject().put("version", 1).put("batchId", UUID.randomUUID().toString())
                .put("kind", "summary").put("observedFrom", 1000).put("observedTo", 2000).put("sentAt", 3000)
                .put("motion", JSONObject().put("state", "moving_inferred").put("source", "context_fusion")
                    .put("confidence", "low").put("reason", "context_changed").put("observedAtMs", 2000).put("ageMs", 1000)))
        val original = body.toString(); val id = storage.saveRequestReturningId(original)!!
        val loaded = OfflineBatchStorage(context).also { it.currentTenantId = "test-tenant" }.loadAllRecords().single { it.id == id }
        assertEquals(original, loaded.requestJson)
        assertFalse(loaded.requestJson!!.contains("motionSensors")); assertFalse(loaded.requestJson!!.contains("dynamicRmsG"))
    }

}
