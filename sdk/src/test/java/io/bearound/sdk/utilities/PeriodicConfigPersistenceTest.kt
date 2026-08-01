package io.bearound.sdk.utilities

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.bearound.sdk.models.PeriodicReconciliationDefaults
import io.bearound.sdk.models.SDKConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PeriodicConfigPersistenceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SDKConfigStorage.clearConfiguration(context)
    }

    @Test
    fun `periodic fields survive a save-load roundtrip`() {
        val config = SDKConfiguration(
            businessToken = "roundtrip-token",
            appId = "io.test",
            periodicReconciliationEnabled = false,
            periodicReconciliationIntervalMillis = 60L * 60L * 1000L,
            periodicScanDurationMillis = 8_000L
        )
        SDKConfigStorage.saveConfiguration(context, config)

        val loaded = SDKConfigStorage.loadConfiguration(context)
        assertNotNull(loaded)
        assertEquals(false, loaded!!.periodicReconciliationEnabled)
        assertEquals(60L * 60L * 1000L, loaded.periodicReconciliationIntervalMillis)
        assertEquals(8_000L, loaded.periodicScanDurationMillis)
    }

    @Test
    fun `legacy config without periodic fields restores the defaults`() {
        // Persist a config, then strip the new keys to simulate prefs written by an
        // SDK version that predates the feature.
        SDKConfigStorage.saveConfiguration(
            context,
            SDKConfiguration(businessToken = "legacy-token", appId = "io.test")
        )
        context.getSharedPreferences("bearound_sdk_config", Context.MODE_PRIVATE).edit()
            .remove("periodic_reconciliation_enabled")
            .remove("periodic_reconciliation_interval_ms")
            .remove("periodic_scan_duration_ms")
            .commit()

        val loaded = SDKConfigStorage.loadConfiguration(context)
        assertNotNull(loaded)
        assertEquals(true, loaded!!.periodicReconciliationEnabled)
        assertEquals(
            PeriodicReconciliationDefaults.DEFAULT_INTERVAL_MILLIS,
            loaded.periodicReconciliationIntervalMillis
        )
        assertEquals(
            PeriodicReconciliationDefaults.DEFAULT_SCAN_DURATION_MILLIS,
            loaded.periodicScanDurationMillis
        )
    }

    @Test
    fun `out-of-range persisted values are re-sanitized on load`() {
        // A corrupted/hand-edited prefs file cannot smuggle an aggressive interval in.
        SDKConfigStorage.saveConfiguration(
            context,
            SDKConfiguration(businessToken = "tampered", appId = "io.test")
        )
        context.getSharedPreferences("bearound_sdk_config", Context.MODE_PRIVATE).edit()
            .putLong("periodic_reconciliation_interval_ms", 60_000L) // 1 min
            .putLong("periodic_scan_duration_ms", 500_000L) // 500 s
            .commit()

        val loaded = SDKConfigStorage.loadConfiguration(context)!!
        assertEquals(
            PeriodicReconciliationDefaults.MINIMUM_INTERVAL_MILLIS,
            loaded.periodicReconciliationIntervalMillis
        )
        assertEquals(
            PeriodicReconciliationDefaults.MAXIMUM_SCAN_DURATION_MILLIS,
            loaded.periodicScanDurationMillis
        )
    }
}
