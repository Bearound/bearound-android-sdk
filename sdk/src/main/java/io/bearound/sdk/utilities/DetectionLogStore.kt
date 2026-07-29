package io.bearound.sdk.utilities

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted detection log — the diagnostic answer to "what did the SDK see, and
 * with the app in which state?".
 *
 * Backed by SharedPreferences (the Android counterpart of iOS `UserDefaults`),
 * so entries survive process death: a detection recorded while the app was
 * closed is still there when the user opens it again. An in-memory mirror keeps
 * reads cheap; the disk write is what makes `terminated` entries meaningful.
 *
 * Each entry carries the state at WRITE time via [AppStateMonitor] — including
 * `terminated` for events during a system-started process (broadcast-delivered
 * scan result / boot / watchdog) before any UI appears.
 *
 * Surfaced to hosts via `BeAroundSDK.getDetectionLogJson()` / `clearDetectionLog()`.
 *
 * Mirrors the iOS `DetectionLogStore` (same JSON shape, same tags, same cap).
 */
internal object DetectionLogStore {

    private const val TAG = "BeAroundSDK-DetLog"
    private const val PREFS_NAME = "bearound_sdk_log"
    private const val STORAGE_KEY = "entries"
    private const val MAX_ENTRIES = 500

    private val lock = Any()
    private var cache: JSONArray? = null
    private var seq = 0

    /**
     * Appends an entry tagged with the current process state.
     *
     * Entries written while the app is closed (`terminated`) are flushed with
     * `commit()` — the system may kill the process right after the wake-up
     * callback, and an async `apply()` would lose exactly the evidence this log
     * exists to capture. Foreground/background writes use `apply()` to stay off
     * the hot path.
     */
    fun append(context: Context, type: String, detail: String) {
        val tag = AppStateMonitor.currentTag()
        try {
            synchronized(lock) {
                val prefs = context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val arr = cache ?: readArray(prefs).also { cache = it }

                val entry = JSONObject()
                    .put("id", "${System.currentTimeMillis()}-${seq++}")
                    .put("timestamp", System.currentTimeMillis())
                    .put("state", tag.raw)
                    .put("type", type)
                    .put("detail", detail)

                val next = JSONArray().put(entry)
                val limit = minOf(arr.length(), MAX_ENTRIES - 1)
                for (i in 0 until limit) next.put(arr.get(i))
                cache = next

                val editor = prefs.edit().putString(STORAGE_KEY, next.toString())
                if (tag == AppStateMonitor.Tag.TERMINATED) editor.commit() else editor.apply()
            }
        } catch (t: Throwable) {
            // Diagnostics must never break detection.
            Log.w(TAG, "append failed: ${t.message}")
        }
    }

    /** The log as a JSON array string (newest first). `"[]"` when empty. */
    fun readJson(context: Context): String = try {
        synchronized(lock) {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            (cache ?: readArray(prefs).also { cache = it }).toString()
        }
    } catch (t: Throwable) {
        Log.w(TAG, "read failed: ${t.message}")
        "[]"
    }

    fun clear(context: Context) {
        synchronized(lock) {
            cache = null
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(STORAGE_KEY).apply()
        }
    }

    private fun readArray(prefs: android.content.SharedPreferences): JSONArray {
        val raw = prefs.getString(STORAGE_KEY, null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (_: Throwable) {
            JSONArray()
        }
    }
}
