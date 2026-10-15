package io.bearound.sdk.utilities

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.bearound.sdk.models.SDKConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The opt-out has to survive a process restore: WorkManager revives the app, the SDK
 * restores its configuration from prefs, and a lost switch means the signal the host
 * disabled starts uploading again — in background, where nobody is looking.
 */
@RunWith(RobolectricTestRunner::class)
class DataCollectionPersistenceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SDKConfigStorage.clearConfiguration(context)
    }

    @Test
    fun `collection switches survive a save-load roundtrip`() {
        SDKConfigStorage.saveConfiguration(
            context,
            SDKConfiguration(
                businessToken = "privacy-token",
                appId = "io.test",
                collectAdvertisingId = false,
                collectLocation = false,
                collectWifi = true
            )
        )

        val loaded = SDKConfigStorage.loadConfiguration(context)
        assertNotNull(loaded)
        assertEquals(false, loaded!!.collectAdvertisingId)
        assertEquals(false, loaded.collectLocation)
        assertEquals(true, loaded.collectWifi)
    }

    @Test
    fun `legacy config without the switches restores everything enabled`() {
        // Every install out there persisted its config before these keys existed.
        // Restoring them as "off" would silently stop collection for the installed base.
        SDKConfigStorage.saveConfiguration(
            context,
            SDKConfiguration(businessToken = "legacy-token", appId = "io.test")
        )
        context.getSharedPreferences("bearound_sdk_config", Context.MODE_PRIVATE).edit()
            .remove("collect_advertising_id")
            .remove("collect_location")
            .remove("collect_wifi")
            .commit()

        val loaded = SDKConfigStorage.loadConfiguration(context)
        assertNotNull(loaded)
        assertEquals(true, loaded!!.collectAdvertisingId)
        assertEquals(true, loaded.collectLocation)
        assertEquals(true, loaded.collectWifi)
    }
    @Test fun `old all off preferences do not enable a new motion collector`() {
        SDKConfigStorage.saveConfiguration(context, SDKConfiguration("fixture-token", "io.fixture", collectLocation = false, collectWifi = false))
        context.getSharedPreferences("bearound_sdk_config", Context.MODE_PRIVATE).edit()
            .remove("collect_motion_sensors").remove("motion_sensors_auto").commit()
        val restored = SDKConfigStorage.loadConfiguration(context)!!
        assertEquals(null, restored.collectMotionSensors)
        assertEquals(false, restored.dataCollectionPolicy.motionSensorsEnabled)
    }
    @Test fun `motion opt out and explicit opt in survive storage without changing old collection switches`() {
        for (motion in listOf(false, true)) {
            SDKConfigStorage.saveConfiguration(context, SDKConfiguration("fixture-token", "io.fixture", collectLocation = false, collectWifi = false, collectMotionSensors = motion))
            val restored = SDKConfigStorage.loadConfiguration(context)!!
            assertEquals(motion, restored.collectMotionSensors); assertEquals(motion, restored.dataCollectionPolicy.motionSensorsEnabled)
            assertEquals(false, restored.collectLocation); assertEquals(false, restored.collectWifi)
        }
    }

}
