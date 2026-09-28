package io.bearound.sdk.visit

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.bearound.sdk.utilities.OfflineBatchStorage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Upgrade path of the retired visit outbox (sdk-visit-cohesion REQ-011).
 *
 * Earlier builds kept undelivered visit events as a JSON array under [LEGACY_KEY] in
 * [VisitStateStore]'s preferences. Visit events now live in [OfflineBatchStorage]; this
 * reads that key one last time, writes every pending event there, and only then removes
 * the key. An event whose write fails stays under the key for the next run, so none is
 * lost in the move (a crash between the write and the removal re-sends it: at-least-once,
 * like the rest of the queue).
 */
@SuppressLint("ApplySharedPref") // commit(): the key goes only after the events are on disk
internal object OutboxMigration {
    private const val TAG = "BeAroundSDK-Visit"

    /** The retired outbox key. Nothing writes it any more. */
    const val LEGACY_KEY = "outbox"

    /** @return how many pending events moved into [storage]. Cheap no-op once the key is gone. */
    @Synchronized
    fun migrate(context: Context, storage: OfflineBatchStorage): Int {
        val prefs = prefs(context)
        return try {
            val raw = prefs.getString(LEGACY_KEY, null) ?: return 0
            val entries = JSONArray(raw)
            val left = JSONArray()
            var moved = 0
            for (index in 0 until entries.length()) {
                val json = entries.optJSONObject(index) ?: continue
                // An entry that does not decode could never be sent: nothing to keep.
                val event = decode(json) ?: continue
                if (storage.saveVisitEvent(event) != null) moved++ else left.put(json)
            }
            val editor = prefs.edit()
            if (left.length() == 0) editor.remove(LEGACY_KEY) else editor.putString(LEGACY_KEY, left.toString())
            editor.commit()
            if (moved > 0) Log.i(TAG, "Moved $moved pending visit event(s) from the retired outbox")
            moved
        } catch (e: Exception) {
            // A value that is not a JSON array (or not a string) was never deliverable.
            Log.w(TAG, "Dropping unreadable retired outbox: ${e.message}")
            prefs.edit().remove(LEGACY_KEY).commit()
            0
        }
    }

    /** The host no longer allows location: what the retired outbox held goes too. */
    @Synchronized
    fun discard(context: Context) {
        val prefs = prefs(context)
        if (prefs.contains(LEGACY_KEY)) prefs.edit().remove(LEGACY_KEY).commit()
    }

    private fun decode(json: JSONObject): VisitEvent? = try {
        VisitEvent.fromJson(json)
    } catch (e: Exception) {
        null
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(VisitStateStore.PREFS_NAME, Context.MODE_PRIVATE)
}
