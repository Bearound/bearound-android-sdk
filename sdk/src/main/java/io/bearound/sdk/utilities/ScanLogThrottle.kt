package io.bearound.sdk.utilities

import io.bearound.sdk.models.Beacon

internal class ScanLogThrottle(
    private val minIntervalMs: Long = 10_000L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private var lastComposition: Set<String>? = null
    private var lastLogAt: Long = 0L

    fun detailIfDue(beacons: List<Beacon>, buildDetail: () -> String): String? {
        if (beacons.isEmpty()) return null

        val composition = beacons.map { "${it.uuid}:${it.identifier}" }.toSet()
        val now = clock()
        if (composition == lastComposition && now - lastLogAt <= minIntervalMs) return null

        val detail = buildDetail()
        lastComposition = composition
        lastLogAt = now
        return detail
    }
}
