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
    private val beaconLock = ReentrantLock()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())

    private var syncRunnable: Runnable? = null
    private var dutyCycleRunnable: Runnable? = null

    private var isSyncing = false
    private lateinit var offlineBatchStorage: OfflineBatchStorage
    private var consecutiveFailures = 0
    private var lastFailureTime: Long? = null

    private var isInBackground = false
    private val isColdStart = true
    private var foregroundScanConfig: ForegroundScanConfig? = null

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
                val metadata = metadataCache[key]
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
            listener?.onBeaconsUpdated(beaconsForListener)

            // Notify if beacons detected in background
            if (isInBackground && enrichedBeacons.isNotEmpty()) {
                listener?.onBeaconDetectedInBackground(enrichedBeacons.size)

                // Update foreground notification with contextual content
                if (BeaconScanService.isRunning) {
                    val content = listener?.onProvideNotificationContent(beaconsForListener)
                    if (content != null) {
                        BeaconScanService.updateNotification(context, content.title, content.text)
                    }
                }
            }
        }

        beaconManager.onError = { error ->
            listener?.onError(error)
        }

        beaconManager.onScanningStateChanged = { isScanning ->
            listener?.onScanningStateChanged(isScanning)
        }

        beaconManager.onBackgroundRangingComplete = {
            syncBeacons()
        }

        // v2.5 — region transitions: gate active BLE scan
        beaconManager.onRegionEnter = {
            handler.post { listener?.onEnterBeaconRegion() }
        }

        beaconManager.onRegionExit = {
            handler.post { listener?.onExitBeaconRegion() }
        }

        beaconManager.onActiveScanShouldStart = {
            Log.d(TAG, "Active scan START — region entered, starting BLE central scan + duty cycle")
            // Bluetooth metadata scan ON only while inside a region.
            bluetoothManager.startScanning()
            handler.post { listener?.onActiveScanStateChanged(true) }
        }

        beaconManager.onActiveScanShouldStop = {
            Log.d(TAG, "Active scan STOP — region exited, stopping BLE central scan")
            bluetoothManager.stopScanning()
            handler.post { listener?.onActiveScanStateChanged(false) }
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
                    collectedBeacons.values.toList()
                }

                listener?.onBeaconsUpdated(beaconsForListener)

                if (isInBackground) {
                    listener?.onBeaconDetectedInBackground(beaconsForListener.size)
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

        backgroundScanManager.disableBackgroundScanning()

        if (BeaconScanService.isRunning) {
            BeaconScanService.stop(context)
        }

        beaconManager.setForegroundState(true)
        // Periodic scanning in foreground is automatic (controlled by sync timer)

        if (isScanning) {
            restartSyncTimer()
        }

        listener?.onAppStateChanged(isInBackground = false)
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
        
        listener?.onAppStateChanged(isInBackground = true)
    }

    /** Configures and activates the SDK. Auto-collects the FCM token if Firebase is present (see [tryAutoCollectFcmToken]). */
    fun configure(
        businessToken: String,
        scanPrecision: ScanPrecision = ScanPrecision.MEDIUM,
        maxQueuedPayloads: MaxQueuedPayloads = MaxQueuedPayloads.MEDIUM,
        technology: String = "android-native"
    ) {
        if(businessToken.trim().isEmpty()){
            throw IllegalArgumentException("Business token cannot be empty")
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

        tryAutoCollectFcmToken(context)

        if (isScanning) {
            startSyncTimer()
        }
    }

    /** Best-effort FCM token fetch. Firebase is compileOnly, so guard against it being absent at runtime; falls back to [setPushToken]. */
    private fun tryAutoCollectFcmToken(context: Context) {
        try {
            if (com.google.firebase.FirebaseApp.getApps(context).isEmpty()) return
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    if (!token.isNullOrEmpty()) {
                        PushTokenStore.setToken(token)
                        Log.i(TAG, "FCM token auto-collected")
                    }
                }
                .addOnFailureListener { e -> Log.w(TAG, "FCM token fetch failed: ${e.message}") }
        } catch (t: Throwable) {
            Log.i(TAG, "Firebase not available; client must call setPushToken() to provide the FCM token")
        }
    }

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
        // Se já estamos escaneando e o token ainda não foi enviado (novo/mudou),
        // empurra agora via register (beacons:[]) — senão só iria no próximo
        // register (TTL) ou ao detectar um beacon. Cobre apps que chamam
        // setPushToken DEPOIS do startScanning: o register-on-init já teria saído
        // sem o token, e o token NÃO faz parte do fingerprint (um register normal
        // não re-dispararia).
        if (isScanning && PushTokenStore.tokenForPayload() != null) {
            scope.launch { registerDeviceIfNeeded(force = true) }
        }
    }

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

    fun startScanning(foregroundScanConfig: ForegroundScanConfig? = null) {
        val config = configuration
        if (config == null) {
            val error = Exception("SDK not configured. Call configure() first.")
            listener?.onError(error)
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
            result.fold(
                onSuccess = {
                    RegisterStore.markRegistered(context, fingerprint)
                    PushTokenStore.markSent()
                    Log.d(TAG, "registerDeviceIfNeeded: registered successfully")
                },
                onFailure = { error ->
                    Log.w(TAG, "registerDeviceIfNeeded: register failed: ${error.message}")
                    // Not persisted to offlineBatchStorage — will retry on next startScanning call.
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

    private fun syncBeacons(forceBackground: Boolean = false) {
        scope.launch {
            if (isSyncing) return@launch

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
            handler.post {
                listener?.onSyncStarted(beaconsToSend.size)
            }

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

                        // Notify listener so UI reflects sync state
                        val updatedBeacons = beaconLock.withLock {
                            collectedBeacons.values.toList()
                        }
                        handler.post {
                            listener?.onBeaconsUpdated(updatedBeacons)
                        }

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
                        PushTokenStore.markSent()
                        DiagnosticsStore.recordSync(success = true, beaconCount = beaconsToSend.size)
                        handler.post {
                            listener?.onSyncCompleted(beaconsToSend.size, success = true, error = null)
                        }
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Sync failed: ${error.message}")
                        handleSyncFailure(beaconsToSend, error, isRetry = false)

                        DiagnosticsStore.recordSync(success = false, beaconCount = beaconsToSend.size)

                        // Notify listener of failure
                        handler.post {
                            listener?.onSyncCompleted(
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

            handler.post {
                listener?.onSyncStarted(beaconsInChunk.size)
            }

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

                handler.post {
                    listener?.onSyncCompleted(
                        beaconsInChunk.size,
                        success = false,
                        error = error as? Exception ?: Exception(error.message)
                    )
                    listener?.onError(error as? Exception ?: Exception(error.message))
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

            PushTokenStore.markSent()
            DiagnosticsStore.recordSync(success = true, beaconCount = beaconsInChunk.size)
            handler.post {
                listener?.onSyncCompleted(beaconsInChunk.size, success = true, error = null)
            }
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
            handler.post {
                listener?.onError(circuitBreakerError)
            }
        }

        handler.post {
            listener?.onError(error as? Exception ?: Exception(error.message))
        }
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
            sdkVersion = Build.VERSION.SDK_INT
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

        // Restore foreground service if it was enabled
        val fgConfig = foregroundScanConfig
        if (fgConfig?.enabled == true && !BeaconScanService.isRunning) {
            BeaconScanService.start(context, fgConfig)
        }

        Log.d(TAG, "Scanning restarted from background")
    }
    
    // endregion
}
