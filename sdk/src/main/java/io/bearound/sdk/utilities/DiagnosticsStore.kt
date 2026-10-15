package io.bearound.sdk.utilities

import androidx.annotation.VisibleForTesting
import io.bearound.sdk.models.AdaptiveSamplingDiagnostics
import io.bearound.sdk.sampling.MotionEvidence
import io.bearound.sdk.sampling.SamplingPlan
import com.google.gson.Gson

/** In-memory, thread-safe store of recent SDK activity for diagnostics. Resets on process death. */
object DiagnosticsStore {
    private const val MAX_ERRORS = 10

    private val lock = Any()
    private val gson = Gson()

    private var lastSyncAt: Long? = null
    private var lastSyncSuccess: Boolean? = null
    private var lastSyncBeaconCount: Int? = null

    private var lastScanAt: Long? = null
    private var lastScanBeaconCount: Int? = null

    private val recentErrors = ArrayDeque<String>(MAX_ERRORS)
    private var sampling = AdaptiveSamplingDiagnostics()

    internal fun recordSamplingPolicy(evidence: MotionEvidence, plan: SamplingPlan, capability: Boolean,
                                      clockUncertaintyMs: Double? = null, nextContextReadElapsedMs: Long? = null,
                                      nextSummaryElapsedMs: Long? = null, nextLiveAttemptElapsedMs: Long? = null) = synchronized(lock) {
        fun wire(value: Any) = gson.toJson(value).trim('"')
        sampling = sampling.copy(state = wire(evidence.state), source = wire(evidence.source),
            confidence = wire(evidence.confidence), reason = wire(evidence.reason), evidenceAgeMs = evidence.ageMs,
            contextStable = evidence.contextStable, capabilityStatus = if (capability) "active" else "unavailable",
            contextIntervalMs = plan.contextIntervalMs, summaryIntervalMs = plan.summaryIntervalMs, liveIntervalMs = plan.liveIntervalMs, clockUncertaintyMs = clockUncertaintyMs,
            mode = if (plan.separationEnabled) "separated" else "legacy", nextContextReadElapsedMs = nextContextReadElapsedMs,
            nextSummaryElapsedMs = nextSummaryElapsedMs, nextLiveAttemptElapsedMs = nextLiveAttemptElapsedMs)
    }

    internal fun recordObservation(unique: Int = 0, cached: Int = 0, contextRead: Boolean = false,
                                   buckets: Int = 0, gaps: Int = 0, denied: Int = 0) = synchronized(lock) {
        sampling = sampling.copy(uniqueSamples = sampling.uniqueSamples + unique,
            cachedObservations = sampling.cachedObservations + cached, contextReads = sampling.contextReads + if (contextRead) 1 else 0,
            bucketCount = sampling.bucketCount + buckets, gapCount = sampling.gapCount + gaps,
            policyDenials = sampling.policyDenials + denied)
    }

    internal fun recordSamplingAttempt(kind: String, bytes: Int, success: Boolean, lagMs: Long = 0,
                                       archiveOnly: Boolean = false, manifestGap: Boolean = false, httpAttempts: Int = 1) = synchronized(lock) {
        sampling = sampling.copy(liveAttempts = sampling.liveAttempts + if (kind == "live") 1 else 0,
            summaryAttempts = sampling.summaryAttempts + if (kind == "summary") 1 else 0,
            transitionAttempts = sampling.transitionAttempts + if (kind == "transition") 1 else 0,
            serializedBytes = sampling.serializedBytes + bytes, failures = sampling.failures + if (success) 0 else 1,
            retryLagMs = maxOf(sampling.retryLagMs, lagMs), archiveOnlyCount = sampling.archiveOnlyCount + if (archiveOnly) 1 else 0,
            manifestGapCount = sampling.manifestGapCount + if (manifestGap) 1 else 0,
            httpAttempts = sampling.httpAttempts + httpAttempts)
    }

    fun sampling(): AdaptiveSamplingDiagnostics = synchronized(lock) { sampling }

    fun recordSync(success: Boolean, beaconCount: Int) {
        synchronized(lock) {
            lastSyncAt = System.currentTimeMillis()
            lastSyncSuccess = success
            lastSyncBeaconCount = beaconCount
        }
    }

    fun recordScan(beaconCount: Int) {
        synchronized(lock) {
            lastScanAt = System.currentTimeMillis()
            lastScanBeaconCount = beaconCount
        }
    }

    fun recordError(msg: String) {
        synchronized(lock) {
            if (recentErrors.size >= MAX_ERRORS) {
                recentErrors.removeFirst()
            }
            recentErrors.addLast("${System.currentTimeMillis()} | $msg")
        }
    }

    fun lastSyncAt(): Long? = synchronized(lock) { lastSyncAt }

    fun lastSyncSuccess(): Boolean? = synchronized(lock) { lastSyncSuccess }

    fun lastSyncBeaconCount(): Int? = synchronized(lock) { lastSyncBeaconCount }

    fun lastScanAt(): Long? = synchronized(lock) { lastScanAt }

    fun lastScanBeaconCount(): Int? = synchronized(lock) { lastScanBeaconCount }

    fun recentErrors(): List<String> = synchronized(lock) { recentErrors.toList() }

    /**
     * Clears all in-memory state. Test-only: this singleton has process-lifetime state and no
     * production reason to reset, but unit tests need order-independence. Public (not
     * reflection) so it survives R8 minification in the release unit-test variant.
     */
    @VisibleForTesting
    fun reset() {
        synchronized(lock) {
            lastSyncAt = null
            lastSyncSuccess = null
            lastSyncBeaconCount = null
            lastScanAt = null
            lastScanBeaconCount = null
            recentErrors.clear()
            sampling = AdaptiveSamplingDiagnostics()
        }
    }
}
