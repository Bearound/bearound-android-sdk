package io.bearound.sdk.background

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.bearound.sdk.BeAroundSDK
import io.bearound.sdk.models.PeriodicReconciliationDefaults
import io.bearound.sdk.models.SDKConfiguration
import io.bearound.sdk.utilities.SDKConfigStorage
import io.bearound.sdk.visit.VisitStateStore
import io.bearound.sdk.visit.VisitTestFixtures
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Visit code must never break the beacon sync: the periodic worker runs the visit tick after
 * the sync, bounded and guarded, so corrupt visit state costs nothing but the visit tick.
 */
@RunWith(RobolectricTestRunner::class)
class BeaconSyncWorkerVisitTest {

    private lateinit var context: Context
    private lateinit var sdk: BeAroundSDK

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
        BackgroundScheduler._resetForTesting()
        // The SDK is a singleton bound to the Application of the first test that built it;
        // Robolectric gives every test a fresh one (prefs, permissions, LocationManager), so
        // rebuild it here. By type, not by name: the release variant is minified.
        BeAroundSDK::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == BeAroundSDK::class.java }
            .forEach { it.isAccessible = true; it.set(null, null) }
        sdk = BeAroundSDK.getInstance(context)
        SDKConfigStorage.clearConfiguration(context)
        SDKConfigStorage.saveConfiguration(
            context,
            SDKConfiguration(
                businessToken = "visit-token",
                appId = "io.test",
                periodicScanDurationMillis = PeriodicReconciliationDefaults.MINIMUM_SCAN_DURATION_MILLIS
            )
        )
        SDKConfigStorage.saveScanningEnabled(context, true)

        // Eligible for visits (soft fence) with a fresh fix at the origin, so the tick
        // reaches the persisted open stop.
        shadowOf(context as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(locationManager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(locationManager).setLastKnownLocation(
            LocationManager.GPS_PROVIDER,
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = VisitTestFixtures.ORIGIN_LAT
                longitude = VisitTestFixtures.ORIGIN_LNG
                accuracy = 10f
                time = System.currentTimeMillis()
            }
        )
        VisitStateStore(context).clear()
        VisitStateStore(context).saveConfig(VisitTestFixtures.configBody(), "etag-1", System.currentTimeMillis())
    }

    @After
    fun tearDown() {
        SDKConfigStorage.saveScanningEnabled(context, false)
        SDKConfigStorage.clearConfiguration(context)
        VisitStateStore(context).clear()
        BeAroundSDK::class.java.declaredFields
            .filter { it.type == SDKConfiguration::class.java }
            .forEach { it.isAccessible = true; it.set(sdk, null) }
    }

    @Test
    fun `corrupt visit state does not fail the beacon sync worker`() = runBlocking {
        val prefs = context.getSharedPreferences(VisitStateStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("open_stop", """{"env":"env-1","arrival":1}""")
            .putString("soft_candidate", "not json")
            .putString("native_registration", """{"sig":"abc"}""")
            .commit()

        val worker = TestListenableWorkerBuilder<BeaconSyncWorker>(context).build()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        // The visit tick ran and dropped the corrupt value instead of throwing on it.
        assertFalse(prefs.contains("open_stop"))
    }
}
