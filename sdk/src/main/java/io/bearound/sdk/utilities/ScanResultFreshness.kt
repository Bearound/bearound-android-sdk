package io.bearound.sdk.utilities

import android.bluetooth.le.ScanResult
import android.os.SystemClock

/**
 * Guards against BLE controller "fossil replay". Some controllers (field case: MediaTek
 * on the Redmi 9C / Helio G35) keep the last captured advertisement in the offloaded
 * batch buffer and re-deliver it on every flush after the beacon stops transmitting —
 * observed as 8 bit-identical copies of the same record, every 2 s, indefinitely
 * (`mtk_bta_batch_scan_reports_cb: full_report` with the buffer never draining). Each
 * re-delivery LOOKS fresh (the callback fires now), so the beacon never expires from
 * the detected map and keeps syncing to the backend with the radio silent.
 *
 * Two independent layers:
 *  1. [isStale] — [ScanResult.getTimestampNanos] carries the capture time
 *     (elapsedRealtimeNanos base). Legitimate delivery latency stays far below
 *     [MAX_AGE_MS]: regular ranging delivers within milliseconds, the slow-beacon
 *     batch flushes every 2 s, PendingIntent adds scheduling jitter of a few seconds.
 *     Anything older was not just received — drop it.
 *  2. [filterControllerReplay] — a batch where the SAME record (address + RSSI +
 *     payload) appears [REPLAY_MIN_COPIES]+ times is a hardware buffer replay, not
 *     air: a 1 TX/s beacon yields at most 2-3 packets per 2 s flush, and real RSSI
 *     fluctuates between packets. Only the replayed copies are removed; distinct
 *     (new) packets in the same batch still pass. Covers controllers that re-stamp
 *     the timestamp on re-delivery, which layer 1 cannot see.
 */
internal object ScanResultFreshness {

    /** Max age for a delivered result to still count as "just received". */
    private const val MAX_AGE_MS = 10_000L

    /** Identical copies of one record in a single batch at/over this count = replay. */
    private const val REPLAY_MIN_COPIES = 4

    /** True when the result was captured longer than [MAX_AGE_MS] ago. */
    fun isStale(result: ScanResult): Boolean {
        val ts = result.timestampNanos
        if (ts <= 0L) return false // stack didn't stamp the capture time — keep legacy behavior
        return ageMs(result) > MAX_AGE_MS
    }

    /** Age of the capture in milliseconds (negative-safe; monotonic clock base). */
    fun ageMs(result: ScanResult): Long =
        (SystemClock.elapsedRealtimeNanos() - result.timestampNanos) / 1_000_000L

    /**
     * Removes hardware-replayed copies from a batch. Returns the list unchanged when no
     * record repeats [REPLAY_MIN_COPIES]+ times bit-identically.
     */
    fun filterControllerReplay(results: List<ScanResult>): List<ScanResult> {
        if (results.size < REPLAY_MIN_COPIES) return results
        val counts = results.groupingBy { fingerprint(it) }.eachCount()
        if (counts.values.none { it >= REPLAY_MIN_COPIES }) return results
        return results.filter { (counts.getValue(fingerprint(it))) < REPLAY_MIN_COPIES }
    }

    private fun fingerprint(result: ScanResult): String =
        "${result.device?.address}|${result.rssi}|${result.scanRecord?.bytes?.contentHashCode()}"
}
