package io.bearound.sdk.utilities

import io.bearound.sdk.models.DeviceLocation

internal data class FreshLocationBurstCapture(
    val isCurrent: () -> Boolean,
    val enqueue: (DeviceLocation, (Boolean) -> Unit) -> Unit
)

/** Serial, bounded foreground-only acquisition. Scheduling and platform I/O are injected. */
internal class FreshLocationBurst(
    private val elapsed: () -> Long,
    private val wallTime: () -> Long,
    private val eligible: () -> Boolean,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val request: ((DeviceLocation?) -> Unit) -> (() -> Unit),
    private val capture: () -> FreshLocationBurstCapture?
) {
    private var generation = 0L
    private var running = false
    private var nextAllowed = Long.MIN_VALUE
    private var cancelRequest: (() -> Unit)? = null
    private val cancellations = mutableListOf<() -> Unit>()
    private var count = 0
    private var captured: FreshLocationBurstCapture? = null
    private var firstFixElapsed = 0L
    private var initialWall = 0L
    private var initialElapsed = 0L
    private var firstTimestamp = 0L
    private var lastTimestamp = 0L

    fun start() {
        if (running || !eligible() || elapsed() < nextAllowed) return
        val owner = capture() ?: return
        if (!owner.isCurrent()) return
        captured = owner
        initialWall = wallTime()
        initialElapsed = elapsed()
        running = true
        count = 0
        val token = ++generation
        nextAllowed = elapsed() + 15 * 60_000L
        cancellations += schedule(110_000L) { if (token == generation) stop() }
        acquire(token)
    }

    fun stop() {
        generation++
        running = false
        captured = null
        cancelRequest?.invoke()
        cancelRequest = null
        cancellations.toList().forEach { it() }
        cancellations.clear()
    }

    private fun acquire(token: Long) {
        if (token != generation) return
        val owner = captured ?: return
        if (!eligible() || !owner.isCurrent() || !clockStable()) { stop(); return }
        val requestedAt = wallTime()
        var completed = false
        val timeout = schedule(10_000L) { if (token == generation) stop() }
        cancellations += timeout
        val cancel = request { fix ->
            if (completed || token != generation) return@request
            completed = true
            timeout()
            cancelRequest = null
            val acceptedElapsed = elapsed()
            if (!eligible() || !owner.isCurrent() || !clockStable() || fix == null || !valid(fix, requestedAt) ||
                (count == 2 && acceptedElapsed - firstFixElapsed < 60_000L)) {
                stop(); return@request
            }
            owner.enqueue(fix) { saved ->
                if (token == generation) {
                    if (!saved || !eligible() || !owner.isCurrent() || !clockStable()) stop() else {
                        lastTimestamp = fix.timestamp
                        if (count++ == 0) { firstTimestamp = fix.timestamp; firstFixElapsed = acceptedElapsed }
                        if (count == 3) stop() else {
                            // Cadence and physical duration use monotonic time, never wall-clock jumps.
                            val due = firstFixElapsed + count * 35_000L
                            cancellations += schedule((due - elapsed()).coerceAtLeast(0)) { acquire(token) }
                        }
                    }
                }
            }
        }
        if (completed || token != generation) cancel() else cancelRequest = cancel
    }

    private fun clockStable(): Boolean =
        kotlin.math.abs((wallTime() - initialWall) - (elapsed() - initialElapsed)) <= 1_000L

    private fun valid(fix: DeviceLocation, requestedAt: Long): Boolean =
        fix.latitude.isFinite() && fix.latitude in -90.0..90.0 &&
            fix.longitude.isFinite() && fix.longitude in -180.0..180.0 &&
            fix.accuracy?.let { it.isFinite() && it > 0 && it <= 50 } == true &&
            fix.isMocked != true && fix.timestamp >= requestedAt &&
            fix.timestamp <= wallTime() && wallTime() - fix.timestamp <= 15_000L &&
            (count == 0 || (fix.timestamp > lastTimestamp && fix.timestamp - lastTimestamp <= 300_000L)) &&
            (count != 2 || fix.timestamp - firstTimestamp >= 60_000L)
}
