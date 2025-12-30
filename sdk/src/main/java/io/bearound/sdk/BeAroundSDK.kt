package io.bearound.sdk

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import io.bearound.sdk.interfaces.BeAroundSDKDelegate
import io.bearound.sdk.interfaces.BluetoothManagerDelegate
import io.bearound.sdk.models.Beacon
import io.bearound.sdk.models.BeaconMetadata
import io.bearound.sdk.models.SDKConfiguration
import io.bearound.sdk.models.SDKInfo
import io.bearound.sdk.models.UserProperties
import io.bearound.sdk.network.APIClient
import io.bearound.sdk.utilities.DeviceInfoCollector
import io.bearound.sdk.utilities.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Main SDK class - Singleton pattern
 * Entry point for all SDK operations
 */
class BeAroundSDK private constructor() {
    companion object {
        private const val TAG = "BeAroundSDK"
        
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

    var delegate: BeAroundSDKDelegate? = null

    private lateinit var context: Context
    private var configuration: SDKConfiguration? = null
    private var sdkInfo: SDKInfo? = null
    private var userProperties: UserProperties? = null

    private lateinit var deviceInfoCollector: DeviceInfoCollector
    private lateinit var beaconManager: BeaconManager
    private lateinit var bluetoothManager: BluetoothManager
    private var apiClient: APIClient? = null

    private val metadataCache = mutableMapOf<String, BeaconMetadata>()
    private val collectedBeacons = mutableMapOf<String, Beacon>()
    private val beaconLock = ReentrantLock()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())

    private var syncRunnable: Runnable? = null
    private var countdownRunnable: Runnable? = null
    private var nextSyncTime: Long? = null

    private var isSyncing = false
    private val failedBatches = mutableListOf<List<Beacon>>()
    private var consecutiveFailures = 0
    private var lastFailureTime: Long? = null
    private val maxFailedBatches = 10

    private var isInBackground = false
    private var wasLaunchedInBackground = false
    private val isColdStart = true

    val isScanning: Boolean
        get() = ::beaconManager.isInitialized && beaconManager.isScanning

    val currentSyncInterval: Long?
        get() = configuration?.validatedSyncInterval

    val currentScanDuration: Long?
        get() = configuration?.scanDuration

    val isPeriodicScanningEnabled: Boolean
        get() = configuration?.enablePeriodicScanning ?: false

    private fun initialize(appContext: Context) {
        context = appContext
        
        // Initialize secure storage
        SecureStorage.initialize(context)
        
        // Initialize managers
        deviceInfoCollector = DeviceInfoCollector(context, isColdStart)
        beaconManager = BeaconManager(context)
        bluetoothManager = BluetoothManager(context)

        setupCallbacks()
        setupLifecycleObserver()
        
        Log.d(TAG, "BeAroundSDK initialized")
    }

    private fun setupCallbacks() {
        // Beacon manager callbacks
        beaconManager.onBeaconsUpdated = { beacons ->
            Log.d(TAG, "========================================")
            Log.d(TAG, "BEACONS UPDATED CALLBACK")
            Log.d(TAG, "Received ${beacons.size} beacon(s) from BeaconManager")

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
                Log.d(TAG, "Total collected beacons in cache: ${collectedBeacons.size}")
            }

            Log.d(TAG, "Calling delegate.didUpdateBeacons() with ${enrichedBeacons.size} beacon(s)")
            enrichedBeacons.forEach { beacon ->
                Log.d(TAG, "  - Beacon ${beacon.identifier}: rssi=${beacon.rssi}, proximity=${beacon.proximity}")
            }
            Log.d(TAG, "========================================")

            delegate?.didUpdateBeacons(enrichedBeacons)
        }

        beaconManager.onError = { error ->
            delegate?.didFailWithError(error)
        }

        beaconManager.onScanningStateChanged = { isScanning ->
            delegate?.didChangeScanning(isScanning)
        }

        beaconManager.onBackgroundRangingComplete = {
            Log.d(TAG, "Background ranging complete - syncing collected beacons")
            syncBeacons()
        }

        // Bluetooth manager callbacks
        bluetoothManager.delegate = object : BluetoothManagerDelegate {
            override fun didDiscoverBeacon(
                uuid: UUID,
                major: Int,
                minor: Int,
                rssi: Int,
                txPower: Int,
                metadata: BeaconMetadata?,
                isConnectable: Boolean
            ) {
                if (configuration?.enableBluetoothScanning != true) return

                metadata?.let {
                    val key = "$major.$minor"
                    metadataCache[key] = it
                    Log.d(TAG, "Cached metadata for beacon $key: battery=${it.batteryLevel}%, " +
                            "firmware=${it.firmwareVersion}, txPower=${it.txPower}dBm, " +
                            "rssi=${it.rssiFromBLE}dBm, connectable=$isConnectable")
                }
            }

            override fun didUpdateBluetoothState(isPoweredOn: Boolean) {
                if (!isPoweredOn && configuration?.enableBluetoothScanning == true) {
                    Log.w(TAG, "Bluetooth is off - metadata scanning unavailable")
                }
            }
        }
    }

    private fun setupLifecycleObserver() {
        if (context is Application) {
            (context as Application).registerActivityLifecycleCallbacks(
                object : Application.ActivityLifecycleCallbacks {
                    private var activityCount = 0

                    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                    override fun onActivityStarted(activity: Activity) {
                        activityCount++
                        if (activityCount == 1) {
                            onAppForegrounded()
                        }
                    }

                    override fun onActivityResumed(activity: Activity) {}
                    override fun onActivityPaused(activity: Activity) {}
                    override fun onActivityStopped(activity: Activity) {
                        activityCount--
                        if (activityCount == 0) {
                            onAppBackgrounded()
                        }
                    }

                    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                    override fun onActivityDestroyed(activity: Activity) {}
                }
            )
        }
    }

    private fun onAppForegrounded() {
        isInBackground = false
        Log.d(TAG, "App entered foreground - restoring periodic mode if configured")
        
        beaconManager.setForegroundState(true)
        
        configuration?.let { config ->
            beaconManager.enablePeriodicScanning = config.enablePeriodicScanning
            if (isScanning) {
                startSyncTimer()
            }
        }
    }

    private fun onAppBackgrounded() {
        isInBackground = true
        Log.d(TAG, "App entered background - switching to continuous ranging mode")
        
        beaconManager.setForegroundState(false)
        
        configuration?.let { config ->
            if (config.enablePeriodicScanning) {
                beaconManager.enablePeriodicScanning = false
                if (isScanning) {
                    beaconManager.startRanging()
                }
            }
        }
    }

    fun configure(
        appId: String,
        syncInterval: Long,
        enableBluetoothScanning: Boolean = false,
        enablePeriodicScanning: Boolean = true
    ) {
        val config = SDKConfiguration(
            appId = appId,
            syncInterval = syncInterval,
            enableBluetoothScanning = enableBluetoothScanning,
            enablePeriodicScanning = enablePeriodicScanning
        )
        
        configuration = config
        apiClient = APIClient(config)

        val buildNumber = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (e: Exception) {
            1
        }

        sdkInfo = SDKInfo(appId = appId, build = buildNumber)

        if (isScanning) {
            startSyncTimer()
        }

        Log.d(TAG, "SDK configured: appId=$appId, syncInterval=${config.validatedSyncInterval}ms, " +
                "periodicScanning=$enablePeriodicScanning, bleScanning=$enableBluetoothScanning")
    }

    fun setUserProperties(properties: UserProperties) {
        userProperties = properties
        Log.d(TAG, "User properties updated: internalId=${properties.internalId}, " +
                "email=${properties.email}, name=${properties.name}, " +
                "custom=${properties.customProperties.size} properties")
    }

    fun clearUserProperties() {
        userProperties = null
        Log.d(TAG, "User properties cleared")
    }

    fun setBluetoothScanning(enabled: Boolean) {
        configuration = configuration?.copy(enableBluetoothScanning = enabled)

        if (enabled && isScanning) {
            bluetoothManager.startScanning()
        } else if (!enabled) {
            bluetoothManager.stopScanning()
            metadataCache.clear()
        }
    }

    val isBluetoothScanningEnabled: Boolean
        get() = configuration?.enableBluetoothScanning ?: false

    fun startScanning() {
        val config = configuration
        if (config == null) {
            val error = Exception("SDK not configured. Call configure() first.")
            delegate?.didFailWithError(error)
            return
        }

        beaconManager.enablePeriodicScanning = config.enablePeriodicScanning
        beaconManager.startScanning()
        startSyncTimer()

        if (config.enableBluetoothScanning) {
            bluetoothManager.startScanning()
        }
    }

    fun stopScanning() {
        beaconManager.stopScanning()
        bluetoothManager.stopScanning()
        stopSyncTimer()
        
        // Final sync before stopping
        syncBeacons()
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
        
        stopSyncTimer()
        startCountdownTimer()

        val syncInterval = config.validatedSyncInterval
        
        syncRunnable = object : Runnable {
            override fun run() {
                nextSyncTime = System.currentTimeMillis() + syncInterval

                // Sync beacons first
                syncBeacons()
                
                if (config.enablePeriodicScanning && !isInBackground) {
                    val scanDuration = config.scanDuration
                    val delayUntilNextRanging = syncInterval - scanDuration
                    
                    // Stop ranging after a short delay to allow sync to complete
                    handler.postDelayed({
                        beaconManager.stopRanging()
                        // Clear UI display after sync completes
                        delegate?.didUpdateBeacons(emptyList())
                    }, 100) // Small delay to ensure sync captures the beacons

                    handler.postDelayed({
                        Log.d(TAG, "Starting ranging ${scanDuration}ms before next sync")
                        beaconManager.startRanging()
                    }, delayUntilNextRanging)
                }
                
                handler.postDelayed(this, syncInterval)
            }
        }

        if (config.enablePeriodicScanning && !isInBackground) {
            val scanDuration = config.scanDuration
            val delayUntilFirstRanging = syncInterval - scanDuration
            nextSyncTime = System.currentTimeMillis() + syncInterval
            
            Log.d(TAG, "First ranging will start in ${delayUntilFirstRanging}ms (sync in ${syncInterval}ms)")
            handler.postDelayed({
                Log.d(TAG, "Starting initial ranging for ${scanDuration}ms")
                beaconManager.startRanging()
            }, delayUntilFirstRanging)
            
            handler.postDelayed(syncRunnable!!, syncInterval)
        } else {
            nextSyncTime = System.currentTimeMillis() + syncInterval
            handler.postDelayed(syncRunnable!!, syncInterval)
        }
    }

    private fun stopSyncTimer() {
        syncRunnable?.let { handler.removeCallbacks(it) }
        syncRunnable = null
    }

    private fun startCountdownTimer() {
        stopCountdownTimer()
        
        countdownRunnable = object : Runnable {
            override fun run() {
                updateCountdown()
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(countdownRunnable!!)
    }

    private fun stopCountdownTimer() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
        nextSyncTime = null
    }

    private fun updateCountdown() {
        val nextSync = nextSyncTime ?: run {
            delegate?.didUpdateSyncStatus(0, beaconManager.isScanning)
            return
        }

        val secondsRemaining = maxOf(0, ((nextSync - System.currentTimeMillis()) / 1000).toInt())
        delegate?.didUpdateSyncStatus(secondsRemaining, beaconManager.isScanning)
    }

    private fun syncBeacons() {
        scope.launch {
            if (isSyncing) {
                Log.d(TAG, "Skipping sync - previous sync still in progress")
                return@launch
            }

            val client = apiClient
            val info = sdkInfo
            
            if (client == null || info == null) {
                Log.w(TAG, "Cannot sync - SDK not configured (apiClient=${client != null}, sdkInfo=${info != null})")
                return@launch
            }

            val shouldRetryFailed = shouldRetryFailedBatches()

            val beaconsToSend = beaconLock.withLock {
                when {
                    shouldRetryFailed && failedBatches.isNotEmpty() -> {
                        val batch = failedBatches.removeAt(0)
                        Log.d(TAG, "Retrying failed batch with ${batch.size} beacon(s)")
                        batch
                    }
                    collectedBeacons.isNotEmpty() -> {
                        val batch = collectedBeacons.values.toList()
                        collectedBeacons.clear()
                        Log.d(TAG, "========================================")
                        Log.d(TAG, "SYNC BEACONS - Preparing batch")
                        Log.d(TAG, "Collected ${batch.size} beacon(s) to send")
                        batch.forEach { beacon ->
                            Log.d(TAG, "  - ${beacon.identifier}: rssi=${beacon.rssi}, timestamp=${beacon.timestamp}")
                        }
                        Log.d(TAG, "========================================")
                        batch
                    }
                    else -> {
                        Log.d(TAG, "No beacons to sync")
                        return@launch
                    }
                }
            }

            if (beaconsToSend.isEmpty()) {
                Log.d(TAG, "Beacons to send is empty, skipping sync")
                return@launch
            }

            isSyncing = true
            Log.d(TAG, "Starting sync of ${beaconsToSend.size} beacon(s)")

            val locationPermission = getLocationPermissionStatus()
            val bluetoothState = if (bluetoothManager.isPoweredOn) "powered_on" else "powered_off"
            val userDevice = deviceInfoCollector.collectDeviceInfo(
                locationPermission = locationPermission,
                bluetoothState = bluetoothState,
                appInForeground = !isInBackground,
                location = beaconManager.lastLocation
            )

            client.sendBeacons(beaconsToSend, info, userDevice, userProperties) { result ->
                isSyncing = false

                result.fold(
                    onSuccess = {
                        Log.d(TAG, "========================================")
                        Log.d(TAG, "SYNC SUCCESS")
                        Log.d(TAG, "Successfully sent ${beaconsToSend.size} beacon(s) to API")
                        Log.d(TAG, "========================================")
                        consecutiveFailures = 0
                        lastFailureTime = null
                    },
                    onFailure = { error ->
                        Log.e(TAG, "========================================")
                        Log.e(TAG, "SYNC FAILED")
                        Log.e(TAG, "Failed to send beacons: ${error.message}")
                        Log.e(TAG, "========================================")
                        handleSyncFailure(beaconsToSend, error)
                    }
                )
            }
        }
    }

    private fun handleSyncFailure(beacons: List<Beacon>, error: Throwable) {
        beaconLock.withLock {
            consecutiveFailures++
            lastFailureTime = System.currentTimeMillis()

            if (failedBatches.size < maxFailedBatches) {
                failedBatches.add(beacons)
                Log.d(TAG, "Queued ${beacons.size} beacon(s) for retry (queue: ${failedBatches.size}/$maxFailedBatches)")
            } else {
                failedBatches.removeAt(0)
                failedBatches.add(beacons)
                Log.w(TAG, "Retry queue full - dropped oldest batch")
            }

            if (consecutiveFailures >= 10) {
                Log.e(TAG, "Circuit breaker triggered - $consecutiveFailures consecutive failures")
                val circuitBreakerError = Exception(
                    "API unreachable after $consecutiveFailures consecutive failures"
                )
                handler.post {
                    delegate?.didFailWithError(circuitBreakerError)
                }
            }
        }

        handler.post {
            delegate?.didFailWithError(error as? Exception ?: Exception(error.message))
        }
    }

    private fun shouldRetryFailedBatches(): Boolean {
        val lastFailure = lastFailureTime ?: return false
        val timeSinceFailure = System.currentTimeMillis() - lastFailure

        val backoffDelay = minOf(
            5000L * Math.pow(2.0, minOf(consecutiveFailures - 1, 3).toDouble()).toLong(),
            60000L
        )

        return timeSinceFailure >= backoffDelay
    }
}

