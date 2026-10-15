package io.bearound.sdk.sampling

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.bearound.sdk.models.Beacon
import io.bearound.sdk.models.BeaconMetadata
import io.bearound.sdk.models.RssiStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.UUID
import kotlin.math.sqrt

class ObservationSummaryTest {
    private var wall = 1_791_907_200_000L
    private var elapsed = 1_000_000L
    private val summary = ObservationSummary({ wall }, { elapsed })
    private val motion = MotionEvidence(MotionState.UNKNOWN, MotionSource.NONE, MotionConfidence.NONE, MotionReason.NO_EVIDENCE, null, null, false)
    private val context = """{"sdk":{"platform":"android","version":"3.9.0","appId":"fixture","build":1},"device":{"deviceId":"fixture-device","timestamp":1791907200000,"timezone":"UTC"},"beacons":[],"location":{"latitude":0.0,"longitude":0.0,"timestamp":1791907190000},"userProperties":{"internalId":"fixture-user"},"syncTrigger":"ble_detection"}"""
    private val uuid = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")

    private fun advance(ms: Long) { wall += ms; elapsed += ms }
    private fun beacon(rssi: Int = -60, at: Long = wall, major: Int = 1, minor: Int = 2, uuid: UUID = this.uuid) =
        Beacon(uuid, major, minor, rssi, Beacon.Proximity.BT, 2.0, Date(at))
    private fun capture(beacons: List<Beacon> = listOf(beacon()), original: String = context, elapsedByBeacon: Map<String, Long> = emptyMap()) =
        summary.capture(beacons, original, motion, elapsedByBeacon)
    private fun parsed(value: PreparedObservation) = JsonParser.parseString(value.payloadJson).asJsonObject
    private fun beacons(value: PreparedObservation) = parsed(value).getAsJsonArray("beacons")
    private fun sampling(value: PreparedObservation) = parsed(value).getAsJsonObject("sampling")
    private fun bucket(value: PreparedObservation, beaconIndex: Int = 0, bucketIndex: Int = 0): JsonObject =
        beacons(value)[beaconIndex].asJsonObject.getAsJsonArray("temporalBuckets")[bucketIndex].asJsonObject

    @Test
    fun `empty capture never manufactures a window live ping or summary`() {
        assertTrue(capture(emptyList()).isEmpty())
        assertFalse(summary.hasPending()); assertNull(summary.live()); assertNull(summary.close())
    }

    @Test
    fun `live references stable stream window bucket and physical sample IDs`() {
        capture()
        val first = summary.live()!!
        val firstBeacon = beacons(first)[0].asJsonObject
        assertEquals(1, firstBeacon["sampleOrdinal"].asInt)
        assertEquals(summary.captureStreamId, sampling(first)["captureStreamId"].asString)
        assertEquals(summary.bootId, sampling(first).getAsJsonObject("clock")["bootId"].asString)
        assertNull(summary.live())
        advance(5_000L); capture(listOf(beacon(-70)))
        val second = summary.live()!!
        assertEquals(2, beacons(second)[0].asJsonObject["sampleOrdinal"].asInt)
        assertEquals(firstBeacon["bucketId"], beacons(second)[0].asJsonObject["bucketId"])
        assertEquals(sampling(first)["windowId"], sampling(second)["windowId"])
        assertNotEquals(first.batchId, second.batchId)
        val closed = summary.close()!!
        assertEquals(firstBeacon["sampleId"], bucket(closed)["firstSampleId"])
        assertEquals(beacons(second)[0].asJsonObject["sampleId"], bucket(closed)["lastSampleId"])
        assertEquals(firstBeacon["bucketId"], bucket(closed)["bucketId"])
        assertEquals(2, bucket(closed)["count"].asInt)
        assertFalse(summary.hasPending())
    }

    @Test
    fun `Welford reuses physical raw RSSI and ignores preaggregated legacy counts`() {
        capture(listOf(beacon(-50).copy(rssiRaw = -70, rssiSamples = RssiStats(99, -90, -40, -60.0, 1.0, wall, wall))))
        advance(1_000L); capture(listOf(beacon(-50).copy(rssiRaw = -60)))
        val value = summary.close()!!
        val stats = beacons(value)[0].asJsonObject.getAsJsonObject("rssiSamples")
        assertEquals(setOf("count", "min", "max", "avg", "stdDev", "firstSeen", "lastSeen"), stats.keySet())
        assertEquals(wall - 1_000L, stats["firstSeen"].asLong)
        assertEquals(wall, stats["lastSeen"].asLong)
        assertEquals(2, stats["count"].asInt); assertEquals(-70, stats["min"].asInt); assertEquals(-60, stats["max"].asInt)
        assertEquals(-65.0, stats["avg"].asDouble, 0.000001); assertEquals(sqrt(50.0), stats["stdDev"].asDouble, 0.000001)
        assertEquals(2, value.uniqueSampleCount)
    }

    @Test
    fun `occupied buckets preserve gaps without filling silent seconds`() {
        capture(); advance(20_000L); capture()
        val value = summary.close()!!
        assertEquals(2, value.bucketCount); assertEquals(2, value.uniqueSampleCount)
        assertEquals(2, beacons(value)[0].asJsonObject.getAsJsonArray("temporalBuckets").size())
        assertEquals(value.observedFrom, bucket(value)["firstSeen"].asLong)
        assertEquals(value.observedTo, bucket(value, bucketIndex = 1)["lastSeen"].asLong)
        assertEquals(1, bucket(value)["count"].asInt); assertEquals(1, bucket(value, bucketIndex = 1)["count"].asInt)
    }

    @Test
    fun `live ordinal restarts only when a new occupied bucket opens`() {
        capture(); assertEquals(1, beacons(summary.live()!!)[0].asJsonObject["sampleOrdinal"].asInt)
        advance(1_000L); capture(); val second = summary.live()!!
        assertEquals(2, beacons(second)[0].asJsonObject["sampleOrdinal"].asInt)
        advance(9_000L); capture(); val third = summary.live()!!
        assertEquals(1, beacons(third)[0].asJsonObject["sampleOrdinal"].asInt)
        assertNotEquals(beacons(second)[0].asJsonObject["bucketId"], beacons(third)[0].asJsonObject["bucketId"])
    }

    @Test
    fun `duplicate cached stale future and out of order reads never increment counts`() {
        val original = beacon(); capture(listOf(original)); summary.live()
        advance(1_000L)
        capture(listOf(original, beacon(at = original.timestamp.time - 1), beacon().copy(isStale = true), beacon(at = wall + 1)))
        assertNull(summary.live()); assertEquals(1, summary.close()!!.uniqueSampleCount)
    }

    @Test
    fun `invalid RSSI and beacon values never become evidence`() {
        capture(listOf(beacon(1), beacon(127), beacon(-128), beacon().copy(accuracy = Double.NaN), beacon(major = -1), beacon(minor = 65536)))
        assertFalse(summary.hasPending())
    }

    @Test
    fun `live selects all fresh eligible beacons and never resends cache at cadence`() {
        capture(listOf(beacon(minor = 1), beacon(minor = 2), beacon(minor = 3)))
        assertEquals(3, beacons(summary.live(5_000L)!!).size()); assertNull(summary.live(5_000L))
        advance(5_001L); capture(listOf(beacon(minor = 2)))
        val next = summary.live(5_000L)!!
        assertEquals(1, beacons(next).size()); assertEquals(2, beacons(next)[0].asJsonObject["minor"].asInt)
        assertEquals(4, summary.close()!!.uniqueSampleCount)
    }

    @Test
    fun `old physical monotonic age cannot be refreshed by callback receipt`() {
        capture(elapsedByBeacon = mapOf("1.2" to elapsed - 31_000L))
        assertNull(summary.live()); assertEquals(1, summary.close()!!.uniqueSampleCount)
    }

    @Test
    fun `same major minor across UUIDs preserves both signal identities`() {
        capture(listOf(beacon(), beacon(uuid = UUID.randomUUID())))
        assertEquals(2, beacons(summary.live()!!).size()); assertEquals(2, beacons(summary.close()!!).size())
    }

    @Test
    fun `context changes close original evidence before accepting new context`() {
        capture(); val originalWall = wall; advance(1_000L)
        val changed = context.replace("fixture-user", "other-user").replace("1791907190000", "1791907201000")
        val closed = capture(original = changed).single()
        assertEquals("fixture-user", parsed(closed).getAsJsonObject("userProperties")["internalId"].asString)
        assertEquals(1_791_907_190_000L, parsed(closed).getAsJsonObject("location")["timestamp"].asLong)
        assertEquals(originalWall, closed.observedTo)
        val next = summary.close()!!
        assertEquals("other-user", parsed(next).getAsJsonObject("userProperties")["internalId"].asString)
        assertNotEquals(sampling(closed)["windowId"], sampling(next)["windowId"])
    }

    @Test
    fun `empty context boundary seals prior evidence without opening a fake window`() {
        capture()
        val closed = capture(emptyList(), context.replace("fixture-user", "other-user"))
        assertEquals(1, closed.size); assertFalse(summary.hasPending())
    }

    @Test
    fun `capture snapshots mutable date and serializes immutable prepared payloads`() {
        val mutable = beacon(); capture(listOf(mutable)); val expected = wall
        mutable.timestamp.time = wall + 90_000L
        val prepared = summary.live()!!; val originalJson = prepared.payloadJson
        advance(5_000L); capture(); summary.close()
        assertEquals(originalJson, prepared.payloadJson); assertEquals(expected, beacons(prepared)[0].asJsonObject["timestamp"].asLong)
    }

    @Test
    fun `gap closes a subsegment with original endpoints and counts the gap once`() {
        capture(); val start = wall; advance(30_001L)
        val closed = capture().single()
        assertEquals(1, closed.gapCount); assertEquals(start, closed.observedFrom); assertEquals(start, closed.observedTo)
        assertEquals(wall, summary.close()!!.observedFrom)
    }

    @Test
    fun `gap limit respects twice the actual baseline callback interval`() {
        val slower = ObservationSummary({ wall }, { elapsed }, baselineCallbackIntervalMs = 60_000L)
        slower.capture(listOf(beacon()), context, motion); advance(60_000L)
        assertTrue(slower.capture(listOf(beacon()), context, motion).isEmpty())
        advance(120_001L)
        assertEquals(1, slower.capture(listOf(beacon()), context, motion).single().gapCount)
    }

    @Test
    fun `five minute windows close even while callbacks remain continuous`() {
        capture()
        repeat(29) { advance(10_000L); assertTrue(capture().isEmpty()) }
        advance(10_000L); val first = capture().single()
        assertEquals(30, first.uniqueSampleCount); assertEquals(290_000L, first.observedTo - first.observedFrom)
        assertEquals(1, summary.close()!!.uniqueSampleCount)
    }

    @Test
    fun `late independent signal cannot expand a window beyond the wire interval`() {
        capture()
        val closed = capture(listOf(beacon(at = wall - 400_000L, minor = 3)))
        assertEquals(1, closed.size)
        assertEquals(0L, closed.single().observedTo - closed.single().observedFrom)
        val historical = summary.close()!!
        assertEquals(wall - 400_000L, historical.observedFrom)
        assertEquals(0L, historical.observedTo - historical.observedFrom)
    }

    @Test
    fun `beacon capacity persists complete windows without dropping observed samples`() {
        val closed = capture((1..101).map { beacon(minor = it) })
        assertEquals(1, closed.size); assertEquals(100, beacons(closed.single()).size()); assertEquals(100, closed.single().uniqueSampleCount)
        assertEquals(1, summary.close()!!.uniqueSampleCount)
    }

    @Test
    fun `payload capacity closes before the 512 KiB boundary and retains original data`() {
        val metadata = BeaconMetadata("f".repeat(8000), 80, 0, 20)
        val readings = (1..100).map { beacon(minor = it).copy(metadata = metadata) }
        val closed = capture(readings)
        assertTrue(closed.isNotEmpty())
        val all = closed + summary.close()!!
        assertEquals(100, all.sumOf { it.uniqueSampleCount })
        all.forEach { assertTrue(it.payloadJson.toByteArray(Charsets.UTF_8).size <= 524_288) }
    }

    @Test
    fun `transition reasons are informative and retain the original window`() {
        capture(); val live = summary.live()!!; advance(1_000L)
        val exit = summary.close("transition", "confirmed_visit_exit")!!
        assertEquals("transition", exit.kind); assertEquals("confirmed_visit_exit", sampling(exit)["transitionReason"].asString)
        assertEquals(sampling(live)["windowId"], sampling(exit)["windowId"]); assertEquals(live.observedTo, exit.observedTo)
    }

    @Test
    fun `motion absence and clock absence are explicit null values`() {
        capture(); val value = sampling(summary.live()!!)
        assertTrue(value.getAsJsonObject("motion")["observedAtMs"].isJsonNull)
        for (field in listOf("serverOffsetMs", "offsetUncertaintyMs", "synchronizedAtMonotonicMs")) assertTrue(value.getAsJsonObject("clock")[field].isJsonNull)
    }

    @Test
    fun `clock synchronization freezes capture metadata and pairs emission wall and monotonic time`() {
        summary.synchronizeClock(25.0, 2.0, elapsed); capture(); val originalSync = elapsed
        advance(5_000L); summary.synchronizeClock(50.0, 3.0, elapsed)
        val closed = summary.close()!!; val sampling = sampling(closed); val clock = sampling.getAsJsonObject("clock")
        assertEquals(25.0, clock["serverOffsetMs"].asDouble, 0.0); assertEquals(originalSync, clock["synchronizedAtMonotonicMs"].asLong)
        assertEquals(wall, sampling["sentAt"].asLong); assertEquals(wall, clock["wallTimeMs"].asLong); assertEquals(elapsed, clock["monotonicTimeMs"].asLong)
        assertEquals(wall - 5_000L, closed.observedTo)
    }

    @Test
    fun `expired synchronization is archive only without rewriting observed timestamps`() {
        summary.synchronizeClock(25.0, 2.0, elapsed); capture(); val at = wall
        advance(300_001L)
        val closed = summary.close()!!
        assertEquals(at, closed.observedTo); assertTrue(sampling(closed).getAsJsonObject("clock")["serverOffsetMs"].isJsonNull)
    }

    @Test
    fun `invalid synchronization never produces trusted clock fields`() {
        for ((offset, uncertainty, at) in listOf(Triple(Double.NaN, 1.0, elapsed), Triple(86_400_001.0, 1.0, elapsed), Triple(0.0, 5001.0, elapsed), Triple(0.0, 1.0, elapsed + 1))) {
            val aggregator = ObservationSummary({ wall }, { elapsed })
            aggregator.synchronizeClock(offset, uncertainty, at); aggregator.capture(listOf(beacon()), context, motion)
            assertTrue(sampling(aggregator.close()!!).getAsJsonObject("clock")["serverOffsetMs"].isJsonNull)
        }
    }

    @Test
    fun `backward wall jump seals original clock pair and cannot reuse an old physical callback`() {
        summary.synchronizeClock(0.0, 1.0, elapsed); capture()
        val originalWall = wall; val originalElapsed = elapsed
        wall -= 86_400_000L; elapsed += 1_000L
        val closed = capture().single()
        assertEquals(originalWall, closed.observedTo)
        assertEquals(originalWall, sampling(closed)["sentAt"].asLong)
        assertEquals(originalElapsed, sampling(closed).getAsJsonObject("clock")["monotonicTimeMs"].asLong)
        val current = summary.live()!!
        assertTrue(sampling(current).getAsJsonObject("clock")["serverOffsetMs"].isJsonNull)
        capture(listOf(beacon(minor = 3)), elapsedByBeacon = mapOf("1.3" to originalElapsed))
        assertEquals(1, summary.close()!!.uniqueSampleCount)
    }

    @Test
    fun `clearing synchronization blocks pending live and starts new uncertain capture window`() {
        summary.synchronizeClock(0.0, 1.0, elapsed); capture(); summary.clearClockSynchronization()
        assertNull(summary.live()); advance(1_000L)
        assertEquals(1, capture().size)
        assertTrue(sampling(summary.live()!!).getAsJsonObject("clock")["serverOffsetMs"].isJsonNull)
    }
    @Test
    fun `unknown peer distance and zero RSSI preserve established beacon guards`() {
        capture(listOf(beacon(0, major = 65535).copy(accuracy = -1.0)))
        val value = summary.live()!!
        assertEquals(-1.0, beacons(value)[0].asJsonObject["accuracy"].asDouble, 0.0)
        assertEquals(0, beacons(value)[0].asJsonObject["rssi"].asInt)
        assertEquals(1, summary.close()!!.uniqueSampleCount)
    }

    @Test
    fun `live header uses current motion while summary preserves original evidence`() {
        capture()
        val current = motion.copy(state = MotionState.MOVING_CONFIRMED, source = MotionSource.NATIVE_ACTIVITY,
            confidence = MotionConfidence.HIGH, reason = MotionReason.NATIVE_MOVING, observedAtMs = wall, ageMs = 0)
        assertEquals("moving_confirmed", sampling(summary.live(motion = current)!!).getAsJsonObject("motion")["state"].asString)
        assertEquals("unknown", sampling(summary.close()!!).getAsJsonObject("motion")["state"].asString)
    }

    @Test
    fun `capacity boundary emits every fresh live beacon before its matching archived segment`() {
        summary.synchronizeClock(0.0, 1.0, elapsed)
        capture((1..100).map { beacon(minor = it) })
        val closed = summary.capture(listOf(beacon(minor = 101)), context, motion, closeLiveMaxAgeMs = 30_000L)
        assertEquals(listOf("live", "summary"), closed.map { it.kind })
        assertEquals(100, beacons(closed[0]).size()); assertEquals(100, beacons(closed[1]).size())
        for (index in 0 until 100) {
            assertEquals(beacons(closed[0])[index].asJsonObject["sampleId"], bucket(closed[1], index)["lastSampleId"])
            assertEquals(beacons(closed[0])[index].asJsonObject["bucketId"], bucket(closed[1], index)["bucketId"])
        }
        assertEquals(101, beacons(summary.live()!!)[0].asJsonObject["minor"].asInt)
    }

    @Test
    fun `negotiating the first clock closes unsynchronized capture without changing historical clocks`() {
        capture(); summary.synchronizeClock(0.0, 1.0, elapsed)
        advance(1L)
        val old = capture().single()
        assertTrue(sampling(old).getAsJsonObject("clock")["serverOffsetMs"].isJsonNull)
        assertEquals(0.0, sampling(summary.live()!!).getAsJsonObject("clock")["serverOffsetMs"].asDouble, 0.0)
    }

}
