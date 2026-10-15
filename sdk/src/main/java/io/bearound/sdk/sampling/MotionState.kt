package io.bearound.sdk.sampling

import com.google.gson.annotations.SerializedName

internal enum class MotionState {
    @SerializedName("unknown") UNKNOWN,
    @SerializedName("moving_inferred") MOVING_INFERRED,
    @SerializedName("moving_confirmed") MOVING_CONFIRMED,
    @SerializedName("stationary_candidate") STATIONARY_CANDIDATE,
    @SerializedName("stationary_inferred") STATIONARY_INFERRED,
    @SerializedName("stationary_confirmed") STATIONARY_CONFIRMED
}

internal enum class MotionSource {
    @SerializedName("native_activity") NATIVE_ACTIVITY,
    @SerializedName("fresh_location") FRESH_LOCATION,
    @SerializedName("context_fusion") CONTEXT_FUSION,
    @SerializedName("none") NONE
}

internal enum class MotionConfidence {
    @SerializedName("none") NONE,
    @SerializedName("low") LOW,
    @SerializedName("medium") MEDIUM,
    @SerializedName("high") HIGH
}

internal enum class MotionReason {
    @SerializedName("startup") STARTUP,
    @SerializedName("native_moving") NATIVE_MOVING,
    @SerializedName("native_stationary_candidate") NATIVE_STATIONARY_CANDIDATE,
    @SerializedName("native_stationary_confirmed") NATIVE_STATIONARY_CONFIRMED,
    @SerializedName("location_displacement") LOCATION_DISPLACEMENT,
    @SerializedName("context_changed") CONTEXT_CHANGED,
    @SerializedName("context_stable") CONTEXT_STABLE,
    @SerializedName("stationary_inferred") STATIONARY_INFERRED,
    @SerializedName("evidence_expired") EVIDENCE_EXPIRED,
    @SerializedName("permission_unavailable") PERMISSION_UNAVAILABLE,
    @SerializedName("source_disabled") SOURCE_DISABLED,
    @SerializedName("bluetooth_unavailable") BLUETOOTH_UNAVAILABLE,
    @SerializedName("clock_uncertain") CLOCK_UNCERTAIN,
    @SerializedName("unsupported") UNSUPPORTED,
    @SerializedName("no_evidence") NO_EVIDENCE
}

internal data class MotionEvidence(
    val state: MotionState,
    val source: MotionSource,
    val confidence: MotionConfidence,
    val reason: MotionReason,
    val observedAtMs: Long?,
    val ageMs: Long?,
    val contextStable: Boolean
)

internal fun MotionEvidence.isMoving() = state == MotionState.MOVING_CONFIRMED || state == MotionState.MOVING_INFERRED

internal fun isInertialMotionTuple(state: String?, source: String?, confidence: String?, reason: String?) =
    state == "moving_inferred" && source == "context_fusion" && confidence == "low" && reason == "context_changed"

internal enum class NativeMotionActivity { STILL, WALKING, RUNNING, CYCLING, VEHICLE }

internal data class NativeActivityObservation(
    val activity: NativeMotionActivity,
    val entering: Boolean,
    val observedAtElapsedMs: Long?
)

/** A window of new physical BLE samples, never a repeated cache or restored region. */
internal data class ContextObservation(
    val windowId: String,
    val observedAtElapsedMs: Long?,
    val beaconRssi: Map<String, Int>,
    val connectedApId: String? = null,
    val fresh: Boolean = true
)

internal data class SamplingCapability(
    val sessionTimeoutMs: Long,
    val advertisedServicesCadenceMs: Long,
    val mapTtlMs: Long,
    val summaryMaxIntervalMs: Long = 300_000L
)

internal data class SamplingPlan(
    val separationEnabled: Boolean,
    val contextIntervalMs: Long?,
    val summaryIntervalMs: Long,
    val liveIntervalMs: Long,
    val immediateFlush: Boolean = false
)
