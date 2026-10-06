package io.bearound.sdk

import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.bearound.sdk.models.Beacon
import io.bearound.sdk.models.RssiStats
import io.bearound.sdk.utilities.ScanStartBudget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothLeScanner
import org.robolectric.util.ReflectionHelpers
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [BeaconManagerRadioRecoveryTest.RadioScannerShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class BeaconManagerRadioRecoveryTest {

    @Implements(BluetoothLeScanner::class)
    class RadioScannerShadow : ShadowBluetoothLeScanner() {
        var failStop = false
        val starts = mutableListOf<ScanCallback>()
        val stops = mutableListOf<ScanCallback>()

        @Implementation
        public override fun startScan(
            filters: List<ScanFilter>,
            settings: ScanSettings,
            callback: ScanCallback
        ) {
            starts += callback
        }

        @Implementation
        public override fun stopScan(callback: ScanCallback) {
            stops += callback
            if (failStop) throw IllegalStateException("Bluetooth radio is off")
        }
    }

    private lateinit var context: Context
    private lateinit var manager: BeaconManager
    private lateinit var scanner: RadioScannerShadow

    @Before
    fun setUp() {
        ScanStartBudget.reset()
        context = ApplicationProvider.getApplicationContext()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        shadowOf(adapter).setEnabled(true)
        val bluetoothScanner = adapter.bluetoothLeScanner
        scanner = Shadow.extract(bluetoothScanner)
        manager = BeaconManager(context)
        ReflectionHelpers.setField(manager, "bluetoothLeScanner", bluetoothScanner)
        ReflectionHelpers.setField(manager, "isInBeaconRegion", true)
        manager.isScanning = true
        manager.startRanging()
        assertTrue(manager.isRanging)
        assertEquals(1, scanner.starts.size)
    }

    @After
    fun tearDown() {
        scanner.failStop = false
        manager.stopScanning()
        ScanStartBudget.reset()
    }

    private fun <T> field(name: String): T = ReflectionHelpers.getField(manager, name)

    private fun invoke(name: String) {
        BeaconManager::class.java.getDeclaredMethod(name).apply {
            isAccessible = true
        }.invoke(manager)
    }

    private fun advance(milliseconds: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(milliseconds, TimeUnit.MILLISECONDS)
    }

    @Test
    fun `stopScanning completes cleanup and callback when regular stop throws`() {
        val beacon = Beacon(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            0, 1, -60, Beacon.Proximity.NEAR, 1.0
        )
        val lastSeen = System.currentTimeMillis()
        field<MutableMap<String, Beacon>>("detectedBeacons")[beacon.identifier] = beacon
        field<MutableMap<String, Long>>("beaconLastSeen")[beacon.identifier] = lastSeen
        field<MutableMap<String, Long>>("lastRegularSampleAt")[beacon.identifier] = lastSeen
        field<MutableMap<String, RssiStats.Accumulator>>("rssiAccumulators")[beacon.identifier] =
            RssiStats.Accumulator().apply { add(-60, lastSeen) }
        ReflectionHelpers.setField(manager, "lastBeaconSeenAt", lastSeen)
        ReflectionHelpers.setField(manager, "isBatchScanning", true)
        invoke("startRangingRefreshTimer")
        invoke("startRegionCleanupTimer")
        val states = mutableListOf<Boolean>()
        manager.onScanningStateChanged = { states += it }
        scanner.failStop = true

        manager.stopScanning()

        assertFalse(manager.isScanning)
        assertFalse(manager.isRanging)
        assertFalse(manager.isInBeaconRegion)
        assertFalse(field<Boolean>("isBatchScanning"))
        assertEquals(listOf(false), states)
        assertEquals(2, scanner.stops.size)
        assertTrue(field<Map<String, Beacon>>("detectedBeacons").isEmpty())
        assertTrue(field<Map<String, Long>>("beaconLastSeen").isEmpty())
        assertTrue(field<Map<String, Long>>("lastRegularSampleAt").isEmpty())
        assertTrue(manager.consumeRssiStats(listOf(beacon.identifier)).isEmpty())
        assertNull(field<Long?>("lastBeaconSeenAt"))
        assertNull(field<Runnable?>("watchdogRunnable"))
        assertNull(field<Runnable?>("rangingRefreshRunnable"))
        assertNull(field<Runnable?>("regionCleanupRunnable"))
        val persisted = context.getSharedPreferences("com.bearound.sdk.config", Context.MODE_PRIVATE)
        assertTrue(persisted.getBoolean("ble_zone_state_v1.inZone", false))
        assertEquals(lastSeen, persisted.getLong("ble_zone_state_v1.lastSeenAt", -1L))
        advance(30_000L)
        assertEquals(1, scanner.starts.size)
    }

    @Test
    fun `stopRanging clears flag and foreground timers when stop throws`() {
        invoke("startRangingRefreshTimer")
        assertNotNull(field<Runnable?>("watchdogRunnable"))
        assertNotNull(field<Runnable?>("rangingRefreshRunnable"))
        scanner.failStop = true

        manager.stopRanging()

        assertTrue(manager.isScanning)
        assertTrue(manager.isInBeaconRegion)
        assertFalse(manager.isRanging)
        assertNull(field<Runnable?>("watchdogRunnable"))
        assertNull(field<Runnable?>("rangingRefreshRunnable"))
        advance(30_000L)
        assertEquals(1, scanner.starts.size)
    }

    @Test
    fun `stopRanging preserves the background refresh policy when stop throws`() {
        ReflectionHelpers.setField(manager, "isInForeground", false)
        invoke("startRangingRefreshTimer")
        val refresh = field<Runnable>("rangingRefreshRunnable")
        scanner.failStop = true

        manager.stopRanging()

        assertFalse(manager.isRanging)
        assertNull(field<Runnable?>("watchdogRunnable"))
        assertSame(refresh, field<Runnable?>("rangingRefreshRunnable"))
    }

    @Test
    fun `restart recovers after backoff when stopping the old scan throws`() {
        val watchdog = field<Runnable>("watchdogRunnable")
        scanner.failStop = true

        invoke("restartRanging")

        assertFalse(manager.isRanging)
        assertSame(watchdog, field<Runnable?>("watchdogRunnable"))
        assertEquals(1, scanner.stops.size)
        advance(499L)
        assertEquals(1, scanner.starts.size)
        advance(1L)
        assertEquals(2, scanner.starts.size)
        assertTrue(manager.isRanging)
        assertSame(watchdog, field<Runnable?>("watchdogRunnable"))
    }

    @Test
    fun `normal restart registers the replacement after the existing backoff`() {
        invoke("restartRanging")

        advance(499L)
        assertEquals(1, scanner.starts.size)
        advance(1L)
        assertEquals(2, scanner.starts.size)
        assertEquals(1, scanner.stops.size)
        assertTrue(manager.isRanging)
    }

    @Test
    fun `restart denied by quota leaves the current session and watchdog intact`() {
        repeat(3) { assertTrue(ScanStartBudget.tryAcquire("test-exhaustion")) }
        val watchdog = field<Runnable>("watchdogRunnable")
        scanner.failStop = true

        invoke("restartRanging")

        assertTrue(manager.isRanging)
        assertSame(watchdog, field<Runnable?>("watchdogRunnable"))
        assertTrue(scanner.stops.isEmpty())
        advance(500L)
        assertEquals(1, scanner.starts.size)
    }

    @Test
    fun `stopScanning prevents a scheduled restart from registering later`() {
        invoke("restartRanging")
        manager.stopScanning()

        advance(500L)

        assertFalse(manager.isScanning)
        assertFalse(manager.isRanging)
        assertEquals(1, scanner.starts.size)
        assertNull(field<Runnable?>("watchdogRunnable"))
    }
}
