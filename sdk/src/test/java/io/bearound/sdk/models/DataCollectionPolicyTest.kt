package io.bearound.sdk.models

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The host's collect-or-not switches: defaults and the config → policy mapping the
 * collectors read. The persistence round-trip lives in `DataCollectionPersistenceTest`.
 */
@RunWith(RobolectricTestRunner::class)
class DataCollectionPolicyTest {

    @After
    fun tearDown() {
        // The store is process-wide; one test's policy must not leak into the next.
        DataCollectionPolicyStore.reset()
    }

    @Test
    fun `everything is collected unless the host says otherwise`() {
        val config = SDKConfiguration(businessToken = "t", appId = "io.test")

        assertTrue(config.collectAdvertisingId)
        assertTrue(config.collectLocation)
        assertTrue(config.collectWifi)
        assertEquals(DataCollectionPolicy.ALL_ENABLED, config.dataCollectionPolicy)
    }

    @Test
    fun `each switch maps to the policy the collectors read`() {
        assertEquals(
            DataCollectionPolicy(advertisingId = false),
            SDKConfiguration(businessToken = "t", appId = "io.test", collectAdvertisingId = false)
                .dataCollectionPolicy
        )
        assertEquals(
            DataCollectionPolicy(location = false),
            SDKConfiguration(businessToken = "t", appId = "io.test", collectLocation = false)
                .dataCollectionPolicy
        )
        assertEquals(
            DataCollectionPolicy(wifi = false),
            SDKConfiguration(businessToken = "t", appId = "io.test", collectWifi = false)
                .dataCollectionPolicy
        )
    }

    @Test
    fun `one switch off never turns another off`() {
        val policy = SDKConfiguration(
            businessToken = "t",
            appId = "io.test",
            collectAdvertisingId = false,
            collectLocation = true,
            collectWifi = false
        ).dataCollectionPolicy

        assertFalse(policy.advertisingId)
        assertTrue(policy.location)
        assertFalse(policy.wifi)
    }

    @Test
    fun `the store hands the collectors what was applied`() {
        DataCollectionPolicyStore.apply(
            SDKConfiguration(businessToken = "t", appId = "io.test", collectLocation = false)
                .dataCollectionPolicy
        )

        assertFalse(DataCollectionPolicyStore.current.location)
        assertTrue(DataCollectionPolicyStore.current.advertisingId)
        assertTrue(DataCollectionPolicyStore.current.wifi)
    }
}
