package io.bearound.sdk.models

import io.bearound.sdk.visit.PlacesConfigClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** The SDK talks to one host, `apiBaseURL`. */
class SDKConfigurationTest {

    private val config = SDKConfiguration(businessToken = "token", appId = "app")

    @Test
    fun `adaptive rollback keeps existing scan and collection defaults`() {
        assertTrue(config.adaptiveSamplingEnabled)
        val rollback = config.copy(adaptiveSamplingEnabled = false)
        assertFalse(rollback.adaptiveSamplingEnabled)
        assertEquals(config.syncInterval, rollback.syncInterval)
        assertEquals(config.dataCollectionPolicy, rollback.dataCollectionPolicy)
        assertEquals(config.presenceHeartbeatIntervalMillis, rollback.presenceHeartbeatIntervalMillis)
    }

    @Test
    fun `apiBaseURL is the only host in the configuration`() {
        // Compare values, not field names: the release variant is minified.
        val urls = SDKConfiguration::class.java.declaredFields
            .filter { it.type == String::class.java }
            .mapNotNull { field -> field.isAccessible = true; field.get(config) as String? }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
        assertEquals(listOf(config.apiBaseURL), urls)
    }

    @Test
    fun `the places client uses apiBaseURL, the ingest host`() {
        val client = PlacesConfigClient.forConfiguration(config)

        assertEquals("https://ingest.bearound.io", config.apiBaseURL)
        assertEquals(config.apiBaseURL, client.baseURL)
        val url = PlacesConfigClient.requestUrl(client.baseURL, -23.5614, -46.6559)
        assertEquals("https://ingest.bearound.io/sdk/places/nearby?lat=-23.561&lng=-46.656", url)
        assertTrue(url.startsWith(config.apiBaseURL))
    }
    @Test
    fun `fixture endpoint resolver is used only by the Debug variant`() {
        val original = SDKConfiguration.endpointResolver
        var calls = 0
        try {
            SDKConfiguration.endpointResolver = { calls++; "https://127.0.0.1:18080" }
            val overridden = SDKConfiguration(businessToken = "fixture-token-configuration", appId = "fixture-app")
            assertEquals(if (io.bearound.sdk.BuildConfig.DEBUG) "https://127.0.0.1:18080" else "https://ingest.bearound.io", overridden.apiBaseURL)
            assertEquals(if (io.bearound.sdk.BuildConfig.DEBUG) 1 else 0, calls)
            assertEquals(overridden.apiBaseURL, PlacesConfigClient.forConfiguration(overridden).baseURL)
        } finally {
            SDKConfiguration.endpointResolver = original
        }
    }

    @Test fun `appended nullable motion override preserves old positional calls and copy auto semantics`() {
        assertTrue(config.dataCollectionPolicy.motionSensorsEnabled)
        assertFalse(config.copy(collectLocation = false, collectWifi = false).dataCollectionPolicy.motionSensorsEnabled)
        assertTrue(config.copy(collectLocation = false, collectWifi = false, collectMotionSensors = true).dataCollectionPolicy.motionSensorsEnabled)
        assertFalse(config.copy(collectMotionSensors = false).dataCollectionPolicy.motionSensorsEnabled)
    }

    @Test fun `published app presence positional argument remains before appended adaptive controls`() {
        val presence = AppPresenceConfiguration(true, listOf(AppPresenceTarget("fixture", androidPackageName = "io.bearound.fixture")))
        val positional = SDKConfiguration("fixture-token", "fixture", ScanPrecision.MEDIUM,
            MaxQueuedPayloads.MEDIUM, "android-native", true, 1_200_000L, 10_000L,
            300_000L, true, false, false, presence)

        assertEquals(presence, positional.appPresence)
        assertTrue(positional.adaptiveSamplingEnabled)
        assertEquals(null, positional.collectMotionSensors)
        assertFalse(positional.dataCollectionPolicy.motionSensorsEnabled)
        assertTrue(positional.copy(collectWifi = true).dataCollectionPolicy.motionSensorsEnabled)
    }

    @Test fun `restored cadence stays outside config identity and precision copy uses its new cadence`() {
        val restored = config.copy().also { it.restoredSyncIntervalMillis = 60_000L }
        assertEquals(config, restored)
        assertEquals(15_000L, restored.copy(scanPrecision = ScanPrecision.HIGH).syncInterval)
        assertEquals(60_000L, restored.copy(collectWifi = false).syncInterval)
        assertFalse(restored.copy(adaptiveSamplingEnabled = false).adaptiveSamplingEnabled)
    }

}
