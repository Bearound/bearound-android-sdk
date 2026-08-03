package io.bearound.sdk

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.bearound.sdk.background.BackgroundScanManager
import io.bearound.sdk.background.BackgroundScheduler
import io.bearound.sdk.background.BeaconScanService
import io.bearound.sdk.background.ImmediateSyncWorker
import io.bearound.sdk.interfaces.BeAroundSDKListener
import io.bearound.sdk.interfaces.BluetoothManagerListener
import io.bearound.sdk.models.Beacon
import io.bearound.sdk.models.BeAroundDiagnostics
import io.bearound.sdk.models.BeaconMetadata
import io.bearound.sdk.models.ForegroundScanConfig
import io.bearound.sdk.models.MaxQueuedPayloads
import io.bearound.sdk.models.PeriodicReconciliationDefaults
import io.bearound.sdk.models.SDKConfiguration
import io.bearound.sdk.models.SDKInfo
import io.bearound.sdk.models.ScanPrecision
import io.bearound.sdk.models.UserProperties
import io.bearound.sdk.network.APIClient
import io.bearound.sdk.network.HttpException
import io.bearound.sdk.telemetry.ErrorReporter
import io.bearound.sdk.utilities.BackgroundReliabilityHelper
import io.bearound.sdk.utilities.OemPowerProfile
import io.bearound.sdk.utilities.DeviceIdentifier
import io.bearound.sdk.utilities.AppStateMonitor
import io.bearound.sdk.utilities.DetectionLogStore
import io.bearound.sdk.utilities.DeviceInfoCollector
import io.bearound.sdk.utilities.DiagnosticsStore
import io.bearound.sdk.utilities.OfflineBatchStorage
import io.bearound.sdk.utilities.PushTokenStore
import io.bearound.sdk.utilities.RegisterStore
import io.bearound.sdk.utilities.SDKConfigStorage
import io.bearound.sdk.utilities.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock as withMutex
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.pow

/**
 * Main SDK class - Singleton pattern
 * Entry point for all SDK operations
 */
class BeAroundSDK private constructor() {
    companion object {
        private const val TAG = "BeAroundSDK"

        /** Scan-log throttle window — same 10 s the iOS SDK uses. */
        private const val SCAN_LOG_THROTTLE_MS = 10_000L

        /**
         * Statuses where an identical retry fails identically: the payload/credential is
         * the problem, not the transport. 413 lands here too — if a SINGLE batch still
         * exceeds the limit it is malformed, not splittable. Everything else (408, 429,
         * 5xx, transport errors) is treated as transient and retried whole.
         */
        private val PERMANENT_HTTP_CODES = setOf(400, 401, 403, 404, 413, 422)

        /**
         * Minimum gap between broadcast-triggered background flushes. Beacons
         * advertise at ~1 Hz; without this, every delivery would fire a POST.
         */
        private const val IMMEDIATE_FLUSH_MIN_GAP_MS = 10_000L

        /**
         * Anti-downgrade scan refresh (Fix B, 2026-07). OEMs on Android 13+ silently
         * downgrade long-lived scan sessions (field-observed: requested BALANCED/LOW_LATENCY
         * demoted to AMBIENT_DISCOVERY/OPPORTUNISTIC on Moto G35 / realme C61, shrinking
         * listening to ~10% duty), and AOSP drops any scan older than 30 min to opportunistic.
         * Re-registering the client restores full duty. 20 min stays safely under the AOSP
         * 30-min cliff and is far above ScanStartBudget's 4-starts/30 s throttle window.
         */
        private const val SCAN_REFRESH_INTERVAL_MS = 20 * 60 * 1000L
        
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: BeAroundSDK? = null

        fun getInstance(context: Context): BeAroundSDK {
            return instance ?: synchronized(this) {
                instance ?: BeAroundSDK().also {
                    it.initialize(context.applicationContext)
                    instance = it
                }
            }
        }
        
    }

    var listener: BeAroundSDKListener? = null

    private lateinit var context: Context
    private var configuration: SDKConfiguration? = null

    /**
     * Credentials handoff for the companion Bearound Telemetry SDK
     * (bearound-telemetry-android-sdk). Read-only; null until [configure] succeeds.
     * Companion apps hand the whole instance over instead of re-entering credentials:
     *
     * ```
     * val bearound = BeAroundSDK.getInstance(this).configure(businessToken = TOKEN)
     * BearoundTelemetrySDK.getInstance(this).configure(bearound)
     * ```
     */
    val businessToken: String?
        get() = configuration?.businessToken

    /**
     * Stable device id for this install — produced by this SDK (ANDROID_ID-based,
     * frozen in secure storage). The companion Bearound Telemetry SDK adopts it via
     * the instance handoff (`telemetry.configure(bearound)`), so both SDKs report as
     * the SAME device.
     */
    val deviceId: String
        get() = DeviceIdentifier.getDeviceId(context)
    private var sdkInfo: SDKInfo? = null
    private var userProperties: UserProperties? = null

    private lateinit var deviceInfoCollector: DeviceInfoCollector
    private lateinit var beaconManager: BeaconManager
    private lateinit var bluetoothManager: BluetoothManager

    /** Device-to-device encounter layer — runs whenever scanning runs. */
    private var encounterMesh: EncounterMeshManager? = null

    /** Timestamp of the last encounters-only upload, for the 60s throttle. */
    @Volatile private var lastEncounterOnlySyncAt = 0L

    /** Keeps the system Wi-Fi scan cache fresh while scanning (35s cadence stays
     * inside Android's 4-per-2-minutes foreground throttle; background ticks skip —
     * background scans are budgeted to ~1-2/hour by the OS and the cache ride-along
     * covers that regime). */
    private val wifiNudgeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val wifiNudgeRunnable = object : Runnable {
        override fun run() {
            if (!isInBackground) deviceInfoCollector.nudgeWifiScan()
            wifiNudgeHandler.postDelayed(this, 35_000)
        }
    }
    private lateinit var backgroundScanManager: BackgroundScanManager
    private lateinit var backgroundScheduler: BackgroundScheduler
    private var apiClient: APIClient? = null

    private val metadataCache = mutableMapOf<String, BeaconMetadata>()
    private val collectedBeacons = mutableMapOf<String, Beacon>()

    /**
     * TTL applied when emitting [collectedBeacons] to the host. The map is ALSO the sync
     * buffer — unsynced entries must be retained until sent — so expired beacons are
     * hidden from listener emissions instead of being removed. Kept in step with the
     * BeaconManager eviction timeout (see startSyncTimer). Without this filter, the
     * metadata-scan and post-sync emission paths re-surfaced beacons long gone from the
     * air ("ghost beacons" on the host list).
     */
    @Volatile
    private var listenerBeaconTtlMs: Long = 30_000L

    /** TTL-filtered snapshot of [collectedBeacons] for host emission. Call under [beaconLock]. */
    private fun collectedForListenerLocked(): List<Beacon> {
        val cutoff = System.currentTimeMillis() - listenerBeaconTtlMs
        return collectedBeacons.values.filter { it.timestamp.time >= cutoff }
    }
    private val beaconLock = ReentrantLock()

    // ErrorReporter's handler: SDK coroutine failures are logged + reported, never rethrown.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + ErrorReporter.coroutineExceptionHandler
    )
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Dispatches a [BeAroundSDKListener] callback on the main thread.
     *
     * SDK callbacks originate from several threads (BLE scan callbacks, background sync
     * coroutines, lifecycle observers). To give a single, predictable threading contract —
     * and avoid the intermittent UI crashes seen when [BeAroundSDKListener.onError] fired on
     * a worker thread — every listener invocation goes through here.
     *
     * If the caller is already on the main thread the block runs inline (no reordering and
     * no self-post that could deadlock a synchronous caller); otherwise it is posted to the
     * main looper. Reads [listener] on the main thread so a null/replaced listener is handled
     * consistently.
     */
    private inline fun dispatchToListener(crossinline block: (BeAroundSDKListener) -> Unit) {
        val run = Runnable { listener?.let(block) }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            run.run()
        } else {
            handler.post(run)
        }
    }

    /** Scan-log throttle state — mirrors iOS `lastScanLogSignature`/`lastScanLogAt`. */
    private var lastScanLogSignature: String? = null
    private var lastScanLogAt: Long = 0L

    private var syncRunnable: Runnable? = null
    private var scanRefreshRunnable: Runnable? = null

    /**
     * Single-flight sync. Every trigger (timer, workers, FCM, watchdog, broadcast flush,
     * manual stop) funnels through [syncBeaconsAwait]; concurrent callers JOIN the
     * in-flight operation and await its REAL result. Replaces the old `isSyncing`
     * Boolean, which had a check-then-set window two callers could both pass, and
     * reported a fake `true` to any caller that arrived mid-sync.
     */
    private val syncMutex = Mutex()
    private var activeSync: Deferred<Boolean>? = null

    private lateinit var offlineBatchStorage: OfflineBatchStorage
    private var consecutiveFailures = 0
    private var lastFailureTime: Long? = null

    // Immediate background flush debounce — elapsedRealtime (counts during Doze,
    // unlike the uptime clock backing Handler.postDelayed).
    @Volatile private var lastImmediateFlushAt = 0L

    private var isInBackground = false
    private var foregroundScanConfig: ForegroundScanConfig? = null
    private val registerInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    val isScanning: Boolean
        get() = ::beaconManager.isInitialized && beaconManager.isScanning

    /**
     * True while the SDK considers the device inside a beacon zone — either the rising
     * edge fired or a fresh persisted zone was restored across a reconfigure/restart.
     * Hosts should read this to INITIALIZE their zone UI: the restore path deliberately
     * does not re-fire [BeAroundSDKListener.onEnterBeaconRegion] (anti-phantom), so a UI
     * that only listens to enter/exit events shows "outside" after a settings apply even
     * though detection is active.
     */
    val isInBeaconRegion: Boolean
        get() = ::beaconManager.isInitialized && beaconManager.isInBeaconRegion

    val currentSyncInterval: Long?
        get() = configuration?.syncInterval

    val currentScanPrecision: ScanPrecision?
        get() = configuration?.scanPrecision

    val isConfigured: Boolean
        get() = configuration != null && apiClient != null

    internal fun attemptConfigRestore() {
        if (isConfigured) return
        
        val savedConfig = SDKConfigStorage.loadConfiguration(context)
        
        if (savedConfig != null) {
            configuration = savedConfig
            apiClient = APIClient(savedConfig)
            
            val buildNumber = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode
            } catch (_: Exception) {
                1
            }
            sdkInfo = SDKInfo(appId = savedConfig.appId, build = buildNumber, technology = savedConfig.technology)

            // Update offline batch storage max count
            offlineBatchStorage.maxBatchCount = savedConfig.maxQueuedPayloads.value
            // Same tenant gate as configure() — restored sessions read only their own queue.
            offlineBatchStorage.currentTenantId = tenantFingerprint(savedConfig.businessToken)

            SDKConfigStorage.loadInternalId(context)?.let { savedId ->
                if (userProperties?.internalId == null) {
                    userProperties = (userProperties ?: UserProperties()).mergedWith(UserProperties(internalId = savedId))
                }
            }
        } else {
            Log.w(TAG, "Failed to restore configuration")
        }
    }

    private fun initialize(appContext: Context) {
        context = appContext
        
        SecureStorage.initialize(context)
        
        // Process-state classification for the persisted detection log — must be
        // installed before anything can record, so events from a system-started
        // process are tagged `terminated` instead of `background`.
        AppStateMonitor.install(context)

        deviceInfoCollector = DeviceInfoCollector(context)
        beaconManager = BeaconManager(context)
        bluetoothManager = BluetoothManager(context)
        encounterMesh = EncounterMeshManager(context)
        bluetoothManager.encounterMesh = encounterMesh
        beaconManager.encounterMesh = encounterMesh
        backgroundScanManager = BackgroundScanManager(context)
        backgroundScheduler = BackgroundScheduler.getInstance(context)
        offlineBatchStorage = OfflineBatchStorage(context)

        // Restore foreground scan config if previously set
        foregroundScanConfig = SDKConfigStorage.loadForegroundScanConfig(context)

        setupCallbacks()
        setupLifecycleObserver()
    }

    private fun setupCallbacks() {
        beaconManager.onBeaconsUpdated = { beacons ->
            val enrichedBeacons = beacons.map { beacon ->
                val key = beacon.identifier
                // Prefer the metadata-scan cache (fresher battery/temperature), but FALL BACK
                // to the metadata the parser already extracted from the scan record. Without
                // the fallback, scan-response beacons (e.g. B:0.135) — which the unfiltered
                // metadata scan misses — had their parsed metadata overwritten with null and
                // synced WITHOUT battery/firmware/temperature.
                val metadata = metadataCache[key] ?: beacon.metadata
                beacon.copy(
                    metadata = metadata,
                    txPower = metadata?.txPower ?: beacon.txPower
                )
            }

            val beaconsForListener = beaconLock.withLock {
                enrichedBeacons.map { beacon ->
                    val existing = collectedBeacons[beacon.identifier]
                    val updated = if (existing?.syncedAt != null) {
                        beacon.copy(syncedAt = existing.syncedAt)
                    } else {
                        beacon
                    }
                    collectedBeacons[beacon.identifier] = updated
                    updated
                }
            }

            // Notify listener of beacon update (with sync state preserved)
            dispatchToListener { it.onBeaconsUpdated(beaconsForListener) }

            // Ranged-scan log (diagnostic, persisted): one entry per composition
            // change or every 10 s — same contract and format as iOS, so the same
            // host UI reads both platforms identically.
            if (enrichedBeacons.isNotEmpty()) {
                val signature = enrichedBeacons.joinToString(", ") {
                    "${it.major}.${it.minor} rssi=${it.rssi}"
                }
                val now = System.currentTimeMillis()
                if (signature != lastScanLogSignature || now - lastScanLogAt > SCAN_LOG_THROTTLE_MS) {
                    lastScanLogSignature = signature
                    lastScanLogAt = now
                    DetectionLogStore.append(context, type = "Scan", detail = signature)
                }
            }

            // Notify if beacons detected in background
            if (isInBackground && enrichedBeacons.isNotEmpty()) {
                DetectionLogStore.append(
                    context,
                    type = "Background",
                    detail = "${enrichedBeacons.size} beacon(s) detectado(s)"
                )
                dispatchToListener { it.onBeaconDetectedInBackground(enrichedBeacons.size) }

                // Update foreground notification with contextual content.
                // Note: onProvideNotificationContent is a value-returning callback consumed
                // synchronously here (not a fire-and-forget event), so it is not routed
                // through the main-thread dispatcher.
                if (BeaconScanService.isRunning) {
                    val content = listener?.onProvideNotificationContent(beaconsForListener)
                    if (content != null) {
                        BeaconScanService.updateNotification(context, content.title, content.text)
                    }
                }
            }
        }

        beaconManager.onError = { error ->
            dispatchToListener { it.onError(error) }
        }

        beaconManager.onScanningStateChanged = { isScanning ->
            dispatchToListener { it.onScanningStateChanged(isScanning) }
        }

        // v2.5 — region transitions: gate active BLE scan
        beaconManager.onRegionEnter = {
            DetectionLogStore.append(context, type = "Região", detail = "Entrou na zona do beacon")
            dispatchToListener { it.onEnterBeaconRegion() }
        }

        beaconManager.onRegionExit = {
            DetectionLogStore.append(context, type = "Região", detail = "Saiu da zona do beacon")
            dispatchToListener { it.onExitBeaconRegion() }
        }

        beaconManager.onActiveScanShouldStart = {
            Log.d(TAG, "Active scan START — region entered, starting ranging + BLE central scan")
            // Regular ranging ON: startRanging() early-returns while out of region, so the
            // rising edge is the only place the first in-region ranging session can start —
            // without this call the region ran on batch/PendingIntent alone (the doctrine
            // comment in BeaconManager.startRanging promised this hookup all along).
            beaconManager.resumeRanging()
            // Bluetooth metadata scan ON only while inside a region.
            bluetoothManager.startScanning()
            dispatchToListener { it.onActiveScanStateChanged(true) }
        }

        beaconManager.onActiveScanShouldStop = {
            Log.d(TAG, "Active scan STOP — region exited, stopping ranging + BLE central scan")
            beaconManager.stopRanging()
            bluetoothManager.stopScanning()
            dispatchToListener { it.onActiveScanStateChanged(false) }
        }

        bluetoothManager.listener = object : BluetoothManagerListener {
            override fun onBeaconDiscovered(
                uuid: UUID,
                major: Int,
                minor: Int,
                rssi: Int,
                txPower: Int,
                metadata: BeaconMetadata?,
                isConnectable: Boolean
            ) {
                metadata?.let {
                    metadataCache["$major.$minor"] = it
                }

                // Surface beacon to UI even when BeaconManager is not ranging.
                // Builds a Beacon and emits the current collected set through the SDK listener.
                val beacon = Beacon(
                    uuid = uuid,
                    major = major,
                    minor = minor,
                    rssi = rssi,
                    proximity = Beacon.Proximity.BT,
                    accuracy = -1.0,
                    timestamp = java.util.Date(),
                    metadata = metadata,
                    txPower = if (txPower != 0) txPower else null
                )

                val beaconsForListener = beaconLock.withLock {
                    val existing = collectedBeacons[beacon.identifier]
                    val updated = if (existing?.syncedAt != null) {
                        beacon.copy(syncedAt = existing.syncedAt)
                    } else {
                        beacon
                    }
                    collectedBeacons[beacon.identifier] = updated
                    collectedForListenerLocked()
                }

                dispatchToListener { it.onBeaconsUpdated(beaconsForListener) }

                if (isInBackground) {
                    dispatchToListener { it.onBeaconDetectedInBackground(beaconsForListener.size) }
                }
            }

            override fun onBluetoothStateChanged(isPoweredOn: Boolean) {
                if (!isPoweredOn) {
                    Log.w(TAG, "Bluetooth is off")
                }
            }
        }
    }

    private fun setupLifecycleObserver() {
        // Lifecycle.addObserver is a main-thread API. The singleton's FIRST getInstance()
        // can come from a WorkManager thread (cold start via FCM/worker) — registering
        // from there throws IllegalStateException. Post when off-main; the observer only
        // feeds isInBackground, so the sub-frame registration delay is harmless.
        val register = Runnable {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    onAppForegrounded()
                }

                override fun onStop(owner: LifecycleOwner) {
                    onAppBackgrounded()
                }
            })
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            register.run()
        } else {
            handler.post(register)
        }
    }

    private fun onAppForegrounded() {
        isInBackground = false
        Log.d(TAG, "App foregrounded")

        // O scan por PendingIntent fica ATIVO também em foreground (antes era desligado
        // aqui). Motivo (campo, 2026-07-21): nos Android 14 AOSP-like (Moto stock, realme
        // ColorOS) o enforcement do neverForLocation filtra os frames de beacon de TODOS
        // os scanners regulares — o caminho de broadcast/PendingIntent é o único que
        // entrega. Desligá-lo em foreground deixava esses aparelhos praticamente cegos
        // exatamente com o app em uso. Custo: é o mesmo scan filtrado de baixo consumo
        // que já roda o dia inteiro em background; o kernel deduplica o trabalho de rádio
        // com os scanners regulares ativos.

        if (BeaconScanService.isRunning) {
            BeaconScanService.stop(context)
        }

        beaconManager.setForegroundState(true)
        // Periodic scanning in foreground is automatic (controlled by sync timer)

        if (isScanning) {
            restartSyncTimer()
        }

        dispatchToListener { it.onAppStateChanged(isInBackground = false) }
    }

    private fun onAppBackgrounded() {
        isInBackground = true
        Log.d(TAG, "App backgrounded")

        beaconManager.setForegroundState(false)
        backgroundScanManager.enableBackgroundScanning()

        // Start foreground service if opted-in and scanning is active
        val fgConfig = foregroundScanConfig
        if (fgConfig?.enabled == true && isScanning) {
            BeaconScanService.start(context, fgConfig)
        }
        
        if (isScanning) {
            restartSyncTimer()
        }

        dispatchToListener { it.onAppStateChanged(isInBackground = true) }
    }

    /** Configures and activates the SDK. Auto-collects the FCM token if Firebase is present (see [tryAutoCollectFcmToken]). */
    /**
     * @param periodicReconciliationEnabled enables the periodic background reconciliation
     *   (WorkManager layer). Best effort — Android may defer the worker (Doze, battery
     *   optimizations, OEM policies). Default: true.
     * @param periodicReconciliationIntervalMillis MINIMUM interval requested between
     *   eligible executions — a floor, never a guaranteed cadence. Accepted range
     *   **15 min (WorkManager's hard minimum) … 24 h**; out-of-range values are clamped
     *   with an ERROR-level log; non-positive values fall back to the 20-min default.
     * @param periodicScanDurationMillis ceiling of the collection window inside the
     *   worker, clamped to **3–30 s**. The worker never registers scanners of its own.
     */
    fun configure(
        businessToken: String,
        scanPrecision: ScanPrecision = ScanPrecision.MEDIUM,
        maxQueuedPayloads: MaxQueuedPayloads = MaxQueuedPayloads.MEDIUM,
        technology: String = "android-native",
        periodicReconciliationEnabled: Boolean = true,
        periodicReconciliationIntervalMillis: Long = PeriodicReconciliationDefaults.DEFAULT_INTERVAL_MILLIS,
        periodicScanDurationMillis: Long = PeriodicReconciliationDefaults.DEFAULT_SCAN_DURATION_MILLIS
    ): BeAroundSDK {
        // NEVER-CRASH-THE-HOST: an embedded SDK must not throw from a public entry
        // point — a host wired to an empty BuildConfig field would crash on startup.
        // Fail silently-but-visibly instead: log, report to telemetry, surface via
        // onError, and leave the SDK unconfigured (every other API no-ops safely).
        if (businessToken.trim().isEmpty()) {
            Log.e(TAG, "Business token cannot be empty — configure() skipped, SDK stays inactive")
            val error = IllegalArgumentException("Business token cannot be empty")
            ErrorReporter.report(error, "configure")
            listener?.onError(error)
            return this
        }

        val appId = context.packageName

        val config = SDKConfiguration(
            businessToken = businessToken,
            appId = appId,
            scanPrecision = scanPrecision,
            maxQueuedPayloads = maxQueuedPayloads,
            technology = technology,
            periodicReconciliationEnabled = periodicReconciliationEnabled,
            periodicReconciliationIntervalMillis =
                PeriodicReconciliationDefaults.sanitizedInterval(periodicReconciliationIntervalMillis),
            periodicScanDurationMillis =
                PeriodicReconciliationDefaults.sanitizedScanDuration(periodicScanDurationMillis)
        )

        configuration = config
        apiClient = APIClient(config)

        val buildNumber = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (_: Exception) {
            1
        }

        sdkInfo = SDKInfo(appId = appId, build = buildNumber, technology = config.technology)

        // Update offline batch storage max count
        offlineBatchStorage.maxBatchCount = config.maxQueuedPayloads.value

        // Tenant isolation: pending batches are read back only under the token they were
        // collected for — a configure() with a DIFFERENT client's token must never drain
        // the previous client's queue through the new credential.
        offlineBatchStorage.currentTenantId = tenantFingerprint(businessToken)

        SDKConfigStorage.saveConfiguration(context, config)

        // Periodic reconciliation: apply the new settings to the unique periodic work.
        // ExistingPeriodicWorkPolicy.UPDATE swaps the spec in place (same interval =
        // no-op churn; new interval = updated schedule) and never interrupts a worker
        // that is already running. Disabling cancels the pending periodic work.
        backgroundScheduler.refreshPeriodicReconciliation(config)

        // Error telemetry — isolated reporter ("try/catch around the library"). Own
        // try/catch: a telemetry failure must never break configure().
        try {
            ErrorReporter.install(context, businessToken)
        } catch (t: Throwable) {
            Log.w(TAG, "Error telemetry install failed: ${t.message}")
        }

        // Guardrail: a third-party library can silently drop neverForLocation from the
        // merged manifest — surface it (log + error telemetry) instead of silent decay.
        io.bearound.sdk.utilities.ManifestPermissionCheck.verify(context)

        tryAutoCollectFcmToken(context)

        // Advertising ID: fetched in the background because AdvertisingIdClient throws if
        // called on the main thread. Lands in the cache before the first sync in practice;
        // if it does not, the field is simply absent from that one payload.
        io.bearound.sdk.utilities.AdvertisingIdCollector.refresh(context)

        // First-access contract: the device must appear in the backend as soon as the SDK
        // is configured — registration (with the push token, once available) must NOT
        // depend on the host also calling startScanning(). TTL-gated, so a no-op when
        // already registered.
        scope.launch { registerDeviceIfNeeded() }

        registerBluetoothStateReceiver()
        logOemProfileOnce()

        if (isScanning) {
            startSyncTimer()
        }
        return this
    }

    /**
     * Bluetooth off→on drops every scan client in the stack while the SDK's local flags stay
     * true — without this receiver, out-of-region detection stays dead until the 15-min
     * watchdog. On STATE_ON, re-arm the batch scan and re-register the PendingIntent scan.
     */
    private val bluetoothStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
            if (intent?.action != android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)
            if (!::beaconManager.isInitialized) return
            when (state) {
                android.bluetooth.BluetoothAdapter.STATE_OFF -> {
                    // Drop the metadata scan's local flag now — the stack already
                    // dropped the client. Without this, STATE_ON's re-arm hit the
                    // "Already scanning" early-return and the scan stayed a zombie.
                    bluetoothManager.onBluetoothPoweredOff()
                }
                android.bluetooth.BluetoothAdapter.STATE_ON -> {
                    if (wasScanningEnabled()) {
                        Log.i(TAG, "Bluetooth back ON — re-arming scan clients")
                        beaconManager.onBluetoothRestored()
                        bluetoothManager.onBluetoothRestored()
                        backgroundScanManager.refreshBackgroundScanning()
                    }
                }
            }
        }
    }

    private var bluetoothStateReceiverRegistered = false

    private fun registerBluetoothStateReceiver() {
        if (bluetoothStateReceiverRegistered) return
        try {
            val filter = android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(bluetoothStateReceiver, filter)
            }
            bluetoothStateReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth state receiver registration failed: ${e.message}")
        }
    }

    private var oemProfileLogged = false

    /** One-shot visibility of the OEM power profile — the top field cause of "stopped detecting". */
    private fun logOemProfileOnce() {
        if (oemProfileLogged) return
        oemProfileLogged = true
        val p = OemPowerProfile.get()
        if (p.aggressiveness != OemPowerProfile.Aggressiveness.STANDARD) {
            Log.w(
                TAG,
                "OEM power profile: ${p.rom ?: android.os.Build.MANUFACTURER} ${p.romVersion ?: ""} " +
                    "(${p.aggressiveness}) — background detection may require user action; " +
                    "see BeAroundSDK.reliabilityStatus()"
            )
        }
    }

    /** Best-effort FCM token fetch. Firebase is compileOnly, so guard against it being absent at runtime; falls back to [setPushToken]. */
    private fun tryAutoCollectFcmToken(context: Context) {
        try {
            if (com.google.firebase.FirebaseApp.getApps(context).isEmpty()) return
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    if (!token.isNullOrEmpty()) {
                        Log.i(TAG, "FCM token auto-collected")
                        // Route through setPushToken so a register-on-init that already went
                        // out WITHOUT the (async) token is followed by a forced re-register —
                        // storing directly would silently hold the token until the next TTL.
                        setPushToken(token)
                    }
                }
                .addOnFailureListener { e -> Log.w(TAG, "FCM token fetch failed: ${e.message}") }
        } catch (t: Throwable) {
            Log.i(TAG, "Firebase not available; client must call setPushToken() to provide the FCM token")
        }
    }

    /**
     * Enables or disables the SDK's error telemetry (enabled by default).
     *
     * When enabled, errors originating in SDK code — uncaught exceptions whose stack
     * contains SDK frames, SDK coroutine failures, and errors caught inside SDK
     * components — are reported to the Bearound backend (`POST /sdk-errors`) together
     * with basic device info (model, OS version, ROM, locale, battery, app state).
     * Reporting is fire-and-forget, rate-limited (max 20/h) and deduplicated; it never
     * throws and never interferes with the host app's own crash handling.
     *
     * Call `setErrorReportingEnabled(false)` at any time (before or after `configure()`)
     * to opt out.
     */
    fun setErrorReportingEnabled(enabled: Boolean) {
        ErrorReporter.setEnabled(enabled)
    }

    /**
     * Merges with previously-set properties (omitted fields are kept); internalId is
     * persisted across app kills.
     */
    fun setUserProperties(properties: UserProperties) {
        userProperties = (userProperties ?: UserProperties()).mergedWith(properties)
        userProperties?.internalId?.let { SDKConfigStorage.saveInternalId(context, it) }
    }

    /**
     * Registers the device's push token (FCM/APNs) so the backend can target this device
     * for push. Sent once with the next sync; re-sent only if the token changes.
     */
    fun setPushToken(token: String) {
        PushTokenStore.setToken(token)
        Log.d(TAG, "Push token registered")
        // If the SDK is already configured and this token was not sent yet (new/changed),
        // push it now via register (beacons:[]) — otherwise it would only go out on the
        // next TTL register or on beacon detection. Covers the FCM token arriving AFTER
        // the initial register (async fetch, mid-session rotation, or a late setPushToken
        // from the host): register-on-init already went out without the token, and the
        // token is NOT part of the fingerprint (a normal register would not re-fire).
        if (isConfigured && PushTokenStore.tokenForPayload() != null) {
            scope.launch { registerDeviceIfNeeded(force = true) }
        }
    }

    /**
     * Handles a Bearound silent-push wake-up (FCM data message). Call this from your
     * `FirebaseMessagingService.onMessageReceived` — or register the SDK's
     * [io.bearound.sdk.push.BearoundMessagingService] in your manifest and it calls this
     * for you. Returns `true` if the message was a Bearound wake-up (handled here); `false`
     * for third-party messages (which the host should keep handling itself).
     *
     * On a Bearound push the SDK restores its config if the app was killed, restarts
     * scanning (always — a backend wake-up overrides a previous [stopScanning]) and
     * flushes pending sync — the Android counterpart of the iOS silent-push wake-up,
     * letting the backend trigger an on-demand scan + sync.
     */
    fun handleRemoteMessage(data: Map<String, String>): Boolean {
        // Marker set by the backend FCM payload (buildFcmPayload → data["bearound"]).
        // Guards against acting on third-party pushes routed through the same service.
        if (data["bearound"] == null) return false
        Log.d(TAG, "Bearound wake-up push received — restarting scan + flushing sync")
        try {
            // Restore config first if the app was killed (cold start via FCM).
            if (!isConfigured) attemptConfigRestore()
            if (!isConfigured) {
                Log.w(TAG, "Wake-up ignored - SDK not configured")
                return true
            }
            // Backend-commanded wake: restart scanning UNCONDITIONALLY and flush pending
            // sync. Product decision — there is no user opt-out; stopScanning() is not a
            // consent gate, so a wake-up push always brings the device back to scanning
            // (unlike the watchdog/boot self-heal paths, which only restore what was on).
            restartScanningFromBackground()
            performBackgroundSync()
        } catch (e: Exception) {
            Log.e(TAG, "handleRemoteMessage error: ${e.message}")
            io.bearound.sdk.telemetry.ErrorReporter.report(e, "BeAroundSDK.handleRemoteMessage")
        }
        return true
    }

    /** Clears all user properties, including the persisted internalId. */
    fun clearUserProperties() {
        userProperties = null
        SDKConfigStorage.saveInternalId(context, null)
    }

    fun enableForegroundScanning(config: ForegroundScanConfig) {
        val enabledConfig = config.copy(enabled = true)
        foregroundScanConfig = enabledConfig
        SDKConfigStorage.saveForegroundScanConfig(context, enabledConfig)

        if (isInBackground && isScanning) {
            BeaconScanService.start(context, enabledConfig)
        }
    }

    fun disableForegroundScanning() {
        foregroundScanConfig = foregroundScanConfig?.copy(enabled = false)
        SDKConfigStorage.saveForegroundScanConfig(context, ForegroundScanConfig(enabled = false))

        if (BeaconScanService.isRunning) {
            BeaconScanService.stop(context)
        }
    }

    val isForegroundScanningEnabled: Boolean
        get() = foregroundScanConfig?.enabled == true

    // region Background reliability (Doze + OEM battery killers) — no location, no policy strings

    /**
     * true if the app is already exempt from Android's battery optimization (Doze). Below
     * Android 6 always true (does not apply). See [openBatteryOptimizationSettings].
     */
    fun isIgnoringBatteryOptimizations(): Boolean =
        BackgroundReliabilityHelper.isIgnoringBatteryOptimizations(context)

    /**
     * Opens the battery-optimization Settings screen so the user can exempt the app —
     * improves background scan survival under Doze. Uses the Settings screen (without
     * the restricted REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission), so it does not
     * trigger Google Play review. @return true if the screen was opened.
     */
    fun openBatteryOptimizationSettings(): Boolean =
        BackgroundReliabilityHelper.openBatteryOptimizationSettings(context)

    /**
     * true if the device is from an OEM (Xiaomi, Huawei, Oppo/Vivo, OnePlus, Samsung…) with
     * a known, resolvable autostart screen. On stock Android (Pixel) returns false — not
     * needed there. See [openManufacturerAutostartSettings].
     */
    fun isAutostartManageable(): Boolean =
        BackgroundReliabilityHelper.isAutostartManageable(context)

    /**
     * Opens the manufacturer's "autostart"/"protected apps" screen, when one exists. Several
     * OEMs kill PendingIntent/broadcast receivers in the background even on Android 14+;
     * enabling autostart is the mitigation. @return true if it opened; false on stock/unmapped
     * OEMs (in that case [openBatteryOptimizationSettings] already covers the essentials).
     */
    fun openManufacturerAutostartSettings(): Boolean =
        BackgroundReliabilityHelper.openManufacturerAutostartSettings(context)

    /**
     * Consolidated reliability view: the ROM's aggressiveness profile (Xiaomi/HyperOS,
     * Huawei, Oppo, Vivo, Samsung…) plus the state of the two actionable levers. Use
     * [io.bearound.sdk.models.ReliabilityStatus.recommendsUserAction] to automatically
     * decide when to show the "allow background detection" onboarding.
     */
    fun reliabilityStatus(): io.bearound.sdk.models.ReliabilityStatus {
        val profile = OemPowerProfile.get()
        val batteryExempt = isIgnoringBatteryOptimizations()
        val needsAction = !batteryExempt &&
            profile.aggressiveness != OemPowerProfile.Aggressiveness.STANDARD
        return io.bearound.sdk.models.ReliabilityStatus(
            oemRom = profile.rom,
            oemRomVersion = profile.romVersion,
            oemAggressiveness = profile.aggressiveness.name.lowercase(),
            isIgnoringBatteryOptimizations = batteryExempt,
            isAutostartManageable = isAutostartManageable(),
            recommendsUserAction = needsAction
        )
    }

    // endregion

    /**
     * Starts beacon scanning.
     *
     * If the required runtime permission is missing (BLUETOOTH_SCAN on Android 12+,
     * ACCESS_FINE/COARSE_LOCATION on Android ≤11), the active scan cannot start and the SDK
     * emits an informative [BeAroundSDKListener.onError] exactly once for this call. The
     * background scheduler + watchdog are still armed on purpose: when the user grants the
     * permission later, scanning resumes without requiring another explicit call.
     *
     * All listener callbacks are dispatched on the main thread.
     */
    fun startScanning(foregroundScanConfig: ForegroundScanConfig? = null) {
        val config = configuration
        if (config == null) {
            val error = Exception("SDK not configured. Call configure() first.")
            dispatchToListener { it.onError(error) }
            return
        }

        // Enable foreground service if config provided
        if (foregroundScanConfig != null) {
            enableForegroundScanning(foregroundScanConfig)
        }

        // Scanning mode is automatic based on app state (foreground/background)
        beaconManager.startScanning()
        // Encounter layer rides the same scan: advertise + recognise other SDK hosts.
        encounterMesh?.start()
        // Wi-Fi observations: keep the system scan cache fresh while scanning (see
        // WifiCollector.nudgeScan — foreground only, inside the OS throttle).
        wifiNudgeHandler.removeCallbacks(wifiNudgeRunnable)
        wifiNudgeHandler.post(wifiNudgeRunnable)
        startSyncTimer()

        // Enable background mechanisms (WorkManager + AlarmManager)
        backgroundScheduler.enableAll()

        // v2.5 — Always enable PendingIntent-based filter scan (low power, kernel-managed).
        // This is what wakes us when a beacon enters range — regardless of app state.
        // Equivalent in spirit to iOS's CLBeaconRegion monitoring.
        backgroundScanManager.enableBackgroundScanning()

        // Fix B — keep long-lived scan sessions at full duty (anti-downgrade re-register).
        startScanRefreshTimer()

        // Persist scanning state for recovery after kill/reboot
        SDKConfigStorage.saveScanningEnabled(context, true)

        // v2.5 — Bluetooth metadata scanning is gated by beacon region presence. It will
        // be started inside onActiveScanShouldStart when the first beacon is detected, and
        // stopped on region exit. BackgroundScanManager.enableBackgroundScanning() (above)
        // already runs the low-power filter scan that wakes us when a beacon appears.

        // Register the device with the backend even when no beacons are in range so that
        // the device appears in the Control Hub on first launch (iOS parity).
        scope.launch { registerDeviceIfNeeded() }
    }

    /**
     * Sends a register event (beacons=[] + syncTrigger="register") when:
     * - the device has never registered, OR
     * - the fingerprint changed (app update, OS update, new businessToken), OR
     * - 24 hours have elapsed since the last successful register.
     *
     * Fires-and-forgets inside the SDK's background [scope] — never blocks [startScanning].
     */
    private suspend fun registerDeviceIfNeeded(force: Boolean = false) {
        val client = apiClient
        val info = sdkInfo
        val config = configuration

        if (client == null || info == null || config == null) {
            Log.w(TAG, "registerDeviceIfNeeded: SDK not fully configured, skipping")
            return
        }

        // Concurrency guard — configure(), startScanning(), setPushToken and the sync-tick
        // retry can all race a register; one in flight is enough.
        if (!registerInFlight.compareAndSet(false, true)) {
            Log.d(TAG, "registerDeviceIfNeeded: register already in flight, skipping")
            return
        }

        val appBuild = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (_: Exception) { 1 }

        val fingerprint = RegisterStore.buildFingerprint(
            deviceId = DeviceIdentifier.getDeviceId(context),
            appId = config.appId,
            businessToken = config.businessToken,
            sdkVersion = info.version,
            osVersion = android.os.Build.VERSION.RELEASE,
            appBuild = appBuild
        )

        if (!force && !RegisterStore.shouldRegister(context, fingerprint)) {
            Log.d(TAG, "registerDeviceIfNeeded: TTL not expired and fingerprint unchanged, skipping")
            registerInFlight.set(false)
            return
        }

        val locationPermission = getLocationPermissionStatus()
        val bluetoothState = if (bluetoothManager.isPoweredOn) "powered_on" else "powered_off"
        val userDevice = deviceInfoCollector.collectDeviceInfo(
            locationPermission = locationPermission,
            bluetoothState = bluetoothState,
            appInForeground = !isInBackground
        )

        Log.d(TAG, "registerDeviceIfNeeded: sending register event")

        client.sendRegister(info, userDevice, userProperties) { result ->
            registerInFlight.set(false)
            result.fold(
                onSuccess = {
                    RegisterStore.markRegistered(context, fingerprint)
                    PushTokenStore.markSent(userDevice.pushToken)
                    Log.d(TAG, "registerDeviceIfNeeded: registered successfully")
                },
                onFailure = { error ->
                    Log.w(TAG, "registerDeviceIfNeeded: register failed: ${error.message}")
                    DiagnosticsStore.recordError("Register failed: ${error.message}")
                    // Surface the failure (e.g. a 401 with the token-rejection body) to the
                    // host so an invalid token or unreachable backend is not silent. Not
                    // queued offline — the sync tick retries it (TTL-gated) while the
                    // process lives, and startScanning()/configure() retry across launches.
                    dispatchToListener {
                        it.onError(error as? Exception ?: Exception(error.message))
                    }
                }
            )
        }
    }

    fun stopScanning() {
        wifiNudgeHandler.removeCallbacks(wifiNudgeRunnable)
        encounterMesh?.stop()
        beaconManager.stopScanning()
        bluetoothManager.stopScanning()
        backgroundScanManager.disableBackgroundScanning()
        backgroundScheduler.disableAll()
        stopSyncTimer()
        stopScanRefreshTimer()

        if (BeaconScanService.isRunning) {
            BeaconScanService.stop(context)
        }
        
        // Persist scanning state
        SDKConfigStorage.saveScanningEnabled(context, false)

        syncBeacons()
    }

    internal fun processBroadcastResults(
        scanResults: List<ScanResult>,
        onFlushSettled: (() -> Unit)? = null
    ) {
        if (!isConfigured) {
            attemptConfigRestore()
            if (!isConfigured) {
                Log.e(TAG, "Cannot process broadcast - SDK not configured")
                onFlushSettled?.invoke()
                return
            }
        }

        val isAppInForeground = isAppInForeground()

        Log.d(TAG, "Processing ${scanResults.size} broadcast results (app in foreground: $isAppInForeground)")

        // v2.5 — Broadcast results MUST be processed in any app state. They are the
        // only signal that fires the region-rising-edge while we are outside the region
        // (active ranging is gated by isInBeaconRegion, so it can't bootstrap itself).
        // Active ranging dedupes by identifier in processBeacon so re-processing is safe.
        io.bearound.sdk.utilities.ScanResultFreshness.filterControllerReplay(scanResults).forEach { result ->
            beaconManager.processExternalScanResult(result)
        }

        val beaconsAfterBroadcast = beaconLock.withLock { collectedBeacons.size }

        // Immediate background flush. The periodic sync timer runs on the uptime
        // clock (Handler.postDelayed), which freezes in Doze — a background
        // detection waiting for it ships minutes late, or only when the app opens.
        // So every background detection flushes NOW (debounced), regardless of the
        // timer. The debounce keeps a 1 Hz beacon from turning into 1 Hz POSTs.
        if (!isAppInForeground && beaconsAfterBroadcast > 0) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastImmediateFlushAt >= IMMEDIATE_FLUSH_MIN_GAP_MS) {
                lastImmediateFlushAt = now
                Log.d(TAG, "Broadcast detected beacons in background - flushing immediately")
                // Fast path: sync while the broadcast window (goAsync) holds the
                // process. Safety net: an expedited Worker with its own execution
                // window + network constraint re-runs the flush if this one dies.
                ImmediateSyncWorker.enqueue(context)
                scope.launch {
                    try {
                        syncBeaconsAwait(forceBackground = true)
                    } finally {
                        onFlushSettled?.invoke()
                    }
                }
                return
            }
        }
        onFlushSettled?.invoke()
    }
    
    private fun isAppInForeground(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val appProcesses = activityManager.runningAppProcesses ?: return false
        
        val packageName = context.packageName
        for (processInfo in appProcesses) {
            if (processInfo.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                processInfo.processName == packageName) {
                return true
            }
        }
        return false
    }

    fun isLocationAvailable(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    fun getLocationPermissionStatus(): String {
        val fineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val backgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }

        return when {
            backgroundLocation -> "authorized_always"
            fineLocation || coarseLocation -> "authorized_when_in_use"
            else -> "denied"
        }
    }

    private fun startSyncTimer() {
        val config = configuration ?: return

        Log.d(TAG, "=== START SYNC TIMER ===")
        Log.d(TAG, "Precision: ${config.scanPrecision}")
        Log.d(TAG, "Sync interval: ${config.syncInterval}ms")

        stopSyncTimer()

        // Beacon eviction timeout for the CONTINUOUS scan modes (3.5.2): a present beacon
        // delivers every ~1-2 s in foreground (LOW_LATENCY) and every ~5 s window in
        // background (BALANCED/LOW_POWER), so 15 s ≈ 3+ missed windows before eviction —
        // the list stays honest without flickering. LOW gets extra margin for its ~10%
        // duty. (The old formula covered the manual scan+pause duty cycle, which no
        // longer exists.)
        val baseTimeout = when (config.scanPrecision) {
            ScanPrecision.HIGH, ScanPrecision.MEDIUM -> 15_000L
            ScanPrecision.LOW -> 25_000L
        }
        // Weak-receiver compensation (Unisoc/Spreadtrum class): the controller captures
        // a fraction of the air, so useful frames arrive 10-45 s apart even at 25 cm —
        // double the retention windows so the host list holds steady instead of
        // flickering (bench: Moto G35 T760, realme C61 T612).
        val weakRx = io.bearound.sdk.utilities.WeakReceiverProfile.isWeakReceiver
        val beaconTimeout = if (weakRx) baseTimeout * 2 else baseTimeout
        val staleMs = if (weakRx) 20_000L else 10_000L
        if (weakRx) Log.i(TAG, "Weak-receiver SoC detected (${android.os.Build.HARDWARE}) — retention windows doubled")
        beaconManager.setBeaconTimeout(beaconTimeout, staleMs)
        // Listener emissions of collectedBeacons expire on the same clock as the manager
        // (setBeaconTimeout clamps to its 30s floor — mirror that so the two lists agree).
        listenerBeaconTtlMs = maxOf(beaconTimeout, 30_000L)
        Log.d(TAG, "Beacon timeout set to ${beaconTimeout}ms")

        when (config.scanPrecision) {
            ScanPrecision.HIGH -> startHighPrecision(config)
            ScanPrecision.MEDIUM, ScanPrecision.LOW -> startContinuousLowDuty(config)
        }
    }

    /**
     * HIGH precision: continuous scanning + sync every 15s
     */
    private fun startHighPrecision(config: SDKConfiguration) {
        Log.d(TAG, "HIGH precision: continuous scan, sync every ${config.syncInterval / 1000}s")

        beaconManager.rangingScanMode = null
        beaconManager.startRanging()

        syncRunnable = object : Runnable {
            override fun run() {
                syncBeacons()
                handler.postDelayed(this, config.syncInterval)
            }
        }
        handler.postDelayed(syncRunnable!!, config.syncInterval)
    }

    /**
     * MEDIUM/LOW: ONE continuous scan registration with a hardware-managed duty cycle.
     *
     * MEDIUM → SCAN_MODE_BALANCED (controller listens ~1 s every ~5 s, ~20% duty);
     * LOW → SCAN_MODE_LOW_POWER (~0.5 s every ~5 s, ~10% duty). Detections land every
     * few seconds in both modes; sync stays on the 60 s timer.
     *
     * Replaces the manual 10 s-scan/10 s-pause duty cycle: that design consumed 3-4 of
     * the 5 scan-starts/30 s the OS allows BY DESIGN, so any extra start (watchdog,
     * batch revive, anti-downgrade refresh, fg/bg flip) tripped the quota and the OS
     * silently starved every scanner for 30 s+ — field-observed on Moto G35 as
     * "minutes without a beacon". One registration = zero start churn: the whole
     * budget stays available for the recovery paths, and beacons never expire inside
     * an artificial pause window.
     */
    private fun startContinuousLowDuty(config: SDKConfiguration) {
        val lowPower = config.scanPrecision == ScanPrecision.LOW
        beaconManager.rangingScanMode = if (lowPower) {
            ScanSettings.SCAN_MODE_LOW_POWER
        } else {
            ScanSettings.SCAN_MODE_BALANCED
        }

        Log.d(
            TAG,
            "${config.scanPrecision} precision: continuous " +
                (if (lowPower) "LOW_POWER (~10% hardware duty)" else "BALANCED (~20% hardware duty)") +
                " scan, sync every ${config.syncInterval / 1000}s"
        )

        beaconManager.startRanging()

        syncRunnable = object : Runnable {
            override fun run() {
                syncBeacons()
                handler.postDelayed(this, config.syncInterval)
            }
        }
        handler.postDelayed(syncRunnable!!, config.syncInterval)
    }

    private fun restartSyncTimer() {
        if (isScanning) {
            startSyncTimer()
        }
    }

    /**
     * Arms ONLY the periodic sync runnable, without touching the scan sessions —
     * for the revive path, where ranging is already (re)started separately and an
     * extra scan-start would eat the OS scan-start quota.
     */
    private fun armSyncTimerOnly() {
        val config = configuration ?: return
        if (syncRunnable != null) return
        syncRunnable = object : Runnable {
            override fun run() {
                syncBeacons()
                handler.postDelayed(this, config.syncInterval)
            }
        }
        handler.postDelayed(syncRunnable!!, config.syncInterval)
        Log.d(TAG, "Sync timer armed (revive path, every ${config.syncInterval / 1000}s)")
    }

    private fun stopSyncTimer() {
        syncRunnable?.let { handler.removeCallbacks(it) }
        syncRunnable = null
    }

    /**
     * Fix B — periodic anti-downgrade refresh (see [SCAN_REFRESH_INTERVAL_MS]).
     *
     * Every tick re-registers the two long-lived scan clients so the platform treats them
     * as fresh sessions at full duty:
     *  - [BluetoothManager.restartScanning] — the metadata (BALANCED) scan;
     *  - [BeaconManager.refreshBatchScan] — the batch scan.
     * Both are budget-aware internally (ScanStartBudget): when the budget has no headroom
     * they keep the current session instead of killing it, so a tick can only ever be a
     * no-op — never a regression. The PendingIntent scan is NOT restarted here: it is
     * kernel-managed, exempt from session downgrade, and re-arming it costs budget.
     */
    private fun startScanRefreshTimer() {
        stopScanRefreshTimer()
        scanRefreshRunnable = object : Runnable {
            override fun run() {
                Log.d(TAG, "Scan refresh tick — re-registering scan sessions (anti-downgrade)")
                bluetoothManager.restartScanning()
                beaconManager.refreshBatchScan()
                handler.postDelayed(this, SCAN_REFRESH_INTERVAL_MS)
            }
        }
        handler.postDelayed(scanRefreshRunnable!!, SCAN_REFRESH_INTERVAL_MS)
        Log.d(TAG, "Scan refresh timer armed (every ${SCAN_REFRESH_INTERVAL_MS / 60000} min)")
    }

    private fun stopScanRefreshTimer() {
        scanRefreshRunnable?.let { handler.removeCallbacks(it) }
        scanRefreshRunnable = null
    }

    /** Attaches encounter-mesh data (sightings + own rotating ids) to a payload.
     * No-op (empty fields, omitted from JSON) before the mesh spins up. */
    private fun io.bearound.sdk.models.UserDevice.withEncounterData(): io.bearound.sdk.models.UserDevice {
        val mesh = encounterMesh ?: return this
        val sightings = mesh.snapshotEncounters()
        if (sightings.isEmpty()) return this
        return copy(encounters = sightings, encounterIds = mesh.currentEncounterIds())
    }

    /** True when the mesh has identified sightings newer than the last encounters-only
     * upload AND that upload was 60s+ ago. Advances the throttle timestamp. */
    private fun shouldSyncEncountersWithoutBeacons(): Boolean {
        val mesh = encounterMesh ?: return false
        val now = System.currentTimeMillis()
        if (now - lastEncounterOnlySyncAt < 60_000) return false
        if (!mesh.hasFreshEncounters(lastEncounterOnlySyncAt)) return false
        lastEncounterOnlySyncAt = now
        Log.d(TAG, "No new beacons — syncing encounter batch")
        return true
    }

    private fun syncBeacons(forceBackground: Boolean = false) {
        scope.launch { syncBeaconsAwait(forceBackground) }
    }

    /**
     * Awaitable sync — the callers that own a guaranteed execution window
     * (goAsync broadcast, WorkManager workers) MUST await the upload instead of
     * fire-and-forget, otherwise the system reclaims the window with the POST
     * still in flight. Returns false only on a failed upload (so workers can retry).
     */
    internal suspend fun syncBeaconsAwait(forceBackground: Boolean = false): Boolean {
        val operation = syncMutex.withMutex {
            activeSync?.takeIf { it.isActive }
                ?: scope.async { performSyncOnce(forceBackground) }.also { activeSync = it }
        }
        return operation.await()
    }

    /** The actual sync body — reached only via the single-flight gate above. */
    private suspend fun performSyncOnce(forceBackground: Boolean): Boolean {
            // Register retry piggyback: if the first register failed (e.g. the app launched
            // offline), nothing else would retry it while the process lives — the TTL gate
            // makes this a no-op once registered.
            registerDeviceIfNeeded()

            val client = apiClient
            val info = sdkInfo

            if (client == null || info == null) {
                Log.w(TAG, "Cannot sync - SDK not configured")
                return true
            }

            val shouldRetryFailed = shouldRetryFailedBatches()

            // Check if we should retry failed batches
            if (shouldRetryFailed) {
                val allRecords = offlineBatchStorage.loadAllRecords()
                if (allRecords.isNotEmpty()) {
                    return syncRetryBatchesInChunks(allRecords, client, info, forceBackground)
                }
            }

            // Regular sync: get collected beacons (skip already synced)
            val rawBeaconsToSend = beaconLock.withLock {
                collectedBeacons.values.filter { !it.alreadySynced }
            }

            // Encounter mesh: sightings must reach the backend even when no physical
            // beacon is around — otherwise a device that only sees other devices never
            // uploads anything. Throttled (60s) and gated on fresh identified sightings.
            if (rawBeaconsToSend.isEmpty() && !shouldSyncEncountersWithoutBeacons()) return true

            // Snapshot + reset per-beacon RSSI accumulators so the payload carries the
            // FULL window stats and the next window starts fresh.
            val freshStats = beaconManager.consumeRssiStats(rawBeaconsToSend.map { it.identifier })
            val beaconsToSend = rawBeaconsToSend.map { b ->
                val stats = freshStats[b.identifier] ?: b.rssiSamples
                if (stats != b.rssiSamples) b.copy(rssiSamples = stats) else b
            }

            // Record the scan result (beacons collected from scanning this window).
            DiagnosticsStore.recordScan(beaconsToSend.size)

            // Persist-BEFORE-send: a durable copy exists before the POST leaves, so a
            // process death mid-request can no longer lose the batch (the old flow only
            // saved AFTER a failure callback — death between send and callback = data
            // gone). On success the exact id is removed; on failure the batch is already
            // on disk for the retry drain. A lost 2xx response re-sends the batch — the
            // known at-least-once trade-off until the backend dedupe lands.
            val persistedBatchId = offlineBatchStorage.saveBatchReturningId(beaconsToSend)
            if (persistedBatchId == null) {
                Log.e(TAG, "Persist-before-send failed — batch has no durable copy (upload proceeds)")
                DiagnosticsStore.recordError("persist-before-send: saveBatch returned null")
            }

            // Notify listener that sync is starting
            dispatchToListener { it.onSyncStarted(beaconsToSend.size) }

            val locationPermission = getLocationPermissionStatus()
            val bluetoothState = if (bluetoothManager.isPoweredOn) "powered_on" else "powered_off"

            val isAppInBackground = if (forceBackground) true else isInBackground

            val userDevice = deviceInfoCollector.collectDeviceInfo(
                locationPermission = locationPermission,
                bluetoothState = bluetoothState,
                appInForeground = !isAppInBackground
            ).withEncounterData()

            // sendBeacons is suspend and invokes the callback before returning,
            // so syncOk is settled by the time we return it.
            var syncOk = false
            client.sendBeacons(beaconsToSend, info, userDevice, userProperties) { result ->
                result.fold(
                    onSuccess = {
                        syncOk = true
                        consecutiveFailures = 0
                        lastFailureTime = null

                        // Delivered — drop the durable copy so the retry drain never
                        // re-sends this exact batch.
                        persistedBatchId?.let { offlineBatchStorage.removeBatch(it) }

                        // Mark synced beacons and schedule removal after 30s
                        val syncedIds = beaconsToSend.map { it.identifier }
                        beaconLock.withLock {
                            syncedIds.forEach { id ->
                                collectedBeacons[id]?.let {
                                    collectedBeacons[id] = it.copy(alreadySynced = true, syncedAt = java.util.Date())
                                }
                            }
                        }
                        Log.d(TAG, "Marked ${syncedIds.size} beacons as synced")

                        // Notify listener so UI reflects sync state (TTL-filtered: beacons
                        // already gone from the air must not resurface here)
                        val updatedBeacons = beaconLock.withLock {
                            collectedForListenerLocked()
                        }
                        dispatchToListener { it.onBeaconsUpdated(updatedBeacons) }

                        handler.postDelayed({
                            val (removedAny, remaining) = beaconLock.withLock {
                                var removed = false
                                syncedIds.forEach { id ->
                                    val beacon = collectedBeacons[id]
                                    if (beacon?.alreadySynced == true) {
                                        collectedBeacons.remove(id)
                                        removed = true
                                    }
                                }
                                Pair(removed, collectedForListenerLocked())
                            }
                            Log.d(TAG, "Removed synced beacons from cache after 30s")
                            // The post-sync emit above can resurface a beacon the detection
                            // map already evicted (listener TTL floor is 30 s vs 15 s
                            // eviction). Without this refresh the host keeps that last
                            // non-empty list forever — the "synced+stale zombie card"
                            // (field: Redmi 9C). Emitting the now-filtered list (possibly
                            // empty) lets the host finally drop it.
                            if (removedAny) {
                                dispatchToListener { it.onBeaconsUpdated(remaining) }
                            }
                        }, 30_000L)

                        // Notify listener of success
                        PushTokenStore.markSent(userDevice.pushToken)
                        DiagnosticsStore.recordSync(success = true, beaconCount = beaconsToSend.size)
                        DetectionLogStore.append(
                            context,
                            type = "Sync OK",
                            detail = "${beaconsToSend.size} beacon(s) enviados ao ingester"
                        )
                        dispatchToListener { it.onSyncCompleted(beaconsToSend.size, success = true, error = null) }
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Sync failed: ${error.message}")
                        // alreadyPersisted: persist-before-send wrote the durable copy up
                        // front — only fall back to saving here when THAT write failed.
                        handleSyncFailure(beaconsToSend, error, alreadyPersisted = persistedBatchId != null)

                        DiagnosticsStore.recordSync(success = false, beaconCount = beaconsToSend.size)
                        DetectionLogStore.append(
                            context,
                            type = "Sync falhou",
                            detail = "${beaconsToSend.size} beacon(s) · ${error.message ?: "erro desconhecido"}"
                        )

                        // Notify listener of failure
                        dispatchToListener {
                            it.onSyncCompleted(
                                beaconsToSend.size,
                                success = false,
                                error = error as? Exception ?: Exception(error.message)
                            )
                        }
                    }
                )
            }
            return syncOk
    }

    /**
     * Sends all retry batches in chunks of 5, sequentially.
     * Stops on the first chunk failure; successfully sent batches are removed from storage.
     * Returns false when a chunk failed (callers with an execution window can retry).
     */
    private suspend fun syncRetryBatchesInChunks(
        allRecords: List<OfflineBatchStorage.StoredBatchRecord>,
        client: APIClient,
        info: SDKInfo,
        forceBackground: Boolean
    ): Boolean {
        val locationPermission = getLocationPermissionStatus()
        val bluetoothState = if (bluetoothManager.isPoweredOn) "powered_on" else "powered_off"
        val isAppInBackground = if (forceBackground) true else isInBackground

        val userDevice = deviceInfoCollector.collectDeviceInfo(
            locationPermission = locationPermission,
            bluetoothState = bluetoothState,
            appInForeground = !isAppInBackground
        )

        val chunks = allRecords.chunked(5)
        Log.d(TAG, "Retrying ${allRecords.size} batches in ${chunks.size} chunk(s) of up to 5")

        for ((chunkIndex, chunk) in chunks.withIndex()) {
            val beaconsInChunk = chunk.flatMap { it.beacons }
            if (beaconsInChunk.isEmpty()) continue

            Log.d(TAG, "Sending retry chunk ${chunkIndex + 1}/${chunks.size} — ${beaconsInChunk.size} beacons from ${chunk.size} batch(es)")

            dispatchToListener { it.onSyncStarted(beaconsInChunk.size) }

            var chunkResult: Result<Unit>? = null
            client.sendBeacons(beaconsInChunk, info, userDevice, userProperties) { result ->
                chunkResult = result
            }

            if (chunkResult?.isFailure == true) {
                val error = chunkResult!!.exceptionOrNull()!!
                val status = (error as? HttpException)?.statusCode
                val isPermanent = status != null && status in PERMANENT_HTTP_CODES

                if (!isPermanent) {
                    // Transient (network, timeout, 408/429/5xx): stop and let the caller's
                    // backoff retry the WHOLE queue later — nothing is lost.
                    Log.e(TAG, "Retry chunk ${chunkIndex + 1}/${chunks.size} failed: ${error.message}")

                    consecutiveFailures++
                    lastFailureTime = System.currentTimeMillis()

                    DiagnosticsStore.recordSync(success = false, beaconCount = beaconsInChunk.size)
                    DiagnosticsStore.recordError("Retry chunk failed: ${error.message}")
                    DetectionLogStore.append(
                        context,
                        type = "Sync falhou",
                        detail = "${beaconsInChunk.size} beacon(s) · ${error.message ?: "erro desconhecido"}"
                    )

                    dispatchToListener {
                        it.onSyncCompleted(
                            beaconsInChunk.size,
                            success = false,
                            error = error as? Exception ?: Exception(error.message)
                        )
                        it.onError(error as? Exception ?: Exception(error.message))
                    }

                    return false
                }

                // Permanent rejection (400/401/403/404/413/422): the backend is healthy but
                // SOME batch in this chunk is poison — an identical retry fails identically,
                // and stopping here let one bad batch block the whole queue for 7 days
                // (head-of-line blocking; field case: the 3.4.5 422-rejected payloads).
                // Bisect: send each batch alone, quarantine the rejected one(s), keep going.
                // NOT counted as consecutiveFailures — the API is reachable.
                Log.w(TAG, "Retry chunk ${chunkIndex + 1}/${chunks.size} rejected permanently (HTTP $status) — bisecting ${chunk.size} batch(es)")
                var delivered = 0
                for (record in chunk) {
                    var singleResult: Result<Unit>? = null
                    client.sendBeacons(record.beacons, info, userDevice, userProperties) { r ->
                        singleResult = r
                    }
                    val singleStatus = (singleResult?.exceptionOrNull() as? HttpException)?.statusCode
                    when {
                        singleResult?.isSuccess == true -> {
                            offlineBatchStorage.removeBatch(record.id)
                            delivered += record.beacons.size
                        }
                        singleStatus != null && singleStatus in PERMANENT_HTTP_CODES -> {
                            DiagnosticsStore.recordError("Batch quarantined (HTTP $singleStatus): ${record.id}")
                            DetectionLogStore.append(
                                context,
                                type = "Sync falhou",
                                detail = "batch rejeitado pelo backend (HTTP $singleStatus) — quarentenado"
                            )
                            offlineBatchStorage.quarantineBatch(record.id)
                        }
                        else -> {
                            // Network blinked mid-bisect: stop; everything left stays queued.
                            return false
                        }
                    }
                }
                if (delivered > 0) {
                    PushTokenStore.markSent(userDevice.pushToken)
                    DiagnosticsStore.recordSync(success = true, beaconCount = delivered)
                    DetectionLogStore.append(
                        context,
                        type = "Sync OK",
                        detail = "$delivered beacon(s) enviados ao ingester (após bisect)"
                    )
                    dispatchToListener { it.onSyncCompleted(delivered, success = true, error = null) }
                }
                continue
            }

            // Chunk succeeded — remove EXACTLY the batches this chunk carried, by id.
            // The old positional removal (`repeat(chunk.size) { removeOldestBatch() }`)
            // deleted whatever happened to be oldest at removal time — a save/expiry
            // between load and remove could delete an UNSENT batch instead.
            consecutiveFailures = 0
            lastFailureTime = null
            val removed = offlineBatchStorage.removeBatches(chunk.map { it.id })

            Log.d(TAG, "Retry chunk ${chunkIndex + 1}/${chunks.size} succeeded — removed $removed batch(es)")

            PushTokenStore.markSent(userDevice.pushToken)
            DiagnosticsStore.recordSync(success = true, beaconCount = beaconsInChunk.size)
            DetectionLogStore.append(
                context,
                type = "Sync OK",
                detail = "${beaconsInChunk.size} beacon(s) enviados ao ingester"
            )
            dispatchToListener { it.onSyncCompleted(beaconsInChunk.size, success = true, error = null) }
        }

        Log.d(TAG, "All retry chunks completed — storage now has ${offlineBatchStorage.getBatchCount()} batch(es)")
        return true
    }

    /** SHA-256 of the business token, truncated — batch files record it, never the raw token. */
    private fun tenantFingerprint(businessToken: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(businessToken.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)

    private fun handleSyncFailure(beacons: List<Beacon>, error: Throwable, alreadyPersisted: Boolean) {
        consecutiveFailures++
        lastFailureTime = System.currentTimeMillis()

        DiagnosticsStore.recordError("Sync failed: ${error.message}")

        // Persist-before-send already wrote the durable copy for the regular path; this
        // save is only the fallback for the rare case that write itself failed.
        if (!alreadyPersisted) {
            val saved = offlineBatchStorage.saveBatch(beacons)
            if (saved) {
                Log.d(TAG, "Saved failed batch to persistent storage (total: ${offlineBatchStorage.getBatchCount()})")
            } else {
                Log.e(TAG, "Failed to save batch to persistent storage")
            }
        }

        if (consecutiveFailures >= 10) {
            val circuitBreakerError = Exception(
                "API unreachable after $consecutiveFailures consecutive failures"
            )
            dispatchToListener { it.onError(circuitBreakerError) }
        }

        dispatchToListener { it.onError(error as? Exception ?: Exception(error.message)) }
    }

    private fun shouldRetryFailedBatches(): Boolean {
        // Check if there are batches in persistent storage
        if (offlineBatchStorage.getBatchCount() == 0) return false
        
        val lastFailure = lastFailureTime ?: return true

        val timeSinceFailure = System.currentTimeMillis() - lastFailure

        val backoffDelay = minOf(
            5000L * 2.0.pow(minOf(consecutiveFailures - 1, 3).toDouble()).toLong(),
            60000L
        )

        return timeSinceFailure >= backoffDelay
    }
    
    // region Background Scheduler Support Methods
    
    /**
     * Check if there are pending beacons to sync
     * Used by WorkManager to decide if sync is needed
     */
    internal fun hasPendingBeacons(): Boolean {
        // Only UNSYNCED beacons count — a map full of alreadySynced entries awaiting
        // their 30s display-cleanup used to keep scheduling sync workers for nothing.
        val hasCollected = beaconLock.withLock { collectedBeacons.values.any { !it.alreadySynced } }
        val hasStored = offlineBatchStorage.getBatchCount() > 0
        return hasCollected || hasStored
    }
    
    /**
     * Get the number of pending batches
     * Useful for debugging and status display
     */
    val pendingBatchCount: Int
        get() = offlineBatchStorage.getBatchCount()

    /**
     * Get all pending batches
     * Useful for debugging and retry queue visualization
     */
    val pendingBatches: List<List<Beacon>>
        get() = offlineBatchStorage.loadAllRecords().map { it.beacons }

    /**
     * Returns a point-in-time snapshot of SDK state for diagnostics/support.
     *
     * Combines persisted identity and push-token state with in-memory activity from
     * [DiagnosticsStore] (recent scan/sync outcomes and errors). Safe to call at any
     * time; values are best-effort and reflect what the SDK has observed so far.
     */
    /**
     * Persisted detection log as a JSON array string (newest first), each entry
     * `{id, timestamp, state, type, detail}` where `state` is one of
     * `foreground` / `background` / `backgroundLocked` / `terminated`.
     *
     * It survives process death, so it is the way to answer "did the SDK detect
     * anything while the app was closed?" — those entries carry `terminated`.
     *
     * Same contract as the iOS SDK's `getDetectionLogJson()`.
     */
    fun getDetectionLogJson(): String = DetectionLogStore.readJson(context)

    /** Clears the persisted detection log. Mirrors iOS `clearDetectionLog()`. */
    fun clearDetectionLog() = DetectionLogStore.clear(context)

    fun diagnostics(): BeAroundDiagnostics {
        val hasBtScan = ::beaconManager.isInitialized && beaconManager.hasBluetoothScanPermission()
        return BeAroundDiagnostics(
            deviceId = DeviceIdentifier.getDeviceId(context),
            pushTokenMasked = PushTokenStore.maskedToken(),
            pushTokenLastSentAt = PushTokenStore.lastSentAt(),
            isScanning = isScanning,
            pendingBatches = pendingBatchCount,
            lastScanAt = DiagnosticsStore.lastScanAt(),
            lastScanBeaconCount = DiagnosticsStore.lastScanBeaconCount(),
            lastSyncAt = DiagnosticsStore.lastSyncAt(),
            lastSyncSuccess = DiagnosticsStore.lastSyncSuccess(),
            lastSyncBeaconCount = DiagnosticsStore.lastSyncBeaconCount(),
            recentErrors = DiagnosticsStore.recentErrors(),
            sdkVersion = BuildConfig.SDK_VERSION,
            osApiLevel = Build.VERSION.SDK_INT,
            hasBluetoothScanPermission = hasBtScan,
            bluetoothEnabled = ::bluetoothManager.isInitialized && bluetoothManager.isPoweredOn,
            foregroundServiceActive = BeaconScanService.isRunning,
            backgroundScanRegistered = ::backgroundScanManager.isInitialized && backgroundScanManager.isRegistered,
            isIgnoringBatteryOptimizations = BackgroundReliabilityHelper.isIgnoringBatteryOptimizations(context)
        )
    }

    /**
     * Perform background sync
     * Called by WorkManager and AlarmManager watchdog
     */
    internal fun performBackgroundSync() {
        Log.d(TAG, "performBackgroundSync called")
        syncBeacons(forceBackground = true)
    }

    /**
     * Awaitable background sync — Workers MUST use this one and await it, so the
     * WorkManager window (and its wakelock) covers the whole upload. The legacy
     * fire-and-forget above returned before the POST even started, letting the
     * system freeze the process with the request in flight.
     */
    internal suspend fun performBackgroundSyncAwait(): Boolean {
        Log.d(TAG, "performBackgroundSync (await) called")
        return syncBeaconsAwait(forceBackground = true)
    }
    
    /**
     * Check if scanning was previously enabled (before app kill/reboot)
     */
    internal fun wasScanningEnabled(): Boolean {
        return SDKConfigStorage.loadScanningEnabled(context)
    }

    /**
     * Re-registers the PendingIntent scan from scratch (see
     * [io.bearound.sdk.background.BackgroundScanManager.refreshBackgroundScanning]).
     * Called by the 15-min watchdog as a self-heal for silently-dead scan clients.
     */
    internal fun refreshBackgroundScanRegistration() {
        backgroundScanManager.refreshBackgroundScanning()
    }
    
    /**
     * Restart scanning from background (after app kill/reboot)
     * Only starts beacon detection, not full UI updates
     */
    internal fun restartScanningFromBackground() {
        Log.d(TAG, "restartScanningFromBackground called")
        
        if (!isConfigured) {
            attemptConfigRestore()
            if (!isConfigured) {
                Log.w(TAG, "Cannot restart scanning - SDK not configured")
                return
            }
        }
        
        val config = configuration ?: return
        
        // Scanning mode is automatic based on app state
        beaconManager.startScanning()

        // Re-enable background mechanisms
        backgroundScanManager.enableBackgroundScanning()
        backgroundScheduler.enableAll()

        // Bluetooth scanning is always enabled in v2.2.0+
        bluetoothManager.startScanning()

        // Fix B — this revive path starts the same long-lived scan sessions as
        // startScanning(), so it needs the same anti-downgrade refresh. Without this,
        // a process revived by the watchdog/boot receiver ran unprotected until the
        // host happened to call startScanning() again.
        startScanRefreshTimer()

        // A revived process never had its sync timer armed (only startScanning()
        // arms it) — without this, it depends entirely on the 15-min fallbacks.
        // NOT startSyncTimer(): that one also (re)starts ranging, and an extra
        // scan-start here would burn the OS scan-start quota (see
        // startContinuousLowDuty). Arm just the timer.
        armSyncTimerOnly()

        // Restore foreground service if it was enabled
        val fgConfig = foregroundScanConfig
        if (fgConfig?.enabled == true && !BeaconScanService.isRunning) {
            BeaconScanService.start(context, fgConfig)
        }

        Log.d(TAG, "Scanning restarted from background")
    }
    
    // endregion
}
