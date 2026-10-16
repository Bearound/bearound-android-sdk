package io.bearound.sdk.sampling

import io.bearound.sdk.models.Beacon
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PhysicalCaptureQueueTest {
    private fun beacon(minor: Int) = Beacon(UUID.randomUUID(), 0, minor, -60, Beacon.Proximity.NEAR, 1.0, Date())

    @Test fun `stop barrier waits for admitted captures and rejects later callbacks`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        val complete = CompletableDeferred<Unit>()
        val captured = mutableListOf<Int>()
        var active = true
        val queue = PhysicalCaptureQueue(scope, capture = { beacon, _, _ ->
            if (beacon.minor == 1) gate.await()
            captured += beacon.minor
        }, overflow = { error("Unexpected overflow") }, failed = { throw it })
        try {
            assertTrue(queue.offer(beacon(1), 10L, 7L) { active })
            assertTrue(queue.offer(beacon(2), 20L, 7L) { active })
            assertTrue(queue.closeAdmission { active = false })
            queue.flush { assertEquals(listOf(1, 2), captured); complete.complete(Unit) }
            assertFalse(queue.offer(beacon(3), 30L, 7L) { active })
            assertFalse(complete.isCompleted)
            gate.complete(Unit)
            complete.await()
        } finally { scope.cancel() }
    }

    @Test fun `saturation triggers one fallback and a pending network request does not block the stop barrier`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        val complete = CompletableDeferred<Unit>()
        val network = CompletableDeferred<Unit>()
        val networkStarted = CompletableDeferred<Unit>()
        val captured = mutableListOf<Int>()
        var gaps = 0
        val queue = PhysicalCaptureQueue(scope, capacity = 2, capture = { beacon, _, _ ->
            if (beacon.minor == 1) gate.await()
            captured += beacon.minor
        }, overflow = {
            gaps++
            scope.launch { networkStarted.complete(Unit); network.await() }
        }, failed = { throw it })
        try {
            assertTrue(queue.offer(beacon(1), 10L, 1L) { true })
            assertTrue(queue.offer(beacon(2), 20L, 1L) { true })
            assertTrue(queue.offer(beacon(3), 30L, 1L) { true })
            repeat(10) { assertFalse(queue.offer(beacon(4), 40L, 1L) { true }) }
            queue.closeAdmission {}
            queue.flush { assertEquals(1, gaps); assertEquals(listOf(1, 2, 3), captured); complete.complete(Unit) }
            gate.complete(Unit)
            complete.await()
            assertTrue(networkStarted.isCompleted)
            assertFalse(network.isCompleted)
        } finally { scope.cancel() }
    }
    @Test fun `immediate restart waits for the stop snapshot before advancing the capture generation`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        val complete = CompletableDeferred<Unit>()
        var generation = 7L
        val captured = mutableListOf<Pair<Int, Long>>()
        val durable = mutableListOf<Pair<Int, Long>>()
        val queue = PhysicalCaptureQueue(scope, capture = { beacon, _, admittedGeneration ->
            if (beacon.minor == 1) gate.await()
            captured += beacon.minor to admittedGeneration
            if (beacon.minor == 3) complete.complete(Unit)
        }, overflow = {}, failed = { throw it })
        try {
            assertTrue(queue.offer(beacon(1), 10L, generation) { true })
            assertTrue(queue.offer(beacon(2), 20L, generation) { true })
            queue.closeAdmission {}
            queue.flush { durable += captured.toList() }
            assertTrue(queue.deferStart {
                generation++
                queue.resume()
                assertTrue(queue.offer(beacon(3), 30L, generation) { true })
            })
            assertEquals(7L, generation)
            gate.complete(Unit)
            complete.await()
            assertEquals(listOf(1 to 7L, 2 to 7L), durable)
            assertEquals(listOf(1 to 7L, 2 to 7L, 3 to 8L), captured)
        } finally { scope.cancel() }
    }

}
