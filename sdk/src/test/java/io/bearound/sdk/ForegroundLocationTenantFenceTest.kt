package io.bearound.sdk

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import io.bearound.sdk.background.BackgroundScheduler
import io.bearound.sdk.models.DeviceLocation
import io.bearound.sdk.models.SDKConfiguration
import io.bearound.sdk.telemetry.ErrorReporter
import io.bearound.sdk.utilities.ForegroundLocationRequest
import io.bearound.sdk.utilities.FreshLocationBurst
import io.bearound.sdk.utilities.FreshLocationBurstCapture
import io.bearound.sdk.utilities.SDKConfigStorage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ForegroundLocationTenantFenceTest {
    @Test fun `actual configure B and reenable reject A fix before posted main cancellation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        BackgroundScheduler._resetForTesting()
        val sdk = BeAroundSDK.getInstance(context)
        sdk.resetNotificationOpenStateForTest(context)
        SDKConfigStorage.clearConfiguration(context)
        val oldResolver = SDKConfiguration.endpointResolver
        val oldTransport = ErrorReporter.transport
        SDKConfiguration.endpointResolver = { "http://127.0.0.1:1" }
        ErrorReporter.transport = { _, _ -> }
        try {
            sdk.configure("fixture-tenant-A", periodicReconciliationEnabled = false,
                collectAdvertisingId = false, collectLocation = false, collectWifi = false)
            val ownerA = sdk.captureForegroundLocationBurst(ForegroundLocationRequest(context))!!
            assertTrue(ownerA.isCurrent())
            var callback: ((DeviceLocation?) -> Unit)? = null
            var enqueues = 0
            val burst = FreshLocationBurst({ 1_000_000L }, { 1_000_000L }, { true }, { _, _ -> ({}) },
                { completion -> callback = completion; ({}) },
                { FreshLocationBurstCapture(ownerA.isCurrent) { _, completion -> enqueues++; completion(true) } })
            burst.start()
            sdk.configure("fixture-tenant-B", periodicReconciliationEnabled = false,
                collectAdvertisingId = false, collectLocation = false, collectWifi = false)
            sdk.setForegroundLocationBurstsEnabled(true)
            // Deliberately do not idle the main looper: A's posted stop has not run.
            assertFalse(ownerA.isCurrent())
            callback!!(DeviceLocation(-23.0, -46.0, 10f, timestamp = 1_000_000L, source = "gps"))
            assertEquals(0, enqueues)
            val ownerB = sdk.captureForegroundLocationBurst(ForegroundLocationRequest(context))!!
            assertTrue(ownerB.isCurrent())
            sdk.setForegroundLocationBurstsEnabled(false)
            sdk.setForegroundLocationBurstsEnabled(true)
            assertFalse("disable/reenable must not revive an older request", ownerB.isCurrent())
            burst.stop()
        } finally {
            sdk.setForegroundLocationBurstsEnabled(false)
            SDKConfiguration.endpointResolver = oldResolver
            ErrorReporter.transport = oldTransport
            SDKConfigStorage.clearConfiguration(context)
            BeAroundSDK::class.java.declaredFields.filter { it.type == SDKConfiguration::class.java }
                .forEach { it.isAccessible = true; it.set(sdk, null) }
            io.bearound.sdk.models.DataCollectionPolicyStore.reset()
        }
    }
}
