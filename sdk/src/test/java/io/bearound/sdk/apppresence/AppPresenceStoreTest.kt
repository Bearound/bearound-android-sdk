package io.bearound.sdk.apppresence

import androidx.test.core.app.ApplicationProvider
import io.bearound.sdk.models.AppPresenceDetectionMethod
import io.bearound.sdk.models.AppPresenceReason
import io.bearound.sdk.models.AppPresenceResult
import io.bearound.sdk.models.AppPresenceSnapshot
import io.bearound.sdk.models.AppPresenceState
import io.bearound.sdk.utilities.AppPresenceReadResult
import io.bearound.sdk.utilities.AppPresenceRecord
import io.bearound.sdk.utilities.AppPresenceStore
import io.bearound.sdk.utilities.DirectoryAppPresenceFileSystem
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class AppPresenceStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var directory: File
    private lateinit var store: AppPresenceStore
    private val namespace = AppPresenceStore.namespace("io.bearound.host", "token-secret-A")

    @Before
    fun setUp() {
        directory = File(temp.root, "presence")
        store = AppPresenceStore(DirectoryAppPresenceFileSystem(directory))
    }

    private fun snapshot(id: String = "snap-1", fingerprint: String = "fp-1") = AppPresenceSnapshot(
        snapshotId = id,
        configurationFingerprint = fingerprint,
        checkedAt = "2026-10-09T12:00:00.000Z",
        cached = false,
        results = listOf(
            AppPresenceResult("present", AppPresenceState.PRESENT, true, null, "2026-10-09T12:00:00.000Z", AppPresenceDetectionMethod.ANDROID_PACKAGE),
            AppPresenceResult("unknown", AppPresenceState.UNKNOWN, null, AppPresenceReason.NOT_DECLARED, "2026-10-09T12:00:00.000Z", AppPresenceDetectionMethod.ANDROID_PACKAGE)
        )
    )

    private fun record(snapshot: AppPresenceSnapshot? = snapshot()) = AppPresenceRecord(
        namespace = namespace,
        hasReservation = true,
        reservedAtEpochMillis = 1_760_000_000_000L,
        reservedElapsedRealtime = 42_000L,
        bootCount = 7,
        fingerprint = snapshot?.configurationFingerprint,
        lastSnapshot = snapshot
    )

    @Test
    fun `missing record reads as Missing`() {
        assertSame(AppPresenceReadResult.Missing, store.read(namespace))
    }

    @Test
    fun `record round-trips with reservation, boot evidence and snapshot nulls`() {
        store.write(record())

        val read = store.read(namespace) as AppPresenceReadResult.Found
        assertEquals(record(), read.record)
        val unknown = read.record.lastSnapshot!!.results[1]
        assertNull(unknown.present)
    }

    @Test
    fun `persisted json carries reservedAt UTC and explicit nulls, never the token`() {
        store.write(record().copy(bootCount = null))

        val file = File(directory, AppPresenceStore.fileName(namespace))
        val raw = file.readText()
        val json = JSONObject(raw)
        assertEquals(1, json.getInt("schemaVersion"))
        assertTrue(json.getString("reservedAt").endsWith("Z"))
        assertTrue(json.has("bootCount") && json.isNull("bootCount"))
        assertFalse(raw.contains("token-secret-A"))
        assertFalse(file.name.contains("token-secret-A"))
    }

    @Test
    fun `namespace isolates host package and client without exposing the token`() {
        val a = AppPresenceStore.namespace("io.bearound.host", "token-secret-A")
        val b = AppPresenceStore.namespace("io.bearound.host", "token-secret-B")
        val otherHost = AppPresenceStore.namespace("io.bearound.other", "token-secret-A")

        assertNotEquals(a, b)
        assertNotEquals(a, otherHost)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(a))
        assertFalse(AppPresenceStore.fileName(a).contains("token"))
    }

    @Test
    fun `atomic write replaces content and leaves no temp file`() {
        store.write(record(snapshot("first")))
        store.write(record(snapshot("second")))

        val read = store.read(namespace) as AppPresenceReadResult.Found
        assertEquals("second", read.record.lastSnapshot!!.snapshotId)
        val leftovers = directory.listFiles()!!.map { it.name }.filter { it.endsWith(".tmp") }
        assertTrue("temp files left: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `garbage, foreign namespace and incomplete reservation are Corrupt`() {
        directory.mkdirs()
        val file = File(directory, AppPresenceStore.fileName(namespace))

        file.writeText("{not json")
        assertSame(AppPresenceReadResult.Corrupt, store.read(namespace))

        file.writeText(record().copy(namespace = "f".repeat(64)).toJson().toString())
        assertSame(AppPresenceReadResult.Corrupt, store.read(namespace))

        val noElapsed = record().toJson().apply { put("reservedElapsedRealtime", JSONObject.NULL) }
        file.writeText(noElapsed.toString())
        assertSame(AppPresenceReadResult.Corrupt, store.read(namespace))

        val otherSchema = record().toJson().apply { put("schemaVersion", 2) }
        file.writeText(otherSchema.toString())
        assertSame(AppPresenceReadResult.Corrupt, store.read(namespace))
    }

    @Test
    fun `clearSnapshot drops the snapshot but keeps the cooldown reservation`() {
        store.write(record())

        store.clearSnapshot(namespace)

        val read = (store.read(namespace) as AppPresenceReadResult.Found).record
        assertTrue(read.hasReservation)
        assertEquals(42_000L, read.reservedElapsedRealtime)
        assertEquals(7, read.bootCount)
        assertNull(read.lastSnapshot)
        assertNull(read.fingerprint)
    }

    @Test
    fun `clearSnapshot keeps a snapshot whose fingerprint is still current`() {
        store.write(record(snapshot(fingerprint = "fp-current")))

        store.clearSnapshot(namespace, keepFingerprint = "fp-current")
        assertEquals("snap-1", (store.read(namespace) as AppPresenceReadResult.Found).record.lastSnapshot?.snapshotId)

        store.clearSnapshot(namespace, keepFingerprint = "fp-new")
        assertNull((store.read(namespace) as AppPresenceReadResult.Found).record.lastSnapshot)
    }

    @Test
    fun `concurrent writers are serialized and the file always parses`() {
        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(64)
        repeat(64) { index ->
            pool.execute {
                try {
                    store.write(record(snapshot("snap-$index")))
                    assertTrue(store.read(namespace) is AppPresenceReadResult.Found)
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()

        val read = store.read(namespace)
        assertTrue("final record must parse, got $read", read is AppPresenceReadResult.Found)
    }

    @Test
    fun `production store lives in the private no-backup directory`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val production = AppPresenceStore.create(context)

        production.write(record())

        val expected = File(File(context.noBackupFilesDir, "bearound_app_presence"), AppPresenceStore.fileName(namespace))
        assertTrue(expected.exists())
        expected.parentFile!!.deleteRecursively()
    }
}
