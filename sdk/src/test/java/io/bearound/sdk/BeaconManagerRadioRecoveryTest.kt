package io.bearound.sdk

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import io.bearound.sdk.models.Beacon
import io.bearound.sdk.utilities.ScanStartBudget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothLeScanner
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    shadows = [
        BeaconManagerRadioRecoveryTest.RadioScannerShadow::class,
        BeaconManagerRadioRecoveryTest.RadioHandlerShadow::class
    ]
)
@LooperMode(LooperMode.Mode.PAUSED)
class BeaconManagerRadioRecoveryTest {

    data class Registration(val settings: ScanSettings, val callback: ScanCallback)

    @Implements(BluetoothLeScanner::class)
    class RadioScannerShadow : ShadowBluetoothLeScanner() {
        var failStop = false
        var stopFailures = 0
        val starts = mutableListOf<Registration>()
        val stops = mutableListOf<ScanCallback>()
        val regularStarts get() = starts.filter { it.settings.reportDelayMillis == 0L }
        val batchStarts get() = starts.filter { it.settings.reportDelayMillis == 2000L }
        val regularStops get() = stops.filter { callback -> regularStarts.any { it.callback === callback } }
        val batchStops get() = stops.filter { callback -> batchStarts.any { it.callback === callback } }

        @Implementation
        public override fun startScan(
            filters: List<ScanFilter>,
            settings: ScanSettings,
            callback: ScanCallback
        ) {
            starts += Registration(settings, callback)
        }

        @Implementation
        public override fun stopScan(callback: ScanCallback) {
            stops += callback
            if (failStop && regularStarts.any { it.callback === callback }) {
                stopFailures++
                throw IllegalStateException("Bluetooth radio is off")
            }
        }
    }

    data class ScheduledPost(val handler: Handler, val runnable: Runnable, val delayMillis: Long)

    @Implements(Handler::class)
    class RadioHandlerShadow {
        @RealObject
        private lateinit var handler: Handler

        companion object {
            val posts = mutableListOf<ScheduledPost>()
            val removals = mutableListOf<Runnable>()

            fun reset() {
                posts.clear()
                removals.clear()
            }
        }

        @Implementation
        fun postDelayed(runnable: Runnable, delayMillis: Long): Boolean {
            if (handler.looper === Looper.getMainLooper()) {
                posts += ScheduledPost(handler, runnable, delayMillis)
            }
            return Shadow.directlyOn(
                handler,
                Handler::class.java,
                "postDelayed",
                ClassParameter.from(Runnable::class.java, runnable),
                ClassParameter.from(Long::class.javaPrimitiveType!!, delayMillis)
            )
        }

        @Implementation
        fun removeCallbacks(runnable: Runnable) {
            if (handler.looper === Looper.getMainLooper()) removals += runnable
            Shadow.directlyOn<Any, Handler>(
                handler,
                Handler::class.java,
                "removeCallbacks",
                ClassParameter.from(Runnable::class.java, runnable)
            )
        }
    }

    private lateinit var context: Context
    private lateinit var manager: BeaconManager
    private lateinit var scanner: RadioScannerShadow

    @Before
    fun setUp() {
        ScanStartBudget.reset()
        RadioHandlerShadow.reset()
        val application = ApplicationProvider.getApplicationContext<Application>()
        context = application
        shadowOf(application).grantPermissions(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        scanner = Shadow.extract(adapter.bluetoothLeScanner)
        val now = System.currentTimeMillis()
        context.getSharedPreferences("com.bearound.sdk.config", Context.MODE_PRIVATE).edit()
            .clear()
            .putBoolean("ble_zone_state_v1.inZone", true)
            .putLong("ble_zone_state_v1.writtenAt", now)
            .putLong("ble_zone_state_v1.lastSeenAt", now)
            .commit()
        manager = BeaconManager(context)
        manager.onError = { throw AssertionError("Unexpected scanning error", it) }
        manager.onActiveScanShouldStart = { manager.startRanging() }
        assertTrue(manager.hasBluetoothScanPermission())

        manager.startScanning()

        assertTrue(manager.isScanning)
        assertTrue(manager.isRanging)
        assertTrue(manager.isInBeaconRegion)
        assertEquals(1, scanner.regularStarts.size)
        assertEquals(1, scanner.batchStarts.size)
    }

    @After
    fun tearDown() {
        try {
            if (::scanner.isInitialized) scanner.failStop = false
            if (::manager.isInitialized) manager.stopScanning()
        } finally {
            ScanStartBudget.reset()
            RadioHandlerShadow.reset()
            if (::context.isInitialized) {
                context.getSharedPreferences("com.bearound.sdk.config", Context.MODE_PRIVATE)
                    .edit().clear().commit()
            }
        }
    }

    private fun timer(delayMillis: Long): ScheduledPost = RadioHandlerShadow.posts.last {
        it.delayMillis == delayMillis && it.handler.hasCallbacks(it.runnable)
    }

    private fun advance(milliseconds: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(milliseconds, TimeUnit.MILLISECONDS)
    }

    private fun scanResult(minor: Int, rssi: Int = -60): ScanResult {
        val payload = byteArrayOf(
            0x01, 0x00,
            0x00, 0x00,
            (minor and 0xFF).toByte(), ((minor shr 8) and 0xFF).toByte(),
            0x00, 0x00,
            0x1B, 0x94.toByte(), 0x0C
        )
        val bytes = byteArrayOf((3 + payload.size).toByte(), 0x16, 0xAD.toByte(), 0xBE.toByte()) + payload
        val record = ScanRecord::class.java
            .getDeclaredMethod("parseFromBytes", ByteArray::class.java)
            .apply { isAccessible = true }
            .invoke(null, bytes) as ScanRecord
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val device = adapter.getRemoteDevice("AA:BB:CC:DD:EE:01")
        return ScanResult(device, record, rssi, SystemClock.elapsedRealtimeNanos())
    }

    private fun assertRecurringRestart() {
        val watchdog = timer(30_000L)
        advance(30_000L)

        assertFalse(manager.isRanging)
        assertEquals(1, scanner.regularStops.size)
        assertSame(watchdog.runnable, timer(30_000L).runnable)
        advance(499L)
        assertEquals(1, scanner.regularStarts.size)
        advance(1L)
        assertEquals(2, scanner.regularStarts.size)
        assertTrue(manager.isRanging)
        assertTrue(watchdog.handler.hasCallbacks(watchdog.runnable))

        advance(29_500L)
        assertFalse(manager.isRanging)
        assertEquals(2, scanner.regularStops.size)
        advance(999L)
        assertEquals(2, scanner.regularStarts.size)
        advance(1L)
        assertEquals(3, scanner.regularStarts.size)
        assertTrue(manager.isRanging)
        assertSame(watchdog.runnable, timer(30_000L).runnable)
        assertEquals(1, scanner.batchStarts.size)
    }

    @Test
    fun `stopScanning completes cleanup and callback when regular stop throws`() {
        val snapshots = mutableListOf<List<Beacon>>()
        manager.onBeaconsUpdated = { snapshots += it }
        manager.setForegroundState(false)
        val beforeDetection = System.currentTimeMillis()
        manager.processExternalScanResult(scanResult(135))
        val afterDetection = System.currentTimeMillis()
        val beacon = snapshots.last().single()
        assertEquals(1, beacon.rssiSamples!!.count)
        val timers = listOf(timer(2_000L), timer(30_000L), timer(120_000L))
        val stopsBefore = scanner.regularStops.size
        val states = mutableListOf<Boolean>()
        manager.onScanningStateChanged = { states += it }
        scanner.failStop = true

        manager.stopScanning()

        assertFalse(manager.isScanning)
        assertFalse(manager.isRanging)
        assertFalse(manager.isInBeaconRegion)
        assertEquals(listOf(false), states)
        assertEquals(stopsBefore + 1, scanner.regularStops.size)
        assertEquals(1, scanner.batchStops.size)
        assertEquals(1, scanner.stopFailures)
        assertTrue(manager.consumeRssiStats(listOf(beacon.identifier)).isEmpty())
        timers.forEach {
            assertFalse(it.handler.hasCallbacks(it.runnable))
            assertTrue(RadioHandlerShadow.removals.any { removed -> removed === it.runnable })
        }
        val persisted = context.getSharedPreferences("com.bearound.sdk.config", Context.MODE_PRIVATE)
        assertTrue(persisted.getBoolean("ble_zone_state_v1.inZone", false))
        assertTrue(persisted.getLong("ble_zone_state_v1.lastSeenAt", -1L) in beforeDetection..afterDetection)

        scanner.failStop = false
        ScanStartBudget.reset()
        manager.startScanning()
        manager.processExternalScanResult(scanResult(136))
        assertEquals(listOf("0.136"), snapshots.last().map { it.identifier })
        scanner.batchStarts.last().callback.onBatchScanResults(listOf(scanResult(135, -85)))
        val restoredBeacon = snapshots.last().single { it.identifier == beacon.identifier }
        assertEquals(-85, restoredBeacon.rssi)
        assertEquals(1, restoredBeacon.rssiSamples!!.count)
        assertEquals(1, manager.consumeRssiStats(listOf(beacon.identifier)).getValue(beacon.identifier).count)

        manager.stopScanning()
        val startsAfterStop = scanner.starts.size
        advance(120_001L)
        assertEquals(startsAfterStop, scanner.starts.size)
        assertFalse(manager.isScanning)
        assertFalse(manager.isRanging)
    }

    @Test
    fun `stopRanging clears flag and foreground timers when stop throws`() {
        manager.setForegroundState(false)
        val refresh = timer(120_000L)
        manager.setForegroundState(true)
        assertFalse(refresh.handler.hasCallbacks(refresh.runnable))
        val watchdog = timer(30_000L)
        val startsBefore = scanner.regularStarts.size
        scanner.failStop = true

        manager.stopRanging()

        assertTrue(manager.isScanning)
        assertTrue(manager.isInBeaconRegion)
        assertFalse(manager.isRanging)
        assertEquals(1, scanner.stopFailures)
        assertFalse(watchdog.handler.hasCallbacks(watchdog.runnable))
        assertFalse(refresh.handler.hasCallbacks(refresh.runnable))
        advance(120_001L)
        assertEquals(startsBefore, scanner.regularStarts.size)
    }

    @Test
    fun `stopRanging preserves the background refresh policy when stop throws`() {
        manager.setForegroundState(false)
        val refresh = timer(120_000L)
        val watchdog = timer(30_000L)
        val startsBefore = scanner.regularStarts.size
        val refreshPostsBefore = RadioHandlerShadow.posts.count { it.runnable === refresh.runnable }
        scanner.failStop = true

        manager.stopRanging()

        assertTrue(manager.isScanning)
        assertTrue(manager.isInBeaconRegion)
        assertFalse(manager.isRanging)
        assertEquals(1, scanner.stopFailures)
        assertFalse(watchdog.handler.hasCallbacks(watchdog.runnable))
        assertTrue(refresh.handler.hasCallbacks(refresh.runnable))
        assertFalse(RadioHandlerShadow.removals.any { it === refresh.runnable })
        advance(120_000L)
        assertEquals(refreshPostsBefore + 1, RadioHandlerShadow.posts.count { it.runnable === refresh.runnable })
        assertTrue(refresh.handler.hasCallbacks(refresh.runnable))
        assertFalse(manager.isRanging)
        assertEquals(startsBefore, scanner.regularStarts.size)
    }

    @Test
    fun `restart recovers after backoff when stopping the old scan throws`() {
        scanner.failStop = true

        assertRecurringRestart()

        assertEquals(2, scanner.stopFailures)
    }

    @Test
    fun `normal restart registers the replacement after the existing backoff`() {
        assertRecurringRestart()

        assertEquals(0, scanner.stopFailures)
    }

    @Test
    fun `restart denied by quota leaves the current session and watchdog intact`() {
        val watchdog = timer(30_000L)
        advance(29_999L)
        ScanStartBudget.freeze(1_500L)
        scanner.failStop = true

        advance(1L)

        assertTrue(manager.isRanging)
        assertTrue(scanner.regularStops.isEmpty())
        assertTrue(watchdog.handler.hasCallbacks(watchdog.runnable))
        advance(500L)
        assertEquals(1, scanner.regularStarts.size)
        assertEquals(0, scanner.stopFailures)

        ScanStartBudget.freeze(0L)
        advance(29_500L)
        assertFalse(manager.isRanging)
        assertEquals(1, scanner.regularStops.size)
        assertEquals(1, scanner.stopFailures)
        advance(999L)
        assertEquals(1, scanner.regularStarts.size)
        advance(1L)
        assertEquals(2, scanner.regularStarts.size)
        assertTrue(manager.isRanging)
        assertSame(watchdog.runnable, timer(30_000L).runnable)
    }

    @Test
    fun `stopScanning prevents a scheduled restart from registering later`() {
        val watchdog = timer(30_000L)
        advance(30_000L)
        assertFalse(manager.isRanging)
        assertEquals(1, scanner.regularStops.size)
        manager.stopScanning()

        advance(500L)
        advance(30_000L)

        assertFalse(manager.isScanning)
        assertFalse(manager.isRanging)
        assertEquals(1, scanner.regularStarts.size)
        assertEquals(1, scanner.batchStops.size)
        assertFalse(watchdog.handler.hasCallbacks(watchdog.runnable))
    }
}
