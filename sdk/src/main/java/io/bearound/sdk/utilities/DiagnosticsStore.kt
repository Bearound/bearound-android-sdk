package io.bearound.sdk.utilities

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
}
