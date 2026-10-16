package io.bearound.bearoundscan.testing

import android.os.Bundle
import io.bearound.sdk.models.AdaptiveSamplingDiagnostics
import io.bearound.sdk.utilities.DiagnosticsStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AdaptiveBenchBootstrapTest {
    @Before
    fun resetDiagnostics() {
        DiagnosticsStore.reset()
    }

    private fun call(
        enabled: Boolean = true,
        caller: Int = 12345,
        method: String = "sampling-diagnostics",
        argument: String? = null,
        extras: Bundle? = null,
        read: () -> AdaptiveSamplingDiagnostics = DiagnosticsStore::sampling
    ): JSONObject = JSONObject(requireNotNull(AdaptiveBenchDiagnosticCall.snapshot(
        method, argument, extras, enabled, caller, 12345, read
    ).getString("json")))

    @Test
    fun `same UID receives only allowlisted existing sampling fields`() {
        var reads = 0
        val expected = AdaptiveSamplingDiagnostics(state = "stationary_inferred", source = "context_fusion",
            confidence = "medium", reason = "context_stable", evidenceAgeMs = 9000, contextStable = true,
            capabilityStatus = "active", contextIntervalMs = 300000, summaryIntervalMs = 300000,
            liveIntervalMs = 5000, uniqueSamples = 3000000000, contextReads = 12,
            clockUncertaintyMs = 1.5, mode = "separated", nextContextReadElapsedMs = 400000, httpAttempts = 2)
        val result = call(read = { reads++; expected })

        val keys = result.keys().asSequence().toSet()
        assertEquals(setOf("state", "source", "confidence", "reason", "evidenceAgeMs", "contextStable",
            "capabilityStatus", "contextIntervalMs", "summaryIntervalMs", "liveIntervalMs", "uniqueSamples",
            "cachedObservations", "contextReads", "bucketCount", "gapCount", "liveAttempts", "summaryAttempts",
            "transitionAttempts", "serializedBytes", "httpAttempts", "failures", "retryLagMs", "archiveOnlyCount", "policyDenials",
            "clockUncertaintyMs", "mode", "nextContextReadElapsedMs", "nextSummaryElapsedMs",
            "nextLiveAttemptElapsedMs", "manifestGapCount"), keys)
        assertEquals(1, reads)
        assertEquals("stationary_inferred", result.getString("state"))
        assertEquals(3000000000L, result.getLong("uniqueSamples"))
        assertEquals(12L, result.getLong("contextReads"))
        assertEquals(2L, result.getLong("httpAttempts"))
        assertEquals(1.5, result.getDouble("clockUncertaintyMs"), 0.0)
        assertEquals(400000L, result.getLong("nextContextReadElapsedMs"))
        assertTrue(result.getBoolean("contextStable"))
        assertFalse(result.has("deviceId"))
        assertFalse(result.has("location"))
        assertFalse(result.has("pushTokenMasked"))
    }

    @Test
    fun `another UID is rejected before accessing diagnostics`() {
        assertThrows(SecurityException::class.java) {
            call(caller = 20001, read = { error("Diagnostics must not be read") })
        }
    }

    @Test
    fun `disabled bench is rejected even for the application UID`() {
        assertThrows(SecurityException::class.java) {
            call(enabled = false, read = { error("Diagnostics must not be read") })
        }
    }

    @Test
    fun `disabled bench is rejected for the trusted ADB shell UID`() {
        assertThrows(SecurityException::class.java) {
            call(enabled = false, caller = 2000, read = { error("Diagnostics must not be read") })
        }
    }

    @Test
    fun `trusted shell cannot mutate state through unsupported calls`() {
        assertThrows(IllegalArgumentException::class.java) {
            call(caller = 2000, method = "reset", read = { error("Diagnostics must not be read") })
        }
    }

    @Test
    fun `unsupported method is rejected without reading or mutating diagnostics`() {
        assertThrows(IllegalArgumentException::class.java) {
            call(method = "reset", read = { error("Diagnostics must not be read") })
        }
    }

    @Test
    fun `arguments cannot inject sampling state`() {
        assertThrows(IllegalArgumentException::class.java) {
            call(argument = "stationary_confirmed", read = { error("Diagnostics must not be read") })
        }
    }

    @Test
    fun `extras cannot inject counters`() {
        val extras = Bundle().apply { putLong("contextReads", 100) }
        assertThrows(IllegalArgumentException::class.java) {
            call(extras = extras, read = { error("Diagnostics must not be read") })
        }
    }

    @Test
    fun `empty content call extras are accepted without collecting new context`() {
        val result = call(extras = Bundle())
        assertEquals(0L, result.getLong("contextReads"))
        assertTrue(result.isNull("contextIntervalMs"))
        assertTrue(result.isNull("clockUncertaintyMs"))
    }

    @Test
    fun `repeated snapshots preserve counters and store state`() {
        DiagnosticsStore.recordObservation(unique = 7, cached = 3, contextRead = true)
        val before = DiagnosticsStore.sampling()
        val first = call()
        val second = call()

        assertEquals(before, DiagnosticsStore.sampling())
        assertEquals(first.toString(), second.toString())
        assertEquals(1L, second.getLong("contextReads"))
        assertEquals(7L, second.getLong("uniqueSamples"))
        assertEquals(3L, second.getLong("cachedObservations"))
    }

    @Test
    fun `snapshots preserve earlier measurements after new real observations`() {
        DiagnosticsStore.recordObservation(contextRead = true)
        val first = call()
        DiagnosticsStore.recordObservation(contextRead = true)
        val second = call()

        assertEquals(1L, first.getLong("contextReads"))
        assertEquals(2L, second.getLong("contextReads"))
    }

    @Test
    fun `provider uses the actual Binder application UID and existing diagnostic store`() {
        val context = RuntimeEnvironment.getApplication()
        val provider = AdaptiveBenchBootstrap()
        ReflectionHelpers.setField(provider, "mContext", context)
        ShadowBinder.setCallingUid(context.applicationInfo.uid)
        DiagnosticsStore.recordObservation(unique = 47, contextRead = true)
        val before = DiagnosticsStore.sampling()

        val result = JSONObject(requireNotNull(provider.call("sampling-diagnostics", null, null).getString("json")))

        assertEquals(47L, result.getLong("uniqueSamples"))
        assertEquals(1L, result.getLong("contextReads"))
        assertEquals(before, DiagnosticsStore.sampling())
    }

    @Test
    fun `provider allows the actual Binder shell UID to read existing counters`() {
        val context = RuntimeEnvironment.getApplication()
        val provider = AdaptiveBenchBootstrap()
        ReflectionHelpers.setField(provider, "mContext", context)
        ShadowBinder.setCallingUid(2000)
        DiagnosticsStore.recordObservation(unique = 11, contextRead = true)
        val before = DiagnosticsStore.sampling()

        val result = JSONObject(requireNotNull(provider.call("sampling-diagnostics", null, null).getString("json")))

        assertEquals(11L, result.getLong("uniqueSamples"))
        assertEquals(1L, result.getLong("contextReads"))
        assertEquals(before, DiagnosticsStore.sampling())
        assertFalse(result.has("deviceId"))
        assertFalse(result.has("location"))
        assertFalse(result.has("pushTokenMasked"))
    }

    @Test
    fun `provider rejects the actual Binder UID of another process`() {
        val context = RuntimeEnvironment.getApplication()
        val provider = AdaptiveBenchBootstrap()
        ReflectionHelpers.setField(provider, "mContext", context)
        ShadowBinder.setCallingUid(context.applicationInfo.uid + 1)

        assertThrows(SecurityException::class.java) {
            provider.call("sampling-diagnostics", null, null)
        }
        assertEquals(AdaptiveSamplingDiagnostics(), DiagnosticsStore.sampling())
    }
}
