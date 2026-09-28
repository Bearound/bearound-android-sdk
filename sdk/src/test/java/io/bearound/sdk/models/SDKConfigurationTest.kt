package io.bearound.sdk.models

import io.bearound.sdk.visit.PlacesConfigClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The SDK talks to one host, `apiBaseURL`. */
class SDKConfigurationTest {

    private val config = SDKConfiguration(businessToken = "token", appId = "app")

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
}
