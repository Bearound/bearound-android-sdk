package io.bearound.sdk.utilities

import io.bearound.sdk.models.Beacon
import io.bearound.sdk.models.DataCollectionPolicy
import io.bearound.sdk.models.UserDevice
import io.bearound.sdk.network.HttpException

/**
 * Sends the batches persisted in [OfflineBatchStorage].
 *
 * Every batch goes up with the `syncTrigger` and the device context persisted WITH it, never
 * with values recomputed at drain time, so a retried beacon keeps the location where it was
 * seen and a retried visit keeps `syncTrigger = "visit"`. That makes it one request per
 * batch; only legacy batches (written before the batch carried its context) share a
 * request, up to [LEGACY_GROUP_SIZE] at a time, because they all fall back to the same
 * send-time snapshot anyway.
 *
 * Transient failures stop the drain and leave everything queued; a permanent rejection
 * quarantines exactly the rejected batch (bisecting a legacy group) and the drain goes on.
 */
internal class StoredBatchDrain(
    private val storage: OfflineBatchStorage,
    private val permanentHttpCodes: Set<Int>,
    private val send: suspend (beacons: List<Beacon>, device: UserDevice, syncTrigger: String?) -> Result<Unit>,
    private val observer: Observer = Observer.NONE,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** Side effects the SDK attaches to the drain (listener, diagnostics, backoff). */
    interface Observer {
        fun onRequestStarted(beaconCount: Int) {}
        fun onRequestDelivered(beaconCount: Int, afterBisect: Boolean) {}
        fun onTransientFailure(beaconCount: Int, error: Throwable) {}
        fun onQuarantined(batchId: String, statusCode: Int) {}

        companion object {
            val NONE = object : Observer {}
        }
    }

    companion object {
        /** Legacy batches per request: the pre-change chunk size. */
        const val LEGACY_GROUP_SIZE = 5

        /** The ingest only honours visit capture times within the last 24 h. */
        const val VISIT_MAX_AGE_MS = 24L * 60 * 60 * 1000

        /**
         * The requests the drain sends, in FIFO order: each batch alone, except consecutive
         * legacy batches, grouped up to [LEGACY_GROUP_SIZE].
         */
        fun plan(records: List<OfflineBatchStorage.StoredBatchRecord>): List<List<OfflineBatchStorage.StoredBatchRecord>> {
            val requests = mutableListOf<List<OfflineBatchStorage.StoredBatchRecord>>()
            var legacy = mutableListOf<OfflineBatchStorage.StoredBatchRecord>()
            for (record in records) {
                if (record.isLegacy) {
                    legacy += record
                    if (legacy.size == LEGACY_GROUP_SIZE) {
                        requests += legacy
                        legacy = mutableListOf()
                    }
                } else {
                    if (legacy.isNotEmpty()) {
                        requests += legacy
                        legacy = mutableListOf()
                    }
                    requests += listOf(record)
                }
            }
            if (legacy.isNotEmpty()) requests += legacy
            return requests
        }

        /**
         * The device block for [record]: the captured context over a send-time snapshot
         * ([fresh], which also covers everything not captured). A legacy batch has no
         * captured context and gets [fresh] as is. The host's CURRENT [policy] still wins:
         * a signal it turned off since the capture does not leave the device.
         */
        fun deviceFor(
            record: OfflineBatchStorage.StoredBatchRecord,
            fresh: UserDevice,
            policy: DataCollectionPolicy
        ): UserDevice {
            val context = record.context ?: return fresh
            return fresh.copy(
                location = if (policy.location) context.location else null,
                wifis = if (policy.wifi) context.wifis ?: fresh.wifis else emptyList()
            )
        }
    }

    /**
     * Sends [records] (oldest first) and removes each batch once delivered.
     * @return false when a transient failure stopped the drain (callers with an execution
     *         window can retry).
     */
    suspend fun drain(
        records: List<OfflineBatchStorage.StoredBatchRecord>,
        fresh: UserDevice,
        policy: DataCollectionPolicy
    ): Boolean {
        val now = clock()
        val live = records.filter { record ->
            if (isExpiredVisit(record, now)) {
                storage.removeBatch(record.id)
                false
            } else {
                true
            }
        }

        for (request in plan(live)) {
            val head = request.first()
            val beaconCount = request.sumOf { it.beacons.size }
            observer.onRequestStarted(beaconCount)
            val result = send(request.flatMap { it.beacons }, deviceFor(head, fresh, policy), head.syncTrigger)

            if (result.isSuccess) {
                storage.removeBatches(request.map { it.id })
                observer.onRequestDelivered(beaconCount, afterBisect = false)
                continue
            }

            val error = result.exceptionOrNull() ?: IllegalStateException("send failed")
            if (permanentStatus(result) == null) {
                // Transient (network, timeout, 408/429/5xx): stop and let the caller's
                // backoff retry the whole queue later; nothing is lost.
                observer.onTransientFailure(beaconCount, error)
                return false
            }

            // Permanent rejection: the backend is healthy but some batch is poison, and an
            // identical retry fails identically. Quarantine exactly the rejected batch (one
            // at a time for a legacy group) so it cannot block the queue behind it.
            var delivered = 0
            for (record in request) {
                val single = if (request.size == 1) {
                    result
                } else {
                    send(record.beacons, deviceFor(record, fresh, policy), record.syncTrigger)
                }
                val status = permanentStatus(single)
                when {
                    single.isSuccess -> {
                        storage.removeBatch(record.id)
                        delivered += record.beacons.size
                    }
                    status != null -> {
                        observer.onQuarantined(record.id, status)
                        storage.quarantineBatch(record.id)
                    }
                    // Network blinked mid-bisect: stop; everything left stays queued.
                    else -> return false
                }
            }
            if (delivered > 0) observer.onRequestDelivered(delivered, afterBisect = true)
        }
        return true
    }

    private fun permanentStatus(result: Result<Unit>): Int? {
        val status = (result.exceptionOrNull() as? HttpException)?.statusCode ?: return null
        return status.takeIf { it in permanentHttpCodes }
    }

    /** A visit whose fix is past the ingest's capture window would be ignored: drop it. */
    private fun isExpiredVisit(record: OfflineBatchStorage.StoredBatchRecord, now: Long): Boolean {
        if (record.syncTrigger != OfflineBatchStorage.VISIT_SYNC_TRIGGER) return false
        // A Wi-Fi visit without GPS has no fix: its first observation dates it.
        val fixAt = record.context?.location?.timestamp
            ?: record.context?.wifis?.firstOrNull()?.timestamp
            ?: return false
        return now - fixAt > VISIT_MAX_AGE_MS
    }
}
