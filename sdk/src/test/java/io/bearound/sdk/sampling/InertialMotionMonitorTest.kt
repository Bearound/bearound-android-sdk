package io.bearound.sdk.sampling

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import io.bearound.sdk.BeAroundSDK
import io.bearound.sdk.models.DataCollectionPolicy
import io.bearound.sdk.models.DataCollectionPolicyStore
import io.bearound.sdk.models.SDKConfiguration
import io.bearound.sdk.utilities.SDKConfigStorage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager

@RunWith(RobolectricTestRunner::class)
class InertialMotionMonitorTest {
    private var now = 100_000L
    private var eligible = true
    private var wallShift = 0L
    private var clockInvalidations = 0
    private val epochs = mutableListOf<Long>()
    private var factories = 0
    private val windows = mutableListOf<InertialWindow>()
    private data class Task(val at: Long, val action: () -> Unit, var canceled: Boolean = false)
    private val tasks = mutableListOf<Task>()
    private val providers = mutableListOf<Provider>()
    private class Provider : InertialSensorProvider {
        lateinit var sample: (InertialSample) -> Unit
        lateinit var error: () -> Unit
        var stops = 0
        var fail = false
        var onStop: () -> Unit = {}
        override fun start(periodUs: Int, onSample: (InertialSample) -> Unit, onError: () -> Unit): Boolean {
            assertEquals(50_000, periodUs); sample = onSample; error = onError
            return !fail
        }
        override fun stop() { stops++; onStop() }
    }
    private fun monitor(generation: Long = 1L, budget: InertialStartBudget = InertialStartBudget(),
                        factory: () -> InertialSensorProvider = { Provider().also { providers += it } }) =
        InertialMotionMonitor(generation, { eligible }, { factories++; factory() }, { delay, action ->
            val task = Task(now + delay, action); tasks += task
            val cancel: () -> Unit = { task.canceled = true }; cancel
        }, { window, epoch -> windows += window; epochs += epoch }, budget, { now }, { 1_800_000_000_000L + now + wallShift }, { clockInvalidations++ })
    private fun emit(provider: Provider = providers.last(), start: Long = now, count: Int = 61, gap: Long = 50L) {
        repeat(count) { i -> now = start + i * gap; provider.sample(InertialSample(now, 0.0, 0.0, if (i % 2 == 0) 1.1 else .9)) }
    }
    private fun runNext(overrun: Long = 0) {
        val next = tasks.first { !it.canceled }; next.canceled = true; now = maxOf(now, next.at + overrun); next.action()
    }

    @Test fun `ineligible execution never constructs a provider or schedules a burst`() {
        eligible = false; monitor().start()
        assertEquals(0, factories); assertTrue(tasks.isEmpty()); assertTrue(windows.isEmpty())
    }
    @Test fun `twenty hertz burst closes at three seconds with original timestamps`() {
        val monitor = monitor(); val start = now; monitor.start(); emit(); runNext()
        assertEquals(start, windows.single().startedAtMonotonicMs)
        assertEquals(start + 3_000, windows.single().endedAtMonotonicMs)
        assertEquals(1L, windows.single().generation); assertEquals(1, providers.single().stops)
        assertEquals(start + 30_000, tasks.first { !it.canceled }.at); monitor.stop()
    }
    @Test fun `process start budget survives stop restart generations and visibility changes`() {
        val budget = InertialStartBudget(); val start = now
        val old = monitor(budget = budget); old.start(); old.stop()
        eligible = false; monitor(2, budget).start(); assertEquals(1, factories)
        eligible = true; now += 29_999
        val current = monitor(3, budget); current.start(); assertEquals(1, factories)
        runNext(); assertEquals(start + 30_000, now); assertEquals(2, factories); current.stop()
    }
    @Test fun `late callbacks and close actions cannot populate a replacement generation`() {
        val old = monitor(); old.start(); val provider = providers.single(); val close = tasks.single().action
        old.stop(); val current = monitor(2); current.start()
        emit(provider); close(); assertTrue(windows.isEmpty()); current.stop()
    }
    @Test fun `revocation during a burst unregisters and discards buffered samples`() {
        val monitor = monitor(); monitor.start(); emit(count = 40)
        eligible = false; monitor.refresh(); providers.single().sample(InertialSample(now + 1, 0.0, 0.0, 1.0))
        assertEquals(1, providers.single().stops); assertTrue(windows.isEmpty())
        assertTrue(tasks.all { it.canceled })
    }
    @Test fun `registration failures and unavailable providers remain bounded without evidence`() {
        val monitor = monitor(factory = { throw IllegalStateException("Sensor unavailable") }); monitor.start()
        assertTrue(windows.isEmpty()); assertEquals(1, factories); monitor.refresh(); assertEquals(1, factories)
        runNext(); assertEquals(2, factories); monitor.stop()
    }
    @Test fun `provider error discards the whole burst and removes its listener`() {
        val monitor = monitor(); monitor.start(); emit(count = 40); providers.single().error()
        assertEquals(1, providers.single().stops); assertTrue(windows.isEmpty()); monitor.stop()
    }
    @Test fun `close overrun at the exact limit is accepted and one millisecond beyond is discarded`() {
        for (overrun in listOf(250L, 251L)) {
            tasks.clear(); providers.clear(); windows.clear()
            val monitor = monitor(); monitor.start(); emit(); runNext(overrun)
            assertEquals(if (overrun == 250L) 1 else 0, windows.size)
            assertEquals(1, providers.single().stops); monitor.stop(); now += 30_000
        }
    }
    @Test fun `duplicate future outside deadline and provider gaps discard the whole burst`() {
        for (kind in listOf("duplicate", "future", "gap", "deadline")) {
            tasks.clear(); providers.clear(); windows.clear()
            val monitor = monitor(); val start = now; monitor.start(); emit(count = 40)
            when (kind) {
                "duplicate" -> providers.single().sample(InertialSample(now, 0.0, 0.0, 1.0))
                "future" -> providers.single().sample(InertialSample(now + 1, 0.0, 0.0, 1.0))
                "gap" -> { now += 251; providers.single().sample(InertialSample(now, 0.0, 0.0, 1.0)) }
                else -> { now = start + 3_001; providers.single().sample(InertialSample(now, 0.0, 0.0, 1.0)) }
            }
            assertTrue(kind, windows.isEmpty()); assertEquals(1, providers.single().stops); monitor.stop(); now += 30_000
        }
    }
    @Test fun `sample cap closes without retaining or accepting an overflow sample`() {
        val monitor = monitor(); val start = now; monitor.start(); emit(count = 128, gap = 20L)
        assertEquals(128, windows.single().sampleCount); assertEquals(start + 2_540, windows.single().endedAtMonotonicMs)
        providers.single().sample(InertialSample(now + 1, 0.0, 0.0, 1.0)); assertEquals(1, windows.size); monitor.stop()
    }
    @Test fun `clock rollback cannot reset the process start budget`() {
        val budget = InertialStartBudget(); val old = monitor(budget = budget); old.start(); old.stop()
        now -= 1; val current = monitor(2, budget); current.start()
        assertEquals(1, factories); assertTrue(tasks.all { it.canceled }); current.stop()
    }
    @Test fun `native provider converts original nanoseconds and acceleration units and unregisters`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val shadow = shadowOf(sensors); val sensor = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER); shadow.addSensor(sensor)
        val samples = mutableListOf<InertialSample>()
        val provider = AndroidInertialSensorProvider(context, Handler(Looper.getMainLooper()))
        assertTrue(provider.start(50_000, { samples += it }, { fail("Unexpected provider error") }))
        val event = ShadowSensorManager.createSensorEvent(3, Sensor.TYPE_ACCELEROMETER)
        event.timestamp = 123_456_789_000L; event.values[0] = SensorManager.GRAVITY_EARTH; event.values[1] = 0f; event.values[2] = 0f
        shadow.sendSensorEventToListeners(event, sensor)
        assertEquals(InertialSample(123_456L, 1.0, 0.0, 0.0), samples.single())
        provider.stop(); assertTrue(shadow.listeners.isEmpty())
    }
    @Test fun `SDK gates both providers before sensor service access and preserves host auto choice without GPS permissions`() {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE, "android.permission.NEARBY_WIFI_DEVICES")
        val lifecycle = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
        val before = lifecycle.currentState; lifecycle.currentState = Lifecycle.State.STARTED
        var sensorReads = 0
        val context = object : ContextWrapper(application) {
            override fun getSystemService(name: String): Any? {
                if (name == Context.SENSOR_SERVICE) { sensorReads++; return null }
                return super.getSystemService(name)
            }
        }
        SDKConfigStorage.saveScanningEnabled(context, true)
        fun field(name: String) = BeAroundSDK::class.java.getDeclaredField(name).apply { isAccessible = true }
        val sdk = BeAroundSDK::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        field("context").set(sdk, context)
        val refresh = BeAroundSDK::class.java.getDeclaredMethod("startMotionMonitor").apply { isAccessible = true }
        try {
            for (config in listOf(SDKConfiguration("fixture-token", "fixture", adaptiveSamplingEnabled = false),
                SDKConfiguration("fixture-token", "fixture", collectMotionSensors = false),
                SDKConfiguration("fixture-token", "fixture", collectLocation = false, collectWifi = false))) {
                field("configuration").set(sdk, config); DataCollectionPolicyStore.apply(config.dataCollectionPolicy); refresh.invoke(sdk)
                assertNull(field("motionMonitor").get(sdk)); assertNull(field("inertialMonitor").get(sdk)); assertEquals(0, sensorReads)
            }
            val enabled = SDKConfiguration("fixture-token", "fixture")
            field("configuration").set(sdk, enabled); DataCollectionPolicyStore.apply(enabled.dataCollectionPolicy)
            val policy = BeAroundSDK::class.java.getDeclaredMethod("effectiveCollectionPolicy").apply { isAccessible = true }.invoke(sdk) as DataCollectionPolicy
            assertFalse(policy.location); assertFalse(policy.wifi); assertTrue(policy.motionSensorsEnabled)
            refresh.invoke(sdk); assertEquals(1, sensorReads)
        } finally {
            (field("inertialMonitor").get(sdk) as? InertialMotionMonitor)?.stop()
            (field("motionMonitor").get(sdk) as? MotionMonitor)?.stop()
            SDKConfigStorage.saveScanningEnabled(context, false); DataCollectionPolicyStore.reset(); lifecycle.currentState = before
        }
    }
    @Test fun `wall mapping is anchored at opening and a large in burst jump discards the window`() {
        val monitor = monitor(); val start = now; monitor.start(); emit(); wallShift = 5_000; runNext()
        assertEquals(1_800_000_000_000L + start + 3_000, epochs.single())
        assertEquals(0, clockInvalidations); monitor.stop()
        tasks.clear(); windows.clear(); now += 30_000
        val current = monitor(); current.start(); emit(); wallShift += 5_001; runNext()
        assertTrue(windows.isEmpty()); assertEquals(1, clockInvalidations); current.stop()
    }

    @Test fun `failed sensor registration unregisters immediately and does not retry before the process budget`() {
        val monitor = monitor(factory = { Provider().also { it.fail = true; providers += it } }); monitor.start()
        assertEquals(1, factories); assertEquals(1, providers.single().stops); assertTrue(windows.isEmpty())
        monitor.refresh(); assertEquals(1, factories); monitor.stop()
    }

    @Suppress("DEPRECATION")
    @Test fun `background execution requires Android foreground status for the actual existing scan service`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val lifecycle = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
        val before = lifecycle.currentState; lifecycle.currentState = Lifecycle.State.CREATED
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val shadow = shadowOf(activity); val previous = activity.getRunningServices(Int.MAX_VALUE)
        val running = io.bearound.sdk.background.BeaconScanService::class.java.getDeclaredField("isRunning").apply { isAccessible = true }
        val wasRunning = running.getBoolean(null); running.setBoolean(null, true)
        fun field(name: String) = BeAroundSDK::class.java.getDeclaredField(name).apply { isAccessible = true }
        val sdk = BeAroundSDK::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        field("context").set(sdk, context)
        field("foregroundScanConfig").set(sdk, io.bearound.sdk.models.ForegroundScanConfig(enabled = true))
        val check = BeAroundSDK::class.java.getDeclaredMethod("inertialExecutionAllowed").apply { isAccessible = true }
        val info = android.app.ActivityManager.RunningServiceInfo().apply {
            pid = android.os.Process.myPid()
            service = android.content.ComponentName(context, io.bearound.sdk.background.BeaconScanService::class.java)
        }
        try {
            shadow.setServices(listOf(info)); assertEquals(false, check.invoke(sdk))
            info.foreground = true; info.pid++; assertEquals(false, check.invoke(sdk))
            info.pid = android.os.Process.myPid(); info.service = android.content.ComponentName(context.packageName, "fixture.OtherService")
            assertEquals(false, check.invoke(sdk))
            info.service = android.content.ComponentName(context, io.bearound.sdk.background.BeaconScanService::class.java)
            assertEquals(true, check.invoke(sdk))
            field("foregroundScanConfig").set(sdk, io.bearound.sdk.models.ForegroundScanConfig(enabled = false))
            assertEquals(false, check.invoke(sdk))
            running.setBoolean(null, false); lifecycle.currentState = Lifecycle.State.STARTED
            assertEquals(true, check.invoke(sdk))
        } finally { running.setBoolean(null, wasRunning); shadow.setServices(previous); lifecycle.currentState = before }
    }

    @Test fun `slow unregister cannot hide a close completion beyond the deadline overrun`() {
        val monitor = monitor(); monitor.start(); emit(); providers.single().onStop = { now += 251 }
        runNext(); assertTrue(windows.isEmpty()); assertEquals(1, providers.single().stops); monitor.stop()
    }

}
