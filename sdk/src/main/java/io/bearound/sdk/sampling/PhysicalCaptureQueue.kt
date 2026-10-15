package io.bearound.sdk.sampling

import io.bearound.sdk.models.Beacon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bounded radio handoff with ordered stop and overflow controls on the existing SDK scope. */
internal class PhysicalCaptureQueue(
    scope: CoroutineScope,
    private val capacity: Int = 256,
    private val capture: suspend (Beacon, Long?, Long) -> Unit,
    private val overflow: suspend (Long) -> Unit,
    private val failed: (Throwable) -> Unit
) {
    private sealed interface Command
    private data class Capture(val beacon: Beacon, val elapsed: Long?, val generation: Long) : Command
    private data class Overflow(val generation: Long) : Command
    private data class Flush(val action: suspend () -> Unit) : Command
    private val lock = Any()
    // Data admission is bounded separately so stop/overflow controls never wait behind a full channel.
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var pending = 0
    private var closed = false
    private var overflowPending = false

    init {
        require(capacity > 0)
        scope.launch {
            for (command in commands) {
                try {
                    when (command) {
                        is Capture -> {
                            synchronized(lock) { pending-- }
                            capture(command.beacon, command.elapsed, command.generation)
                        }
                        is Overflow -> {
                            overflow(command.generation)
                            synchronized(lock) { overflowPending = false }
                        }
                        is Flush -> command.action()
                    }
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    failed(error)
                    if (command is Overflow) synchronized(lock) { overflowPending = false }
                }
            }
        }
    }

    fun resume() = synchronized(lock) { closed = false }

    fun offer(beacon: Beacon, elapsed: Long?, generation: Long, allowed: () -> Boolean): Boolean = synchronized(lock) {
        if (closed || !allowed()) return@synchronized false
        if (pending >= capacity) {
            if (!overflowPending) {
                overflowPending = true
                commands.trySend(Overflow(generation))
            }
            return@synchronized false
        }
        pending++
        if (commands.trySend(Capture(beacon, elapsed, generation)).isFailure) {
            pending--
            return@synchronized false
        }
        true
    }

    fun closeAdmission(beforeBarrier: () -> Unit): Boolean = synchronized(lock) {
        beforeBarrier()
        if (closed) return@synchronized false
        closed = true
        true
    }

    fun deferStart(action: suspend () -> Unit): Boolean = synchronized(lock) {
        if (!closed) return@synchronized false
        commands.trySend(Flush(action))
        true
    }

    fun flush(action: suspend () -> Unit) {
        commands.trySend(Flush(action))
    }
}
