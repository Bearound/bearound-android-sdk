package io.bearound.sdk.models

import kotlin.math.sqrt

/**
 * Aggregated RSSI statistics over a sync window for a single beacon.
 *
 * Built incrementally via [Accumulator] so we never store all samples — just running sums.
 * Useful for trilateration weighting: avg gives a stable estimate, stdDev signals noise.
 */
data class RssiStats(
    val count: Int,
    val min: Int,
    val max: Int,
    val avg: Double,
    val stdDev: Double,
    val firstSeen: Long,
    val lastSeen: Long
) {
    /**
     * Streaming accumulator using Welford's online algorithm for variance.
     * Constant memory regardless of sample count.
     */
    class Accumulator {
        private var n: Int = 0
        private var minVal: Int = Int.MAX_VALUE
        private var maxVal: Int = Int.MIN_VALUE
        private var mean: Double = 0.0
        private var m2: Double = 0.0
        private var first: Long = 0L
        private var last: Long = 0L

        fun add(rssi: Int, timestampMs: Long) {
            n += 1
            if (rssi < minVal) minVal = rssi
            if (rssi > maxVal) maxVal = rssi

            val delta = rssi - mean
            mean += delta / n
            val delta2 = rssi - mean
            m2 += delta * delta2

            if (n == 1) first = timestampMs
            last = timestampMs
        }

        fun snapshot(): RssiStats? {
            if (n == 0) return null
            val variance = if (n > 1) m2 / (n - 1) else 0.0
            return RssiStats(
                count = n,
                min = minVal,
                max = maxVal,
                avg = mean,
                stdDev = sqrt(variance),
                firstSeen = first,
                lastSeen = last
            )
        }

        fun reset() {
            n = 0
            minVal = Int.MAX_VALUE
            maxVal = Int.MIN_VALUE
            mean = 0.0
            m2 = 0.0
            first = 0L
            last = 0L
        }
    }
}
