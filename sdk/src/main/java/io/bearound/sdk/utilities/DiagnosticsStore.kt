package io.bearound.sdk.utilities

import androidx.annotation.VisibleForTesting

/** In-memory, thread-safe store of recent SDK activity for diagnostics. Resets on process death. */
object DiagnosticsStore {
    private const val MAX_ERRORS = 10

    private val lock = Any()

    private var lastSyncAt: Long? = null
    private var lastSyncSuccess: Boolean? = null
    private var lastSyncBeaconCount: Int? = null

    private var lastScanAt: Long? = null
    private var lastScanBeaconCount: Int? = null

    private val recentErrors = ArrayDeque<String>(MAX_ERRORS)

    fun recordSync(success: Boolean, beaconCount: Int) {
        synchronized(lock) {
            lastSyncAt = System.currentTimeMillis()
            lastSyncSuccess = success
            lastSyncBeaconCount = beaconCount
        }
    }

    fun recordScan(beaconCount: Int) {
        synchronized(lock) {
            lastScanAt = System.currentTimeMillis()
            lastScanBeaconCount = beaconCount
        }
    }

    fun recordError(msg: String) {
        synchronized(lock) {
            if (recentErrors.size >= MAX_ERRORS) {
                recentErrors.removeFirst()
            }
            recentErrors.addLast("${System.currentTimeMillis()} | $msg")
        }
    }

    fun lastSyncAt(): Long? = synchronized(lock) { lastSyncAt }

    fun lastSyncSuccess(): Boolean? = synchronized(lock) { lastSyncSuccess }

    fun lastSyncBeaconCount(): Int? = synchronized(lock) { lastSyncBeaconCount }

    fun lastScanAt(): Long? = synchronized(lock) { lastScanAt }

    fun lastScanBeaconCount(): Int? = synchronized(lock) { lastScanBeaconCount }

    fun recentErrors(): List<String> = synchronized(lock) { recentErrors.toList() }

    /**
     * Clears all in-memory state. Test-only: this singleton has process-lifetime state and no
     * production reason to reset, but unit tests need order-independence. Public (not
     * reflection) so it survives R8 minification in the release unit-test variant.
     */
    @VisibleForTesting
    fun reset() {
        synchronized(lock) {
            lastSyncAt = null
            lastSyncSuccess = null
            lastSyncBeaconCount = null
            lastScanAt = null
            lastScanBeaconCount = null
            recentErrors.clear()
        }
    }
}
