package io.bearound.sdk.push

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Verb reported to the ads--tracker for a Bearound push event (design.md Amendment 1).
 */
enum class PushEventVerb(val wireValue: String) {
    RECEIVED("received"),
    OPEN("open");
}

/**
 * Parses the `bearound` marker carried by an FCM `data` payload or a notification-tap
 * `Intent` extra. Both are the same JSON string shape: `{"t":..,"sid":uuid,"d":string,
 * "tr":"https://track.bearound.io"}`. Sync pushes only have `t`.
 *
 * Measurable only when `sid`, `d` and `tr` are all present and `tr` is `https`
 * (design.md Amendment 1 contract).
 */
internal data class PushMarker(
    val sid: String,
    val d: String,
    val tr: String
) {
    companion object {
        /** Parses [raw] (the `bearound` marker's JSON string value). Null if malformed or not measurable. */
        fun parse(raw: String?): PushMarker? {
            if (raw.isNullOrBlank()) return null
            return try {
                val json = JSONObject(raw)
                val sid = json.optString("sid").takeIf { it.isNotBlank() } ?: return null
                val d = json.optString("d").takeIf { it.isNotBlank() } ?: return null
                val tr = json.optString("tr").takeIf { it.isNotBlank() } ?: return null
                if (!tr.startsWith("https://")) return null
                PushMarker(sid = sid, d = d, tr = tr)
            } catch (_: Throwable) {
                null
            }
        }
    }
}

/** Outcome of one tracker hit attempt (design.md section 9 / Amendment 1). */
internal enum class PushHitOutcome {
    /** 2xx, or any 4xx other than 429: drop the entry, do not retry. */
    DRAIN,
    /** 5xx, 429, or a transport error: keep the entry queued for retry. */
    KEEP
}

/**
 * Isolated transport for one tracker hit. Abstracted so tests can inject a fake without a
 * real network round trip. Production default is [HttpPushEventTransport].
 */
internal fun interface PushEventTransport {
    fun send(url: String): PushHitOutcome
}

/**
 * Default transport: `GET {tr}/v1/push:{verb}?d={d}`, no Authorization header, no body,
 * short timeouts (parity with [io.bearound.sdk.telemetry.ErrorReporter]'s transport).
 */
internal object HttpPushEventTransport : PushEventTransport {
    private const val TAG = "BeAroundSDK-PushQueue"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    override fun send(url: String): PushHitOutcome {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = false
            }
            val code = connection.responseCode
            Log.d(TAG, "Push hit sent (HTTP $code)")
            when {
                code in 200..299 -> PushHitOutcome.DRAIN
                code == 429 -> PushHitOutcome.KEEP
                code in 400..499 -> PushHitOutcome.DRAIN
                else -> PushHitOutcome.KEEP
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Push hit failed: ${t.message}")
            PushHitOutcome.KEEP
        } finally {
            connection?.disconnect()
        }
    }
}

/**
 * Persisted, deduped, capped queue of push receipt/open tracker hits with best-effort
 * immediate delivery off the main thread and exponential-backoff retry.
 *
 * Separate from [io.bearound.sdk.utilities.OfflineBatchStorage] (which only ever carries
 * beacon batches). Backed by its own SharedPreferences file so entries survive process
 * death: a notification tap can launch the app before `configure()` runs, and the hits
 * carry no auth (design.md Amendment 1: "no Authorization"), so the queue flushes even
 * before the business token is known.
 *
 * Thread-safety: every public entry point synchronizes on [lock].
 */
internal object PushEventQueue {

    private const val TAG = "BeAroundSDK-PushQueue"
    private const val DEFAULT_PREFS_NAME = "bearound_sdk_push_events"
    private const val STORAGE_KEY = "queue"

    /**
     * Overridable SharedPreferences file name. Tests give each run a unique name via
     * [resetForTest] so a background thread left over from a previous test (this is a
     * process-wide singleton) can never read or write the CURRENT test's persisted queue,
     * regardless of thread-scheduling timing.
     */
    @Volatile
    private var prefsName: String = DEFAULT_PREFS_NAME

    internal const val MAX_ENTRIES = 200
    internal const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val BASE_RETRY_DELAY_MS = 2_000L
    private const val MAX_RETRY_DELAY_MS = 300_000L

    private val lock = ReentrantLock()

    /**
     * Bumped by [resetForTest]. A background thread scheduled before a reset checks this
     * before touching shared state, so a stray retry/flush thread from a previous test
     * (JVM-shared static object) never runs against the next test's fresh state.
     */
    @Volatile
    private var generation = 0

    /** Local dedupe of `(sid, verb)` seen in this process install. */
    private val seen = mutableSetOf<String>()

    /**
     * Keys currently being sent by an in-flight [sendOne] call. `enqueue()` calls
     * `flush()` once per event, and two events enqueued back-to-back (e.g. `open` then
     * `received` for the same tap) each spawn their OWN flush thread that reads the WHOLE
     * persisted queue: without this guard, both threads would see both entries (the
     * second flush racing ahead of the first entry's drain) and send each one twice.
     * Guarded by [lock].
     */
    private val inFlight = mutableSetOf<String>()

    /** Overridable transport for tests. Default hits the tracker over HTTP. */
    internal var transport: PushEventTransport = HttpPushEventTransport

    /** Overridable retry scheduler for tests (default: a background thread sleep). */
    internal var scheduleRetry: (delayMs: Long, action: () -> Unit) -> Unit = { delayMs, action ->
        thread(name = "bearound-push-retry", isDaemon = true) {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                return@thread
            }
            action()
        }
    }

    internal data class Entry(
        val verb: PushEventVerb,
        val sid: String,
        val d: String,
        val tr: String,
        val enqueuedAt: Long,
        val attempt: Int = 0
    )

    private fun dedupeKey(sid: String, verb: PushEventVerb) = "$sid|${verb.wireValue}"

    /**
     * Enqueues a tracker hit and attempts immediate delivery off the main thread. No-op if
     * `(sid, verb)` was already enqueued (or drained) in this process install (REQ-025).
     */
    fun enqueue(context: Context, verb: PushEventVerb, marker: PushMarker) {
        val key = dedupeKey(marker.sid, verb)
        lock.withLock {
            if (seen.contains(key)) return
            seen.add(key)

            val entries = loadEntries(context).toMutableList()
            val alreadyPersisted = entries.any { it.sid == marker.sid && it.verb == verb }
            if (!alreadyPersisted) {
                entries.add(
                    Entry(
                        verb = verb,
                        sid = marker.sid,
                        d = marker.d,
                        tr = marker.tr,
                        enqueuedAt = System.currentTimeMillis()
                    )
                )
            }
            saveEntries(context, evict(entries))
        }
        flush(context)
    }

    /** Attempts to deliver every persisted entry. Best-effort: never throws, never blocks the caller. */
    fun flush(context: Context) {
        val flushGeneration = generation
        thread(name = "bearound-push-flush", isDaemon = true) {
            if (generation != flushGeneration) return@thread
            // Claim every entry not already in flight BEFORE spawning the send, atomically
            // with the read: two flush() calls racing (e.g. `open` then `received` enqueued
            // back-to-back) must never both pick up the same entry.
            val claimed = lock.withLock {
                loadEntries(context).filter { entry ->
                    val key = dedupeKey(entry.sid, entry.verb)
                    if (inFlight.contains(key)) {
                        false
                    } else {
                        inFlight.add(key)
                        true
                    }
                }
            }
            for (entry in claimed) sendOne(context, entry, flushGeneration)
        }
    }

    /**
     * Sends one entry. The caller must already hold its [inFlight] claim; this function
     * always releases it before returning (including synchronously-completed retries, so a
     * zero-delay [scheduleRetry] test double does not see a claim held by its own,
     * not-yet-unwound outer call).
     */
    private fun sendOne(context: Context, entry: Entry, entryGeneration: Int) {
        val key = dedupeKey(entry.sid, entry.verb)
        if (generation != entryGeneration) {
            lock.withLock { inFlight.remove(key) }
            return
        }
        val url = buildUrl(entry)
        val outcome = try {
            transport.send(url)
        } catch (t: Throwable) {
            Log.w(TAG, "Push hit transport threw: ${t.message}")
            PushHitOutcome.KEEP
        }
        // Release BEFORE drain/backoff: keepAndBackoff may schedule a retry that runs
        // synchronously (immediately, same call stack, e.g. in tests) and needs to
        // re-claim the same key: it must see the claim already released here, not still
        // held by this (not-yet-returned) frame.
        lock.withLock { inFlight.remove(key) }
        if (generation != entryGeneration) return // reset happened while the transport ran
        when (outcome) {
            PushHitOutcome.DRAIN -> drain(context, entry)
            PushHitOutcome.KEEP -> keepAndBackoff(context, entry, entryGeneration)
        }
    }

    private fun drain(context: Context, sent: Entry) {
        lock.withLock {
            val entries = loadEntries(context).filterNot { it.sid == sent.sid && it.verb == sent.verb }
            saveEntries(context, entries)
        }
    }

    private fun keepAndBackoff(context: Context, sent: Entry, entryGeneration: Int) {
        val nextAttempt = lock.withLock {
            val entries = loadEntries(context).map {
                if (it.sid == sent.sid && it.verb == sent.verb) it.copy(attempt = it.attempt + 1) else it
            }
            saveEntries(context, entries)
            entries.firstOrNull { it.sid == sent.sid && it.verb == sent.verb }?.attempt ?: (sent.attempt + 1)
        }

        val delay = minOf(BASE_RETRY_DELAY_MS * (1L shl (nextAttempt - 1).coerceIn(0, 20)), MAX_RETRY_DELAY_MS)
        val key = dedupeKey(sent.sid, sent.verb)
        scheduleRetry(delay) {
            if (generation != entryGeneration) return@scheduleRetry
            // Re-claim before retrying: a flush() triggered by a DIFFERENT enqueue() in the
            // meantime must not pick up this entry while the scheduled retry also runs it.
            val claimed = lock.withLock {
                if (inFlight.contains(key)) false else { inFlight.add(key); true }
            }
            if (claimed) sendOne(context, sent.copy(attempt = nextAttempt), entryGeneration)
        }
    }

    /** `{tr}/v1/push:{verb}?d={URL-encoded d}` (design.md Amendment 1). */
    internal fun buildUrl(entry: Entry): String = buildUrl(entry.tr, entry.verb, entry.d)

    /** Test-visible overload taking the raw fields instead of an [Entry]. */
    internal fun buildUrl(tr: String, verb: PushEventVerb, d: String): String {
        val encodedD = URLEncoder.encode(d, "UTF-8")
        return "$tr/v1/push:${verb.wireValue}?d=$encodedD"
    }

    /** Applies the 7-day age cap, then the 200-entry cap (oldest dropped first). */
    private fun evict(entries: List<Entry>): List<Entry> {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        var result = entries.filter { it.enqueuedAt >= cutoff }
        if (result.size > MAX_ENTRIES) {
            result = result.sortedBy { it.enqueuedAt }.takeLast(MAX_ENTRIES)
        }
        return result
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private fun loadEntries(context: Context): List<Entry> {
        val raw = prefs(context).getString(STORAGE_KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val verb = PushEventVerb.entries.firstOrNull { it.wireValue == obj.optString("verb") }
                    ?: return@mapNotNull null
                Entry(
                    verb = verb,
                    sid = obj.optString("sid"),
                    d = obj.optString("d"),
                    tr = obj.optString("tr"),
                    enqueuedAt = obj.optLong("enqueuedAt"),
                    attempt = obj.optInt("attempt", 0)
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Push queue read failed, resetting: ${t.message}")
            emptyList()
        }
    }

    private fun saveEntries(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("verb", entry.verb.wireValue)
                    put("sid", entry.sid)
                    put("d", entry.d)
                    put("tr", entry.tr)
                    put("enqueuedAt", entry.enqueuedAt)
                    put("attempt", entry.attempt)
                }
            )
        }
        // commit(), not apply(): every save is immediately followed by a read (from this
        // thread or the flush thread `enqueue()` spawns right after): apply()'s async
        // write is not guaranteed visible by then. Writes are small and infrequent (capped
        // at 200 entries), so the synchronous cost is negligible.
        prefs(context).edit().putString(STORAGE_KEY, array.toString()).commit()
    }

    // region test support

    /** Resets in-memory and persisted state. Test-only. */
    internal fun resetForTest(context: Context) {
        generation++ // invalidates any background thread scheduled by a previous test
        lock.withLock {
            seen.clear()
            inFlight.clear()
            // A fresh prefs file per reset, not just clearing the old one: a background
            // thread left over from the PREVIOUS test (this is a process-wide singleton)
            // could still be mid-flight and write to the OLD file after this clear runs,
            // resurrecting a stale entry the current test would then see.
            prefsName = "${DEFAULT_PREFS_NAME}_test_${System.nanoTime()}"
            prefs(context).edit().remove(STORAGE_KEY).commit()
        }
        transport = HttpPushEventTransport
        scheduleRetry = { delayMs, action ->
            thread(name = "bearound-push-retry", isDaemon = true) {
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    return@thread
                }
                action()
            }
        }
    }

    /** Number of persisted entries. Test-only. */
    internal fun sizeForTest(context: Context): Int = lock.withLock { loadEntries(context).size }

    /** Directly persists raw entries, then applies eviction, bypassing enqueue/dedupe. Test-only. */
    internal fun seedForTest(
        context: Context,
        verb: PushEventVerb,
        sid: String,
        d: String = "d",
        tr: String = "https://track.bearound.io",
        enqueuedAt: Long = System.currentTimeMillis(),
        attempt: Int = 0
    ) {
        lock.withLock {
            val entries = loadEntries(context).toMutableList()
            entries.add(Entry(verb, sid, d, tr, enqueuedAt, attempt))
            saveEntries(context, evict(entries))
        }
    }

    // endregion
}
