package io.bearound.sdk.telemetry

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import io.bearound.sdk.BuildConfig
import io.bearound.sdk.models.SDKConfiguration
import io.bearound.sdk.utilities.DeviceIdentifier
import io.bearound.sdk.utilities.OemPowerProfile
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Isolated SDK error telemetry — a "try/catch around the library".
 *
 * Captures errors originating in SDK code (uncaught exceptions whose stack contains
 * `io.bearound.sdk` frames, SDK coroutine failures, and errors reported from existing
 * catch blocks) and ships them to the ingest backend (`POST /sdk-errors`).
 *
 * GOLDEN RULE: this reporter must NEVER throw, NEVER break the host app, and NEVER
 * interfere with the SDK's existing flow. Every public entry point swallows its own
 * failures; transport runs on a private scope with 5 s timeouts; delivery is
 * fire-and-forget (the response is ignored).
 *
 * Safeguards:
 * - in-memory rate limit: at most [MAX_REPORTS_PER_HOUR] reports per rolling hour;
 * - dedupe: the same error hash (sha256 of `type|context|first stack line`) is
 *   suppressed for [DEDUPE_WINDOW_MS];
 * - stack traces truncated to [MAX_STACK_CHARS] chars;
 * - the chained uncaught handler ALWAYS delegates to the previous handler.
 */
object ErrorReporter {

    private const val TAG = "BeAroundSDK-ErrorRep"

    private const val ENDPOINT_PATH = "/sdk-errors"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    private const val MAX_STACK_CHARS = 8_000
    private const val MAX_REPORTS_PER_HOUR = 20
    private const val RATE_WINDOW_MS = 60L * 60L * 1000L
    private const val DEDUPE_WINDOW_MS = 5L * 60L * 1000L
    /** Upper bound for the dedupe map before expired entries are pruned. */
    private const val DEDUPE_MAP_PRUNE_SIZE = 64

    private const val SDK_PACKAGE_PREFIX = "io.bearound.sdk"
    private const val TELEMETRY_PACKAGE_PREFIX = "io.bearound.sdk.telemetry"
    /** How deep the cause chain is walked when looking for SDK frames. */
    private const val MAX_CAUSE_DEPTH = 5

    @Volatile
    private var enabled: Boolean = true

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var businessToken: String? = null

    @Volatile
    private var handlerInstalled = false

    // Rate limit + dedupe state (guarded by gateLock — report() can run on any thread).
    private val gateLock = Any()
    private val reportTimestamps = ArrayDeque<Long>()
    private val lastReportedAt = HashMap<String, Long>()

    /**
     * Private transport scope — NEVER the SDK's main scope, so a slow/broken delivery can
     * not stall or fail SDK work. Its handler only logs: routing reporter-internal errors
     * back through [report] would recurse.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            safeLog { Log.w(TAG, "Error reporter internal failure: ${t.message}") }
        }
    )

    /**
     * Same host the [io.bearound.sdk.network.APIClient] posts to. The base URL lives in
     * [SDKConfiguration.apiBaseURL] (a fixed value); a throwaway instance reads the single
     * source of truth instead of duplicating the literal here.
     */
    private val baseUrl: String by lazy {
        SDKConfiguration(businessToken = "", appId = "").apiBaseURL
    }

    /** Overridable transport for tests. Default posts the JSON body over HTTP. */
    internal var transport: (body: String, token: String) -> Unit = { body, token ->
        httpPost(body, token)
    }

    /**
     * [CoroutineExceptionHandler] for the SDK's own coroutine scopes: logs and reports the
     * failure, and does NOT rethrow — an SDK error must never crash the host app.
     */
    val coroutineExceptionHandler: CoroutineExceptionHandler =
        CoroutineExceptionHandler { _, throwable ->
            try {
                Log.w(TAG, "Unhandled SDK coroutine error: ${throwable.message}", throwable)
                report(throwable, "coroutine")
            } catch (_: Throwable) {
                // Never propagate anything out of the reporter.
            }
        }

    /**
     * Installs the reporter. Idempotent — safe to call on every `configure()`.
     *
     * Stores the application context + business token used for delivery and chains a
     * [Thread.setDefaultUncaughtExceptionHandler] that:
     * 1. only reports crashes whose stack contains SDK frames (excluding this package);
     * 2. ALWAYS delegates to the previously-installed handler (host crashes are never
     *    swallowed and host crash reporters keep working);
     * 3. runs its whole body inside try/catch.
     */
    fun install(context: Context, businessToken: String) {
        try {
            appContext = context.applicationContext
            this.businessToken = businessToken

            if (!handlerInstalled) {
                synchronized(this) {
                    if (!handlerInstalled) {
                        val previous = Thread.getDefaultUncaughtExceptionHandler()
                        if (previous !is SdkUncaughtExceptionHandler) {
                            Thread.setDefaultUncaughtExceptionHandler(
                                SdkUncaughtExceptionHandler(previous)
                            )
                        }
                        handlerInstalled = true
                    }
                }
            }
        } catch (t: Throwable) {
            safeLog { Log.w(TAG, "Error telemetry install failed: ${t.message}") }
        }
    }

    /** Enables/disables error reporting at runtime. Default: enabled. */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    /**
     * Reports an SDK error. Fire-and-forget: builds the payload synchronously (cheap,
     * exception-proof) and delivers it on the reporter's private IO scope. No-ops when
     * disabled, not installed, rate-limited, or recently deduped. Never throws.
     *
     * @param context origin of the error — "uncaught-handler", "coroutine" or the
     *   SDK component name (e.g. "BeaconScanService.onStartCommand").
     */
    fun report(throwable: Throwable, context: String) {
        try {
            if (!enabled) return
            val ctx = appContext ?: return
            val token = businessToken ?: return
            if (token.isBlank()) return

            val stack = stackTraceOf(throwable)
            val hash = computeHash(throwable.javaClass.name, context, stack)
            if (!shouldReport(hash)) return

            val body = buildPayload(throwable, context, stack, ctx).toString()
            scope.launch { safeSend(body, token) }
        } catch (t: Throwable) {
            // GOLDEN RULE: reporting must never throw into the host.
            safeLog { Log.w(TAG, "Error report skipped: ${t.message}") }
        }
    }

    // region internals (exposed as internal for unit tests)

    /**
     * Chained default uncaught-exception handler. Reports SDK-originated crashes and
     * ALWAYS delegates to [previous] — the host's crash flow is untouched.
     */
    private class SdkUncaughtExceptionHandler(
        private val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            try {
                if (isFromSdk(throwable)) {
                    report(throwable, "uncaught-handler")
                }
            } catch (_: Throwable) {
                // Never let telemetry interfere with the host's crash handling.
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    /**
     * True when the stack trace (or a cause up to [MAX_CAUSE_DEPTH] deep) contains a frame
     * from the SDK package — excluding this telemetry package, so reporter-internal errors
     * are never classified as SDK crashes.
     */
    internal fun isFromSdk(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            val hit = current.stackTrace.any { frame ->
                frame.className.startsWith(SDK_PACKAGE_PREFIX) &&
                    !frame.className.startsWith(TELEMETRY_PACKAGE_PREFIX)
            }
            if (hit) return true
            current = current.cause
            depth++
        }
        return false
    }

    /** Full stack trace string, truncated to [MAX_STACK_CHARS] chars. */
    internal fun stackTraceOf(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        val stack = writer.toString()
        return if (stack.length > MAX_STACK_CHARS) stack.substring(0, MAX_STACK_CHARS) else stack
    }

    /** sha256 hex of `type|context|first line of the stack trace` — the dedupe key. */
    internal fun computeHash(type: String, context: String, stackTrace: String): String {
        val firstLine = stackTrace.lineSequence().firstOrNull()?.trim().orEmpty()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$type|$context|$firstLine".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Combined dedupe + rate-limit gate. Returns true when this hash may be reported now
     * (and records the attempt). Thread-safe.
     */
    internal fun shouldReport(hash: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        synchronized(gateLock) {
            val last = lastReportedAt[hash]
            if (last != null && nowMs - last < DEDUPE_WINDOW_MS) return false

            while (reportTimestamps.isNotEmpty() && nowMs - reportTimestamps.peekFirst() > RATE_WINDOW_MS) {
                reportTimestamps.pollFirst()
            }
            if (reportTimestamps.size >= MAX_REPORTS_PER_HOUR) return false

            reportTimestamps.addLast(nowMs)
            lastReportedAt[hash] = nowMs

            if (lastReportedAt.size > DEDUPE_MAP_PRUNE_SIZE) {
                val iterator = lastReportedAt.entries.iterator()
                while (iterator.hasNext()) {
                    if (nowMs - iterator.next().value > DEDUPE_WINDOW_MS) iterator.remove()
                }
            }
            return true
        }
    }

    /** Builds the `/sdk-errors` payload. Every device probe is individually exception-proof. */
    internal fun buildPayload(
        throwable: Throwable,
        errorContext: String,
        stackTrace: String,
        context: Context
    ): JSONObject {
        val profile = try {
            OemPowerProfile.get()
        } catch (_: Throwable) {
            null
        }

        val error = JSONObject().apply {
            put("type", throwable.javaClass.name)
            put("message", throwable.message ?: "")
            put("stackTrace", stackTrace)
            put("context", errorContext)
        }

        val device = JSONObject().apply {
            put("deviceId", safeDeviceId(context))
            put("model", Build.MODEL ?: "unknown")
            put("manufacturer", Build.MANUFACTURER ?: "unknown")
            put("os", "android")
            put("osVersion", Build.VERSION.RELEASE ?: "unknown")
            put("osApiLevel", Build.VERSION.SDK_INT)
            put("rom", profile?.rom ?: JSONObject.NULL)
            put("romVersion", profile?.romVersion ?: JSONObject.NULL)
            put("locale", Locale.getDefault().toLanguageTag())
            batteryLevel(context)?.let { put("batteryLevel", it) }
            currentAppState()?.let { put("appState", it) }
        }

        val sdk = JSONObject().apply {
            put("version", BuildConfig.SDK_VERSION)
            put("platform", "android")
            put("appId", context.packageName ?: "unknown")
        }

        return JSONObject().apply {
            put("error", error)
            put("device", device)
            put("sdk", sdk)
            put("occurredAt", isoUtcNow())
        }
    }

    /** Runs the transport with every failure swallowed — delivery is best-effort. */
    internal fun safeSend(body: String, token: String) {
        try {
            transport(body, token)
        } catch (t: Throwable) {
            safeLog { Log.w(TAG, "Error report delivery failed: ${t.message}") }
        }
    }

    private fun httpPost(body: String, token: String) {
        val connection = URL(baseUrl + ENDPOINT_PATH).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", token)
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            // Fire-and-forget: read the code to complete the exchange, ignore the body.
            val code = connection.responseCode
            Log.d(TAG, "Error report sent (HTTP $code)")
        } finally {
            connection.disconnect()
        }
    }

    private fun safeDeviceId(context: Context): String = try {
        DeviceIdentifier.getDeviceId(context)
    } catch (_: Throwable) {
        "unknown"
    }

    private fun batteryLevel(context: Context): Int? = try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
    } catch (_: Throwable) {
        null
    }

    /**
     * Cheap foreground/background probe via [ProcessLifecycleOwner] (already an SDK
     * dependency). Omitted (null) when the lifecycle is unavailable — e.g. very early in
     * process start.
     */
    private fun currentAppState(): String? = try {
        val foreground = ProcessLifecycleOwner.get()
            .lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (foreground) "foreground" else "background"
    } catch (_: Throwable) {
        null
    }

    private fun isoUtcNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    private inline fun safeLog(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // Even logging must not throw (Log is not mocked in plain JVM tests).
        }
    }

    /** Test hook: clears gate state and configuration so tests are independent. */
    internal fun resetForTest() {
        synchronized(gateLock) {
            reportTimestamps.clear()
            lastReportedAt.clear()
        }
        enabled = true
        appContext = null
        businessToken = null
        transport = { body, token -> httpPost(body, token) }
    }

    // endregion
}
