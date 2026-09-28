package io.bearound.sdk.push

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
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
                // Case-insensitive scheme check, parity with the iOS SDK.
                if (!tr.startsWith("https://", ignoreCase = true)) return null
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

    /**
     * Maps an HTTP status code to a [PushHitOutcome] (design.md section 9 / Amendment 1):
     * 2xx or any 4xx other than 429 drains, 429/5xx keeps. Pure function, extracted so the
     * mapping itself can be unit-tested without a network round trip.
     */
    internal fun outcomeForStatus(code: Int): PushHitOutcome = when {
        code in 200..299 -> PushHitOutcome.DRAIN
        code == 429 -> PushHitOutcome.KEEP
        code in 400..499 -> PushHitOutcome.DRAIN
        else -> PushHitOutcome.KEEP
    }

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
            outcomeForStatus(code)
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
     * Keys currently being sent by an in-flight [sendOne] call OR waiting on a scheduled
     * retry ([retryScheduled]). `enqueue()` calls `flush()` once per event, and two events
     * enqueued back-to-back (e.g. `open` then `received` for the same tap) each spawn
     * their OWN flush thread that reads the WHOLE persisted queue: without this guard,
     * both threads would see both entries (the second flush racing ahead of the first
     * entry's drain) and send each one twice. A key stays in this set for the ENTIRE
     * retry chain (not just the single HTTP call), so a later `flush()` never re-claims
     * an entry that already has a retry pending. Guarded by [lock].
     */
    private val inFlight = mutableSetOf<String>()

    /**
     * Keys with a retry already scheduled on [retryExecutor]. Single-flight per key: at
     * most one pending retry can exist for a given `(sid, verb)` at a time, so repeated
     * `flush()` calls on a KEEP'd entry never pile up sleeping retry threads. Guarded by
     * [lock].
     */
    private val retryScheduled = mutableSetOf<String>()

    /** Overridable transport for tests. Default hits the tracker over HTTP. */
    internal var transport: PushEventTransport = HttpPushEventTransport

    /**
     * Single-thread scheduler owned by the queue: every retry across every key is
     * serialized on ONE thread instead of spawning a new sleeping thread per retry
     * attempt. Recreated by [resetForTest] so a stale scheduled task from a previous test
     * cannot fire against the next test's state.
     */
    @Volatile
    private var retryExecutor: ScheduledExecutorService = newRetryExecutor()

    private fun newRetryExecutor(): ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "bearound-push-retry").apply { isDaemon = true }
        }

    /** Overridable retry scheduler for tests (default: the queue's own executor). */
    internal var scheduleRetry: (delayMs: Long, action: () -> Unit) -> Unit = { delayMs, action ->
        try {
            retryExecutor.schedule({ action() }, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Executor was shut down by a concurrent resetForTest(); the scheduled
            // generation check in the caller already makes this a safe no-op.
        }
    }

    /**
     * Overridable persistence executor for tests (default: a dedicated background
     * thread). `enqueue()` is called from host callbacks that can run on the UI thread
     * (an Activity's `onCreate`/`onResume`, a Flutter/RN method-channel handler): the
     * SharedPreferences read and the fsync'd `commit()` write must never block that
     * thread.
     */
    internal var runPersistence: (action: () -> Unit) -> Unit = { action ->
        persistenceExecutor.execute(action)
    }

    @Volatile
    private var persistenceExecutor: java.util.concurrent.ExecutorService = newPersistenceExecutor()

    private fun newPersistenceExecutor(): java.util.concurrent.ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "bearound-push-persist").apply { isDaemon = true }
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
     *
     * The in-memory dedupe check (cheap, no I/O) runs synchronously on the caller's
     * thread so a duplicate is rejected without any dispatch. The SharedPreferences
     * read/write always runs on [runPersistence] (a background executor by default):
     * callers include host UI-thread callbacks (`onActivityCreated`/`onActivityResumed`,
     * a Flutter/RN method-channel handler), which must never block on disk I/O.
     */
    fun enqueue(context: Context, verb: PushEventVerb, marker: PushMarker) {
        val key = dedupeKey(marker.sid, verb)
        val isNew = lock.withLock {
            if (seen.contains(key)) {
                false
            } else {
                seen.add(key)
                true
            }
        }
        if (!isNew) return

        runPersistence {
            lock.withLock {
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
    }

    /**
     * Attempts to deliver every persisted entry. Best-effort: never throws, never blocks
     * the caller. Evicts stale entries (age cap, then count cap) on every call, so a queue
     * left kept-but-never-flushed does not carry expired entries forever.
     */
    fun flush(context: Context) {
        val flushGeneration = generation
        thread(name = "bearound-push-flush", isDaemon = true) {
            if (generation != flushGeneration) return@thread
            // Claim every entry not already in flight or awaiting a scheduled retry,
            // atomically with the read: two flush() calls racing (e.g. `open` then
            // `received` enqueued back-to-back) must never both pick up the same entry,
            // and an entry with a retry already pending must not be re-sent early by a
            // later flush.
            val claimed = lock.withLock {
                val evicted = evict(loadEntries(context))
                saveEntries(context, evicted)
                evicted.filter { entry ->
                    val key = dedupeKey(entry.sid, entry.verb)
                    if (inFlight.contains(key) || retryScheduled.contains(key)) {
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
     * releases it when the attempt does NOT lead to a scheduled retry (drain, or a retry
     * being scheduled keeps the claim held for the whole chain via [keepAndBackoff]).
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
        if (generation != entryGeneration) {
            lock.withLock { inFlight.remove(key) }
            return // reset happened while the transport ran
        }
        when (outcome) {
            PushHitOutcome.DRAIN -> {
                drain(context, entry)
                lock.withLock { inFlight.remove(key) }
            }
            PushHitOutcome.KEEP -> keepAndBackoff(context, entry, entryGeneration)
        }
    }

    private fun drain(context: Context, sent: Entry) {
        lock.withLock {
            val entries = loadEntries(context).filterNot { it.sid == sent.sid && it.verb == sent.verb }
            saveEntries(context, entries)
        }
    }

    /**
     * Bumps the persisted attempt count and schedules ONE retry for this key. The
     * [inFlight] claim is deliberately KEPT (not released) for the whole retry chain: a
     * later `flush()` must not re-claim this entry while a retry is pending for it,
     * otherwise k persisted entries times n flush() calls would each start their own
     * independent, unbounded retry chain.
     */
    private fun keepAndBackoff(context: Context, sent: Entry, entryGeneration: Int) {
        val key = dedupeKey(sent.sid, sent.verb)
        val nextAttempt = lock.withLock {
            val entries = loadEntries(context).map {
                if (it.sid == sent.sid && it.verb == sent.verb) it.copy(attempt = it.attempt + 1) else it
            }
            saveEntries(context, entries)
            val attempt = entries.firstOrNull { it.sid == sent.sid && it.verb == sent.verb }?.attempt
                ?: (sent.attempt + 1)
            // Entry may have been dropped by a concurrent evict() between the send and
            // here; if so, there is nothing left to retry, and the claim (both inFlight
            // and any retry) must be released now, not held forever.
            if (entries.none { it.sid == sent.sid && it.verb == sent.verb }) {
                inFlight.remove(key)
                return@withLock null
            }
            retryScheduled.add(key)
            attempt
        } ?: return

        val delay = minOf(BASE_RETRY_DELAY_MS * (1L shl (nextAttempt - 1).coerceIn(0, 20)), MAX_RETRY_DELAY_MS)
        scheduleRetry(delay) {
            lock.withLock { retryScheduled.remove(key) }
            if (generation != entryGeneration) {
                lock.withLock { inFlight.remove(key) }
                return@scheduleRetry
            }
            // Re-read under the lock instead of resending the captured [sent] copy: the
            // entry may have changed (a later enqueue is a no-op due to dedupe, but drain
            // or eviction could have removed it) since this retry was scheduled.
            val current = lock.withLock { loadEntries(context).firstOrNull { it.sid == sent.sid && it.verb == sent.verb } }
            if (current == null) {
                lock.withLock { inFlight.remove(key) }
                return@scheduleRetry
            }
            sendOne(context, current, entryGeneration)
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
        // apply(): the write is async (no fsync wait), but every read of this key goes
        // through loadEntries(), which always runs under [lock] on the SAME background
        // executor/thread that just wrote it (runPersistence, or the flush thread right
        // after): the in-process memory visibility apply() guarantees for same-process
        // reads is all this needs. commit() would block the caller (enqueue() can run on
        // the host's UI thread) on a disk fsync for no additional safety here.
        prefs(context).edit().putString(STORAGE_KEY, array.toString()).apply()
    }

    // region test support

    /** Resets in-memory and persisted state. Test-only. */
    internal fun resetForTest(context: Context) {
        generation++ // invalidates any background thread/task scheduled by a previous test
        val oldRetryExecutor = retryExecutor
        val oldPersistenceExecutor = persistenceExecutor
        lock.withLock {
            seen.clear()
            inFlight.clear()
            retryScheduled.clear()
            // A fresh prefs file per reset, not just clearing the old one: a background
            // thread left over from the PREVIOUS test (this is a process-wide singleton)
            // could still be mid-flight and write to the OLD file after this clear runs,
            // resurrecting a stale entry the current test would then see.
            prefsName = "${DEFAULT_PREFS_NAME}_test_${System.nanoTime()}"
            prefs(context).edit().remove(STORAGE_KEY).commit()
            // Fresh executors per reset: a task already queued on the OLD executor (e.g. a
            // pending retry) must never run against the NEXT test's state. The generation
            // bump above also gates every callback, so this is belt-and-suspenders.
            retryExecutor = newRetryExecutor()
            persistenceExecutor = newPersistenceExecutor()
        }
        oldRetryExecutor.shutdownNow()
        oldPersistenceExecutor.shutdownNow()
        transport = HttpPushEventTransport
        scheduleRetry = { delayMs, action ->
            try {
                retryExecutor.schedule({ action() }, delayMs, TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // Executor was shut down by a concurrent resetForTest().
            }
        }
        runPersistence = { action -> persistenceExecutor.execute(action) }
    }

    /** Number of persisted entries. Test-only. */
    internal fun sizeForTest(context: Context): Int = lock.withLock { loadEntries(context).size }

    /** `sid`s of every persisted entry, oldest first. Test-only: proves WHICH entries survived eviction, not just how many. */
    internal fun sidsForTest(context: Context): List<String> =
        lock.withLock { loadEntries(context).sortedBy { it.enqueuedAt }.map { it.sid } }

    /** Number of keys with a retry currently scheduled. Test-only. */
    internal fun pendingRetryCountForTest(): Int = lock.withLock { retryScheduled.size }

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

    /**
     * Like [seedForTest] but skips eviction on write, so a stale (already-expired) entry
     * actually lands in storage instead of being dropped on the spot. Exists to test that
     * [flush] itself evicts a stale entry it finds, independent of the write path.
     * Test-only.
     */
    internal fun seedForTestWithoutEviction(
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
            saveEntries(context, entries)
        }
    }

    // endregion
}
