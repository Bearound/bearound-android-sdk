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
import io.bearound.sdk.models.BeaconMetadata
import io.bearound.sdk.models.ForegroundScanConfig
import io.bearound.sdk.models.MaxQueuedPayloads
import io.bearound.sdk.models.SDKConfiguration
import io.bearound.sdk.models.SDKInfo
import io.bearound.sdk.models.ScanPrecision
import io.bearound.sdk.models.UserProperties
import io.bearound.sdk.network.APIClient
import io.bearound.sdk.utilities.DeviceInfoCollector
import io.bearound.sdk.utilities.OfflineBatchStorage
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
            sdkInfo = SDKInfo(appId = savedConfig.appId, build = buildNumber)
            
            // Update offline batch storage max count
            offlineBatchStorage.maxBatchCount = savedConfig.maxQueuedPayloads.value
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
                // Bluetooth scanning is always enabled in v2.2.0+
                metadata?.let {
                    metadataCache["$major.$minor"] = it
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

    fun configure(
        businessToken: String,
        scanPrecision: ScanPrecision = ScanPrecision.MEDIUM,
        maxQueuedPayloads: MaxQueuedPayloads = MaxQueuedPayloads.MEDIUM
    ) {
        if(businessToken.trim().isEmpty()){
            throw IllegalArgumentException("Business token cannot be empty")
        }

        val appId = context.packageName

        val config = SDKConfiguration(
            businessToken = businessToken,
            appId = appId,
            scanPrecision = scanPrecision,
            maxQueuedPayloads = maxQueuedPayloads
        )

        configuration = config
        apiClient = APIClient(config)

        val buildNumber = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (_: Exception) {
            1
        }

        sdkInfo = SDKInfo(appId = appId, build = buildNumber)

        // Update offline batch storage max count
        offlineBatchStorage.maxBatchCount = config.maxQueuedPayloads.value

        SDKConfigStorage.saveConfiguration(context, config)

        if (isScanning) {
            startSyncTimer()
        }
    }

    fun setUserProperties(properties: UserProperties) {
        userProperties = properties
    }

    fun clearUserProperties() {
        userProperties = null
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

    fun startScanning() {
        val config = configuration
        if (config == null) {
            val error = Exception("SDK not configured. Call configure() first.")
            listener?.onError(error)
            return
        }

        // Scanning mode is automatic based on app state (foreground/background)
        beaconManager.startScanning()
        startSyncTimer()
        
        // Enable background mechanisms (WorkManager + AlarmManager)
        backgroundScheduler.enableAll()
        
        // Persist scanning state for recovery after kill/reboot
        SDKConfigStorage.saveScanningEnabled(context, true)

        // Bluetooth metadata scanning: always attempt to start
        bluetoothManager.startScanning()
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
                sdkInfo = SDKInfo(appId = savedConfig.appId, build = buildNumber)
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
        
        if (isAppInForeground) {
            Log.d(TAG, "Ignoring broadcast results - app in foreground (using regular ranging)")
            return
        }
        
        val beaconsBeforeBroadcast = beaconLock.withLock { collectedBeacons.size }
        
        scanResults.forEach { result ->
            beaconManager.processExternalScanResult(result)
        }
        
        val beaconsAfterBroadcast = beaconLock.withLock { collectedBeacons.size }
        val timerIsActive = (syncRunnable != null)
        
        if (!timerIsActive && beaconsAfterBroadcast > 0) {
            Log.d(TAG, "Broadcast detected beacons but no timer active - syncing immediately")
            syncBeacons(forceBackground = true)
        } else {
            Log.d(TAG, "Beacons collected from broadcast - will sync on next timer cycle")
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
            val beaconsToSend = beaconLock.withLock {
                collectedBeacons.values.filter { !it.alreadySynced }
            }

            if (beaconsToSend.isEmpty()) return@launch

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
                appInForeground = !isAppInBackground,
                location = beaconManager.lastLocation
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
                        handler.post {
                            listener?.onSyncCompleted(beaconsToSend.size, success = true, error = null)
                        }
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Sync failed: ${error.message}")
                        handleSyncFailure(beaconsToSend, error, isRetry = false)

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
            appInForeground = !isAppInBackground,
            location = beaconManager.lastLocation
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
