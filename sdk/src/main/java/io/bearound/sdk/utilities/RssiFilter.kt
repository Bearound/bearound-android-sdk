package io.bearound.sdk.utilities

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Per-beacon RSSI smoothing pipeline.
 *
 * Pipeline: raw sample -> sliding median (window 5) -> EMA (alpha 0.3) -> smoothed RSSI.
 *
 * Median rejects single-packet outliers (common on Android with low-duty-cycle scans).
 * EMA smooths the median series so the UI value does not jitter between adjacent medians.
 *
 * Thread-safe: one filter instance per identifier; the registry guards lookup/creation.
 */
class RssiFilter(
    private val medianWindow: Int = DEFAULT_MEDIAN_WINDOW,
    private val emaAlpha: Double = DEFAULT_EMA_ALPHA
) {
    companion object {
        const val DEFAULT_MEDIAN_WINDOW = 5
        const val DEFAULT_EMA_ALPHA = 0.3
    }

    private val window: ArrayDeque<Int> = ArrayDeque(medianWindow)
    private var ema: Double? = null

    /**
     * Push a raw RSSI sample and return the smoothed value.
     * The first sample bootstraps the EMA so the output never lags during cold start.
     */
    fun update(rawRssi: Int): Int {
        window.addLast(rawRssi)
        if (window.size > medianWindow) {
            window.removeFirst()
        }

        val median = computeMedian(window)

        val current = ema
        val next = if (current == null) median else emaAlpha * median + (1.0 - emaAlpha) * current
        ema = next

        return next.toInt()
    }

    fun reset() {
        window.clear()
        ema = null
    }

    private fun computeMedian(values: Collection<Int>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) {
            sorted[n / 2].toDouble()
        } else {
            (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
        }
    }
}

/**
 * Registry of RssiFilter instances keyed by beacon identifier (major.minor).
 * Eviction is the caller's responsibility (call [remove] when a beacon expires).
 */
class RssiFilterRegistry(
    private val medianWindow: Int = RssiFilter.DEFAULT_MEDIAN_WINDOW,
    private val emaAlpha: Double = RssiFilter.DEFAULT_EMA_ALPHA
) {
    private val lock = ReentrantLock()
    private val filters = mutableMapOf<String, RssiFilter>()

    fun smooth(identifier: String, rawRssi: Int): Int = lock.withLock {
        val filter = filters.getOrPut(identifier) { RssiFilter(medianWindow, emaAlpha) }
        filter.update(rawRssi)
    }

    fun remove(identifier: String) = lock.withLock {
        filters.remove(identifier)
    }

    fun clear() = lock.withLock {
        filters.clear()
    }
}
