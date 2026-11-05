package io.bearound.sdk.utilities

import io.bearound.sdk.models.DeviceLocation
import org.junit.Assert.*
import org.junit.Test

class FreshLocationBurstTest {
    private class Fixture {
        var now = 1_000_000L
        var wallOffset = 0L
        var allowed = true
        var persist = true
        var deferPersistence = false
        var persistenceCompletion: ((Boolean) -> Unit)? = null
        var tenant = "A"
        var generation = 1L
        var requests = 0
        var cancelled = 0
        val queued = mutableListOf<DeviceLocation>()
        var callback: ((DeviceLocation?) -> Unit)? = null
        data class Task(val due: Long, val action: () -> Unit, var cancelled: Boolean = false)
        val tasks = mutableListOf<Task>()
        val burst = FreshLocationBurst({ now }, { now + wallOffset }, { allowed }, { delay, action ->
            val task = Task(now + delay, action); tasks += task
            val cancel: () -> Unit = { task.cancelled = true }; cancel
        }, { completion ->
            requests++; callback = completion
            val cancel: () -> Unit = { cancelled++ }; cancel
        }, {
            val pinnedTenant = tenant; val pinnedGeneration = generation
            FreshLocationBurstCapture({ tenant == pinnedTenant && generation == pinnedGeneration },
                { fix, completion ->
                    if (persist) queued += fix
                    if (deferPersistence) persistenceCompletion = completion else completion(persist)
                })
        })
        fun advance(delta: Long) {
            val target = now + delta
            while (true) {
                val next = tasks.filter { !it.cancelled && it.due <= target }.minByOrNull { it.due } ?: break
                next.cancelled = true; now = next.due; next.action()
            }
            now = target
        }
        fun fix(timestamp: Long = now + wallOffset, accuracy: Float = 10f) =
            DeviceLocation(-23.0, -46.0, accuracy, timestamp = timestamp, source = "gps", isMocked = false)
        fun deliver(fix: DeviceLocation? = fix()) { callback!!(fix) }
    }

    @Test fun `three independent original timestamps are queued over seventy seconds`() {
        val f = Fixture(); f.burst.start(); f.deliver()
        f.advance(35_000); f.deliver(); f.advance(35_000); f.deliver()
        assertEquals(listOf(1_000_000L, 1_035_000L, 1_070_000L), f.queued.map { it.timestamp })
        f.advance(100_000)
        assertEquals(3, f.requests)
        f.burst.start(); assertEquals(3, f.requests)
        f.advance(730_000); f.burst.start(); assertEquals(4, f.requests)
    }

    @Test fun `cached repeated timestamp cannot become another observation`() {
        val f = Fixture(); f.burst.start(); f.deliver()
        f.advance(35_000); f.deliver(f.fix(1_000_000))
        f.advance(100_000)
        assertEquals(1, f.queued.size); assertEquals(2, f.requests)
    }

    @Test fun `poor inaccurate future mocked and missing positions do not enqueue`() {
        for (kind in 0..5) {
            val f = Fixture(); f.burst.start()
            f.deliver(when(kind) {
                0 -> f.fix(accuracy = 51f)
                1 -> f.fix(timestamp = f.now + 1)
                2 -> f.fix(timestamp = f.now - 1)
                3 -> f.fix().copy(isMocked = true)
                4 -> f.fix(accuracy = Float.NaN)
                else -> null
            })
            f.advance(110_000); assertTrue(f.queued.isEmpty()); assertEquals(1, f.requests)
        }
    }

    @Test fun `stop cancels acquisition and rejects callbacks from the previous generation`() {
        val f = Fixture(); f.burst.start(); val late = f.callback!!
        f.burst.stop(); late(f.fix()); f.advance(110_000)
        assertEquals(1, f.cancelled); assertTrue(f.queued.isEmpty()); assertEquals(1, f.requests)
    }

    @Test fun `disabled policy at callback prevents persistence and further requests`() {
        val f = Fixture(); f.burst.start(); f.allowed = false; f.deliver()
        f.advance(110_000); assertTrue(f.queued.isEmpty()); assertEquals(1, f.requests)
    }

    @Test fun `ineligible start does not request and concurrent starts coalesce`() {
        val f = Fixture(); f.allowed = false; f.burst.start(); assertEquals(0, f.requests)
        f.allowed = true; f.burst.start(); f.burst.start(); assertEquals(1, f.requests)
    }

    @Test fun `request timeout cancels listener and enters cooldown`() {
        val f = Fixture(); f.burst.start(); f.advance(10_000)
        assertEquals(1, f.cancelled); f.burst.start(); assertEquals(1, f.requests)
    }

    @Test fun `persistence failure stops rather than sending an unretained fix`() {
        val f = Fixture(); f.persist = false; f.burst.start(); f.deliver(); f.advance(110_000)
        assertTrue(f.queued.isEmpty()); assertEquals(1, f.requests)
    }
    @Test fun `stop cancels every timer including the request timeout`() {
        val f = Fixture(); f.burst.start(); f.burst.stop()
        assertTrue(f.tasks.all { it.cancelled })
    }

    @Test fun `clock change cannot extend the monotonic hard deadline`() {
        val f = Fixture(); f.burst.start(); f.deliver()
        f.wallOffset = -600_000
        f.advance(35_000); f.deliver()
        f.advance(75_000)
        assertTrue(f.tasks.all { it.cancelled })
        assertEquals(1, f.requests)
        f.burst.start(); assertEquals(1, f.requests)
    }

    @Test fun `tenant reconfigure and reenable cannot admit an old request before posted stop`() {
        val f = Fixture(); f.burst.start(); val acquiredUnderA = f.callback!!
        f.tenant = "B"; f.generation++; f.allowed = true
        acquiredUnderA(f.fix())
        assertTrue(f.queued.isEmpty()); f.advance(110_000); assertEquals(1, f.requests)
    }

    @Test fun `forward clock jump cannot compress physical acquisition duration`() {
        val f = Fixture(); f.burst.start(); f.deliver()
        f.wallOffset = 20_000
        f.advance(35_000)
        assertEquals(1, f.requests); assertEquals(1, f.queued.size)
        f.advance(15_000); assertEquals(1, f.requests)
    }

    @Test fun `fresh callback after request deadline cannot enqueue when timer dispatch is blocked`() {
        val f = Fixture(); f.burst.start()
        // Move real clocks without dispatching the queued Handler timeout.
        f.now += 11_000
        f.deliver(f.fix())
        assertTrue(f.queued.isEmpty())
        assertTrue(f.tasks.all { it.cancelled })
    }

    @Test fun `late persistence completion cannot schedule another acquisition after burst deadline`() {
        val f = Fixture(); f.deferPersistence = true
        f.burst.start(); f.deliver()
        f.now += 111_000
        f.persistenceCompletion!!(true)
        assertEquals(1, f.requests)
        assertTrue(f.tasks.all { it.cancelled })
    }

    @Test fun `scheduled acquisition checks elapsed deadline before timer dispatch`() {
        val f = Fixture(); f.burst.start(); f.deliver()
        val nextAcquisition = f.tasks.single { !it.cancelled && it.due == f.now + 35_000 }
        f.now += 111_000
        // A delayed callback can reach the looper ahead of the timeout runnable.
        nextAcquisition.action()
        assertEquals(1, f.requests)
        assertTrue(f.tasks.all { it.cancelled })
    }

}
