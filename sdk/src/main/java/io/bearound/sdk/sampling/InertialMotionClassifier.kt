package io.bearound.sdk.sampling

import kotlin.math.abs
import kotlin.math.sqrt

internal data class InertialSample(
    val monotonicAtMs: Long,
    val xG: Double,
    val yG: Double,
    val zG: Double
)

internal enum class InertialClassification { ACTIVE, QUIET, AMBIGUOUS }

internal data class InertialWindow(
    val generation: Long,
    val startedAtMonotonicMs: Long,
    val endedAtMonotonicMs: Long,
    val sampleCount: Int,
    val dynamicRmsG: Double,
    val excursionRatio: Double,
    val classification: InertialClassification
)

internal object InertialMotionClassifier {
    const val BURST_DURATION_MS = 3_000L
    const val MINIMUM_SAMPLE_COUNT = 30
    const val MAXIMUM_SAMPLE_COUNT = 128
    const val MINIMUM_SAMPLE_SPAN_MS = 2_000L
    const val MAXIMUM_SAMPLE_GAP_MS = 250L
    const val MAXIMUM_COMPLETION_AGE_MS = 5_000L
    private const val ACTIVE_RMS_G = 0.06
    private const val ACTIVE_SAMPLE_THRESHOLD_G = 0.04
    private const val MINIMUM_ACTIVE_FRACTION = 0.2
    private const val QUIET_RMS_G = 0.02
    private const val NUMERIC_TOLERANCE_G = 0.000001

    fun classify(
        samples: List<InertialSample>,
        generation: Long,
        openedAtMonotonicMs: Long,
        closedAtMonotonicMs: Long,
        completedAtMonotonicMs: Long
    ): InertialWindow? {
        if (samples.size !in MINIMUM_SAMPLE_COUNT..MAXIMUM_SAMPLE_COUNT ||
            openedAtMonotonicMs < 0L || closedAtMonotonicMs < openedAtMonotonicMs ||
            closedAtMonotonicMs - openedAtMonotonicMs > BURST_DURATION_MS ||
            completedAtMonotonicMs < closedAtMonotonicMs
        ) return null

        val firstAt = samples.first().monotonicAtMs
        val lastAt = samples.last().monotonicAtMs
        if (firstAt < openedAtMonotonicMs || lastAt > closedAtMonotonicMs ||
            lastAt < firstAt || lastAt - firstAt < MINIMUM_SAMPLE_SPAN_MS ||
            completedAtMonotonicMs - lastAt > MAXIMUM_COMPLETION_AGE_MS
        ) return null

        val magnitudes = DoubleArray(samples.size)
        samples.forEachIndexed { index, sample ->
            if (sample.monotonicAtMs < openedAtMonotonicMs ||
                sample.monotonicAtMs > closedAtMonotonicMs ||
                !sample.xG.isFinite() || !sample.yG.isFinite() || !sample.zG.isFinite()
            ) return null
            if (index > 0) {
                val previousAt = samples[index - 1].monotonicAtMs
                if (sample.monotonicAtMs <= previousAt ||
                    sample.monotonicAtMs - previousAt > MAXIMUM_SAMPLE_GAP_MS
                ) return null
            }
            val magnitude = sqrt(sample.xG * sample.xG + sample.yG * sample.yG + sample.zG * sample.zG)
            if (!magnitude.isFinite()) return null
            magnitudes[index] = magnitude
        }

        val sorted = magnitudes.sorted()
        val middle = sorted.size / 2
        val median = if (sorted.size % 2 == 0) {
            sorted[middle - 1] + (sorted[middle] - sorted[middle - 1]) / 2.0
        } else {
            sorted[middle]
        }
        var squaredSum = 0.0
        var excursions = 0
        magnitudes.forEach { magnitude ->
            val dynamic = magnitude - median
            squaredSum += dynamic * dynamic
            if (abs(dynamic) + NUMERIC_TOLERANCE_G >= ACTIVE_SAMPLE_THRESHOLD_G) excursions++
        }
        val rms = sqrt(squaredSum / samples.size)
        if (!rms.isFinite()) return null
        val excursionRatio = excursions.toDouble() / samples.size
        val classification = when {
            rms + NUMERIC_TOLERANCE_G >= ACTIVE_RMS_G && excursionRatio >= MINIMUM_ACTIVE_FRACTION ->
                InertialClassification.ACTIVE
            rms <= QUIET_RMS_G + NUMERIC_TOLERANCE_G -> InertialClassification.QUIET
            else -> InertialClassification.AMBIGUOUS
        }
        return InertialWindow(generation, firstAt, lastAt, samples.size, rms, excursionRatio, classification)
    }
}
