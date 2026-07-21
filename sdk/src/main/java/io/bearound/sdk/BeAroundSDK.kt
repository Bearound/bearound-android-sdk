package io.bearound.sdk

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.le.ScanResult
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
import io.bearound.sdk.interfaces.BeAroundSDKListener
import io.bearound.sdk.interfaces.BluetoothManagerListener
import io.bearound.sdk.models.Beacon
import io.bearound.sdk.models.BeAroundDiagnostics
import io.bearound.sdk.models.BeaconMetadata
import io.bearound.sdk.models.ForegroundScanConfig
import io.bearound.sdk.models.MaxQueuedPayloads
import io.bearound.sdk.models.SDKConfiguration
import io.bearound.sdk.models.SDKInfo
import io.bearound.sdk.models.ScanPrecision
import io.bearound.sdk.models.UserProperties
import io.bearound.sdk.network.APIClient
import io.bearound.sdk.telemetry.ErrorReporter
import io.bearound.sdk.utilities.BackgroundReliabilityHelper
import io.bearound.sdk.utilities.OemPowerProfile
import io.bearound.sdk.utilities.DeviceIdentifier
import io.bearound.sdk.utilities.DeviceInfoCollector
import io.bearound.sdk.utilities.DiagnosticsStore
import io.bearound.sdk.utilities.OfflineBatchStorage
import io.bearound.sdk.utilities.PushTokenStore
import io.bearound.sdk.utilities.RegisterStore
import io.bearound.sdk.utilities.SDKConfigStorage
import io.bearound.sdk.utilities.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    private var sdkInfo: SDKInfo? = null
    private var userProperties: UserProperties? = null

    private lateinit var deviceInfoCollector: DeviceInfoCollector
    private lateinit var beaconManager: BeaconManager
    private lateinit var bluetoothManager: BluetoothManager
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

    private var syncRunnable: Runnable? = null
    private var dutyCycleRunnable: Runnable? = null
    private var scanRefreshRunnable: Runnable? = null

    private var isSyncing = false
    private lateinit var offlineBatchStorage: OfflineBatchStorage
    private var consecutiveFailures = 0
    private var lastFailureTime: Long? = null

    private var isInBackground = false
    private val isColdStart = true
    private var foregroundScanConfig: ForegroundScanConfig? = null
    private val registerInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    val isScanning: Boolean
        get() = ::beaconManager.isInitialized && beaconManager.isScanning

    val currentSyncInterval: Long?
        get() = configuration?.syncInterval

    val currentScanDuration: Long?
        get() = configuration?.precisionScanDuration

    val currentScanPrecision: ScanPrecision?
        get() = configuration?.scanPrecision

    val currentPauseDuration: Long?
        get() = configuration?.precisionPauseDuration

    val isPeriodicScanningEnabled: Boolean
        get() = configuration?.scanPrecision != ScanPrecision.HIGH

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
        
        deviceInfoCollector = DeviceInfoCollector(context, isColdStart)
        beaconManager = BeaconManager(context)
        bluetoothManager = BluetoothManager(context)
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

            // Notify if beacons detected in background
            if (isInBackground && enrichedBeacons.isNotEmpty()) {
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

        beaconManager.onBackgroundRangingComplete = {
            syncBeacons()
        }

        // v2.5 — region transitions: gate active BLE scan
        beaconManager.onRegionEnter = {
            dispatchToListener { it.onEnterBeaconRegion() }
        }

        beaconManager.onRegionExit = {
            dispatchToListener { it.onExitBeaconRegion() }
        }

        beaconManager.onActiveScanShouldStart = {
            Log.d(TAG, "Active scan START — region entered, starting BLE central scan + duty cycle")
            // Bluetooth metadata scan ON only while inside a region.
            bluetoothManager.startScanning()
            dispatchToListener { it.onActiveScanStateChanged(true) }
        }

        beaconManager.onActiveScanShouldStop = {
            Log.d(TAG, "Active scan STOP — region exited, stopping BLE central scan")
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
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                onAppForegrounded()
            }

            override fun onStop(owner: LifecycleOwner) {
                onAppBackgrounded()
            }
        })
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
    fun configure(
        businessToken: String,
        scanPrecision: ScanPrecision = ScanPrecision.MEDIUM,
        maxQueuedPayloads: MaxQueuedPayloads = MaxQueuedPayloads.MEDIUM,
        technology: String = "android-native"
    ) {
        // NEVER-CRASH-THE-HOST: an embedded SDK must not throw from a public entry
        // point — a host wired to an empty BuildConfig field would crash on startup.
        // Fail silently-but-visibly instead: log, report to telemetry, surface via
        // onError, and leave the SDK unconfigured (every other API no-ops safely).
        if (businessToken.trim().isEmpty()) {
            Log.e(TAG, "Business token cannot be empty — configure() skipped, SDK stays inactive")
            val error = IllegalArgumentException("Business token cannot be empty")
            ErrorReporter.report(error, "configure")
            listener?.onError(error)
            return
        }

        val appId = context.packageName

        val config = SDKConfiguration(
            businessToken = businessToken,
            appId = appId,
            scanPrecision = scanPrecision,
            maxQueuedPayloads = maxQueuedPayloads,
            technology = technology
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

        SDKConfigStorage.saveConfiguration(context, config)

        // Error telemetry — isolated reporter ("try/catch around the library"). Own
        // try/catch: a telemetry failure must never break configure().
        try {
            ErrorReporter.install(context, businessToken)
        } catch (t: Throwable) {
            Log.w(TAG, "Error telemetry install failed: ${t.message}")
        }

        tryAutoCollectFcmToken(context)

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
            if (state == android.bluetooth.BluetoothAdapter.STATE_ON &&
                ::beaconManager.isInitialized && wasScanningEnabled()
            ) {
                Log.i(TAG, "Bluetooth back ON — re-arming scan clients")
                beaconManager.onBluetoothRestored()
                backgroundScanManager.refreshBackgroundScanning()
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

    internal fun startQuickScan() {
        if (!isConfigured) {
            val savedConfig = SDKConfigStorage.loadConfiguration(context)
            if (savedConfig != null) {
                configuration = savedConfig
                apiClient = APIClient(savedConfig)
                
                val buildNumber = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionCode
                } catch (e: Exception) {
                    1
                }
                sdkInfo = SDKInfo(appId = savedConfig.appId, build = buildNumber, technology = savedConfig.technology)
            } else {
                Log.w(TAG, "Cannot start quick scan - SDK not configured")
                return
            }
        }
        
        beaconManager.startScanning()
        beaconManager.startRanging()
        
        // Always attempt Bluetooth scanning
        bluetoothManager.startScanning()
    }

    internal fun stopQuickScan() {
        beaconManager.stopScanning()
        bluetoothManager.stopScanning()
        syncBeacons()
    }

    internal fun processBroadcastResults(scanResults: List<ScanResult>) {
        if (!isConfigured) {
            attemptConfigRestore()
            if (!isConfigured) {
                Log.e(TAG, "Cannot process broadcast - SDK not configured")
                return
            }
        }

        val isAppInForeground = isAppInForeground()

        Log.d(TAG, "Processing ${scanResults.size} broadcast results (app in foreground: $isAppInForeground)")

        // v2.5 — Broadcast results MUST be processed in any app state. They are the
        // only signal that fires the region-rising-edge while we are outside the region
        // (active ranging is gated by isInBeaconRegion, so it can't bootstrap itself).
        // Active ranging dedupes by identifier in processBeacon so re-processing is safe.
        scanResults.forEach { result ->
            beaconManager.processExternalScanResult(result)
        }

        val beaconsAfterBroadcast = beaconLock.withLock { collectedBeacons.size }
        val timerIsActive = (syncRunnable != null)

        // Only force-sync from broadcast when in background (foreground has its own sync timer).
        if (!isAppInForeground && !timerIsActive && beaconsAfterBroadcast > 0) {
            Log.d(TAG, "Broadcast detected beacons in background - syncing immediately")
            syncBeacons(forceBackground = true)
        }
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

        // Adaptive beacon timeout: cover scan + pause + 5s buffer so beacons don't expire mid-duty-cycle
        val beaconTimeout = config.precisionScanDuration + config.precisionPauseDuration + 5_000L
        beaconManager.setBeaconTimeout(beaconTimeout)
        // Listener emissions of collectedBeacons expire on the same clock as the manager
        // (setBeaconTimeout clamps to its 30s floor — mirror that so the two lists agree).
        listenerBeaconTtlMs = maxOf(beaconTimeout, 30_000L)
        Log.d(TAG, "Beacon timeout set to ${beaconTimeout}ms")

        when (config.scanPrecision) {
            ScanPrecision.HIGH -> startHighPrecision(config)
            ScanPrecision.MEDIUM, ScanPrecision.LOW -> startDutyCycle(config)
        }
    }

    /**
     * HIGH precision: continuous scanning + sync every 15s
     */
    private fun startHighPrecision(config: SDKConfiguration) {
        Log.d(TAG, "HIGH precision: continuous scan, sync every ${config.syncInterval / 1000}s")

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
     * MEDIUM/LOW precision: duty cycle with scan+pause windows
     * MEDIUM: 3 cycles of 10s scan + 10s pause per 60s window
     * LOW: 1 cycle of 10s scan + 50s pause per 60s window
     */
    private fun startDutyCycle(config: SDKConfiguration) {
        val scanDuration = config.precisionScanDuration
        val pauseDuration = config.precisionPauseDuration
        val cycleCount = config.precisionCycleCount
        val cycleInterval = config.precisionCycleInterval

        Log.d(TAG, "${config.scanPrecision} precision: ${cycleCount}x (${scanDuration/1000}s scan + ${pauseDuration/1000}s pause) per ${cycleInterval/1000}s window")

        runDutyCycleWindow(scanDuration, pauseDuration, cycleCount, cycleInterval)
    }

    private fun runDutyCycleWindow(
        scanDuration: Long,
        pauseDuration: Long,
        cycleCount: Int,
        cycleInterval: Long
    ) {
        var currentCycle = 0

        fun scheduleCycle() {
            if (currentCycle >= cycleCount) {
                // All cycles in this window done — sync and schedule next window
                Log.d(TAG, "Duty cycle window complete — syncing and scheduling next window")
                syncBeacons()

                // Calculate remaining time until next window
                val elapsedInWindow = cycleCount.toLong() * (scanDuration + pauseDuration)
                val remainingDelay = maxOf(0L, cycleInterval - elapsedInWindow)

                dutyCycleRunnable = Runnable {
                    runDutyCycleWindow(scanDuration, pauseDuration, cycleCount, cycleInterval)
                }
                handler.postDelayed(dutyCycleRunnable!!, remainingDelay)
                return
            }

            // Start scan phase
            Log.d(TAG, "Duty cycle ${currentCycle + 1}/$cycleCount: starting scan (${scanDuration / 1000}s)")
            beaconManager.resumeRanging()
            bluetoothManager.resumeScanning()

            // Schedule pause after scan duration
            dutyCycleRunnable = Runnable {
                Log.d(TAG, "Duty cycle ${currentCycle + 1}/$cycleCount: pausing (${pauseDuration / 1000}s)")
                beaconManager.pauseRanging()
                bluetoothManager.pauseScanning()
                currentCycle++

                // Schedule next cycle after pause
                dutyCycleRunnable = Runnable {
                    scheduleCycle()
                }
                handler.postDelayed(dutyCycleRunnable!!, pauseDuration)
            }
            handler.postDelayed(dutyCycleRunnable!!, scanDuration)
        }

        // Start first cycle immediately
        scheduleCycle()
    }

    private fun restartSyncTimer() {
        if (isScanning) {
            startSyncTimer()
        }
    }

    private fun stopSyncTimer() {
        syncRunnable?.let { handler.removeCallbacks(it) }
        syncRunnable = null
        dutyCycleRunnable?.let { handler.removeCallbacks(it) }
        dutyCycleRunnable = null
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

    private fun syncBeacons(forceBackground: Boolean = false) {
        scope.launch {
            if (isSyncing) return@launch

            // Register retry piggyback: if the first register failed (e.g. the app launched
            // offline), nothing else would retry it while the process lives — the TTL gate
            // makes this a no-op once registered.
            registerDeviceIfNeeded()

            val client = apiClient
            val info = sdkInfo
            
            if (client == null || info == null) {
                Log.w(TAG, "Cannot sync - SDK not configured")
                return@launch
            }

            val shouldRetryFailed = shouldRetryFailedBatches()

            // Check if we should retry failed batches
            if (shouldRetryFailed) {
                val allBatches = offlineBatchStorage.loadAllBatches()
                if (allBatches.isNotEmpty()) {
                    syncRetryBatchesInChunks(allBatches, client, info, forceBackground)
                    return@launch
                }
            }

            // Regular sync: get collected beacons (skip already synced)
            val rawBeaconsToSend = beaconLock.withLock {
                collectedBeacons.values.filter { !it.alreadySynced }
            }

            if (rawBeaconsToSend.isEmpty()) return@launch

            // Snapshot + reset per-beacon RSSI accumulators so the payload carries the
            // FULL window stats and the next window starts fresh.
            val freshStats = beaconManager.consumeRssiStats(rawBeaconsToSend.map { it.identifier })
            val beaconsToSend = rawBeaconsToSend.map { b ->
                val stats = freshStats[b.identifier] ?: b.rssiSamples
                if (stats != b.rssiSamples) b.copy(rssiSamples = stats) else b
            }

            // Record the scan result (beacons collected from scanning this window).
            DiagnosticsStore.recordScan(beaconsToSend.size)

            isSyncing = true

            // Notify listener that sync is starting
            dispatchToListener { it.onSyncStarted(beaconsToSend.size) }

            val locationPermission = getLocationPermissionStatus()
            val bluetoothState = if (bluetoothManager.isPoweredOn) "powered_on" else "powered_off"

            val isAppInBackground = if (forceBackground) true else isInBackground

            val userDevice = deviceInfoCollector.collectDeviceInfo(
                locationPermission = locationPermission,
                bluetoothState = bluetoothState,
                appInForeground = !isAppInBackground
            )

            client.sendBeacons(beaconsToSend, info, userDevice, userProperties) { result ->
                isSyncing = false

                result.fold(
                    onSuccess = {
                        consecutiveFailures = 0
                        lastFailureTime = null

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
                            beaconLock.withLock {
                                syncedIds.forEach { id ->
                                    val beacon = collectedBeacons[id]
                                    if (beacon?.alreadySynced == true) {
                                        collectedBeacons.remove(id)
                                    }
                                }
                            }
                            Log.d(TAG, "Removed synced beacons from cache after 30s")
                        }, 30_000L)

                        // Notify listener of success
                        PushTokenStore.markSent(userDevice.pushToken)
                        DiagnosticsStore.recordSync(success = true, beaconCount = beaconsToSend.size)
                        dispatchToListener { it.onSyncCompleted(beaconsToSend.size, success = true, error = null) }
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Sync failed: ${error.message}")
                        handleSyncFailure(beaconsToSend, error, isRetry = false)

                        DiagnosticsStore.recordSync(success = false, beaconCount = beaconsToSend.size)

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
        }
    }

    /**
     * Sends all retry batches in chunks of 5, sequentially.
     * Stops on the first chunk failure; successfully sent batches are removed from storage.
     */
    private suspend fun syncRetryBatchesInChunks(
        allBatches: List<List<Beacon>>,
        client: APIClient,
        info: SDKInfo,
        forceBackground: Boolean
    ) {
        isSyncing = true

        val locationPermission = getLocationPermissionStatus()
        val bluetoothState = if (bluetoothManager.isPoweredOn) "powered_on" else "powered_off"
        val isAppInBackground = if (forceBackground) true else isInBackground

        val userDevice = deviceInfoCollector.collectDeviceInfo(
            locationPermission = locationPermission,
            bluetoothState = bluetoothState,
            appInForeground = !isAppInBackground
        )

        val chunks = allBatches.chunked(5)
        Log.d(TAG, "Retrying ${allBatches.size} batches in ${chunks.size} chunk(s) of up to 5")

        for ((chunkIndex, chunk) in chunks.withIndex()) {
            val beaconsInChunk = chunk.flatten()
            if (beaconsInChunk.isEmpty()) continue

            Log.d(TAG, "Sending retry chunk ${chunkIndex + 1}/${chunks.size} — ${beaconsInChunk.size} beacons from ${chunk.size} batch(es)")

            dispatchToListener { it.onSyncStarted(beaconsInChunk.size) }

            var chunkResult: Result<Unit>? = null
            client.sendBeacons(beaconsInChunk, info, userDevice, userProperties) { result ->
                chunkResult = result
            }

            if (chunkResult?.isFailure == true) {
                val error = chunkResult!!.exceptionOrNull()!!
                Log.e(TAG, "Retry chunk ${chunkIndex + 1}/${chunks.size} failed: ${error.message}")

                consecutiveFailures++
                lastFailureTime = System.currentTimeMillis()

                DiagnosticsStore.recordSync(success = false, beaconCount = beaconsInChunk.size)
                DiagnosticsStore.recordError("Retry chunk failed: ${error.message}")

                dispatchToListener {
                    it.onSyncCompleted(
                        beaconsInChunk.size,
                        success = false,
                        error = error as? Exception ?: Exception(error.message)
                    )
                    it.onError(error as? Exception ?: Exception(error.message))
                }

                isSyncing = false
                return
            }

            // Chunk succeeded — remove these batches from storage (oldest first)
            consecutiveFailures = 0
            lastFailureTime = null
            repeat(chunk.size) {
                offlineBatchStorage.removeOldestBatch()
            }

            Log.d(TAG, "Retry chunk ${chunkIndex + 1}/${chunks.size} succeeded — removed ${chunk.size} batch(es)")

            PushTokenStore.markSent(userDevice.pushToken)
            DiagnosticsStore.recordSync(success = true, beaconCount = beaconsInChunk.size)
            dispatchToListener { it.onSyncCompleted(beaconsInChunk.size, success = true, error = null) }
        }

        isSyncing = false
        Log.d(TAG, "All retry chunks completed — storage now has ${offlineBatchStorage.getBatchCount()} batch(es)")
    }

    private fun handleSyncFailure(beacons: List<Beacon>, error: Throwable, isRetry: Boolean) {
        consecutiveFailures++
        lastFailureTime = System.currentTimeMillis()

        DiagnosticsStore.recordError("Sync failed: ${error.message}")

        // Save to persistent storage (only if not already a retry)
        if (!isRetry) {
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
        val hasCollected = beaconLock.withLock { collectedBeacons.isNotEmpty() }
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
        get() = offlineBatchStorage.loadAllBatches()

    /**
     * Returns a point-in-time snapshot of SDK state for diagnostics/support.
     *
     * Combines persisted identity and push-token state with in-memory activity from
     * [DiagnosticsStore] (recent scan/sync outcomes and errors). Safe to call at any
     * time; values are best-effort and reflect what the SDK has observed so far.
     */
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

        // Restore foreground service if it was enabled
        val fgConfig = foregroundScanConfig
        if (fgConfig?.enabled == true && !BeaconScanService.isRunning) {
            BeaconScanService.start(context, fgConfig)
        }

        Log.d(TAG, "Scanning restarted from background")
    }
    
    // endregion
}
