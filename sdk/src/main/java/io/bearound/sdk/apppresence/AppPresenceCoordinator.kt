package io.bearound.sdk.apppresence

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import io.bearound.sdk.collectors.AppPresenceCollector
import io.bearound.sdk.interfaces.BeAroundSDKListener
import io.bearound.sdk.models.AppPresenceConfiguration
import io.bearound.sdk.models.AppPresenceConfigurationException
import io.bearound.sdk.models.AppPresenceSnapshot
import io.bearound.sdk.models.AppPresenceTime
import io.bearound.sdk.models.AppPresenceValidator
import io.bearound.sdk.utilities.AppPresenceReadResult
import io.bearound.sdk.utilities.AppPresenceRecord
import io.bearound.sdk.utilities.AppPresenceStorageException
import io.bearound.sdk.utilities.AppPresenceStore
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Time sources of the coordinator. Only [elapsedRealtime] ever releases a cooldown. */
internal interface AppPresenceClock {
    fun wallMillis(): Long
    fun elapsedRealtime(): Long
}

internal object SystemAppPresenceClock : AppPresenceClock {
    override fun wallMillis(): Long = System.currentTimeMillis()
    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
}

/**
 * Lifecycle, scheduling and delivery of the app presence feature.
 *
 * - Runs only while activated by `startScanning` (independent of BLE and permissions), in
 *   the main process, with an enabled, non-empty configuration and a known client.
 * - Triggers ([tryRun]): foreground, the active sync timer, activation and reconfiguration.
 *   There is no worker, service or alarm of its own: nothing runs while the app is not.
 * - At most one round per [MIN_INTERVAL_MILLIS] per namespace (host package + client),
 *   enforced by a reservation persisted BEFORE any package is checked. Eligibility uses
 *   `elapsedRealtime` and the boot count only; the wall clock never releases a window.
 * - One serial round at a time; a generation counter discards a round whose configuration,
 *   client or activation changed while it ran (its reservation stays consumed).
 * - Snapshots are persisted before dispatch, delivered on the main thread, replayed with
 *   `cached = true` to each newly assigned listener and deduplicated per assignment.
 *
 * Nothing here is uploaded, logged with target data, or reported to error telemetry.
 */
internal class AppPresenceCoordinator(
    private val packageName: String,
    private val isMainProcess: Boolean,
    private val store: AppPresenceStore,
    private val collector: AppPresenceCollector,
    private val clock: AppPresenceClock,
    private val bootCount: () -> Int?,
    private val executor: Executor,
    private val mainPoster: (Runnable) -> Unit,
    private val errorSink: (Exception) -> Unit,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {
    companion object {
        private const val TAG = "BeAroundAppPresence"

        /** Minimum gap between two reservations of the same namespace: one hour. */
        const val MIN_INTERVAL_MILLIS = 3_600_000L

        fun create(
            context: Context,
            mainHandler: Handler,
            errorSink: (Exception) -> Unit
        ): AppPresenceCoordinator {
            val app = context.applicationContext ?: context
            return AppPresenceCoordinator(
                packageName = app.packageName,
                isMainProcess = isMainProcess(app),
                store = AppPresenceStore.create(app),
                collector = AppPresenceCollector.create(app),
                clock = SystemAppPresenceClock,
                bootCount = { readBootCount(app) },
                executor = Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "bearound-app-presence").apply { isDaemon = true }
                },
                mainPoster = { runnable -> mainHandler.post(runnable) },
                errorSink = errorSink
            )
        }

        /** `Settings.Global.BOOT_COUNT` on API 24+, `null` when unavailable (API 23, OEM). */
        internal fun readBootCount(context: Context): Int? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
            return try {
                Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Whether this is the host's main process. Secondary processes (`:remote` services
         * and the like) never run the feature. An undeterminable name counts as main: the
         * reservation file still serializes any concurrent attempt.
         */
        internal fun isMainProcess(context: Context): Boolean {
            val name = currentProcessName(context) ?: return true
            return name == context.packageName
        }

        private fun currentProcessName(context: Context): String? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            try {
                File("/proc/self/cmdline").readText().trim { it <= ' ' || it == '\u0000' }
                    .takeIf { it.isNotEmpty() }?.let { return it }
            } catch (_: Exception) {
                // fall through
            }
            return try {
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                manager?.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Process launch reference for the quarantine fallback. */
    private val launchElapsed: Long = clock.elapsedRealtime()

    private val lock = Any()

    // region state guarded by [lock]
    private var configuration: AppPresenceConfiguration = AppPresenceConfiguration.DISABLED
    private var fingerprint: String? = null
    private var namespace: String? = null
    private var active = false
    private var generation = 0L
    private var roundQueued = false
    private var cacheNamespace: String? = null
    private var cachedSnapshot: AppPresenceSnapshot? = null
    private val nextEligibleElapsed = HashMap<String, Long>()
    private var storageErrorGeneration = -1L
    private var listener: BeAroundSDKListener? = null
    private var listenerEpoch = 0L
    private var deliveredEpoch = -1L
    private var deliveredSnapshotId: String? = null
    // endregion

    private fun isOn(config: AppPresenceConfiguration) = config.enabled && config.targets.isNotEmpty()

    /**
     * Applies a new configuration. Invalid: the feature is disabled and the error returned
     * (the caller surfaces it). Disabled or empty: the snapshot is deleted, the cooldown kept.
     * Never queries by itself; an active session may run a round if eligible.
     */
    fun configure(configuration: AppPresenceConfiguration): AppPresenceConfigurationException? {
        val error = configuration.validationError()
        val effective = if (error == null) AppPresenceValidator.normalize(configuration)
        else AppPresenceConfiguration.DISABLED
        val newFingerprint = if (isOn(effective)) AppPresenceValidator.fingerprint(effective.targets) else null
        val (ns, wasActive) = synchronized(lock) {
            this.configuration = effective
            this.fingerprint = newFingerprint
            generation++
            if (newFingerprint == null || cachedSnapshot?.configurationFingerprint != newFingerprint) {
                cachedSnapshot = null
            }
            namespace to active
        }
        if (ns != null) clearPersistedSnapshot(ns, newFingerprint)
        if (wasActive) tryRun()
        return error
    }

    /** Current client. `null` (not configured) stops the feature until a token is known. */
    fun setBusinessToken(businessToken: String?) {
        val ns = businessToken?.takeIf { it.isNotBlank() }?.let { AppPresenceStore.namespace(packageName, it) }
        val (wasActive, fp) = synchronized(lock) {
            if (ns == namespace) return
            namespace = ns
            generation++
            cachedSnapshot = null
            cacheNamespace = null
            active to fingerprint
        }
        if (ns != null) clearPersistedSnapshot(ns, fp)
        if (wasActive) tryRun()
    }

    /** `startScanning`: enables triggers and tries a first eligible round right away. */
    fun activate() {
        if (!isMainProcess) return
        synchronized(lock) { active = true }
        tryRun()
    }

    /**
     * `stopScanning`: no more rounds; the running round and every pending delivery (live or
     * replay) are discarded. The cache stays readable through [lastSnapshot].
     */
    fun pause() {
        synchronized(lock) {
            active = false
            generation++
            listenerEpoch++
        }
    }

    /** Assigns the listener and replays the cached compatible snapshot to it (cached = true). */
    fun setListener(newListener: BeAroundSDKListener?) {
        val epoch = synchronized(lock) {
            listener = newListener
            ++listenerEpoch
        }
        if (newListener == null) return
        mainPoster(Runnable { replay(epoch) })
    }

    /** The last compatible snapshot with `cached = true`, or null. Never queries any package. */
    fun lastSnapshot(): AppPresenceSnapshot? = synchronized(lock) { compatibleSnapshotLocked() }?.copy(cached = true)

    /** Requests a round. Cheap no-op unless active, configured and enabled. */
    fun tryRun() {
        if (!isMainProcess) return
        synchronized(lock) {
            if (!active || !isOn(configuration) || namespace == null || roundQueued) return
            roundQueued = true
        }
        try {
            executor.execute {
                try {
                    runRound()
                } catch (e: Exception) {
                    Log.w(TAG, "App presence round aborted (${e.javaClass.simpleName})")
                } finally {
                    synchronized(lock) { roundQueued = false }
                }
            }
        } catch (_: RejectedExecutionException) {
            synchronized(lock) { roundQueued = false }
        }
    }

    private class RoundInput(
        val generation: Long,
        val namespace: String,
        val configuration: AppPresenceConfiguration,
        val fingerprint: String
    )

    private fun runRound() {
        val input = synchronized(lock) {
            val ns = namespace
            val fp = fingerprint
            if (!active || !isOn(configuration) || ns == null || fp == null) return
            RoundInput(generation, ns, configuration, fp)
        }
        val now = clock.elapsedRealtime()
        val known = synchronized(lock) { nextEligibleElapsed[input.namespace] }
        if (known != null && now < known) return

        val read = try {
            store.read(input.namespace)
        } catch (e: IOException) {
            reportStorageFailure(input.generation, e)
            return
        }
        if (known == null) {
            val next = nextEligibleFrom(read, input.namespace, now)
            synchronized(lock) { nextEligibleElapsed[input.namespace] = next }
            if (now < next) return
        }

        // Reserve durably BEFORE the first package lookup. A crash from here on consumes
        // the window; a write failure cancels the round without checking anything.
        val base = (read as? AppPresenceReadResult.Found)?.record
        val reservation = AppPresenceRecord(
            namespace = input.namespace,
            hasReservation = true,
            reservedAtEpochMillis = clock.wallMillis(),
            reservedElapsedRealtime = now,
            bootCount = bootCount(),
            fingerprint = base?.fingerprint,
            lastSnapshot = base?.lastSnapshot
        )
        try {
            store.write(reservation)
        } catch (e: IOException) {
            reportStorageFailure(input.generation, e)
            return
        }
        synchronized(lock) { nextEligibleElapsed[input.namespace] = now + MIN_INTERVAL_MILLIS }
        if (isStale(input.generation)) return

        val results = collector.collect(input.configuration.targets)
        val snapshot = AppPresenceSnapshot(
            snapshotId = idGenerator(),
            configurationFingerprint = input.fingerprint,
            checkedAt = AppPresenceTime.format(clock.wallMillis()),
            cached = false,
            results = results
        )
        if (isStale(input.generation)) return

        try {
            store.write(reservation.copy(fingerprint = input.fingerprint, lastSnapshot = snapshot))
        } catch (_: IOException) {
            // The reservation is already durable; only the replay across restarts is lost.
            Log.w(TAG, "App presence snapshot not persisted")
        }
        synchronized(lock) {
            if (generation != input.generation) return
            cacheNamespace = input.namespace
            cachedSnapshot = snapshot
        }
        mainPoster(Runnable { deliverLive(snapshot, input.generation) })
    }

    /**
     * Eligibility of the first round of this namespace in this process:
     * - no record (first install) or no reservation: now;
     * - same boot (equal boot count) and non-regressive elapsed time: reservation + interval;
     * - otherwise (reboot, API 23, unreadable boot count, corrupt record): quarantine of one
     *   interval from process launch. A corrupt record, or a known new boot, is rewritten as
     *   that quarantine marker, so later relaunches in the same boot reuse it instead of
     *   restarting the quarantine. With no boot count (API 23) every process quarantines.
     */
    private fun nextEligibleFrom(read: AppPresenceReadResult, namespace: String, now: Long): Long {
        val quarantine = launchElapsed + MIN_INTERVAL_MILLIS
        return when (read) {
            AppPresenceReadResult.Missing -> Long.MIN_VALUE
            AppPresenceReadResult.Corrupt -> {
                writeQuarantineMarker(namespace, bootCount(), base = null)
                quarantine
            }
            is AppPresenceReadResult.Found -> {
                val record = read.record
                val reservedElapsed = record.reservedElapsedRealtime
                if (!record.hasReservation || reservedElapsed == null) return Long.MIN_VALUE
                val currentBoot = bootCount()
                val sameBoot = currentBoot != null && record.bootCount != null && currentBoot == record.bootCount
                if (sameBoot && now >= reservedElapsed) {
                    reservedElapsed + MIN_INTERVAL_MILLIS
                } else {
                    if (currentBoot != null) writeQuarantineMarker(namespace, currentBoot, base = record)
                    quarantine
                }
            }
        }
    }

    /** Persists a reservation at process launch: the quarantine as a same-boot interval. */
    private fun writeQuarantineMarker(namespace: String, boot: Int?, base: AppPresenceRecord?) {
        try {
            store.write(
                AppPresenceRecord(
                    namespace = namespace,
                    hasReservation = true,
                    reservedAtEpochMillis = clock.wallMillis(),
                    reservedElapsedRealtime = launchElapsed,
                    bootCount = boot,
                    fingerprint = base?.fingerprint,
                    lastSnapshot = base?.lastSnapshot
                )
            )
        } catch (_: IOException) {
            // The in-memory quarantine still holds; the reservation write fails closed later.
            Log.w(TAG, "App presence quarantine marker could not be persisted")
        }
    }

    private fun isStale(roundGeneration: Long): Boolean = synchronized(lock) { generation != roundGeneration }

    private fun clearPersistedSnapshot(namespace: String, keepFingerprint: String?) {
        try {
            executor.execute {
                try {
                    store.clearSnapshot(namespace, keepFingerprint)
                } catch (_: IOException) {
                    Log.w(TAG, "App presence snapshot could not be cleared")
                }
            }
        } catch (_: RejectedExecutionException) {
            // Executor gone: the in-memory filter already hides the stale snapshot.
        }
    }

    private fun reportStorageFailure(roundGeneration: Long, cause: IOException) {
        val report = synchronized(lock) {
            if (storageErrorGeneration == roundGeneration) false
            else {
                storageErrorGeneration = roundGeneration
                true
            }
        }
        Log.w(TAG, "App presence storage unavailable, round cancelled")
        if (report) errorSink(AppPresenceStorageException("App presence storage unavailable", cause))
    }

    /** Last compatible snapshot. Loads the persisted one once per namespace. Hold [lock]. */
    private fun compatibleSnapshotLocked(): AppPresenceSnapshot? {
        val ns = namespace ?: return null
        val fp = fingerprint ?: return null
        if (!isOn(configuration)) return null
        if (cacheNamespace != ns) {
            cacheNamespace = ns
            cachedSnapshot = try {
                (store.read(ns) as? AppPresenceReadResult.Found)?.record?.lastSnapshot
            } catch (_: IOException) {
                null
            }
        }
        return cachedSnapshot?.takeIf { it.configurationFingerprint == fp }
    }

    private fun deliverLive(snapshot: AppPresenceSnapshot, roundGeneration: Long) {
        val (target, epoch) = synchronized(lock) {
            if (generation != roundGeneration || !active) return
            val current = listener ?: return
            if (deliveredEpoch == listenerEpoch && deliveredSnapshotId == snapshot.snapshotId) return
            deliveredEpoch = listenerEpoch
            deliveredSnapshotId = snapshot.snapshotId
            current to listenerEpoch
        }
        invoke(target, snapshot, epoch)
    }

    private fun replay(epoch: Long) {
        val (target, snapshot) = synchronized(lock) {
            if (epoch != listenerEpoch) return
            val current = listener ?: return
            val cached = compatibleSnapshotLocked() ?: return
            if (deliveredEpoch == epoch && deliveredSnapshotId == cached.snapshotId) return
            deliveredEpoch = epoch
            deliveredSnapshotId = cached.snapshotId
            current to cached.copy(cached = true)
        }
        invoke(target, snapshot, epoch)
    }

    private fun invoke(target: BeAroundSDKListener, snapshot: AppPresenceSnapshot, epoch: Long) {
        try {
            target.onAppPresenceUpdated(snapshot)
        } catch (e: Exception) {
            // A throwing host callback must not break other deliveries or the SDK.
            Log.w(TAG, "onAppPresenceUpdated threw ${e.javaClass.simpleName} (listener epoch $epoch)")
        }
    }
}
