package io.bearound.sdk.utilities

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests the heartbeat delivery logic of [PushTokenStore].
 *
 * [PushTokenStore] is backed by [SecureStorage] (EncryptedSharedPreferences), which needs
 * an Android [android.content.Context]; Robolectric supplies one. Each test re-initializes
 * [SecureStorage] against a fresh Robolectric application and clears the persisted keys, so
 * the tests are independent despite both objects being singletons.
 */
@RunWith(RobolectricTestRunner::class)
class PushTokenStoreTest {

    // Mirror of the keys PushTokenStore reads/writes (private consts there). Only used to
    // simulate elapsed time by writing LAST_SENT_AT directly — the store reads it back.
    private val tokenKey = "io.bearound.sdk.pushToken"
    private val lastSentKey = "io.bearound.sdk.pushTokenLastSent"
    private val lastSentAtKey = "io.bearound.sdk.pushTokenLastSentAt"

    private val token = "fcm-token-abcdefghijklmnop-0001"

    @Before
    fun setUp() {
        SecureStorage.initialize(ApplicationProvider.getApplicationContext())
        // Ensure a clean slate even though Robolectric gives a fresh app per test.
        SecureStorage.delete(tokenKey)
        SecureStorage.delete(lastSentKey)
        SecureStorage.delete(lastSentAtKey)
    }

    @Test
    fun `never-sent token is returned by tokenForPayload`() {
        PushTokenStore.setToken(token)

        assertEquals(token, PushTokenStore.tokenForPayload())
    }

    @Test
    fun `tokenForPayload returns null when there is no token at all`() {
        assertNull(PushTokenStore.tokenForPayload())
    }

    @Test
    fun `after markSent the same token is no longer offered`() {
        PushTokenStore.setToken(token)
        PushTokenStore.markSent()

        assertNull("an already-sent, unchanged token should not be re-sent", PushTokenStore.tokenForPayload())
    }

    @Test
    fun `a changed token is offered again`() {
        PushTokenStore.setToken(token)
        PushTokenStore.markSent()
        assertNull(PushTokenStore.tokenForPayload())

        val rotated = "fcm-token-ZZZZZZZZZZZZZZZZ-9999"
        PushTokenStore.setToken(rotated)

        assertEquals(rotated, PushTokenStore.tokenForPayload())
    }

    @Test
    fun `heartbeat re-sends an unchanged token after more than 7 days`() {
        PushTokenStore.setToken(token)
        PushTokenStore.markSent()
        assertNull("fresh send should suppress immediate re-send", PushTokenStore.tokenForPayload())

        // Simulate the last successful send being 8 days ago by rewriting LAST_SENT_AT,
        // which tokenForPayload reads back. The 7-day window (604800000 ms) is now exceeded.
        val eightDaysAgo = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000
        SecureStorage.save(lastSentAtKey, eightDaysAgo.toString())

        assertEquals(token, PushTokenStore.tokenForPayload())
    }

    @Test
    fun `markSent records the sent token and a timestamp`() {
        assertNull(PushTokenStore.lastSentAt())

        val before = System.currentTimeMillis()
        PushTokenStore.setToken(token)
        PushTokenStore.markSent()
        val after = System.currentTimeMillis()

        val at = PushTokenStore.lastSentAt()
        assertNotNull("lastSentAt should be set after markSent", at)
        assertEquals(true, at!! in before..after)
    }

    @Test
    fun `maskedToken shows first 8 and last 4 for a long token`() {
        PushTokenStore.setToken(token)

        // token = "fcm-token-abcdefghijklmnop-0001" -> "fcm-toke…0001"
        assertEquals("fcm-toke…0001", PushTokenStore.maskedToken())
    }

    @Test
    fun `maskedToken hides short tokens wholesale`() {
        PushTokenStore.setToken("short12")

        assertEquals("…", PushTokenStore.maskedToken())
    }

    @Test
    fun `maskedToken is null when no token stored`() {
        assertNull(PushTokenStore.maskedToken())
    }
}
