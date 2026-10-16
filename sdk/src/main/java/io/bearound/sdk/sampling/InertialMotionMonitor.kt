package io.bearound.sdk.sampling

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock

internal interface InertialSensorProvider {
    fun start(periodUs: Int, onSample: (InertialSample) -> Unit, onError: () -> Unit): Boolean
    fun stop()
}

/** Survives monitor generations and visibility changes in the current SDK process. */
internal class InertialStartBudget {
    private var lastStart: Long? = null

    @Synchronized fun delayUntilEligible(now: Long): Long = lastStart?.let {
        if (now < it) Long.MAX_VALUE else (30_000L - (now - it)).coerceAtLeast(0L)
    } ?: 0L

    @Synchronized fun reserve(now: Long): Boolean {
        if (now < 0 || delayUntilEligible(now) != 0L) return false
        lastStart = now
        return true
    }
}

internal class InertialMotionMonitor(
    private val generation: Long,
    private val eligible: () -> Boolean,
    private val providerFactory: () -> InertialSensorProvider,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val onWindow: (InertialWindow, Long) -> Unit,
    private val budget: InertialStartBudget = InertialStartBudget(),
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onClockUncertain: () -> Unit = {},
    private val sampleEligible: () -> Boolean = eligible
) {
    private class Burst(val id: Long, val opened: Long, val deadline: Long, val openedWall: Long) {
        val samples = mutableListOf<InertialSample>()
        var provider: InertialSensorProvider? = null
        var cancelClose: (() -> Unit)? = null
    }
    private val lock = Any()
    private var running = false
    private var nextId = 0L
    private var burst: Burst? = null
    private var cancelNext: (() -> Unit)? = null

    fun start() {
        synchronized(lock) { running = true }
        refresh()
    }

    fun stop() {
        val old = synchronized(lock) {
            running = false
            cancelNext?.invoke(); cancelNext = null
            burst.also { burst = null; it?.cancelClose?.invoke(); it?.samples?.clear() }
        }
        old?.provider?.let { runCatching { it.stop() } }
    }

    fun refresh() {
        if (!allowed()) { stop(); return }
        val current = synchronized(lock) {
            if (!running || burst != null || cancelNext != null) return
            val now = elapsedRealtime()
            val delay = budget.delayUntilEligible(now)
            if (delay != 0L) {
                if (delay != Long.MAX_VALUE) cancelNext = schedule(delay) {
                    synchronized(lock) { cancelNext = null }
                    refresh()
                }
                return
            }
            if (now < 0 || now > Long.MAX_VALUE - 3_000L || !budget.reserve(now)) return
            Burst(++nextId, now, now + 3_000L, clock()).also { burst = it }
        }
        val provider = runCatching { providerFactory() }.getOrNull()
        if (provider == null) { finish(current, false); return }
        synchronized(lock) {
            if (burst !== current || !running || !allowed()) {
                runCatching { provider.stop() }
                return
            }
            current.provider = provider
            current.cancelClose = schedule((current.deadline - elapsedRealtime()).coerceAtLeast(0L)) { finish(current, true) }
        }
        if (elapsedRealtime() !in current.opened..current.deadline || !allowed()) { finish(current, false); return }
        val registered = runCatching {
            provider.start(50_000, { sample -> onSample(current, sample) }, { finish(current, false) })
        }.getOrDefault(false)
        if (!registered) finish(current, false)
        else if (synchronized(lock) { burst !== current || !running } || !allowed()) runCatching { provider.stop() }
    }

    private fun allowed(): Boolean = runCatching { eligible() }.getOrDefault(false)

    private fun onSample(current: Burst, sample: InertialSample) {
        var complete = false
        var valid = true
        synchronized(lock) {
            if (!running || burst !== current) return
            val now = elapsedRealtime()
            val previous = current.samples.lastOrNull()?.monotonicAtMs
            valid = runCatching { sampleEligible() }.getOrDefault(false) && now >= current.opened && now <= current.deadline + 250L &&
                sample.monotonicAtMs in current.opened..minOf(now, current.deadline) &&
                sample.xG.isFinite() && sample.yG.isFinite() && sample.zG.isFinite() &&
                (previous == null || (sample.monotonicAtMs > previous && sample.monotonicAtMs - previous <= 250L))
            if (valid) {
                current.samples += sample
                complete = current.samples.size == 128
            }
        }
        if (!valid) finish(current, false) else if (complete) finish(current, true)
    }

    private fun finish(current: Burst, accept: Boolean) {
        val samples = synchronized(lock) {
            if (burst !== current) return
            burst = null
            current.cancelClose?.invoke()
            current.samples.toList().also { current.samples.clear() }
        }
        current.provider?.let { runCatching { it.stop() } }
        val completed = elapsedRealtime()
        val clockReliable = completed >= current.opened && kotlin.math.abs(clock().toDouble() -
            (current.openedWall.toDouble() + completed - current.opened)) <= 5_000.0
        if (!clockReliable) onClockUncertain()
        if (accept && clockReliable && synchronized(lock) { running } && allowed() &&
            completed in current.opened..current.deadline + 250L) {
            InertialMotionClassifier.classify(samples, generation, current.opened,
                minOf(completed, current.deadline), completed)?.let { window ->
                if (synchronized(lock) { running } && allowed())
                    onWindow(window, current.openedWall + (window.endedAtMonotonicMs - current.opened))
            }
        }
        if (synchronized(lock) { running } && allowed()) refresh() else stop()
    }
}

/** Creates no sensor service or registration until the monitor has passed its execution gates. */
internal class AndroidInertialSensorProvider(private val context: Context, private val handler: Handler) : InertialSensorProvider {
    private var manager: SensorManager? = null
    private var listener: SensorEventListener? = null

    override fun start(periodUs: Int, onSample: (InertialSample) -> Unit, onError: () -> Unit): Boolean {
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        val callback = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size < 3) { onError(); return }
                val gravity = SensorManager.GRAVITY_EARTH.toDouble()
                onSample(InertialSample(event.timestamp / 1_000_000L,
                    event.values[0].toDouble() / gravity, event.values[1].toDouble() / gravity, event.values[2].toDouble() / gravity))
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager = sensors; listener = callback
        return sensors.registerListener(callback, sensor, periodUs, handler)
    }

    override fun stop() {
        listener?.let { manager?.unregisterListener(it) }
        listener = null; manager = null
    }
}
