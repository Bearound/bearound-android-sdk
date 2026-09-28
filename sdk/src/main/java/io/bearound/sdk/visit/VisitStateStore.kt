package io.bearound.sdk.visit

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted visit state: the last good places config (D-22: a failed fetch keeps the last
 * list and the last `visit_detection_enabled`), the open stop (REQ-023: a departure seen by
 * the next process still pairs with the arrival of the previous one) and the outbox of
 * events not yet delivered.
 *
 * Writes use `commit()`: every value here is small, and an event must be on disk before the
 * request that carries it leaves.
 */
@SuppressLint("ApplySharedPref") // commit() on purpose, see above
internal class VisitStateStore(context: Context) {

    companion object {
        const val PREFS_NAME = "bearound_sdk_visit"

        private const val KEY_CONFIG_BODY = "places_config_body"
        private const val KEY_ETAG = "places_etag"
        private const val KEY_FETCHED_AT = "places_fetched_at"
        private const val KEY_LAST_FAILED_FETCH_AT = "places_last_failed_fetch_at"
        private const val KEY_OPEN_STOP = "open_stop"
        private const val KEY_LAST_DEPARTURE_AT = "last_departure_at"
        private const val KEY_SOFT_CANDIDATE = "soft_candidate"
        private const val KEY_SOFT_LAST_FIX_AT = "soft_last_fix_at"
        private const val KEY_OUTBOX = "outbox"
        private const val KEY_NATIVE_FAILED_AT = "native_failed_at"
        private const val KEY_NATIVE_REGISTRATION = "native_registration"

        /** Undelivered events beyond this are dropped oldest first. */
        const val OUTBOX_MAX = 20
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

    /** A stop whose arrival was sent and whose departure was not yet. */
    data class OpenStop(val environmentId: String?, val arrival: VisitFix, val lastInside: VisitFix)

    var openStop: OpenStop?
        get() = readJson(KEY_OPEN_STOP)?.let {
            OpenStop(
                environmentId = if (it.has("env")) it.getString("env") else null,
                arrival = VisitFix.fromJson(it.getJSONObject("arrival")),
                lastInside = VisitFix.fromJson(it.getJSONObject("lastInside"))
            )
        }
        set(value) = writeJson(KEY_OPEN_STOP, value?.let {
            JSONObject().apply {
                it.environmentId?.let { env -> put("env", env) }
                put("arrival", it.arrival.toJson())
                put("lastInside", it.lastInside.toJson())
            }
        })

    /** Fix time of the last departure sent, so a redelivered transition is not re-sent. */
    var lastDepartureAt: Long?
        get() = getLongOrNull(KEY_LAST_DEPARTURE_AT)
        set(value) = putLongOrNull(KEY_LAST_DEPARTURE_AT, value)

    /** Soft fence: inside a target, waiting for the minimum dwell. */
    data class Candidate(val environmentId: String, val first: VisitFix, val last: VisitFix)

    var softCandidate: Candidate?
        get() = readJson(KEY_SOFT_CANDIDATE)?.let {
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
        get() = readJson(KEY_NATIVE_REGISTRATION)?.let {
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

    // region Outbox

    @Synchronized
    fun enqueue(event: VisitEvent) {
        val events = outbox().toMutableList()
        events.add(event)
        writeOutbox(events.takeLast(OUTBOX_MAX))
    }

    @Synchronized
    fun outbox(): List<VisitEvent> {
        val raw = prefs.getString(KEY_OUTBOX, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                try {
                    VisitEvent.fromJson(array.getJSONObject(index))
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun remove(event: VisitEvent) {
        val events = outbox().toMutableList()
        if (events.remove(event)) writeOutbox(events)
    }

    @Synchronized
    fun clearOutbox() {
        prefs.edit().remove(KEY_OUTBOX).commit()
    }

    private fun writeOutbox(events: List<VisitEvent>) {
        if (events.isEmpty()) {
            prefs.edit().remove(KEY_OUTBOX).commit()
            return
        }
        val array = JSONArray()
        events.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_OUTBOX, array.toString()).commit()
    }

    // endregion

    fun clear() {
        parsed = null
        prefs.edit().clear().commit()
    }

    private fun getLongOrNull(key: String): Long? =
        if (prefs.contains(key)) prefs.getLong(key, 0L) else null

    private fun putLongOrNull(key: String, value: Long?) {
        prefs.edit().apply { if (value != null) putLong(key, value) else remove(key) }.commit()
    }

    private fun readJson(key: String): JSONObject? = try {
        prefs.getString(key, null)?.let(::JSONObject)
    } catch (e: Exception) {
        null
    }

    private fun writeJson(key: String, value: JSONObject?) {
        prefs.edit().apply { if (value != null) putString(key, value.toString()) else remove(key) }.commit()
    }
}
