package io.bearound.sdk.telemetry

import androidx.test.core.app.ApplicationProvider
import io.bearound.sdk.BuildConfig
import io.bearound.sdk.utilities.SecureStorage
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests for [ErrorReporter] — the isolated SDK error telemetry.
 *
 * The reporter is a singleton object; [ErrorReporter.resetForTest] restores gate state,
 * enabled flag, configuration and transport between tests so they stay independent.
 * Robolectric supplies the Android Context needed by the payload builder (Build.*,
 * BatteryManager, DeviceIdentifier via SecureStorage).
 */
@RunWith(RobolectricTestRunner::class)
class ErrorReporterTest {

    private val token = "business-token-test-0001"

    @Before
    fun setUp() {
        ErrorReporter.resetForTest()
        SecureStorage.initialize(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        ErrorReporter.resetForTest()
    }

    // region hash

    @Test
    fun `hash is stable for identical inputs`() {
        val stack = "java.lang.SecurityException: denied\n\tat io.bearound.sdk.X.y(X.kt:1)"
        val a = ErrorReporter.computeHash("java.lang.SecurityException", "coroutine", stack)
        val b = ErrorReporter.computeHash("java.lang.SecurityException", "coroutine", stack)

        assertEquals(a, b)
        assertTrue("hash must be sha256 hex (64 chars)", a.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun `hash only depends on the FIRST line of the stack trace`() {
        val first = "java.lang.SecurityException: denied"
        val a = ErrorReporter.computeHash("T", "c", "$first\n\tat frame.One(A.kt:1)")
        val b = ErrorReporter.computeHash("T", "c", "$first\n\tat frame.Other(B.kt:99)")

        assertEquals("different tails, same first line -> same hash", a, b)
    }

    @Test
    fun `hash changes when type, context or first line change`() {
        val base = ErrorReporter.computeHash("TypeA", "ctx", "line1\nline2")

        assertNotEquals(base, ErrorReporter.computeHash("TypeB", "ctx", "line1\nline2"))
        assertNotEquals(base, ErrorReporter.computeHash("TypeA", "other", "line1\nline2"))
        assertNotEquals(base, ErrorReporter.computeHash("TypeA", "ctx", "changed\nline2"))
    }

    // endregion

    // region rate limiter + dedupe

    @Test
    fun `rate limiter allows at most 20 reports per rolling hour`() {
        val now = 1_000_000L
        var allowed = 0
        repeat(25) { i ->
            if (ErrorReporter.shouldReport("hash-$i", now + i)) allowed++
        }

        assertEquals(20, allowed)
    }

    @Test
    fun `rate limiter recovers after the hour window rolls over`() {
        val now = 1_000_000L
        repeat(20) { i -> ErrorReporter.shouldReport("hash-$i", now) }
        assertFalse("window is full", ErrorReporter.shouldReport("hash-late", now + 1))

        val afterWindow = now + 60L * 60L * 1000L + 1
        assertTrue(
            "old timestamps expired -> reporting allowed again",
            ErrorReporter.shouldReport("hash-after", afterWindow)
        )
    }

    @Test
    fun `same hash is deduped for 5 minutes`() {
        val now = 1_000_000L
        assertTrue(ErrorReporter.shouldReport("dup", now))
        assertFalse("same hash right away", ErrorReporter.shouldReport("dup", now + 1))
        assertFalse("same hash within 5 min", ErrorReporter.shouldReport("dup", now + 4 * 60 * 1000L))
        assertTrue("after 5 min the hash may fire again", ErrorReporter.shouldReport("dup", now + 5 * 60 * 1000L + 1))
    }

    @Test
    fun `different hashes are not deduped against each other`() {
        val now = 1_000_000L
        assertTrue(ErrorReporter.shouldReport("hash-a", now))
        assertTrue(ErrorReporter.shouldReport("hash-b", now))
    }

    // endregion

    // region package filter

    private fun exceptionWithFrames(vararg classNames: String): Exception =
        Exception("test").apply {
            stackTrace = classNames.map { cn ->
                StackTraceElement(cn, "method", "File.kt", 1)
            }.toTypedArray()
        }

    @Test
    fun `stack containing an SDK frame is classified as from-SDK`() {
        val e = exceptionWithFrames(
            "android.app.ActivityThread",
            "io.bearound.sdk.BeaconManager",
            "java.lang.Thread"
        )

        assertTrue(ErrorReporter.isFromSdk(e))
    }

    @Test
    fun `stack without SDK frames is NOT from-SDK`() {
        val e = exceptionWithFrames("com.example.host.MainActivity", "android.os.Handler")

        assertFalse(ErrorReporter.isFromSdk(e))
    }

    @Test
    fun `telemetry-only frames do NOT count as SDK frames`() {
        // Reporter-internal errors must never be classified as SDK crashes.
        val e = exceptionWithFrames("io.bearound.sdk.telemetry.ErrorReporter", "java.lang.Thread")

        assertFalse(ErrorReporter.isFromSdk(e))
    }

    @Test
    fun `SDK frame in the cause chain is detected`() {
        val cause = exceptionWithFrames("io.bearound.sdk.background.BackgroundScanManager")
        val wrapper = Exception("wrapper", cause).apply {
            stackTrace = arrayOf(StackTraceElement("com.example.host.App", "run", "App.kt", 1))
        }

        assertTrue(ErrorReporter.isFromSdk(wrapper))
    }

    // endregion

    // region stack truncation

    @Test
    fun `stack trace is truncated to 8000 chars`() {
        val huge = Exception("x".repeat(20_000))

        val stack = ErrorReporter.stackTraceOf(huge)

        assertEquals(8000, stack.length)
    }

    @Test
    fun `short stack trace is kept intact`() {
        val e = Exception("boom")

        val stack = ErrorReporter.stackTraceOf(e)

        assertTrue(stack.length < 8000)
        assertTrue(stack.startsWith("java.lang.Exception: boom"))
    }

    // endregion

    // region payload contract

    @Test
    fun `payload carries the contract fields`() {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        ErrorReporter.install(appContext, token)

        val e = SecurityException("Nearby devices denied")
        val stack = ErrorReporter.stackTraceOf(e)
        val payload = ErrorReporter.buildPayload(e, "BeaconScanService.onStartCommand", stack, appContext)

        val error = payload.getJSONObject("error")
        assertEquals("java.lang.SecurityException", error.getString("type"))
        assertEquals("Nearby devices denied", error.getString("message"))
        assertEquals(stack, error.getString("stackTrace"))
        assertEquals("BeaconScanService.onStartCommand", error.getString("context"))

        val device = payload.getJSONObject("device")
        assertTrue(device.getString("deviceId").isNotBlank())
        assertTrue(device.getString("model").isNotBlank())
        assertTrue(device.getString("manufacturer").isNotBlank())
        assertEquals("android", device.getString("os"))
        assertTrue(device.getString("osVersion").isNotBlank())
        assertTrue(device.getInt("osApiLevel") > 0)
        assertTrue("rom key present (nullable)", device.has("rom"))
        assertTrue("romVersion key present (nullable)", device.has("romVersion"))
        assertTrue(device.getString("locale").isNotBlank())

        val sdk = payload.getJSONObject("sdk")
        assertEquals(BuildConfig.SDK_VERSION, sdk.getString("version"))
        assertEquals("android", sdk.getString("platform"))
        assertEquals(appContext.packageName, sdk.getString("appId"))

        assertTrue(
            "occurredAt must be ISO-8601 UTC",
            payload.getString("occurredAt").matches(Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$"""))
        )
    }

    @Test
    fun `device carries a permissions snapshot with the expected keys and vocabulary`() {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        ErrorReporter.install(appContext, token)

        val payload = ErrorReporter.buildPayload(
            SecurityException("x"),
            "BeaconScanService.onStartCommand",
            "stack",
            appContext
        )
        val permissions = payload.getJSONObject("device").getJSONObject("permissions")

        val expectedKeys = listOf(
            "bluetoothScan", "bluetoothConnect", "bluetoothAdvertise",
            "fineLocation", "coarseLocation", "backgroundLocation", "postNotifications"
        )
        val vocabulary = setOf("granted", "denied", "not_applicable")
        expectedKeys.forEach { key ->
            assertTrue("permission key '$key' present", permissions.has(key))
            assertTrue(
                "permission '$key' value must be in the granted/denied/not_applicable vocabulary",
                permissions.getString(key) in vocabulary
            )
        }
    }

    @Test
    fun `device carries a systemState snapshot`() {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        ErrorReporter.install(appContext, token)

        val payload = ErrorReporter.buildPayload(
            Exception("x"),
            "coroutine",
            "stack",
            appContext
        )
        val device = payload.getJSONObject("device")

        assertTrue("systemState object present", device.has("systemState"))
        val systemState = device.getJSONObject("systemState")
        // All fields are optional/nullable; whichever are present must be booleans.
        listOf(
            "bluetoothEnabled", "locationServicesEnabled", "notificationsEnabled",
            "ignoringBatteryOptimizations", "powerSaveMode", "foregroundServiceActive"
        ).forEach { key ->
            if (systemState.has(key)) {
                // getBoolean throws if the value is not a boolean -> asserts the type.
                systemState.getBoolean(key)
            }
        }
    }

    @Test
    fun `a throwing permission getter does not break the report`() {
        // A Context whose permission/service probes all throw exercises every try/catch in the
        // snapshot helpers. The report must still be built and still carry the snapshots.
        val real = ApplicationProvider.getApplicationContext<android.content.Context>()
        val hostile = object : android.content.ContextWrapper(real) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                throw RuntimeException("permission probe blew up")

            override fun checkSelfPermission(permission: String): Int =
                throw RuntimeException("permission probe blew up")

            override fun getSystemService(name: String): Any? =
                throw RuntimeException("service probe blew up")
        }
        ErrorReporter.install(real, token)

        val payload = ErrorReporter.buildPayload(
            SecurityException("boom"),
            "BeaconScanService.onStartCommand",
            "stack",
            hostile
        )

        val device = payload.getJSONObject("device")
        val permissions = device.getJSONObject("permissions")
        // Every permission fell back to not_applicable instead of throwing.
        assertEquals("not_applicable", permissions.getString("fineLocation"))
        assertEquals("not_applicable", permissions.getString("bluetoothScan"))
        // systemState is present (possibly with fields omitted) and the report did not throw.
        assertTrue(device.has("systemState"))
    }

    @Test
    fun `payload stack trace respects the 8000 char cap`() {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        ErrorReporter.install(appContext, token)

        val huge = Exception("y".repeat(20_000))
        val stack = ErrorReporter.stackTraceOf(huge)
        val payload = ErrorReporter.buildPayload(huge, "coroutine", stack, appContext)

        assertEquals(8000, payload.getJSONObject("error").getString("stackTrace").length)
    }

    // endregion

    // region delivery + never-throw guarantee

    @Test
    fun `report delivers the payload through the transport`() {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        ErrorReporter.install(appContext, token)

        val latch = CountDownLatch(1)
        val sentBody = AtomicReference<String>()
        val sentToken = AtomicReference<String>()
        ErrorReporter.transport = { body, authToken ->
            sentBody.set(body)
            sentToken.set(authToken)
            latch.countDown()
        }

        ErrorReporter.report(Exception("delivery test"), "BeaconManager.startScanning")

        assertTrue("transport should be invoked", latch.await(5, TimeUnit.SECONDS))
        assertEquals(token, sentToken.get())
        val json = JSONObject(sentBody.get())
        assertEquals("delivery test", json.getJSONObject("error").getString("message"))
        assertEquals("BeaconManager.startScanning", json.getJSONObject("error").getString("context"))
    }

    @Test
    fun `report with failing transport does not throw`() {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        ErrorReporter.install(appContext, token)
        ErrorReporter.transport = { _, _ -> throw RuntimeException("network down") }

        // Must not propagate — fire-and-forget with all failures swallowed.
        ErrorReporter.report(Exception("boom"), "coroutine")

        // And the synchronous send wrapper itself must swallow transport failures.
        ErrorReporter.safeSend("{}", token)
    }

    @Test
    fun `report before install is a silent no-op`() {
        val latch = CountDownLatch(1)
        ErrorReporter.transport = { _, _ -> latch.countDown() }

        ErrorReporter.report(Exception("no context yet"), "coroutine")

        assertFalse("nothing must be sent without install()", latch.await(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `report when disabled does not deliver`() {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        ErrorReporter.install(appContext, token)

        val latch = CountDownLatch(1)
        ErrorReporter.transport = { _, _ -> latch.countDown() }
        ErrorReporter.setEnabled(false)

        ErrorReporter.report(Exception("disabled"), "coroutine")

        assertFalse("disabled reporter must not send", latch.await(300, TimeUnit.MILLISECONDS))
    }

    // endregion
}
