package io.bearound.sdk.sampling

import android.os.SystemClock
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.bearound.sdk.models.Beacon
import io.bearound.sdk.models.RssiStats
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

internal data class PreparedObservation(
    val batchId: String,
    val payloadJson: String,
    val kind: String,
    val uniqueSampleCount: Int,
    val bucketCount: Int,
    val gapCount: Int,
    val observedFrom: Long,
    val observedTo: Long
)

internal class ObservationSummary(
    private val clock: () -> Long = System::currentTimeMillis,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    val captureStreamId: String = UUID.randomUUID().toString(),
    val bootId: String = UUID.randomUUID().toString(),
    baselineCallbackIntervalMs: Long = 10_000L
) {
    private data class ClockSynchronization(val offset: Double, val uncertainty: Double, val synchronizedAt: Long)
    private data class Watermark(val wall: Long, val elapsed: Long)
    private data class Sample(val beacon: JsonObject, val id: String, val wall: Long, val elapsed: Long, val bucketId: String, val ordinal: Int, var emitted: Boolean = false)
    private class Bucket(val id: String = UUID.randomUUID().toString()) {
        val stats = RssiStats.Accumulator()
        var firstId = ""
        var lastId = ""
        var count = 0
    }
    private class Signal {
        val stats = RssiStats.Accumulator()
        val buckets = linkedMapOf<Long, Bucket>()
        var latest: Sample? = null
        var baseBytes = 0
    }
    private class Window(val id: String, val context: JsonObject, val contextBytes: Int, val motion: MotionEvidence, val synchronization: ClockSynchronization?, val synchronizationGeneration: Long) {
        val signals = linkedMapOf<String, Signal>()
        var first = Long.MAX_VALUE
        var last = Long.MIN_VALUE
        var firstElapsed = Long.MAX_VALUE
        var lastElapsed = Long.MIN_VALUE
        var captureWall = 0L
        var captureElapsed = 0L
        var sampleCount = 0
        var bucketCount = 0
        var gapCount = 0
        var estimatedBytes = contextBytes + ENVELOPE_BYTES
    }

    private val gson = GsonBuilder().serializeNulls().create()
    private val gapThresholdMs = max(30_000L, baselineCallbackIntervalMs.coerceIn(0L, WINDOW_MS) * 2L)
    private val watermarks = linkedMapOf<String, Watermark>()
    private var synchronization: ClockSynchronization? = null
    private var synchronizationGeneration = 0L
    private var current: Window? = null
    private var sequence = 0L
    private var lastClockPair: Watermark? = null
    private var minimumSourceElapsed = Long.MIN_VALUE

    @Synchronized
    fun synchronizeClock(serverOffsetMs: Double, offsetUncertaintyMs: Double, synchronizedAtElapsedMs: Long) {
        val elapsed = elapsedRealtime()
        val value = if (serverOffsetMs.isFinite() && abs(serverOffsetMs) <= 86_400_000.0 &&
            offsetUncertaintyMs.isFinite() && offsetUncertaintyMs in 0.0..5_000.0 &&
            synchronizedAtElapsedMs >= 0L && elapsed >= synchronizedAtElapsedMs && elapsed - synchronizedAtElapsedMs <= LEASE_MS
        ) ClockSynchronization(serverOffsetMs, offsetUncertaintyMs, synchronizedAtElapsedMs) else null
        if (value == null) clearClockSynchronization() else {
            if (synchronization == null) synchronizationGeneration++
            synchronization = value
        }
    }

    @Synchronized
    fun clearClockSynchronization() { synchronization = null; synchronizationGeneration++ }

    @Synchronized
    fun capture(beacons: List<Beacon>, contextJson: String, motion: MotionEvidence, sourceElapsedByBeacon: Map<String, Long> = emptyMap(), closeLiveMaxAgeMs: Long? = null): List<PreparedObservation> {
        val wall = clock()
        val elapsed = elapsedRealtime()
        val closed = mutableListOf<PreparedObservation>()
        fun sealCurrent() {
            closeLiveMaxAgeMs?.let { maxAge -> live(maxAge, motion)?.let(closed::add) }
            close()?.let(closed::add)
        }
        val context = JsonParser.parseString(contextJson).asJsonObject.deepCopy().apply { remove("beacons"); remove("sampling") }
        val contextBytes = gson.toJson(context).toByteArray(Charsets.UTF_8).size
        require(contextBytes + ENVELOPE_BYTES < PAYLOAD_BYTES) { "Observation context exceeds payload limit" }
        val previousPair = lastClockPair
        if (previousPair != null && (elapsed < previousPair.elapsed || abs((wall - previousPair.wall).toDouble() - (elapsed - previousPair.elapsed)) > 120_000.0)) {
            clearClockSynchronization()
            sealCurrent()
            watermarks.clear()
            minimumSourceElapsed = if (elapsed >= previousPair.elapsed) previousPair.elapsed else Long.MIN_VALUE
        }
        lastClockPair = Watermark(wall, elapsed)
        watermarks.entries.removeAll { elapsed >= it.value.elapsed && elapsed - it.value.elapsed > WATERMARK_TTL_MS }
        if (current?.let { it.context != context || it.synchronizationGeneration != synchronizationGeneration } == true) sealCurrent()

        for (beacon in beacons.sortedBy { it.timestamp.time }) {
            val observed = beacon.timestamp.time
            val rawRssi = beacon.rssiRaw ?: beacon.rssi
            if (beacon.isStale || rawRssi !in -127..0 || beacon.rssi !in -127..0 || observed <= 0L || observed > wall ||
                !beacon.accuracy.isFinite() || beacon.accuracy < -1.0 || beacon.major !in 0..65535 || beacon.minor !in 0..65535) continue
            val age = wall - observed
            val sourceElapsed = sourceElapsedByBeacon[beacon.identifier] ?: (elapsed - age)
            if (sourceElapsed <= minimumSourceElapsed || sourceElapsed < 0L || sourceElapsed > elapsed || elapsed - sourceElapsed > WATERMARK_TTL_MS) continue
            val key = "${beacon.uuid}_${beacon.major}_${beacon.minor}"
            val watermark = watermarks[key]
            if (watermark != null && (observed <= watermark.wall || sourceElapsed <= watermark.elapsed)) continue
            check(watermark != null || watermarks.size < MAX_WATERMARKS) { "Observation identity capacity exceeded" }
            val base = beaconJson(beacon, observed)
            val baseBytes = gson.toJson(base).toByteArray(Charsets.UTF_8).size
            require(contextBytes + ENVELOPE_BYTES + baseBytes + STATS_BYTES + BUCKET_BYTES <= PAYLOAD_BYTES) { "Observation sample exceeds payload limit" }
            val existing = current
            val gap = existing != null && (sourceElapsed - existing.lastElapsed > gapThresholdMs ||
                (watermark != null && sourceElapsed - watermark.elapsed > gapThresholdMs))
            if (existing != null && (existing.context != context || maxOf(existing.last, observed) - minOf(existing.first, observed) >= WINDOW_MS ||
                    sourceElapsed - existing.firstElapsed >= WINDOW_MS || gap)) {
                if (gap) existing.gapCount++
                sealCurrent()
            }
            var window = current ?: Window(UUID.randomUUID().toString(), context.deepCopy(), contextBytes, motion.copy(), validSynchronization(synchronization, elapsed), synchronizationGeneration).also { current = it }
            val bucketIndex = observed / BUCKET_MS
            val signal = window.signals[key]
            val addedBucket = signal?.buckets?.containsKey(bucketIndex) != true
            val estimate = window.estimatedBytes + if (signal == null) baseBytes + STATS_BYTES else baseBytes - signal.baseBytes
            val totalEstimate = estimate + if (addedBucket) BUCKET_BYTES else 0
            if ((signal == null && window.signals.size >= MAX_BEACONS) || (addedBucket && (signal?.buckets?.size ?: 0) >= MAX_BUCKETS) ||
                window.sampleCount >= MAX_SAMPLES || totalEstimate > PAYLOAD_BYTES) {
                sealCurrent()
                window = Window(UUID.randomUUID().toString(), context.deepCopy(), contextBytes, motion.copy(), validSynchronization(synchronization, elapsed), synchronizationGeneration).also { current = it }
            }
            val target = window.signals.getOrPut(key) { Signal() }
            val occupied = !target.buckets.containsKey(bucketIndex)
            val bucket = target.buckets.getOrPut(bucketIndex) { Bucket() }
            val sampleId = UUID.nameUUIDFromBytes("$captureStreamId:${++sequence}:$key".toByteArray(Charsets.UTF_8)).toString()
            if (bucket.count == 0) bucket.firstId = sampleId
            bucket.lastId = sampleId
            bucket.count++
            bucket.stats.add(rawRssi, observed)
            target.stats.add(rawRssi, observed)
            window.estimatedBytes += baseBytes - target.baseBytes + if (target.latest == null) STATS_BYTES else 0
            if (occupied) { window.estimatedBytes += BUCKET_BYTES; window.bucketCount++ }
            target.baseBytes = baseBytes
            target.latest = Sample(base, sampleId, observed, sourceElapsed, bucket.id, bucket.count)
            window.first = minOf(window.first, observed)
            window.last = maxOf(window.last, observed)
            window.firstElapsed = minOf(window.firstElapsed, sourceElapsed)
            window.lastElapsed = maxOf(window.lastElapsed, sourceElapsed)
            window.captureWall = wall
            window.captureElapsed = elapsed
            window.sampleCount++
            watermarks[key] = Watermark(observed, sourceElapsed)
            check(window.estimatedBytes <= PAYLOAD_BYTES) { "Observation sample exceeds payload limit" }
        }
        return closed
    }

    @Synchronized
    fun live(maxAgeMs: Long = 30_000L, motion: MotionEvidence? = null): PreparedObservation? {
        val window = current ?: return null
        val elapsed = elapsedRealtime()
        if (window.synchronizationGeneration != synchronizationGeneration || maxAgeMs <= 0L) return null
        val ageLimit = maxAgeMs.coerceIn(0L, 30_000L)
        val samples = window.signals.values.mapNotNull { it.latest }.filter { !it.emitted && elapsed >= it.elapsed && elapsed - it.elapsed <= ageLimit }
        if (samples.isEmpty()) return null
        val beacons = JsonArray()
        samples.forEach { sample ->
            beacons.add(sample.beacon.deepCopy().apply { addProperty("sampleId", sample.id); addProperty("bucketId", sample.bucketId); addProperty("sampleOrdinal", sample.ordinal) })
        }
        val observation = prepare(window, "live", null, beacons, samples.minOf { it.wall }, samples.maxOf { it.wall }, samples.size, samples.map { it.bucketId }.distinct().size, motion)
        samples.forEach { it.emitted = true }
        return observation
    }

    @Synchronized
    fun close(kind: String = "summary", transitionReason: String? = null): PreparedObservation? {
        require(kind == "summary" || kind == "transition") { "Unsupported observation kind" }
        require((kind != "transition" || transitionReason != null) && (transitionReason == null || transitionReason in TRANSITION_REASONS)) { "Invalid observation transition reason" }
        val window = current ?: return null
        if (window.sampleCount == 0) { current = null; return null }
        val beacons = JsonArray()
        window.signals.values.forEach { signal ->
            val base = signal.latest!!.beacon.deepCopy()
            base.add("rssiSamples", gson.toJsonTree(signal.stats.snapshot()!!))
            val buckets = JsonArray()
            signal.buckets.toSortedMap().values.forEach { bucket ->
                val stats = gson.toJsonTree(bucket.stats.snapshot()!!).asJsonObject
                stats.addProperty("bucketId", bucket.id); stats.addProperty("firstSampleId", bucket.firstId); stats.addProperty("lastSampleId", bucket.lastId)
                buckets.add(stats)
            }
            base.add("temporalBuckets", buckets)
            beacons.add(base)
        }
        val observation = prepare(window, kind, transitionReason, beacons, window.first, window.last, window.sampleCount, window.bucketCount)
        current = null
        return observation
    }

    @Synchronized
    fun hasPending(): Boolean = (current?.sampleCount ?: 0) > 0

    private fun validSynchronization(value: ClockSynchronization?, elapsed: Long): ClockSynchronization? =
        value?.takeIf { elapsed >= it.synchronizedAt && elapsed - it.synchronizedAt <= LEASE_MS }

    private fun prepare(window: Window, kind: String, transitionReason: String?, beacons: JsonArray, first: Long, last: Long, samples: Int, buckets: Int, liveMotion: MotionEvidence? = null): PreparedObservation {
        val emissionWall = clock()
        val emissionElapsed = elapsedRealtime()
        val regressedWall = emissionWall < window.last - 120_000L
        val wall = if (regressedWall) window.captureWall else emissionWall
        val elapsed = if (regressedWall) window.captureElapsed else emissionElapsed
        val batchId = UUID.randomUUID().toString()
        val sync = validSynchronization(window.synchronization, elapsed)
        val clockJson = JsonObject().apply {
            addProperty("bootId", bootId); addProperty("wallTimeMs", wall); addProperty("monotonicTimeMs", elapsed)
            add("serverOffsetMs", sync?.let { gson.toJsonTree(it.offset) } ?: JsonNull.INSTANCE)
            add("offsetUncertaintyMs", sync?.let { gson.toJsonTree(it.uncertainty) } ?: JsonNull.INSTANCE)
            add("synchronizedAtMonotonicMs", sync?.let { gson.toJsonTree(it.synchronizedAt) } ?: JsonNull.INSTANCE)
        }
        val sampling = JsonObject().apply {
            addProperty("version", 1); addProperty("kind", kind); addProperty("batchId", batchId)
            addProperty("captureStreamId", captureStreamId); addProperty("windowId", window.id)
            addProperty("observedFrom", first); addProperty("observedTo", last); addProperty("sentAt", wall)
            add("motion", gson.toJsonTree(liveMotion ?: window.motion)); add("clock", clockJson)
            transitionReason?.let { addProperty("transitionReason", it) }
        }
        val payload = window.context.deepCopy().apply { add("beacons", beacons); add("sampling", sampling) }
        val json = gson.toJson(payload)
        check(json.toByteArray(Charsets.UTF_8).size <= PAYLOAD_BYTES) { "Observation payload exceeds limit" }
        return PreparedObservation(batchId, json, kind, samples, buckets, window.gapCount, first, last)
    }

    private fun beaconJson(beacon: Beacon, observed: Long): JsonObject = JsonObject().apply {
        addProperty("uuid", beacon.uuid.toString()); addProperty("major", beacon.major); addProperty("minor", beacon.minor)
        addProperty("rssi", beacon.rssi); addProperty("accuracy", beacon.accuracy); addProperty("proximity", beacon.proximity.toApiString()); addProperty("timestamp", observed)
        beacon.txPower?.let { addProperty("txPower", it) }; beacon.rssiRaw?.let { addProperty("rssiRaw", it) }
        beacon.metadata?.let { metadata -> add("metadata", JsonObject().apply {
            addProperty("battery", metadata.batteryLevel); addProperty("firmware", metadata.firmwareVersion); addProperty("movements", metadata.movements); addProperty("temperature", metadata.temperature)
            metadata.txPower?.let { addProperty("txPower", it) }; metadata.rssiFromBLE?.let { addProperty("rssiFromBLE", it) }; metadata.isConnectable?.let { addProperty("isConnectable", it) }
        }) }
    }

    private companion object {
        const val BUCKET_MS = 10_000L
        const val WINDOW_MS = 300_000L
        const val LEASE_MS = 300_000L
        const val WATERMARK_TTL_MS = 600_000L
        const val MAX_WATERMARKS = 4096
        const val MAX_BEACONS = 100
        const val MAX_BUCKETS = 40
        const val MAX_SAMPLES = 65_536
        const val PAYLOAD_BYTES = 524_288
        const val ENVELOPE_BYTES = 4096
        const val STATS_BYTES = 256
        const val BUCKET_BYTES = 384
        val TRANSITION_REASONS = setOf("confirmed_visit_arrival", "confirmed_visit_exit", "context_changed")
    }
}
