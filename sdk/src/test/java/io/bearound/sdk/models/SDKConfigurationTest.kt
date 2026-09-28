package io.bearound.sdk.models

import io.bearound.sdk.visit.PlacesConfigClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** sdk-visit-cohesion REQ-004: the SDK talks to one host, the ingest's. */
class SDKConfigurationTest {

    private val config = SDKConfiguration(businessToken = "token", appId = "app")

    @Test
    fun `the configuration has no Control Hub host any more`() {
        val members = SDKConfiguration::class.java.declaredFields.map { it.name } +
            SDKConfiguration::class.java.declaredMethods.map { it.name }
        assertFalse(members.any { it.contains("controlHub", ignoreCase = true) })
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
}
