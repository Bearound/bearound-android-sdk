package io.bearound.sdk.utilities

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests the TTL + fingerprint logic of [RegisterStore].
 *
 * [RegisterStore] is backed by a named [android.content.SharedPreferences] file, so each test
 * calls [RegisterStore.clear] in [setUp] to start from a clean state.
 *
 * NOTE: This test suite requires the Android + Robolectric test infrastructure. It CANNOT be
 * compiled or executed in the current environment (no Android SDK or JDK present). It is
 * provided as the specification for the behaviour of [RegisterStore] and must be run in a
 * standard Android Gradle test task (`./gradlew :sdk:test`).
 */
@RunWith(RobolectricTestRunner::class)
class RegisterStoreTest {

    private lateinit var context: Context

    // A reusable sample fingerprint; components themselves are not validated by RegisterStore.
    private val fingerprint = "device-id-001|com.example.app|tok-abc|3.4.0|14|42"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RegisterStore.clear(context)
    }

    // region shouldRegister

    @Test
    fun `shouldRegister returns true when never registered`() {
        assertTrue(RegisterStore.shouldRegister(context, fingerprint))
    }

    @Test
    fun `shouldRegister returns false immediately after markRegistered with same fingerprint`() {
        RegisterStore.markRegistered(context, fingerprint)

        assertFalse(RegisterStore.shouldRegister(context, fingerprint))
    }

    @Test
    fun `shouldRegister returns true when fingerprint changes`() {
        RegisterStore.markRegistered(context, fingerprint)

        val newFingerprint = "device-id-001|com.example.app|tok-abc|3.5.0|14|43"
        assertTrue(
            "fingerprint change must force re-register",
            RegisterStore.shouldRegister(context, newFingerprint)
        )
    }

    @Test
    fun `shouldRegister returns true after TTL has elapsed`() {
        RegisterStore.markRegistered(context, fingerprint)
        assertFalse("just registered — should be false", RegisterStore.shouldRegister(context, fingerprint))

        // Simulate time passing beyond TTL by directly overwriting the persisted timestamp.
        // We write a value older than 24 h into the shared preferences that RegisterStore reads.
        val pastTimestamp = System.currentTimeMillis() - (RegisterStore.TTL_MS + 1_000L)
        context.getSharedPreferences("bearound_sdk_register", Context.MODE_PRIVATE)
            .edit()
            .putLong("last_register_at", pastTimestamp)
            .apply()

        assertTrue(
            "should register again after TTL expired",
            RegisterStore.shouldRegister(context, fingerprint)
        )
    }

    @Test
    fun `shouldRegister returns false when fingerprint unchanged and TTL not yet elapsed`() {
        RegisterStore.markRegistered(context, fingerprint)

        // Advance time by less than the TTL.
        val recentTimestamp = System.currentTimeMillis() - (RegisterStore.TTL_MS - 60_000L)
        context.getSharedPreferences("bearound_sdk_register", Context.MODE_PRIVATE)
            .edit()
            .putLong("last_register_at", recentTimestamp)
            .apply()

        assertFalse(RegisterStore.shouldRegister(context, fingerprint))
    }

    // endregion

    // region markRegistered

    @Test
    fun `markRegistered persists a non-null lastRegisterAt`() {
        assertNull("no registration yet", RegisterStore.lastRegisterAt(context))

        val before = System.currentTimeMillis()
        RegisterStore.markRegistered(context, fingerprint)
        val after = System.currentTimeMillis()

        val at = RegisterStore.lastRegisterAt(context)
        assertNotNull("lastRegisterAt must be set after markRegistered", at)
        assertTrue("timestamp should be within the call window", at!! in before..after)
    }

    @Test
    fun `markRegistered updates timestamp on subsequent calls`() {
        RegisterStore.markRegistered(context, fingerprint)
        val first = RegisterStore.lastRegisterAt(context)

        // Small sleep to guarantee the clock advances (millisecond resolution).
        Thread.sleep(2)

        RegisterStore.markRegistered(context, fingerprint)
        val second = RegisterStore.lastRegisterAt(context)

        assertNotNull(first)
        assertNotNull(second)
        assertTrue("second markRegistered should record a later timestamp", second!! >= first!!)
    }

    // endregion

    // region buildFingerprint

    @Test
    fun `buildFingerprint produces a pipe-separated string of all six components`() {
        val fp = RegisterStore.buildFingerprint(
            deviceId = "dev-1",
            appId = "com.test",
            businessToken = "tok-xyz",
            sdkVersion = "3.4.0",
            osVersion = "14",
            appBuild = 77
        )

        assertEquals("dev-1|com.test|tok-xyz|3.4.0|14|77", fp)
    }

    @Test
    fun `buildFingerprint differs when any component changes`() {
        val base = RegisterStore.buildFingerprint("d", "app", "tok", "1.0", "13", 1)

        val changed = listOf(
            RegisterStore.buildFingerprint("d2", "app", "tok", "1.0", "13", 1),
            RegisterStore.buildFingerprint("d", "app2", "tok", "1.0", "13", 1),
            RegisterStore.buildFingerprint("d", "app", "tok2", "1.0", "13", 1),
            RegisterStore.buildFingerprint("d", "app", "tok", "2.0", "13", 1),
            RegisterStore.buildFingerprint("d", "app", "tok", "1.0", "14", 1),
            RegisterStore.buildFingerprint("d", "app", "tok", "1.0", "13", 2)
        )

        changed.forEach { fp ->
            assertTrue("fingerprint '$fp' must differ from '$base'", fp != base)
        }
    }

    // endregion

    // region clear

    @Test
    fun `clear resets state so shouldRegister returns true`() {
        RegisterStore.markRegistered(context, fingerprint)
        assertFalse(RegisterStore.shouldRegister(context, fingerprint))

        RegisterStore.clear(context)

        assertTrue("after clear, shouldRegister must return true", RegisterStore.shouldRegister(context, fingerprint))
        assertNull("after clear, lastRegisterAt must be null", RegisterStore.lastRegisterAt(context))
    }

    // endregion
}
