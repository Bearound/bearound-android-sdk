package io.bearound.sdk.models

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [BeAroundDiagnostics.summary] — the human-readable rendering used
 * in logs and support tickets.
 */
class BeAroundDiagnosticsTest {

    private fun sample(
        recentErrors: List<String> = emptyList(),
        pushTokenMasked: String? = "abcd1234…wxyz",
        pushTokenLastSentAt: Long? = 1_700_000_000_000L,
    ) = BeAroundDiagnostics(
        deviceId = "device-123",
        pushTokenMasked = pushTokenMasked,
        pushTokenLastSentAt = pushTokenLastSentAt,
        isScanning = true,
        pendingBatches = 4,
        lastScanAt = 1_700_000_111_000L,
        lastScanBeaconCount = 9,
        lastSyncAt = 1_700_000_222_000L,
        lastSyncSuccess = true,
        lastSyncBeaconCount = 8,
        recentErrors = recentErrors,
        sdkVersion = 34,
    )

    @Test
    fun `summary contains the key fields`() {
        val text = sample().summary()

        assertTrue("header present", text.contains("BeAround Diagnostics"))
        assertTrue("deviceId present", text.contains("deviceId: device-123"))
        assertTrue("masked token present", text.contains("pushTokenMasked: abcd1234…wxyz"))
        assertTrue("pendingBatches present", text.contains("pendingBatches: 4"))
        assertTrue("isScanning present", text.contains("isScanning: true"))
        assertTrue("sdkVersion present", text.contains("sdkVersion (OS API): 34"))
        assertTrue("lastSyncSuccess present", text.contains("lastSyncSuccess: true"))
    }

    @Test
    fun `summary renders none for empty errors branch`() {
        val text = sample(recentErrors = emptyList()).summary()

        assertTrue("recentErrors header present", text.contains("recentErrors:"))
        assertTrue("empty branch renders (none)", text.contains("(none)"))
    }

    @Test
    fun `summary lists each error in the with-errors branch`() {
        val errors = listOf("1700000000000 | first failure", "1700000005000 | second failure")
        val text = sample(recentErrors = errors).summary()

        assertTrue("must not show (none) when errors exist", !text.contains("(none)"))
        errors.forEach { e ->
            assertTrue("error line present: $e", text.contains("  - $e"))
        }
    }

    @Test
    fun `summary renders fallbacks when token and timestamp are absent`() {
        val text = sample(pushTokenMasked = null, pushTokenLastSentAt = null).summary()

        assertTrue("null token renders as none", text.contains("pushTokenMasked: none"))
        assertTrue("null lastSentAt renders as never", text.contains("pushTokenLastSentAt: never"))
    }
}
