package io.bearound.sdk.network

import io.bearound.sdk.models.SDKConfiguration
import io.bearound.sdk.visit.VisitTestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DevicePayloadOperatorTest {

    private val client = APIClient(SDKConfiguration(businessToken = "token", appId = "io.test"))

    @Test
    fun `operator fields are written under device network`() {
        val device = VisitTestFixtures.device().copy(
            simMccMnc = "72410",
            simOperatorName = "Example Mobile",
            networkMccMnc = "724005"
        )

        val payload = client.buildDevicePayload(device)

        val network = payload.getJSONObject("network")
        assertEquals("72410", network.getString("simMccMnc"))
        assertEquals("Example Mobile", network.getString("simOperatorName"))
        assertEquals("724005", network.getString("networkMccMnc"))
        assertFalse(payload.has("simMccMnc"))
    }

    @Test
    fun `absent operator values are omitted from the payload`() {
        val payload = client.buildDevicePayload(VisitTestFixtures.device())

        val network = payload.getJSONObject("network")
        assertFalse(network.has("simMccMnc"))
        assertFalse(network.has("simOperatorName"))
        assertFalse(network.has("networkMccMnc"))
    }
}
