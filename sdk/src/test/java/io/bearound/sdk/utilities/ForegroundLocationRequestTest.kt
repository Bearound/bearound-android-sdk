package io.bearound.sdk.utilities

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ForegroundLocationRequestTest {
    private lateinit var context: Context
    private lateinit var manager: LocationManager
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
    }
    @Test fun `explicit request is removed after a new position and preserves source time`() {
        val platform = ForegroundLocationRequest(context)
        var received: io.bearound.sdk.models.DeviceLocation? = null
        val cancel = platform.request { received = it }
        assertEquals(1, shadowOf(manager).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).size)
        val timestamp = System.currentTimeMillis()
        val fix = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = -23.0; longitude = -46.0; accuracy = 12f; time = timestamp
            elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
        }
        shadowOf(manager).simulateLocation(fix)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(timestamp, received?.timestamp)
        assertEquals("gps", received?.source)
        assertTrue(shadowOf(manager).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isEmpty())
        cancel()
    }
    @Test fun `cancellation removes registered platform listener`() {
        val cancel = ForegroundLocationRequest(context).request { fail("cancelled request delivered") }
        cancel()
        assertTrue(shadowOf(manager).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isEmpty())
    }
    @Test fun `missing grants do not start an active request`() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        assertFalse(ForegroundLocationRequest(context).eligible())
        var called = false
        ForegroundLocationRequest(context).request { called = true; assertNull(it) }
        assertTrue(called)
        assertTrue(shadowOf(manager).getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isEmpty())
    }
}
