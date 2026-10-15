package io.bearound.sdk.sampling

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.bearound.sdk.models.DataCollectionPolicy
import io.bearound.sdk.models.DeviceLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveSamplingPolicyTest {
    private var elapsed = 1_000_000L
    private var wall = 1_800_000_000_000L
    private val policy = AdaptiveSamplingPolicy({ elapsed }, { wall })
    private val capability = SamplingCapability(900_000L, 10_000L, 90_000L)

    private fun advance(millis: Long) { elapsed += millis; wall += millis }
    private fun fix(latitude: Double = 0.0, accuracy: Float = 10f, timestamp: Long = wall, provider: String = "gps") =
        DeviceLocation(latitude, 0.0, accuracy = accuracy, timestamp = timestamp, source = provider)
    private fun context(id: String = elapsed.toString(), beacons: Map<String, Int> = mapOf("beacon-a" to -60), ap: String? = "ap-a",
                        fresh: Boolean = true, observed: Long? = elapsed) =
        policy.observeContext(ContextObservation(id, observed, beacons, ap, fresh))
    private fun native(activity: NativeMotionActivity, observed: Long? = elapsed, entering: Boolean = true) =
        policy.onNativeActivity(NativeActivityObservation(activity, entering, observed))
    private fun stableContext(withFixes: Boolean = false) {
        repeat(3) { index ->
            if (index > 0) advance(60_000L)
            context()
            if (withFixes) policy.observeLocation(fix())
        }
    }

    @Test
    fun `starts unknown without evidence or native permission`() {
        val evidence = policy.evidence()
        assertEquals(MotionState.UNKNOWN, evidence.state)
        assertEquals(MotionReason.STARTUP, evidence.reason)
        assertNull(evidence.observedAtMs)
        assertFalse(evidence.contextStable)
        assertEquals(SamplingPlan(true, 60_000L, 60_000L, 10_000L), policy.plan(60_000L, capability))
    }

    @Test
    fun `serializes the shared motion vocabulary`() {
        val evidence = MotionEvidence(MotionState.MOVING_CONFIRMED, MotionSource.NATIVE_ACTIVITY,
            MotionConfidence.MEDIUM, MotionReason.NATIVE_MOVING, wall, 0L, false)
        val json = Gson().toJson(evidence)
        assertTrue(json.contains("\"state\":\"moving_confirmed\""))
        assertTrue(json.contains("\"source\":\"native_activity\""))
        assertTrue(json.contains("\"confidence\":\"medium\""))
        assertTrue(json.contains("\"reason\":\"native_moving\""))
        val fields = JsonParser.parseString(json).asJsonObject
        assertEquals(setOf("state", "source", "confidence", "reason", "observedAtMs", "ageMs", "contextStable"), fields.keySet())
        assertEquals(wall, fields["observedAtMs"].asLong)
        assertEquals(0L, fields["ageMs"].asLong)
        assertFalse(fields["contextStable"].asBoolean)
    }

    @Test
    fun `native movement is immediate with original timestamp and medium confidence`() {
        NativeMotionActivity.values().filter { it != NativeMotionActivity.STILL }.forEach { activity ->
            policy.reset()
            native(activity, elapsed - 10_000L)
            val evidence = policy.evidence()
            assertEquals(MotionState.MOVING_CONFIRMED, evidence.state)
            assertEquals(MotionConfidence.MEDIUM, evidence.confidence)
            assertEquals(wall - 10_000L, evidence.observedAtMs)
            assertEquals(10_000L, evidence.ageMs)
            assertEquals(30_000L, policy.plan(60_000L, capability).contextIntervalMs)
        }
    }

    @Test
    fun `stationary native activity needs sixty seconds and new corroborating context`() {
        context()
        native(NativeMotionActivity.STILL)
        assertEquals(MotionState.STATIONARY_CANDIDATE, policy.evidence().state)
        advance(59_999L)
        context()
        assertEquals(MotionState.STATIONARY_CANDIDATE, policy.evidence().state)
        advance(1L)
        assertEquals(MotionState.STATIONARY_CONFIRMED, policy.evidence().state)
        assertEquals(MotionReason.NATIVE_STATIONARY_CONFIRMED, policy.evidence().reason)
    }

    @Test
    fun `silence never confirms stationary activity`() {
        native(NativeMotionActivity.STILL)
        advance(60_000L)
        assertEquals(MotionState.STATIONARY_CANDIDATE, policy.evidence().state)
        advance(60_001L)
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
        assertEquals(MotionReason.EVIDENCE_EXPIRED, policy.evidence().reason)
    }

    @Test
    fun `native exit and revocation remove the native contribution immediately`() {
        native(NativeMotionActivity.WALKING)
        advance(1L)
        native(NativeMotionActivity.WALKING, entering = false)
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
        advance(1L)
        native(NativeMotionActivity.STILL)
        policy.invalidateNative()
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
        assertEquals(MotionReason.PERMISSION_UNAVAILABLE, policy.evidence().reason)
    }

    @Test
    fun `future missing and expired activity timestamps cannot supply evidence`() {
        listOf<Long?>(null, elapsed + 1L, elapsed - 120_001L).forEach { timestamp ->
            native(NativeMotionActivity.WALKING, timestamp)
            assertEquals(MotionState.UNKNOWN, policy.evidence().state)
            assertFalse(policy.plan(60_000L, capability).immediateFlush)
        }
    }

    @Test
    fun `delayed activity neither renews freshness nor opens a current novelty window`() {
        native(NativeMotionActivity.WALKING, elapsed - 100_000L)
        assertEquals(100_000L, policy.evidence().ageMs)
        assertFalse(policy.plan(60_000L, capability).immediateFlush)
        advance(20_001L)
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
    }

    @Test
    fun `duplicate and out of order activity do not override newer movement`() {
        native(NativeMotionActivity.WALKING)
        native(NativeMotionActivity.STILL)
        native(NativeMotionActivity.STILL, elapsed - 1L)
        assertEquals(MotionState.MOVING_CONFIRMED, policy.evidence().state)
        advance(120_001L)
        native(NativeMotionActivity.WALKING, elapsed - 120_001L)
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
    }

    @Test
    fun `stable context requires three independent windows across two minutes`() {
        context()
        advance(60_000L)
        context()
        assertFalse(policy.evidence().contextStable)
        advance(59_999L)
        context()
        assertFalse(policy.evidence().contextStable)
        advance(20_000L)
        context()
        assertTrue(policy.evidence().contextStable)
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
        assertEquals(MotionReason.CONTEXT_STABLE, policy.evidence().reason)
    }

    @Test
    fun `frequent fresh callbacks preserve independent context window anchors`() {
        repeat(25) { index ->
            if (index > 0) advance(5_000L)
            context()
        }
        assertTrue(policy.evidence().contextStable)
    }

    @Test
    fun `context stability can coexist with walking within a store`() {
        stableContext()
        native(NativeMotionActivity.WALKING)
        assertTrue(policy.evidence().contextStable)
        assertEquals(MotionState.MOVING_CONFIRMED, policy.evidence().state)
        assertEquals(15_000L, policy.plan(60_000L, capability).summaryIntervalMs)
    }

    @Test
    fun `repeated cache coalescing empty windows and restored state never build stability`() {
        context(id = "cache")
        advance(60_000L)
        context(id = "cache")
        context(id = "coalesced", fresh = false)
        advance(60_000L)
        context(id = "restored-in-zone", fresh = false)
        context(beacons = emptyMap())
        context(observed = null)
        assertFalse(policy.evidence().contextStable)
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
    }

    @Test
    fun `stale future and out of order context cannot refresh the source`() {
        stableContext()
        val latest = elapsed
        advance(30_001L)
        context(observed = latest)
        context(observed = elapsed + 1L)
        context(observed = latest - 1L)
        assertFalse(policy.evidence().contextStable)
        assertEquals(60_000L, policy.plan(60_000L, capability).contextIntervalMs)
    }

    @Test
    fun `AP changes beacon overlap and RSSI threshold exit stability immediately`() {
        listOf(
            ContextObservation("new-ap", null, mapOf("beacon-a" to -60), "ap-b"),
            ContextObservation("new-beacons", null, mapOf("beacon-b" to -60), "ap-a"),
            ContextObservation("new-rssi", null, mapOf("beacon-a" to -69), "ap-a")
        ).forEach { change ->
            policy.reset()
            stableContext()
            advance(1L)
            policy.observeContext(change.copy(observedAtElapsedMs = elapsed))
            assertFalse(policy.evidence().contextStable)
            assertEquals(MotionReason.CONTEXT_CHANGED, policy.evidence().reason)
            assertTrue(policy.plan(60_000L, capability).immediateFlush)
        }
    }

    @Test
    fun `context comparison accepts eighty percent overlap and eight decibels`() {
        val initial = (1..5).associate { "beacon-$it" to -60 }
        context(beacons = initial)
        advance(60_000L)
        context(beacons = (1..4).associate { "beacon-$it" to -68 })
        advance(60_000L)
        context(beacons = (1..4).associate { "beacon-$it" to -68 })
        assertTrue(policy.evidence().contextStable)
    }

    @Test
    fun `fresh GPS movement exceeds distance uncertainty and speed thresholds`() {
        policy.observeLocation(fix(accuracy = 20f))
        advance(10_000L)
        policy.observeLocation(fix(latitude = 0.0005, accuracy = 20f))
        assertEquals(MotionState.MOVING_INFERRED, policy.evidence().state)
        assertEquals(MotionConfidence.LOW, policy.evidence().confidence)
        assertEquals(MotionSource.FRESH_LOCATION, policy.evidence().source)
        assertTrue(policy.plan(60_000L, capability).immediateFlush)
    }

    @Test
    fun `GPS drift within summed uncertainty never confirms physical stillness`() {
        repeat(3) { index ->
            if (index > 0) advance(60_000L)
            policy.observeLocation(fix(latitude = index * 0.0001, accuracy = 50f))
            context()
        }
        assertEquals(MotionState.STATIONARY_INFERRED, policy.evidence().state)
        assertEquals(MotionConfidence.LOW, policy.evidence().confidence)
    }

    @Test
    fun `GPS alone and stable context without distinct fixes cannot infer stationary`() {
        stableContext()
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
        policy.reset()
        repeat(3) { index ->
            if (index > 0) advance(60_000L)
            policy.observeLocation(fix())
        }
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
    }

    @Test
    fun `invalid stale and future GPS fixes cannot cause movement`() {
        listOf(0f, -1f, 50.01f, Float.NaN, Float.POSITIVE_INFINITY).forEach { accuracy ->
            policy.observeLocation(fix(accuracy = accuracy))
        }
        policy.observeLocation(fix(timestamp = wall - 60_001L))
        policy.observeLocation(fix(timestamp = wall + 1L))
        advance(10_000L)
        policy.observeLocation(fix(latitude = 0.005))
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
        assertFalse(policy.plan(60_000L, capability).immediateFlush)
    }

    @Test
    fun `repeated last known fixes never increase the stationary count`() {
        val cached = fix()
        repeat(3) { index ->
            if (index > 0) advance(60_000L)
            policy.observeLocation(cached)
            context()
        }
        assertTrue(policy.evidence().contextStable)
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
    }

    @Test
    fun `GPS requires five to one hundred twenty seconds between independent fixes`() {
        listOf(4_999L, 120_001L).forEach { gap ->
            policy.reset()
            policy.observeLocation(fix())
            advance(gap)
            policy.observeLocation(fix(latitude = 0.005))
            assertEquals(MotionState.UNKNOWN, policy.evidence().state)
        }
    }

    @Test
    fun `slow resolved displacement exits stable policy without claiming movement`() {
        stableContext(withFixes = true)
        advance(120_000L)
        context()
        policy.observeLocation(fix(latitude = 0.0004))
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
        assertFalse(policy.evidence().contextStable)
        assertTrue(policy.plan(60_000L, capability).immediateFlush)
    }

    @Test
    fun `location contribution expires despite fresh context`() {
        stableContext(withFixes = true)
        assertEquals(MotionState.STATIONARY_INFERRED, policy.evidence().state)
        advance(60_001L)
        context()
        assertTrue(policy.evidence().contextStable)
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
    }

    @Test
    fun `forbidden collection does not call suppliers and reenabling requires new observations`() {
        stableContext(withFixes = true)
        policy.updateCollectionPolicy(DataCollectionPolicy(location = false, wifi = false))
        policy.collectLocation { throw AssertionError("Forbidden location read") }
        assertNull(policy.collectConnectedAp { throw AssertionError("Forbidden AP read") })
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
        val old = fix()
        policy.updateCollectionPolicy(DataCollectionPolicy.ALL_ENABLED)
        policy.observeLocation(old)
        context()
        assertFalse(policy.evidence().contextStable)
        advance(1L)
        stableContext(withFixes = true)
        assertEquals(MotionState.STATIONARY_INFERRED, policy.evidence().state)
    }

    @Test
    fun `wifi disabled permits BLE context without reading or using AP identity`() {
        policy.updateCollectionPolicy(DataCollectionPolicy(wifi = false))
        advance(1L)
        repeat(3) { index ->
            if (index > 0) advance(60_000L)
            context(ap = "forbidden-ap-$index")
        }
        assertTrue(policy.evidence().contextStable)
    }

    @Test
    fun `location disable removes inferred motion without erasing native movement`() {
        policy.observeLocation(fix())
        advance(10_000L)
        policy.observeLocation(fix(latitude = 0.005))
        policy.updateCollectionPolicy(DataCollectionPolicy(location = false))
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
        native(NativeMotionActivity.WALKING)
        assertEquals(MotionState.MOVING_CONFIRMED, policy.evidence().state)
    }

    @Test
    fun `clock regression lifecycle reset and bluetooth loss invalidate stability`() {
        stableContext(withFixes = true)
        elapsed -= 1L
        assertEquals(MotionReason.CLOCK_UNCERTAIN, policy.evidence().reason)
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
        stableContext()
        policy.invalidateContext()
        assertEquals(MotionReason.BLUETOOTH_UNAVAILABLE, policy.evidence().reason)
        stableContext()
        policy.reset()
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
        assertFalse(policy.evidence().contextStable)
    }

    @Test
    fun `wall clock changes do not renew existing monotonic activity evidence`() {
        native(NativeMotionActivity.WALKING)
        wall += 86_400_000L
        elapsed += 120_001L
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
    }

    @Test
    fun `stable summary respects the session bound and live remains independent`() {
        stableContext()
        assertEquals(SamplingPlan(true, 300_000L, 300_000L, 10_000L), policy.plan(60_000L, capability))
        assertEquals(120_000L, policy.plan(60_000L, capability.copy(sessionTimeoutMs = 360_000L)).summaryIntervalMs)
        assertEquals(5_000L, policy.plan(5_000L, capability).liveIntervalMs)
        assertEquals(3_000L, policy.plan(60_000L, capability.copy(mapTtlMs = 9_000L)).liveIntervalMs)
    }

    @Test
    fun `missing incompatible or disabled capability preserves the legacy cadence`() {
        stableContext()
        val fallback = SamplingPlan(false, null, 60_000L, 60_000L)
        assertEquals(fallback, policy.plan(60_000L, null))
        assertEquals(fallback, policy.plan(60_000L, capability, adaptiveEnabled = false))
        assertEquals(fallback, policy.plan(60_000L, capability.copy(summaryMaxIntervalMs = 59_999L)))
        assertEquals(fallback, policy.plan(60_000L, capability.copy(sessionTimeoutMs = 179_999L)))
        assertEquals(fallback, policy.plan(60_000L, capability.copy(advertisedServicesCadenceMs = 0L)))
    }
    private fun inertial(end: Long = elapsed, classification: InertialClassification = InertialClassification.ACTIVE,
                         generation: Long = 0L) = InertialWindow(generation, end - 3_000, end, 61, .1, .5, classification)

    @Test fun `co moving stable BLE and rejected GPS100 permit only corroborated low confidence movement`() {
        stableContext(); policy.observeLocation(fix(accuracy = 100f))
        policy.observeInertial(inertial()); assertEquals(MotionState.UNKNOWN, policy.evidence().state)
        advance(30_000); context(); policy.observeInertial(inertial())
        val evidence = policy.evidence()
        assertTrue(evidence.contextStable); assertEquals(MotionState.MOVING_INFERRED, evidence.state)
        assertEquals(MotionSource.CONTEXT_FUSION, evidence.source); assertEquals(MotionReason.CONTEXT_CHANGED, evidence.reason)
        assertEquals(MotionConfidence.LOW, evidence.confidence); assertEquals(0L, evidence.ageMs)
        assertEquals(5_000L, policy.plan(60_000, capability.copy(advertisedServicesCadenceMs = 5_000)).liveIntervalMs)
    }

    @Test fun `corroborated movement TTL follows the newest original end after its first corroborator ages out`() {
        val start = elapsed; advance(3_000); policy.observeInertial(inertial())
        advance(30_000); policy.observeInertial(inertial()); val observed = policy.evidence().observedAtMs
        advance(31_000)
        assertEquals(MotionState.MOVING_INFERRED, policy.evidence().state); assertEquals(31_000L, policy.evidence().ageMs)
        assertEquals(observed, policy.evidence().observedAtMs)
        advance(29_000); assertEquals(MotionState.MOVING_INFERRED, policy.evidence().state)
        assertEquals(60_000L, policy.evidence().ageMs)
        advance(1); assertEquals(start + 93_001, elapsed); assertEquals(MotionState.UNKNOWN, policy.evidence().state)
    }

    @Test fun `quiet ambiguous replay wrong generation overlap and stale windows cannot renew movement`() {
        policy.observeInertial(inertial()); advance(30_000); val active = inertial(); policy.observeInertial(active)
        val observed = policy.evidence().observedAtMs
        advance(30_000)
        policy.observeInertial(inertial(classification = InertialClassification.QUIET))
        policy.observeInertial(inertial(classification = InertialClassification.AMBIGUOUS))
        policy.observeInertial(active); policy.observeInertial(inertial(generation = 1))
        policy.observeInertial(inertial(end = elapsed - 5_001))
        assertEquals(observed, policy.evidence().observedAtMs); assertEquals(30_000L, policy.evidence().ageMs)
        advance(30_001); assertEquals(MotionState.UNKNOWN, policy.evidence().state)
    }

    @Test fun `a single active burst contradicts native STILL without ever confirming stationary`() {
        stableContext(withFixes = true); native(NativeMotionActivity.STILL)
        policy.observeInertial(inertial())
        assertFalse(policy.evidence().state in listOf(MotionState.STATIONARY_CANDIDATE, MotionState.STATIONARY_CONFIRMED, MotionState.STATIONARY_INFERRED))
        advance(1); native(NativeMotionActivity.STILL); advance(60_000); context()
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
        native(NativeMotionActivity.STILL); advance(60_000); context()
        assertEquals(MotionState.STATIONARY_CONFIRMED, policy.evidence().state)
    }

    @Test fun `sustained inertial and later native movement never renew the original novelty window`() {
        stableContext(); policy.observeInertial(inertial())
        advance(30_000); context(); policy.observeInertial(inertial()); val began = elapsed
        repeat(3) { advance(30_000); context(); policy.observeInertial(inertial()) }
        assertEquals(began + 90_000, elapsed)
        assertTrue(policy.evidence().contextStable); assertEquals(60_000L, policy.plan(60_000, capability).contextIntervalMs)
        assertEquals(60_000L, policy.plan(60_000, capability).summaryIntervalMs)
        native(NativeMotionActivity.WALKING)
        assertEquals(MotionState.MOVING_CONFIRMED, policy.evidence().state)
        assertEquals(60_000L, policy.plan(60_000, capability).contextIntervalMs)
        assertEquals(5_000L, policy.plan(5_000, capability).liveIntervalMs)
        assertEquals(60_000L, policy.plan(60_000, capability.copy(advertisedServicesCadenceMs = 60_000, mapTtlMs = 300_000)).liveIntervalMs)
    }

    @Test fun `motion opt out removes sensor novelty immediately but preserves independent BLE and GPS attention`() {
        policy.observeInertial(inertial()); advance(30_000); policy.observeInertial(inertial())
        assertEquals(30_000L, policy.plan(60_000, capability).contextIntervalMs)
        policy.updateCollectionPolicy(DataCollectionPolicy(motionSensors = false))
        assertEquals(MotionState.UNKNOWN, policy.evidence().state)
        assertEquals(60_000L, policy.plan(60_000, capability).contextIntervalMs)
        context(ap = "first"); advance(1); context(ap = "new")
        policy.updateCollectionPolicy(DataCollectionPolicy(motionSensors = true)); policy.updateCollectionPolicy(DataCollectionPolicy(motionSensors = false))
        assertEquals(30_000L, policy.plan(60_000, capability).contextIntervalMs)
        policy.reset(); policy.observeLocation(fix()); advance(10_000); policy.observeLocation(fix(latitude = .001))
        policy.updateCollectionPolicy(DataCollectionPolicy(motionSensors = true)); policy.updateCollectionPolicy(DataCollectionPolicy(motionSensors = false))
        assertEquals(MotionSource.FRESH_LOCATION, policy.evidence().source)
        assertEquals(30_000L, policy.plan(60_000, capability).contextIntervalMs)
    }

    @Test fun `inertial lifecycle invalidation removes only its own contribution and novelty`() {
        policy.observeInertial(inertial()); advance(30_000); policy.observeInertial(inertial())
        policy.invalidateInertial()
        assertEquals(MotionState.UNKNOWN, policy.evidence().state); assertEquals(60_000L, policy.plan(60_000, capability).contextIntervalMs)
        policy.observeLocation(fix()); advance(10_000); policy.observeLocation(fix(latitude = .001))
        policy.invalidateInertial()
        assertEquals(MotionSource.FRESH_LOCATION, policy.evidence().source)
        assertEquals(30_000L, policy.plan(60_000, capability).contextIntervalMs)
    }

    @Test fun `wall clock changes after inference never rejuvenate the frozen sensor epoch or monotonic TTL`() {
        policy.observeInertial(inertial()); advance(30_000); val epoch = wall - 123
        policy.observeInertial(inertial(), epoch); val observed = policy.evidence().observedAtMs
        wall += 500_000; elapsed += 1_000
        assertEquals(epoch, observed); assertEquals(observed, policy.evidence().observedAtMs)
        assertEquals(1_000L, policy.evidence().ageMs)
        elapsed += 59_001; assertEquals(MotionState.UNKNOWN, policy.evidence().state)
    }

}
