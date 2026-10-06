package io.bearound.sdk.utilities

import io.bearound.sdk.models.Beacon
import io.bearound.sdk.models.BeaconMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date
import java.util.UUID

class ScanLogThrottleTest {

    private class Harness {
        var now = 0L
        var detailCalls = 0
        val throttle = ScanLogThrottle { now }

        fun offer(beacons: List<Beacon>): String? = throttle.detailIfDue(beacons) {
            detailCalls++
            beacons.joinToString(", ") { "${it.major}.${it.minor} rssi=${it.rssi}" }
        }
    }

    private fun beacon(
        minor: Int = 1,
        rssi: Int = -60,
        uuid: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    ) = Beacon(
        uuid = uuid,
        major = 0,
        minor = minor,
        rssi = rssi,
        proximity = Beacon.Proximity.NEAR,
        accuracy = 1.0,
        timestamp = Date(0L)
    )

    @Test
    fun `first nonempty composition at zero builds detail exactly once`() {
        val h = Harness()

        assertNull(h.offer(emptyList()))
        assertEquals(0, h.detailCalls)
        assertEquals("0.1 rssi=-60", h.offer(listOf(beacon())))
        assertEquals(1, h.detailCalls)
    }

    @Test
    fun `twelve callbacks with alternating RSSI only log at zero and eleven seconds`() {
        val h = Harness()
        val admitted = mutableListOf<Pair<Long, String>>()

        repeat(12) { index ->
            h.now = index * 1_000L
            val rssi = if (index % 2 == 0) -60 else -61
            h.offer(listOf(beacon(rssi = rssi)))?.let { admitted += h.now to it }
        }

        assertEquals(listOf(0L to "0.1 rssi=-60", 11_000L to "0.1 rssi=-61"), admitted)
        assertEquals(2, h.detailCalls)
    }

    @Test
    fun `reordering and duplicates do not change the composition`() {
        val h = Harness()
        val first = beacon(minor = 1)
        val second = beacon(minor = 2)
        h.offer(listOf(first, second))
        h.now = 1_000L

        assertNull(h.offer(listOf(second, first)))
        assertNull(h.offer(listOf(second, first, first.copy(rssi = -90))))
        assertEquals(1, h.detailCalls)
    }

    @Test
    fun `metadata power proximity and sync changes do not admit a stable composition`() {
        val h = Harness()
        val original = beacon()
        h.offer(listOf(original))
        h.now = 1_000L
        val updated = original.copy(
            metadata = BeaconMetadata("2.0", 80, 3, 25, txPower = -59),
            txPower = -59,
            proximity = Beacon.Proximity.FAR,
            accuracy = 8.0,
            timestamp = Date(1_000L),
            alreadySynced = true,
            syncedAt = Date(1_000L),
            rssiRaw = -90,
            isStale = true
        )

        assertNull(h.offer(listOf(updated)))
        assertEquals(1, h.detailCalls)
    }

    @Test
    fun `adding and removing a beacon admit details immediately`() {
        val h = Harness()
        val first = beacon(minor = 1)
        val second = beacon(minor = 2)
        h.offer(listOf(first))
        h.now = 1_000L

        assertEquals("0.1 rssi=-60, 0.2 rssi=-60", h.offer(listOf(first, second)))
        h.now = 2_000L
        assertEquals("0.2 rssi=-60", h.offer(listOf(second)))
        assertEquals(3, h.detailCalls)
    }

    @Test
    fun `changing only UUID admits a beacon with the same identifier`() {
        val h = Harness()
        val original = beacon()
        val replacement = beacon(uuid = UUID.fromString("00000000-0000-0000-0000-000000000002"))
        h.offer(listOf(original))
        h.now = 1_000L

        assertEquals(original.identifier, replacement.identifier)
        assertEquals("0.1 rssi=-60", h.offer(listOf(replacement)))
        assertEquals(2, h.detailCalls)
    }

    @Test
    fun `empty callbacks do not build detail or reset composition and time`() {
        val h = Harness()
        val beacons = listOf(beacon())
        h.offer(beacons)
        h.now = 5_000L

        assertNull(h.offer(emptyList()))
        assertEquals(1, h.detailCalls)
        h.now = 10_000L
        assertNull(h.offer(beacons))
        assertEquals(1, h.detailCalls)
        h.now = 10_001L
        assertEquals("0.1 rssi=-60", h.offer(beacons))
        assertEquals(2, h.detailCalls)
    }

    @Test
    fun `strict interval admits current RSSI and list order only after ten seconds`() {
        val h = Harness()
        h.offer(listOf(beacon(minor = 1), beacon(minor = 2)))
        val updated = listOf(beacon(minor = 2, rssi = -81), beacon(minor = 1, rssi = -45))
        h.now = 10_000L

        assertNull(h.offer(updated))
        assertEquals(1, h.detailCalls)
        h.now = 10_001L
        assertEquals("0.2 rssi=-81, 0.1 rssi=-45", h.offer(updated))
        assertEquals(2, h.detailCalls)
    }

    @Test
    fun `a composition change restarts the interval`() {
        val h = Harness()
        h.offer(listOf(beacon(minor = 1)))
        val changed = listOf(beacon(minor = 2))
        h.now = 1_000L
        assertEquals("0.2 rssi=-60", h.offer(changed))

        h.now = 10_001L
        assertNull(h.offer(changed))
        h.now = 11_000L
        assertNull(h.offer(changed))
        h.now = 11_001L
        assertEquals("0.2 rssi=-60", h.offer(changed))
        assertEquals(3, h.detailCalls)
    }

    @Test
    fun `wall clock rollback suppresses the same composition`() {
        val h = Harness()
        val beacons = listOf(beacon())
        h.now = 1_000L
        h.offer(beacons)
        h.now = 500L

        assertNull(h.offer(beacons))
        assertEquals(1, h.detailCalls)
        h.now = 11_001L
        assertEquals("0.1 rssi=-60", h.offer(beacons))
        assertEquals(2, h.detailCalls)
    }

    @Test
    fun `admitted and suppressed callbacks leave their input and identifiers unchanged`() {
        val h = Harness()
        val input = mutableListOf(beacon(minor = 2), beacon(minor = 1))
        val original = input.map { it.copy(timestamp = Date(it.timestamp.time)) }
        val identifiers = input.map { it.identifier }

        assertEquals("0.2 rssi=-60, 0.1 rssi=-60", h.offer(input))
        h.now = 1_000L
        assertNull(h.offer(input))

        assertEquals(original, input)
        assertEquals(identifiers, input.map { it.identifier })
        assertEquals(1, h.detailCalls)
    }
}
