package io.bearound.sdk.visit

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted visit state: the last good places config (a failed fetch keeps the last
 * list and the last `visit_detection_enabled`), the open stop (a departure seen by
 * the next process still pairs with the arrival of the previous one) and the native
 * registration. Undelivered visit events are NOT kept here: they live in the SDK's single
 * queue, `OfflineBatchStorage`; [OutboxMigration] moves what an
 * older version left under the retired outbox key.
 *
 * Writes use `commit()`: every value here is small, and state must be on disk before the
 * request that depends on it leaves.
 */
@SuppressLint("ApplySharedPref") // commit() on purpose, see above
internal class VisitStateStore(context: Context) {

    companion object {
        const val PREFS_NAME = "bearound_sdk_visit"
        private const val TAG = "BeAroundSDK-Visit"

        private const val KEY_CONFIG_BODY = "places_config_body"
        private const val KEY_ETAG = "places_etag"
        private const val KEY_FETCHED_AT = "places_fetched_at"
        private const val KEY_LAST_FAILED_FETCH_AT = "places_last_failed_fetch_at"
        private const val KEY_OPEN_STOP = "open_stop"
        private const val KEY_LAST_DEPARTURE_AT = "last_departure_at"
        private const val KEY_SOFT_CANDIDATE = "soft_candidate"
        private const val KEY_SOFT_LAST_FIX_AT = "soft_last_fix_at"
        private const val KEY_NATIVE_FAILED_AT = "native_failed_at"
        private const val KEY_NATIVE_REGISTRATION = "native_registration"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // region Places config

    data class CachedConfig(val config: PlacesConfig, val etag: String?, val fetchedAt: Long)

    @Volatile private var parsed: Pair<String, PlacesConfig>? = null

    fun loadConfig(): CachedConfig? {
        val body = prefs.getString(KEY_CONFIG_BODY, null) ?: return null
        val fetchedAt = prefs.getLong(KEY_FETCHED_AT, -1L).takeIf { it >= 0 } ?: return null
        val config = parsed?.takeIf { it.first == body }?.second
            ?: try {
                PlacesConfig.parse(body).also { parsed = body to it }
            } catch (e: Exception) {
                return null
            }
        return CachedConfig(config, prefs.getString(KEY_ETAG, null), fetchedAt)
    }

    fun saveConfig(body: String, etag: String?, fetchedAt: Long) {
        prefs.edit()
            .putString(KEY_CONFIG_BODY, body)
            .apply { if (etag != null) putString(KEY_ETAG, etag) else remove(KEY_ETAG) }
            .putLong(KEY_FETCHED_AT, fetchedAt)
            .commit()
    }

    /** 304: the cached list is still current as of [fetchedAt]. */
    fun touchConfig(fetchedAt: Long) {
        if (!prefs.contains(KEY_CONFIG_BODY)) return
        prefs.edit().putLong(KEY_FETCHED_AT, fetchedAt).commit()
    }

    var lastFailedFetchAt: Long?
        get() = getLongOrNull(KEY_LAST_FAILED_FETCH_AT)
        set(value) = putLongOrNull(KEY_LAST_FAILED_FETCH_AT, value)

    // endregion

    // region Stops

    /**
     * A stop whose arrival was sent and whose departure was not yet. ONE stop per environment,
     * whichever detector opened it: [sources] says who has seen it, and [apIds] are the
     * matched Wi-Fi access points. A stop opened by Wi-Fi alone has no fix, so [arrival] and
     * [lastInside] are null and [arrivedAt] is the time of the first sighting.
     */
    data class OpenStop(
        val environmentId: String?,
        val arrival: VisitFix?,
        val lastInside: VisitFix?,
        val arrivedAt: Long = arrival?.timestamp ?: 0L,
        val sources: Set<VisitSource> = setOf(VisitSource.GPS),
        val apIds: List<String> = emptyList()
    )

    var openStop: OpenStop?
        get() = readJson(KEY_OPEN_STOP) {
            val arrival = if (it.has("arrival")) VisitFix.fromJson(it.getJSONObject("arrival")) else null
            OpenStop(
                environmentId = if (it.has("env")) it.getString("env") else null,
                arrival = arrival,
                lastInside = if (it.has("lastInside")) VisitFix.fromJson(it.getJSONObject("lastInside")) else null,
                // Written before Wi-Fi stops: the arrival fix is the arrival time, GPS is the source.
                arrivedAt = if (it.has("at")) it.getLong("at") else arrival!!.timestamp,
                sources = if (it.has("sources")) {
                    val wires = it.getJSONArray("sources")
                    (0 until wires.length()).mapNotNull { index -> VisitSource.fromWire(wires.getString(index)) }.toSet()
                } else {
                    setOf(VisitSource.GPS)
                },
                apIds = if (it.has("apIds")) {
                    val ids = it.getJSONArray("apIds")
                    (0 until ids.length()).map { index -> ids.getString(index) }
                } else {
                    emptyList()
                }
            )
        }
        set(value) = writeJson(KEY_OPEN_STOP, value?.let {
            JSONObject().apply {
                it.environmentId?.let { env -> put("env", env) }
                it.arrival?.let { fix -> put("arrival", fix.toJson()) }
                it.lastInside?.let { fix -> put("lastInside", fix.toJson()) }
                put("at", it.arrivedAt)
                put("sources", JSONArray(it.sources.map { source -> source.wire }))
                put("apIds", JSONArray(it.apIds))
            }
        })

    /** Fix time of the last departure sent, so a redelivered transition is not re-sent. */
    var lastDepartureAt: Long?
        get() = getLongOrNull(KEY_LAST_DEPARTURE_AT)
        set(value) = putLongOrNull(KEY_LAST_DEPARTURE_AT, value)

    /** Soft fence: inside a target, waiting for the minimum dwell. */
    data class Candidate(val environmentId: String, val first: VisitFix, val last: VisitFix)

    var softCandidate: Candidate?
        get() = readJson(KEY_SOFT_CANDIDATE) {
            Candidate(
                environmentId = it.getString("env"),
                first = VisitFix.fromJson(it.getJSONObject("first")),
                last = VisitFix.fromJson(it.getJSONObject("last"))
            )
        }
        set(value) = writeJson(KEY_SOFT_CANDIDATE, value?.let {
            JSONObject().apply {
                put("env", it.environmentId)
                put("first", it.first.toJson())
                put("last", it.last.toJson())
            }
        })

    /** Soft fence: fix time of the last fix evaluated, so one fix is never counted twice. */
    var softLastFixAt: Long?
        get() = getLongOrNull(KEY_SOFT_LAST_FIX_AT)
        set(value) = putLongOrNull(KEY_SOFT_LAST_FIX_AT, value)

    /** When native geofence registration last failed; the factory falls back to soft. */
    var nativeFailedAt: Long?
        get() = getLongOrNull(KEY_NATIVE_FAILED_AT)
        set(value) = putLongOrNull(KEY_NATIVE_FAILED_AT, value)

    /**
     * The geofence set Play Services currently holds for the SDK: what was registered
     * ([signature]), when, and in which boot (geofences do not survive a reboot).
     */
    data class NativeRegistration(val signature: String, val registeredAt: Long, val bootAt: Long)

    var nativeRegistration: NativeRegistration?
        get() = readJson(KEY_NATIVE_REGISTRATION) {
            NativeRegistration(it.getString("sig"), it.getLong("at"), it.getLong("boot"))
        }
        set(value) = writeJson(KEY_NATIVE_REGISTRATION, value?.let {
            JSONObject().apply {
                put("sig", it.signature)
                put("at", it.registeredAt)
                put("boot", it.bootAt)
            }
        })

    // endregion

    fun clear() {
        parsed = null
        prefs.edit().clear().commit()
    }

    /** A value of the wrong type (a corrupt or foreign write) reads as absent and is dropped. */
    private fun getLongOrNull(key: String): Long? {
        if (!prefs.contains(key)) return null
        return try {
            prefs.getLong(key, 0L)
        } catch (e: Exception) {
            dropCorrupt(key, e)
            null
        }
    }

    private fun putLongOrNull(key: String, value: Long?) {
        prefs.edit().apply { if (value != null) putLong(key, value) else remove(key) }.commit()
    }

    /**
     * Reads [key] as JSON and maps it with [decode]. Visit state must never break the caller
     * (the beacon sync shares the worker window): a value that does not parse, lacks a field
     * or has the wrong type reads as absent and is removed, so it cannot fail again.
     */
    private fun <T> readJson(key: String, decode: (JSONObject) -> T): T? {
        return try {
            prefs.getString(key, null)?.let { decode(JSONObject(it)) }
        } catch (e: Exception) {
            dropCorrupt(key, e)
            null
        }
    }

    private fun dropCorrupt(key: String, error: Exception) {
        Log.w(TAG, "Dropping corrupt visit state '$key': ${error.message}")
        try {
            prefs.edit().remove(key).commit()
        } catch (e: Exception) {
            Log.w(TAG, "Could not drop corrupt visit state '$key': ${e.message}")
        }
    }

    private fun writeJson(key: String, value: JSONObject?) {
        prefs.edit().apply { if (value != null) putString(key, value.toString()) else remove(key) }.commit()
    }
}
