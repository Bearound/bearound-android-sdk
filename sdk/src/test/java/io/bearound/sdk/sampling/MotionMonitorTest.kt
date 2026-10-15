package io.bearound.sdk.sampling

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MotionMonitorTest {
    private class FakeRegistrar(var status: MotionAvailability = MotionAvailability.AUTHORIZED) : MotionActivityRegistrar {
        data class Registration(val generation: Long, val event: (NativeActivityObservation) -> Unit, val result: (Throwable?) -> Unit)
        val registrations = mutableListOf<Registration>()
        val removals = mutableListOf<Long>()
        var error: Exception? = null
        override fun availability() = status
        override fun register(generation: Long, onEvent: (NativeActivityObservation) -> Unit, onResult: (Throwable?) -> Unit) {
            error?.let { throw it }
            registrations += Registration(generation, onEvent, onResult)
        }
        override fun unregister(generation: Long) { removals += generation }
    }

    private var elapsed = 1_000_000L
    private val policy = AdaptiveSamplingPolicy({ elapsed }, { 1_800_000_000_000L + elapsed })
    private val registrar = FakeRegistrar()
    private val monitor = MotionMonitor(policy, registrar) { elapsed }
    private fun moving(timestamp: Long? = elapsed) = NativeActivityObservation(NativeMotionActivity.WALKING, true, timestamp)

    @Test
    fun `absent denied and unavailable native activity use fallback without registration`() {
        listOf(MotionAvailability.PERMISSION_ABSENT, MotionAvailability.PERMISSION_DENIED,
            MotionAvailability.SERVICES_UNAVAILABLE).forEach { status ->
            registrar.status = status
            monitor.start()
            assertTrue(registrar.registrations.isEmpty())
            assertEquals(MotionState.UNKNOWN, policy.evidence().state)
            assertEquals(MotionReason.PERMISSION_UNAVAILABLE, policy.evidence().reason)
        }
    }

    @Test
    fun `already authorized activity registers and delivers native evidence`() {
        monitor.start()
        val registration = registrar.registrations.single()
        registration.result(null)
        registration.event(moving())
        assertEquals(MotionState.MOVING_CONFIRMED, policy.evidence().state)
    }

    @Test
    fun `permission revocation invalidates native activity before another delivery`() {
        monitor.start()
        val registration = registrar.registrations.single()
        registration.event(moving())
        registrar.status = MotionAvailability.PERMISSION_DENIED
        monitor.refreshAuthorization()
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
        assertEquals(MotionReason.PERMISSION_UNAVAILABLE, policy.evidence().reason)
        elapsed += 1L
        registration.event(moving())
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
        assertTrue(registrar.removals.contains(registration.generation))
    }

    @Test
    fun `each event checks authorization and cannot read a revoked source`() {
        monitor.start()
        val registration = registrar.registrations.single()
        registrar.status = MotionAvailability.PERMISSION_DENIED
        registration.event(moving())
        assertEquals(MotionReason.PERMISSION_UNAVAILABLE, policy.evidence().reason)
        assertTrue(registrar.removals.contains(registration.generation))
    }

    @Test
    fun `synchronous and asynchronous registration failures degrade without throwing`() {
        registrar.error = SecurityException("Permission revoked")
        monitor.start()
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
        registrar.error = null
        monitor.start()
        val registration = registrar.registrations.single()
        registration.result(IllegalStateException("Play Services unavailable"))
        registration.event(moving())
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
        assertEquals(MotionReason.UNSUPPORTED, policy.evidence().reason)
        assertTrue(registrar.removals.contains(registration.generation))
    }

    @Test
    fun `stop removes observations and ignores late registration and event callbacks`() {
        monitor.start()
        val old = registrar.registrations.single()
        old.event(moving())
        monitor.stop()
        elapsed += 1L
        old.result(null)
        old.event(moving())
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
        assertTrue(registrar.removals.count { it == old.generation } >= 2)
    }

    @Test
    fun `restart ignores the previous generation without removing its replacement`() {
        monitor.start()
        val old = registrar.registrations.single()
        elapsed += 1L
        monitor.start()
        val current = registrar.registrations.last()
        old.result(null)
        old.event(moving())
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
        current.result(null)
        current.event(moving())
        assertEquals(MotionState.MOVING_CONFIRMED, policy.evidence().state)
        assertFalse(registrar.removals.contains(current.generation))
    }

    @Test
    fun `historical missing and future source timestamps cannot start a restored monitor`() {
        monitor.start()
        val registration = registrar.registrations.single()
        registration.event(moving(elapsed - 1L))
        registration.event(moving(null))
        registration.event(moving(elapsed + 1L))
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
        assertFalse(policy.plan(60_000L, SamplingCapability(900_000L, 10_000L, 90_000L)).immediateFlush)
    }

    @Test
    fun `stale activity callback does not gain freshness from delivery time`() {
        monitor.start()
        val registration = registrar.registrations.single()
        val original = elapsed
        elapsed += 120_001L
        registration.event(moving(original))
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
    }

    @Test
    fun `clock regression requires lifecycle restart before accepting native delivery`() {
        monitor.start()
        val registration = registrar.registrations.single()
        registration.event(moving())
        elapsed -= 1L
        registration.event(moving())
        assertEquals(MotionReason.CLOCK_UNCERTAIN, policy.evidence().reason)
        monitor.stop()
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
    }

    @Test
    fun `library manifest exports no motion receiver and introduces no activity permission`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_RECEIVERS)
        assertFalse(info.requestedPermissions.orEmpty().contains(Manifest.permission.ACTIVITY_RECOGNITION))
        assertFalse(info.requestedPermissions.orEmpty().contains("com.google.android.gms.permission.ACTIVITY_RECOGNITION"))
        val receiver = info.receivers.orEmpty().single { it.name == MotionTransitionReceiver::class.java.name }
        assertFalse(receiver.exported)
        assertEquals(MotionAvailability.PERMISSION_ABSENT, PlayServicesMotionRegistrar(context).availability())
    }

    @Test
    fun `real registrar skips registration without existing authorization`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val actual = PlayServicesMotionRegistrar(context)
        var failure: Throwable? = null
        actual.register(500L, { throw AssertionError("Unexpected activity callback") }) { failure = it }
        assertTrue(failure is SecurityException)
    }

    @Test
    fun `receiver forwards the original monotonic timestamp and ignores unknown generations`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = "bearound-motion://receiver-test/1"
        val received = mutableListOf<NativeActivityObservation>()
        MotionTransitionDispatch.register(id) { received += it }
        try {
            val intent = Intent(context, MotionTransitionReceiver::class.java)
                .setAction(MotionTransitionReceiver.ACTION_TRANSITION).setData(Uri.parse(id))
            val result = ActivityTransitionResult(listOf(
                ActivityTransitionEvent(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER, 123_000_000L),
                ActivityTransitionEvent(DetectedActivity.STILL, ActivityTransition.ACTIVITY_TRANSITION_ENTER, 124_000_000L)
            ))
            com.google.android.gms.common.internal.safeparcel.SafeParcelableSerializer.serializeToIntentExtra(
                result, intent, "com.google.android.location.internal.EXTRA_ACTIVITY_TRANSITION_RESULT")
            val receiver = MotionTransitionReceiver()
            receiver.onReceive(context, intent)
            assertEquals(listOf(NativeActivityObservation(NativeMotionActivity.WALKING, true, 123L),
                NativeActivityObservation(NativeMotionActivity.STILL, true, 124L)), received)
            MotionTransitionDispatch.unregister(id)
            receiver.onReceive(context, intent)
            assertEquals(2, received.size)
        } finally {
            MotionTransitionDispatch.unregister(id)
        }
    }
    @Test fun `motion opt out is checked before native availability and registration`() {
        var reads = 0
        val guarded = object : MotionActivityRegistrar {
            override fun availability(): MotionAvailability { reads++; return MotionAvailability.AUTHORIZED }
            override fun register(generation: Long, onEvent: (NativeActivityObservation) -> Unit, onResult: (Throwable?) -> Unit) { org.junit.Assert.fail("Forbidden registration") }
            override fun unregister(generation: Long) = Unit
        }
        val monitor = MotionMonitor(policy, guarded, collectionAllowed = { false }, elapsedRealtime = { elapsed })
        monitor.start(); monitor.refreshAuthorization(); assertEquals(0, reads); monitor.stop()
    }
    @Test fun `native monitor stop preserves already permitted fresh location inference`() {
        policy.observeLocation(io.bearound.sdk.models.DeviceLocation(0.0, 0.0, accuracy = 10f, timestamp = 1_800_000_000_000L + elapsed, source = "fixture"))
        elapsed += 10_000
        policy.observeLocation(io.bearound.sdk.models.DeviceLocation(.001, 0.0, accuracy = 10f, timestamp = 1_800_000_000_000L + elapsed, source = "fixture"))
        monitor.start(); monitor.stop(); assertEquals(MotionSource.FRESH_LOCATION, policy.evidence().source)
    }

}
