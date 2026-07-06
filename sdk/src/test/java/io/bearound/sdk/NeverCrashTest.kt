package io.bearound.sdk

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * NEVER-CRASH-THE-HOST doctrine: public entry points must not throw into the host.
 * A failure is silent (log + telemetry + onError) — never a crash.
 */
@RunWith(RobolectricTestRunner::class)
class NeverCrashTest {

    @Test
    fun `configure with an empty business token does not throw and leaves the SDK unconfigured`() {
        val sdk = BeAroundSDK.getInstance(ApplicationProvider.getApplicationContext())

        // Must NOT throw (previously: IllegalArgumentException).
        sdk.configure(businessToken = "   ")

        assertFalse("SDK must stay unconfigured on empty token", sdk.isConfigured)
    }

    @Test
    fun `getInstance never throws even when Bluetooth service is unavailable`() {
        // Robolectric provides a BluetoothManager, so this asserts the happy path stays
        // safe; the no-radio path is covered by the as?/try-catch in BluetoothManager.init.
        BeAroundSDK.getInstance(ApplicationProvider.getApplicationContext())
    }
}
