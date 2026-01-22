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
import io.bearound.sdk.interfaces.BeAroundSDKListener
import io.bearound.sdk.interfaces.BluetoothManagerListener
import io.bearound.sdk.models.BackgroundScanInterval
import io.bearound.sdk.models.Beacon
import io.bearound.sdk.models.BeaconMetadata
import io.bearound.sdk.models.ForegroundScanInterval
import io.bearound.sdk.models.MaxQueuedPayloads
import io.bearound.sdk.models.SDKConfiguration
import io.bearound.sdk.models.SDKInfo
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

    private var isSyncing = false
    private lateinit var offlineBatchStorage: OfflineBatchStorage
    private var consecutiveFailures = 0
    private var lastFailureTime: Long? = null

    private var isInBackground = false
    private val isColdStart = true

    val isScanning: Boolean
        get() = ::beaconManager.isInitialized && beaconManager.isScanning

    val currentSyncInterval: Long?
        get() = configuration?.syncInterval(isInBackground)

    val currentScanDuration: Long?
        get() = configuration?.scanDuration(isInBackground)

    val isPeriodicScanningEnabled: Boolean
        get() = !isInBackground  // Periodic in foreground, continuous in background

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

            beaconLock.withLock {
                enrichedBeacons.forEach { beacon ->
                    collectedBeacons[beacon.identifier] = beacon
                }
            }

            // Notify listener of beacon update
            listener?.onBeaconsUpdated(enrichedBeacons)
            
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
        // Continuous scanning in background is automatic (BeaconManager handles it)
        
        if (isScanning) {
            restartSyncTimer()
        }
        
        listener?.onAppStateChanged(isInBackground = true)
    }

    fun configure(
        businessToken: String,
        foregroundScanInterval: ForegroundScanInterval = ForegroundScanInterval.SECONDS_15,
        backgroundScanInterval: BackgroundScanInterval = BackgroundScanInterval.SECONDS_30,
        maxQueuedPayloads: MaxQueuedPayloads = MaxQueuedPayloads.MEDIUM
    ) {
        if(businessToken.trim().isEmpty()){
            throw IllegalArgumentException("Business token cannot be empty")
        }

        val appId = context.packageName

        val config = SDKConfiguration(
            businessToken = businessToken,
            appId = appId,
            foregroundScanInterval = foregroundScanInterval,
            backgroundScanInterval = backgroundScanInterval,
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
        
        // Capture current background state - this won't change during this timer's lifecycle
        // When app state changes, restartSyncTimer() is called which creates a new timer
        val backgroundMode = isInBackground
        
        Log.d(TAG, "=== START SYNC TIMER ===")
        Log.d(TAG, "isInBackground: $backgroundMode")
        Log.d(TAG, "Scanning mode: ${if (backgroundMode) "continuous" else "periodic"}")
        
        stopSyncTimer()

        val syncInterval = config.syncInterval(backgroundMode)
        Log.d(TAG, "Sync interval to use: ${syncInterval}ms (${syncInterval/1000}s)")
        
        // Check if continuous mode (5s interval = no stop/start cycling)
        val isContinuousMode = !backgroundMode && syncInterval == 5000L
        
        syncRunnable = object : Runnable {
            override fun run() {
                syncBeacons()
                
                // Periodic scanning in foreground only (not in continuous mode)
                if (!backgroundMode && !isContinuousMode) {
                    val scanDuration = config.scanDuration(false)
                    val delayUntilNextRanging = syncInterval - scanDuration
                    
                    // Only stop ranging if there's a pause period
                    if (delayUntilNextRanging > 0) {
                        handler.postDelayed({
                            beaconManager.stopRanging()
                            // DON'T clear the UI - keep showing collected beacons
                        }, 100)

                        handler.postDelayed({
                            beaconManager.startRanging()
                        }, delayUntilNextRanging)
                    }
                }
                // In continuous mode or background: no stop/start cycle
                
                handler.postDelayed(this, syncInterval)
            }
        }

        if (!backgroundMode) {
            if (isContinuousMode) {
                // Continuous mode: start ranging immediately, no cycling
                beaconManager.startRanging()
                handler.postDelayed(syncRunnable!!, syncInterval)
            } else {
                // Periodic scanning: delay first ranging
                val scanDuration = config.scanDuration(false)
                val delayUntilFirstRanging = syncInterval - scanDuration
                
                handler.postDelayed({
                    beaconManager.startRanging()
                }, delayUntilFirstRanging)
                
                handler.postDelayed(syncRunnable!!, syncInterval)
            }
        } else {
            // Background: continuous scanning (just schedule the sync timer)
            handler.postDelayed(syncRunnable!!, syncInterval)
        }
    }
    
    private fun restartSyncTimer() {
        if (isScanning) {
            startSyncTimer()
        }
    }

    private fun stopSyncTimer() {
        syncRunnable?.let { handler.removeCallbacks(it) }
        syncRunnable = null
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

            val beaconsToSend: List<Beacon>
            val isRetry: Boolean
            
            when {
                shouldRetryFailed -> {
                    // Try to load from persistent storage first (FIFO)
                    val failedBatch = offlineBatchStorage.loadOldestBatch()
                    if (failedBatch != null) {
                        beaconsToSend = failedBatch
                        isRetry = true
                        Log.d(TAG, "Retrying failed batch with ${beaconsToSend.size} beacons")
                    } else {
                        // No failed batches, try collected beacons
                        beaconsToSend = beaconLock.withLock {
                            if (collectedBeacons.isNotEmpty()) {
                                val batch = collectedBeacons.values.toList()
                                // DON'T clear - keep beacons for continuous updates
                                batch
                            } else {
                                emptyList()
                            }
                        }
                        isRetry = false
                    }
                }
                else -> {
                    // Get collected beacons
                    beaconsToSend = beaconLock.withLock {
                        if (collectedBeacons.isNotEmpty()) {
                            val batch = collectedBeacons.values.toList()
                            // DON'T clear - keep beacons for continuous updates
                            batch
                        } else {
                            emptyList()
                        }
                    }
                    isRetry = false
                }
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
                        
                        // If this was a retry, remove the batch from storage
                        if (isRetry) {
                            offlineBatchStorage.removeOldestBatch()
                            Log.d(TAG, "Removed successful retry batch from storage")
                        }
                        
                        // Notify listener of success
                        handler.post {
                            listener?.onSyncCompleted(beaconsToSend.size, success = true, error = null)
                        }
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Sync failed: ${error.message}")
                        handleSyncFailure(beaconsToSend, error, isRetry)
                        
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
        
        Log.d(TAG, "Scanning restarted from background")
    }
    
    // endregion
}
