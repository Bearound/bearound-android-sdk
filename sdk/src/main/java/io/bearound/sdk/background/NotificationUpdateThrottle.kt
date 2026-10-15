package io.bearound.sdk.background

import android.os.SystemClock

/**
 * Rate limiter for re-posting an ongoing notification (the foreground-service one).
 *
 * The scan callback fires several times per second while beacons are in range. Posting
 * the foreground notification on every callback made Android's NotificationManager
 * throttle the whole package ("enqueue rate ... Shedding"), which DROPS the app's other
 * notifications, rich pushes included. This helper enforces two rules:
 *
 * - dedupe: content equal to what is already shown is never posted again;
 * - throttle: at most one post per [minIntervalMs]. Changes inside the window are
 *   coalesced; only the latest one is kept and posted when the window ends (trailing).
 *
 * Pure logic with an injectable clock: the caller performs the post and schedules the
 * trailing callback ([Decision.ScheduleTrailing]) then calls [onTrailingDue].
 */
internal class NotificationUpdateThrottle<T : Any>(
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) {

    sealed interface Decision {
        /** Post the offered content now. */
        object PostNow : Decision

        /** Nothing to do now (duplicate, or coalesced into an already scheduled trailing post). */
        object Skip : Decision

        /** Keep the content pending and call [onTrailingDue] after [delayMs]. */
        data class ScheduleTrailing(val delayMs: Long) : Decision
    }

    private var lastPosted: T? = null
    private var lastPostAt: Long? = null
    private var pending: T? = null
    private var trailingScheduled = false

    @Synchronized
    fun offer(content: T): Decision {
        if (content == lastPosted) {
            // The latest desired state is already on screen: a pending change is obsolete.
            pending = null
            return Decision.Skip
        }
        val now = clock()
        val last = lastPostAt
        if (last == null || now - last >= minIntervalMs) {
            markPosted(content, now)
            return Decision.PostNow
        }
        pending = content
        if (trailingScheduled) return Decision.Skip
        trailingScheduled = true
        return Decision.ScheduleTrailing(last + minIntervalMs - now)
    }

    /** Returns the content to post when the window ends, or null when nothing changed. */
    @Synchronized
    fun onTrailingDue(): T? {
        trailingScheduled = false
        val content = pending ?: return null
        pending = null
        if (content == lastPosted) return null
        markPosted(content, clock())
        return content
    }

    /** Records a post made outside [offer] (e.g. the initial startForeground). */
    @Synchronized
    fun recordPosted(content: T) {
        pending = null
        markPosted(content, clock())
    }

    @Synchronized
    fun reset() {
        lastPosted = null
        lastPostAt = null
        pending = null
        trailingScheduled = false
    }

    private fun markPosted(content: T, now: Long) {
        lastPosted = content
        lastPostAt = now
        pending = null
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 5_000L
    }
}
