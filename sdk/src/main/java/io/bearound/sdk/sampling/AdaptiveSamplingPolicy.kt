package io.bearound.sdk.sampling

import android.os.SystemClock
import io.bearound.sdk.models.DataCollectionPolicy
import io.bearound.sdk.models.DeviceLocation
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Process-local evidence only. Lifecycle invalidation never restores a monotonic clock. */
internal class AdaptiveSamplingPolicy(
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val clock: () -> Long = System::currentTimeMillis,
    collectionPolicy: DataCollectionPolicy = DataCollectionPolicy.ALL_ENABLED
) {
    companion object {
        const val ACTIVITY_TTL_MS = 120_000L
        const val LOCATION_TTL_MS = 60_000L
        const val CONTEXT_TTL_MS = 30_000L
        const val STATIONARY_DELAY_MS = 60_000L
        const val STABILITY_SPAN_MS = 120_000L
        const val NOVELTY_WINDOW_MS = 90_000L
        private const val HISTORY_MS = 180_000L
        private const val MIN_CONTEXT_GAP_MS = 20_000L
    }

    private data class TimedActivity(val observation: NativeActivityObservation, val epochMs: Long)
    private data class TimedFix(val fix: DeviceLocation, val elapsedMs: Long)
    private data class TimedContext(val observation: ContextObservation, val epochMs: Long)

    private var collectionPolicy = collectionPolicy
    private var lastClock: Long? = null
    private var native: TimedActivity? = null
    private var nativeWatermark: Long? = null
    private val fixes = mutableListOf<TimedFix>()
    private val contexts = mutableListOf<TimedContext>()
    private var latestContext: TimedContext? = null
    private var locationWatermark: Long? = null
    private var contextWatermark: Long? = null
    private var lastWindowId: String? = null
    private var movingFix: TimedFix? = null
    private var noveltyAt: Long? = null
    private var noveltyReason = MotionReason.CONTEXT_CHANGED
    private var emptyReason = MotionReason.STARTUP

    @Synchronized
    fun reset(reason: MotionReason = MotionReason.STARTUP) {
        native = null
        nativeWatermark = null
        fixes.clear()
        contexts.clear()
        latestContext = null
        locationWatermark = null
        contextWatermark = null
        lastWindowId = null
        movingFix = null
        noveltyAt = null
        lastClock = null
        emptyReason = reason
    }

    @Synchronized
    fun updateCollectionPolicy(policy: DataCollectionPolicy) {
        if (policy.location != collectionPolicy.location) {
            fixes.clear()
            movingFix = null
            locationWatermark = maxOf(locationWatermark ?: Long.MIN_VALUE, clock())
            noveltyAt = null
            emptyReason = MotionReason.SOURCE_DISABLED
        }
        if (policy.wifi != collectionPolicy.wifi) {
            // Discard the old AP contribution on disable and require new windows on enable.
            contexts.clear()
            latestContext = null
            contextWatermark = maxOf(contextWatermark ?: Long.MIN_VALUE, elapsedRealtime())
            lastWindowId = null
            noveltyAt = null
            emptyReason = MotionReason.SOURCE_DISABLED
        }
        collectionPolicy = policy
    }

    /** The supplier is not invoked when collection is forbidden. */
    @Synchronized
    fun collectLocation(reader: () -> DeviceLocation?) {
        if (collectionPolicy.location) observeLocation(reader())
    }

    /** Guard an optional AP read before constructing a context window. */
    @Synchronized
    fun collectConnectedAp(reader: () -> String?): String? =
        if (collectionPolicy.wifi) reader() else null

    @Synchronized
    fun observeLocation(fix: DeviceLocation?) {
        val now = now() ?: return
        if (!collectionPolicy.location || fix == null) return
        val accuracy = fix.accuracy ?: return
        val age = clock() - fix.timestamp
        if (age !in 0..LOCATION_TTL_MS || !accuracy.isFinite() || accuracy <= 0f || accuracy > 50f ||
            !fix.latitude.isFinite() || !fix.longitude.isFinite() ||
            fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0 ||
            fix.source.isBlank() || fix.timestamp <= (locationWatermark ?: Long.MIN_VALUE)
        ) return
        val current = TimedFix(fix, now - age)
        val previous = fixes.lastOrNull()
        locationWatermark = fix.timestamp
        if (previous != null) {
            val gap = current.elapsedMs - previous.elapsedMs
            if (gap < 5_000L) return
            if (gap > STABILITY_SPAN_MS) fixes.clear()
            else {
                val distance = distanceMeters(previous.fix, fix)
                if (distance > maxOf(25.0, previous.fix.accuracy!!.toDouble() + accuracy)) {
                    movingFix = current.takeIf { distance / (gap / 1000.0) >= 0.8 }
                    noveltyAt = current.elapsedMs
                    noveltyReason = MotionReason.LOCATION_DISPLACEMENT
                    contexts.clear()
                    latestContext = null
                    fixes.clear()
                }
            }
        }
        fixes += current
        fixes.removeAll { now - it.elapsedMs > HISTORY_MS }
    }

    @Synchronized
    fun observeContext(observation: ContextObservation) {
        val now = now() ?: return
        val observed = observation.observedAtElapsedMs ?: return
        if (!observation.fresh || observation.windowId.isBlank() || observation.windowId == lastWindowId ||
            observation.beaconRssi.isEmpty() || observation.beaconRssi.values.any { it !in -127..0 } ||
            now - observed !in 0..CONTEXT_TTL_MS || observed <= (contextWatermark ?: Long.MIN_VALUE)
        ) return
        val filtered = observation.copy(
            connectedApId = observation.connectedApId.takeIf { collectionPolicy.wifi },
            beaconRssi = observation.beaconRssi.toMap()
        )
        val previous = latestContext?.observation
        val changed = previous != null && !sameContext(previous, filtered)
        contextWatermark = observed
        lastWindowId = observation.windowId
        val current = TimedContext(filtered, clock() - (now - observed))
        latestContext = current
        if (changed) {
            contexts.clear()
            fixes.clear()
            native = native?.takeUnless { it.observation.activity == NativeMotionActivity.STILL }
            noveltyAt = observed
            noveltyReason = MotionReason.CONTEXT_CHANGED
        } else if (contexts.lastOrNull()?.let { observed - it.observation.observedAtElapsedMs!! < MIN_CONTEXT_GAP_MS } == true) {
            // A fresh sample refreshes age without moving the independent window's anchor.
            return
        } else if (previous != null && observed - previous.observedAtElapsedMs!! > STABILITY_SPAN_MS) {
            contexts.clear()
        }
        contexts += current
        contexts.removeAll { now - it.observation.observedAtElapsedMs!! > HISTORY_MS }
    }

    @Synchronized
    fun onNativeActivity(observation: NativeActivityObservation) {
        val now = now() ?: return
        val observed = observation.observedAtElapsedMs
        if (observed == null || now - observed !in 0..ACTIVITY_TTL_MS) {
            invalidateNative(MotionReason.CLOCK_UNCERTAIN)
            return
        }
        if (observed <= (nativeWatermark ?: Long.MIN_VALUE)) return
        nativeWatermark = observed
        if (!observation.entering) {
            if (native?.observation?.activity == observation.activity) native = null
            emptyReason = MotionReason.NO_EVIDENCE
            return
        }
        native = TimedActivity(observation, clock() - (now - observed))
        if (observation.activity != NativeMotionActivity.STILL) {
            noveltyAt = observed
            noveltyReason = MotionReason.NATIVE_MOVING
            fixes.clear()
            movingFix = null
        }
    }

    @Synchronized
    fun invalidateNative(reason: MotionReason = MotionReason.PERMISSION_UNAVAILABLE) {
        native = null
        if (noveltyReason == MotionReason.NATIVE_MOVING) noveltyAt = null
        emptyReason = reason
    }

    @Synchronized
    fun invalidateContext(reason: MotionReason = MotionReason.BLUETOOTH_UNAVAILABLE) {
        contexts.clear()
        latestContext = null
        contextWatermark = maxOf(contextWatermark ?: Long.MIN_VALUE, elapsedRealtime())
        lastWindowId = null
        noveltyAt = null
        emptyReason = reason
    }

    @Synchronized
    fun evidence(): MotionEvidence {
        val now = now() ?: return unknown(MotionReason.CLOCK_UNCERTAIN)
        val context = latestContext?.takeIf {
            now - it.observation.observedAtElapsedMs!! in 0..CONTEXT_TTL_MS
        }
        val contextStable = context != null && contexts.size >= 3 &&
            contexts.last().observation.observedAtElapsedMs!! - contexts.first().observation.observedAtElapsedMs!! >= STABILITY_SPAN_MS
        val activity = native?.takeIf { now - it.observation.observedAtElapsedMs!! in 0..ACTIVITY_TTL_MS }
        if (native != null && activity == null) {
            native = null
            emptyReason = MotionReason.EVIDENCE_EXPIRED
        }
        if (activity != null && activity.observation.activity != NativeMotionActivity.STILL) {
            return MotionEvidence(MotionState.MOVING_CONFIRMED, MotionSource.NATIVE_ACTIVITY,
                MotionConfidence.MEDIUM, MotionReason.NATIVE_MOVING, activity.epochMs,
                now - activity.observation.observedAtElapsedMs!!, contextStable)
        }
        val moving = movingFix?.takeIf { now - it.elapsedMs in 0..LOCATION_TTL_MS }
        if (moving != null) {
            return MotionEvidence(MotionState.MOVING_INFERRED, MotionSource.FRESH_LOCATION,
                MotionConfidence.LOW, MotionReason.LOCATION_DISPLACEMENT, moving.fix.timestamp,
                now - moving.elapsedMs, contextStable)
        }
        if (activity != null) {
            val observed = activity.observation.observedAtElapsedMs!!
            val corroborated = context != null && contexts.size >= 2 &&
                context.observation.observedAtElapsedMs!! > observed &&
                (noveltyAt == null || noveltyAt!! < observed)
            val confirmed = now - observed >= STATIONARY_DELAY_MS && corroborated
            return MotionEvidence(
                if (confirmed) MotionState.STATIONARY_CONFIRMED else MotionState.STATIONARY_CANDIDATE,
                MotionSource.NATIVE_ACTIVITY, MotionConfidence.MEDIUM,
                if (confirmed) MotionReason.NATIVE_STATIONARY_CONFIRMED else MotionReason.NATIVE_STATIONARY_CANDIDATE,
                activity.epochMs, now - observed, contextStable
            )
        }
        val latestFix = fixes.lastOrNull()?.takeIf { now - it.elapsedMs in 0..LOCATION_TTL_MS }
        if (contextStable && latestFix != null && fixes.size >= 3 &&
            latestFix.elapsedMs - fixes.first().elapsedMs >= STABILITY_SPAN_MS &&
            fixes.all { distanceMeters(it.fix, latestFix.fix) <= maxOf(25.0, it.fix.accuracy!!.toDouble() + latestFix.fix.accuracy!!) }
        ) {
            return MotionEvidence(MotionState.STATIONARY_INFERRED, MotionSource.CONTEXT_FUSION,
                MotionConfidence.LOW, MotionReason.STATIONARY_INFERRED, latestFix.fix.timestamp,
                now - latestFix.elapsedMs, true)
        }
        if (contextStable) {
            return MotionEvidence(MotionState.UNKNOWN, MotionSource.CONTEXT_FUSION,
                MotionConfidence.LOW, MotionReason.CONTEXT_STABLE, context!!.epochMs,
                now - context.observation.observedAtElapsedMs!!, true)
        }
        if (context != null && noveltyAt?.let { now - it in 0 until NOVELTY_WINDOW_MS } == true) {
            return MotionEvidence(MotionState.UNKNOWN, MotionSource.CONTEXT_FUSION,
                MotionConfidence.LOW, noveltyReason, context.epochMs,
                now - context.observation.observedAtElapsedMs!!, false)
        }
        val expired = contexts.isNotEmpty() || fixes.isNotEmpty() || movingFix != null
        return unknown(if (expired) MotionReason.EVIDENCE_EXPIRED else emptyReason)
    }

    @Synchronized
    fun plan(baseIntervalMs: Long, capability: SamplingCapability?, adaptiveEnabled: Boolean = true): SamplingPlan {
        require(baseIntervalMs > 0) { "Base interval must be positive" }
        val evidence = evidence()
        val fallback = SamplingPlan(false, null, baseIntervalMs, baseIntervalMs)
        if (!adaptiveEnabled || capability == null || capability.sessionTimeoutMs <= 0 ||
            capability.advertisedServicesCadenceMs <= 0 || capability.mapTtlMs < 3 ||
            capability.summaryMaxIntervalMs < baseIntervalMs || capability.sessionTimeoutMs / 3 < baseIntervalMs
        ) return fallback
        val now = lastClock ?: return fallback
        val live = minOf(baseIntervalMs, capability.advertisedServicesCadenceMs, 60_000L, capability.mapTtlMs / 3)
        val novelty = noveltyAt?.let { now - it in 0 until NOVELTY_WINDOW_MS } == true
        val summary = when {
            novelty -> minOf(baseIntervalMs, 15_000L)
            evidence.contextStable -> minOf(300_000L, capability.sessionTimeoutMs / 3, capability.summaryMaxIntervalMs)
            evidence.state == MotionState.MOVING_CONFIRMED || evidence.state == MotionState.MOVING_INFERRED ->
                minOf(60_000L, capability.sessionTimeoutMs / 3, capability.summaryMaxIntervalMs)
            else -> baseIntervalMs
        }
        return SamplingPlan(true, if (novelty) 30_000L else if (evidence.contextStable) 300_000L else 60_000L,
            summary, live, novelty)
    }

    private fun now(): Long? {
        val value = elapsedRealtime()
        val previous = lastClock
        if (value < 0 || (previous != null && value < previous)) {
            reset(MotionReason.CLOCK_UNCERTAIN)
            lastClock = value.takeIf { it >= 0 }
            return null
        }
        lastClock = value
        return value
    }

    private fun unknown(reason: MotionReason) =
        MotionEvidence(MotionState.UNKNOWN, MotionSource.NONE, MotionConfidence.NONE, reason, null, null, false)

    private fun sameContext(first: ContextObservation, second: ContextObservation): Boolean {
        if (first.connectedApId != second.connectedApId) return false
        val intersection = first.beaconRssi.keys.intersect(second.beaconRssi.keys).size
        val union = first.beaconRssi.keys.union(second.beaconRssi.keys).size
        return union > 0 && intersection.toDouble() / union >= 0.8 &&
            abs(median(first.beaconRssi.values) - median(second.beaconRssi.values)) <= 8.0
    }

    private fun median(values: Collection<Int>): Double {
        val sorted = values.sorted()
        return (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2.0
    }

    private fun distanceMeters(first: DeviceLocation, second: DeviceLocation): Double {
        val lat = Math.toRadians(second.latitude - first.latitude)
        val lng = Math.toRadians(second.longitude - first.longitude)
        val value = sin(lat / 2) * sin(lat / 2) + cos(Math.toRadians(first.latitude)) *
            cos(Math.toRadians(second.latitude)) * sin(lng / 2) * sin(lng / 2)
        return 6_371_000.0 * 2 * asin(sqrt(value.coerceIn(0.0, 1.0)))
    }
}
