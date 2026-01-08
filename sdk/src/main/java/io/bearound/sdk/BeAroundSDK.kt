package io.bearound.sdk

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import io.bearound.sdk.background.BackgroundScanManager
import io.bearound.sdk.interfaces.BeAroundSDKDelegate
import io.bearound.sdk.interfaces.BluetoothManagerDelegate
import io.bearound.sdk.models.Beacon
import io.bearound.sdk.models.BeaconMetadata
import io.bearound.sdk.models.SDKConfiguration
import io.bearound.sdk.models.SDKInfo
import io.bearound.sdk.models.UserProperties
import io.bearound.sdk.network.APIClient
import io.bearound.sdk.utilities.DeviceInfoCollector
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

    var delegate: BeAroundSDKDelegate? = null

    private lateinit var context: Context
    private var configuration: SDKConfiguration? = null
    private var sdkInfo: SDKInfo? = null
    private var userProperties: UserProperties? = null

    private lateinit var deviceInfoCollector: DeviceInfoCollector
    private lateinit var beaconManager: BeaconManager
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var backgroundScanManager: BackgroundScanManager
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
    private val isColdStart = true
    private var activityCount = 0

    val isScanning: Boolean
        get() = ::beaconManager.isInitialized && beaconManager.isScanning

    val currentSyncInterval: Long?
        get() = configuration?.validatedSyncInterval

    val currentScanDuration: Long?
        get() = configuration?.scanDuration

    val isPeriodicScanningEnabled: Boolean
        get() = configuration?.enablePeriodicScanning ?: false

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
            } catch (e: Exception) {
                1
            }
            sdkInfo = SDKInfo(appId = savedConfig.appId, build = buildNumber)
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

            delegate?.didUpdateBeacons(enrichedBeacons)
        }

        beaconManager.onError = { error ->
            delegate?.didFailWithError(error)
        }

        beaconManager.onScanningStateChanged = { isScanning ->
            delegate?.didChangeScanning(isScanning)
        }

        beaconManager.onBackgroundRangingComplete = {
            syncBeacons()
        }

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
                    metadataCache["$major.$minor"] = it
                }
            }

            override fun didUpdateBluetoothState(isPoweredOn: Boolean) {
                if (!isPoweredOn && configuration?.enableBluetoothScanning == true) {
                    Log.w(TAG, "Bluetooth is off")
                }
            }
        }
    }

    private fun setupLifecycleObserver() {
        if (context is Application) {
            (context as Application).registerActivityLifecycleCallbacks(
                object : Application.ActivityLifecycleCallbacks {
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
        businessToken: String,
        syncInterval: Long,
        enableBluetoothScanning: Boolean = false,
        enablePeriodicScanning: Boolean = true
    ) {
        if(businessToken.trim().isEmpty()){
            throw IllegalArgumentException("Business token cannot be empty")
        }

        val appId = context.packageName

        val config = SDKConfiguration(
            businessToken = businessToken,
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
        
        SDKConfigStorage.saveConfiguration(context, config)
        backgroundScanManager.enableBackgroundScanning()

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
        
        val config = configuration
        if (config?.enableBluetoothScanning == true) {
            bluetoothManager.startScanning()
        }
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
        
        scanResults.forEach { result ->
            beaconManager.processExternalScanResult(result)
        }
        
        syncBeacons(forceBackground = !isAppInForeground)
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
        
        stopSyncTimer()
        startCountdownTimer()

        val syncInterval = config.validatedSyncInterval
        
        syncRunnable = object : Runnable {
            override fun run() {
                nextSyncTime = System.currentTimeMillis() + syncInterval
                syncBeacons()
                
                if (config.enablePeriodicScanning && !isInBackground) {
                    val scanDuration = config.scanDuration
                    val delayUntilNextRanging = syncInterval - scanDuration

                    handler.postDelayed({
                        beaconManager.stopRanging()
                        delegate?.didUpdateBeacons(emptyList())
                    }, 100)

                    handler.postDelayed({
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
            
            handler.postDelayed({
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

            val beaconsToSend = beaconLock.withLock {
                when {
                    shouldRetryFailed && failedBatches.isNotEmpty() -> {
                        failedBatches.removeAt(0)
                    }
                    collectedBeacons.isNotEmpty() -> {
                        val batch = collectedBeacons.values.toList()
                        collectedBeacons.clear()
                        batch
                    }
                    else -> {
                        return@launch
                    }
                }
            }

            if (beaconsToSend.isEmpty()) return@launch

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

            client.sendBeacons(beaconsToSend, info, userDevice, userProperties) { result ->
                isSyncing = false

                result.fold(
                    onSuccess = {
                        consecutiveFailures = 0
                        lastFailureTime = null
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Sync failed: ${error.message}")
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
            } else {
                failedBatches.removeAt(0)
                failedBatches.add(beacons)
            }

            if (consecutiveFailures >= 10) {
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
            5000L * 2.0.pow(minOf(consecutiveFailures - 1, 3).toDouble()).toLong(),
            60000L
        )

        return timeSinceFailure >= backoffDelay
    }
}

