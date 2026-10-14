package io.bearound.sdk.utilities

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.bearound.sdk.models.SDKConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SDKConfigStorageTest {
    private lateinit var context: Context
    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SDKConfigStorage.clearConfiguration(context)
    }

    @Test fun `old configuration restores adaptive defaults without changing collection switches`() {
        context.getSharedPreferences("bearound_sdk_config", Context.MODE_PRIVATE).edit()
            .putBoolean("is_configured", true).putString("business_token", "fixture-token")
            .putBoolean("collect_location", false).commit()
        val config = SDKConfigStorage.loadConfiguration(context)!!
        assertTrue(config.adaptiveSamplingEnabled)
        assertFalse(config.collectLocation)
        assertEquals(300_000L, config.presenceHeartbeatIntervalMillis)
        assertEquals(60_000L, config.syncInterval)
    }

    @Test fun `rollback and empty heartbeat cadence survive configuration restore`() {
        SDKConfigStorage.saveConfiguration(context, SDKConfiguration("fixture-token", context.packageName,
            adaptiveSamplingEnabled = false, presenceHeartbeatIntervalMillis = 600_000L,
            collectWifi = false, collectAdvertisingId = false))
        val config = SDKConfigStorage.loadConfiguration(context)!!
        assertFalse(config.adaptiveSamplingEnabled)
        assertFalse(config.collectWifi)
        assertFalse(config.collectAdvertisingId)
        assertEquals(600_000L, config.presenceHeartbeatIntervalMillis)
    }

    @Test fun `a restored five second baseline is preserved instead of replaced with a precision default`() {
        context.getSharedPreferences("bearound_sdk_config", Context.MODE_PRIVATE).edit()
            .putBoolean("is_configured", true).putString("business_token", "fixture-token")
            .putLong("sync_interval", 5_000L).commit()
        val config = SDKConfigStorage.loadConfiguration(context)!!
        assertEquals(5_000L, config.syncInterval)
        SDKConfigStorage.saveConfiguration(context, config)
        assertEquals(5_000L, SDKConfigStorage.loadConfiguration(context)!!.syncInterval)
    }
}
