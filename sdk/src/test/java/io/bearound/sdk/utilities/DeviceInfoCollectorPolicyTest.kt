package io.bearound.sdk.utilities

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import io.bearound.sdk.models.DataCollectionPolicy
import io.bearound.sdk.models.DataCollectionPolicyStore
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The switch is only worth anything if the COLLECTOR honours it — a config→policy mapping
 * test would pass just as happily with the collector ignoring the policy entirely. So this
 * one hands the platform a real fix and asserts it does, and then does not, reach the payload.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceInfoCollectorPolicyTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        seedLastKnownLocation()
    }

    @After
    fun tearDown() {
        DataCollectionPolicyStore.reset()
    }

    @Test
    fun `the fix reaches the payload by default`() {
        val device = collect()

        assertNotNull("baseline: the seeded fix must be visible", device.location)
    }

    @Test
    fun `collectLocation off keeps the same fix out of the payload`() {
        DataCollectionPolicyStore.apply(DataCollectionPolicy(location = false))

        val device = collect()

        assertNull(device.location)
        // The authorisation status is NOT the position: it stays, so the backend can still
        // explain why data is missing.
        assertNotNull(device.locationPermission)
    }

    @Test
    fun `collectWifi off empties the observations and the connected AP fields`() {
        DataCollectionPolicyStore.apply(DataCollectionPolicy(wifi = false))

        val device = collect()

        assertNull(device.apId)
        assertNull(device.wifiSSID)
        assert(device.wifis.isEmpty())
    }

    @Test
    fun `collectAdvertisingId off keeps the id and its opt-out flag out of the payload`() {
        DataCollectionPolicyStore.apply(DataCollectionPolicy(advertisingId = false))

        val device = collect()

        assertNull(device.advertisingId)
        assertNull(device.limitAdTracking)
    }

    private fun collect() = DeviceInfoCollector(context).collectDeviceInfo(
        locationPermission = "authorized_always",
        bluetoothState = "powered_on",
        appInForeground = true
    )

    /** A fix fresh enough to pass LocationCollector's 10-minute staleness filter. */
    @Suppress("DEPRECATION") // Robolectric's replacement (setProviderLocation) is not in this version
    private fun seedLastKnownLocation() {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val fix = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = -23.5615
            longitude = -46.6559
            accuracy = 12f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = System.nanoTime()
        }
        shadowOf(manager).apply {
            setProviderEnabled(LocationManager.GPS_PROVIDER, true)
            setLastKnownLocation(LocationManager.GPS_PROVIDER, fix)
        }
    }
}
