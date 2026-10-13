package io.bearound.sdk.utilities

import android.content.Context
import io.bearound.sdk.models.AppPresenceSnapshot
import io.bearound.sdk.models.AppPresenceTime
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * The app presence storage could not be read or written. The round is cancelled before
 * any app is checked (fail closed). Delivered through [io.bearound.sdk.interfaces.BeAroundSDKListener.onError]
 * only; never sent to error telemetry.
 */
class AppPresenceStorageException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause) {
    val code: String = CODE

    companion object {
        const val CODE = "app_presence_storage_unavailable"
    }
}

/**
 * Persisted state of one namespace (host package + client): the cooldown reservation and
 * the most recent snapshot. No result history is kept.
 *
 * [reservedElapsedRealtime] is `SystemClock.elapsedRealtime()` at reservation time and
 * [bootCount] the `Settings.Global.BOOT_COUNT` read then (null when unavailable). Together
 * they decide whether a relaunch in the same boot can reuse the interval. [reservedAtEpochMillis]
 * is informative only: wall clock never releases a cooldown.
 */
internal data class AppPresenceRecord(
    val namespace: String,
    val hasReservation: Boolean,
    val reservedAtEpochMillis: Long?,
    val reservedElapsedRealtime: Long?,
    val bootCount: Int?,
    val fingerprint: String?,
    val lastSnapshot: AppPresenceSnapshot?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", AppPresenceStore.SCHEMA_VERSION)
        put("namespace", namespace)
        put("hasReservation", hasReservation)
        put("reservedAt", reservedAtEpochMillis?.let { AppPresenceTime.format(it) } ?: JSONObject.NULL)
        put("reservedAtEpochMillis", reservedAtEpochMillis ?: JSONObject.NULL)
        put("reservedElapsedRealtime", reservedElapsedRealtime ?: JSONObject.NULL)
        put("bootCount", bootCount ?: JSONObject.NULL)
        put("fingerprint", fingerprint ?: JSONObject.NULL)
        put("lastSnapshot", lastSnapshot?.toJson() ?: JSONObject.NULL)
    }

    companion object {
        /** Throws [JSONException] on any shape problem: the caller treats it as corrupt. */
        fun fromJson(json: JSONObject): AppPresenceRecord {
            if (json.getInt("schemaVersion") != AppPresenceStore.SCHEMA_VERSION) {
                throw JSONException("unsupported schemaVersion")
            }
            val hasReservation = json.getBoolean("hasReservation")
            val reservedAt = optLong(json, "reservedAtEpochMillis")
            val reservedElapsed = optLong(json, "reservedElapsedRealtime")
            if (hasReservation && (reservedAt == null || reservedElapsed == null)) {
                throw JSONException("reservation without timestamps")
            }
            val bootCount = optLong(json, "bootCount")?.let {
                if (it < Int.MIN_VALUE || it > Int.MAX_VALUE) throw JSONException("bootCount out of range")
                it.toInt()
            }
            val snapshot = if (json.isNull("lastSnapshot")) null
            else AppPresenceSnapshot.fromJson(json.getJSONObject("lastSnapshot"))
            return AppPresenceRecord(
                namespace = json.getString("namespace"),
                hasReservation = hasReservation,
                reservedAtEpochMillis = reservedAt,
                reservedElapsedRealtime = reservedElapsed,
                bootCount = bootCount,
                fingerprint = if (json.isNull("fingerprint")) null else json.getString("fingerprint"),
                lastSnapshot = snapshot
            )
        }

        private fun optLong(json: JSONObject, key: String): Long? {
            if (!json.has(key) || json.isNull(key)) return null
            return when (val value = json.get(key)) {
                is Int -> value.toLong()
                is Long -> value
                else -> throw JSONException("$key must be an integer")
            }
        }
    }
}

/** Outcome of reading a namespace record. I/O failures are thrown, not mapped here. */
internal sealed class AppPresenceReadResult {
    /** No record: first run of this namespace on this install. */
    object Missing : AppPresenceReadResult()

    /** The file exists but does not parse or belongs to another namespace. */
    object Corrupt : AppPresenceReadResult()

    data class Found(val record: AppPresenceRecord) : AppPresenceReadResult()
}

/** File access behind [AppPresenceStore], injectable so tests can simulate I/O failures. */
internal interface AppPresenceFileSystem {
    /** Returns the file content, or `null` when the file does not exist. */
    @Throws(IOException::class)
    fun read(name: String): String?

    /** Replaces the file content atomically and durably, or throws. */
    @Throws(IOException::class)
    fun writeAtomically(name: String, content: String)

    /** Runs [block] holding the cross-process file lock. */
    @Throws(IOException::class)
    fun <T> withFileLock(block: () -> T): T
}

/**
 * Private files under [directory]: temp file + fsync + rename for every write, and an
 * exclusive [java.nio.channels.FileLock] on a sibling lock file around every access.
 */
internal class DirectoryAppPresenceFileSystem(private val directory: File) : AppPresenceFileSystem {

    override fun read(name: String): String? {
        val file = File(directory, name)
        if (!file.exists()) return null
        return file.readText(Charsets.UTF_8)
    }

    override fun writeAtomically(name: String, content: String) {
        ensureDirectory()
        val target = File(directory, name)
        val temp = File(directory, "$name.tmp")
        FileOutputStream(temp).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("rename failed")
        }
    }

    override fun <T> withFileLock(block: () -> T): T {
        ensureDirectory()
        val file = RandomAccessFile(File(directory, LOCK_FILE), "rw")
        try {
            val lock = file.channel.lock()
            try {
                return block()
            } finally {
                lock.release()
            }
        } finally {
            file.close()
        }
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("storage directory unavailable")
        }
    }

    private companion object {
        const val LOCK_FILE = ".lock"
    }
}

/**
 * Private, serialized storage of the app presence reservation and last snapshot, one JSON
 * file per namespace. The namespace is a SHA-256 of the host package and of the
 * SHA-256 of the business token: the token never appears in a file name or in the content.
 *
 * Every operation runs under a process-wide monitor plus the file lock. Writes are
 * synchronous and durable when they return (never `SharedPreferences.apply`), which is what
 * lets the coordinator rely on "reservation persisted" before querying any package.
 */
internal class AppPresenceStore(private val fileSystem: AppPresenceFileSystem) {

    companion object {
        const val SCHEMA_VERSION = 1
        private const val DIRECTORY = "bearound_app_presence"

        /** Serializes in-process access: a JVM cannot hold two FileLocks on one file. */
        private val PROCESS_LOCK = Any()

        fun create(context: Context): AppPresenceStore {
            val base = context.noBackupFilesDir ?: context.filesDir
            return AppPresenceStore(DirectoryAppPresenceFileSystem(File(base, DIRECTORY)))
        }

        /** Namespace id of a host package + client. Lowercase hex, 64 chars. */
        fun namespace(packageName: String, businessToken: String): String =
            sha256Hex("$packageName\n${sha256Hex(businessToken)}")

        internal fun fileName(namespace: String): String = "ns_$namespace.json"

        private fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }

    /** Reads the namespace record. Throws [IOException] when the storage is unreadable. */
    @Throws(IOException::class)
    fun read(namespace: String): AppPresenceReadResult = locked { readUnlocked(namespace) }

    /** Writes [record] durably. Throws [IOException] on failure. */
    @Throws(IOException::class)
    fun write(record: AppPresenceRecord) = locked { writeUnlocked(record) }

    /**
     * Drops the stored snapshot of [namespace] when [keepFingerprint] is null or differs from
     * its fingerprint, keeping the reservation marker. A corrupt or missing record is left as is.
     */
    @Throws(IOException::class)
    fun clearSnapshot(namespace: String, keepFingerprint: String? = null) = locked {
        val result = readUnlocked(namespace)
        if (result is AppPresenceReadResult.Found) {
            val record = result.record
            val stale = record.lastSnapshot != null &&
                (keepFingerprint == null || record.lastSnapshot.configurationFingerprint != keepFingerprint)
            if (stale) writeUnlocked(record.copy(lastSnapshot = null, fingerprint = null))
        }
    }

    private fun <T> locked(block: () -> T): T = synchronized(PROCESS_LOCK) {
        try {
            fileSystem.withFileLock(block)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            // OverlappingFileLockException, SecurityException and friends: storage unavailable.
            throw IOException("storage unavailable", e)
        }
    }

    private fun readUnlocked(namespace: String): AppPresenceReadResult {
        val raw = fileSystem.read(fileName(namespace)) ?: return AppPresenceReadResult.Missing
        return try {
            val record = AppPresenceRecord.fromJson(JSONObject(raw))
            if (record.namespace != namespace) AppPresenceReadResult.Corrupt
            else AppPresenceReadResult.Found(record)
        } catch (_: JSONException) {
            AppPresenceReadResult.Corrupt
        }
    }

    private fun writeUnlocked(record: AppPresenceRecord) {
        fileSystem.writeAtomically(fileName(record.namespace), record.toJson().toString())
    }
}
