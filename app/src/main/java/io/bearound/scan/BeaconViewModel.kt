package io.bearound.scan

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.bearound.sdk.BeAroundSDK
import io.bearound.sdk.interfaces.BeAroundSDKListener
import io.bearound.sdk.models.Beacon
import io.bearound.sdk.models.MaxQueuedPayloads
import io.bearound.sdk.models.ScanPrecision
import io.bearound.telemetry.BearoundTelemetrySDK
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date

enum class BeaconSortOption(val displayName: String) {
    PROXIMITY("Proximidade"),
    ID("ID")
}

data class BeAroundScanState(
    val isScanning: Boolean = false,
    val beacons: List<Beacon> = emptyList(),
    val statusMessage: String = "Pronto",
    val locationPermissionStatus: String = "Verificando...",
    val bluetoothStatus: String = "Verificando...",
    val notificationStatus: String = "Verificando...",
    val lastScanTime: Date? = null,
    val currentSyncInterval: Int = 60,
    val scanPrecision: ScanPrecision = ScanPrecision.MEDIUM,
    val maxQueuedPayloads: MaxQueuedPayloads = MaxQueuedPayloads.MEDIUM,
    val isInBackground: Boolean = false,
    val sortOption: BeaconSortOption = BeaconSortOption.PROXIMITY,
    val pinnedBeaconIds: Set<String> = emptySet(),
    val retryBatches: List<List<Beacon>> = emptyList(),
    val retryBatchCount: Int = 0,
    /**
     * Non-null when the sample cannot start because BUSINESS_TOKEN is missing. The UI shows
     * this instead of the app crashing on launch (SDK.configure throws on a blank token).
     */
    val configurationError: String? = null
)

class BeaconViewModel(application: Application) : AndroidViewModel(application), BeAroundSDKListener {
    private val _state = MutableStateFlow(BeAroundScanState())
    val state: StateFlow<BeAroundScanState> = _state.asStateFlow()

    private val sdk = BeAroundSDK.getInstance(application)

    // Full Bearound: the telemetry companion runs alongside tracking — credentials and
    // device id come from the tracking instance (one-liner handoff in configureSDK).
    private val telemetry = BearoundTelemetrySDK.getInstance(application)
    private val notificationManager = NotificationManager(application)
    
    private var wasInBeaconRegion = false
    private var scanStartTime: Date? = null
    private var previousListener: BeAroundSDKListener? = null

    init {
        // Save the background listener (registered in Application)
        previousListener = sdk.listener
        
        // Set this ViewModel as listener while UI is active
        sdk.listener = this
        
        updatePermissionStatus()
        checkBluetoothStatus()
        checkNotificationStatus()

        // BUSINESS_TOKEN comes from local.properties (gitignored) or the BUSINESS_TOKEN env var.
        // Without it, sdk.configure() throws — so surface a friendly state instead of crashing.
        if (BuildConfig.BUSINESS_TOKEN.isBlank()) {
            _state.value = _state.value.copy(
                configurationError = "Configure BUSINESS_TOKEN em local.properties",
                statusMessage = "Configuração necessária"
            )
        } else {
            // Configure SDK
            configureSDK(
                _state.value.scanPrecision,
                _state.value.maxQueuedPayloads
            )

            // Auto-start scanning
            startScanning()
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        // Restore background listener when ViewModel is destroyed
        sdk.listener = previousListener
        android.util.Log.d("BeaconViewModel", "ViewModel cleared - restored background listener")
    }

    private fun configureSDK(
        precision: ScanPrecision,
        maxQueued: MaxQueuedPayloads
    ) {
        // configure() returns self — hand the instance to the telemetry companion,
        // which extracts the business token AND the device id from it, so both SDKs
        // report as the same device.
        val bearound = sdk.configure(
            businessToken = BuildConfig.BUSINESS_TOKEN,
            scanPrecision = precision,
            maxQueuedPayloads = maxQueued
        )
        telemetry.configure(bearound)
    }

    fun startScanning() {
        if (_state.value.configurationError != null) {
            return
        }

        if (!hasRequiredPermissions()) {
            _state.value = _state.value.copy(
                statusMessage = "Permissões necessárias",
                isScanning = false
            )
            return
        }

        sdk.startScanning()
        telemetry.startScanning()
        scanStartTime = Date()
        wasInBeaconRegion = false
        
        _state.value = _state.value.copy(
            isScanning = true,
            statusMessage = "Scaneando...",
            lastScanTime = Date()
        )
    }

    fun stopScanning() {
        sdk.stopScanning()
        telemetry.stopScanning()
        scanStartTime = null
        wasInBeaconRegion = false
        
        _state.value = _state.value.copy(
            isScanning = false,
            statusMessage = "Parado"
        )
    }

    fun updateConfiguration(
        precision: ScanPrecision,
        maxQueued: MaxQueuedPayloads
    ) {
        if (_state.value.configurationError != null) {
            return
        }

        configureSDK(precision, maxQueued)
        _state.value = _state.value.copy(
            scanPrecision = precision,
            maxQueuedPayloads = maxQueued,
            currentSyncInterval = (sdk.currentSyncInterval ?: 0L).toInt() / 1000,
            statusMessage = "Configuração atualizada"
        )
    }

    fun changeSortOption(option: BeaconSortOption) {
        _state.value = _state.value.copy(sortOption = option)
        val sorted = sortBeacons(_state.value.beacons, option, _state.value.pinnedBeaconIds)
        _state.value = _state.value.copy(beacons = sorted)
    }

    fun refreshRetryQueue() {
        _state.value = _state.value.copy(
            retryBatches = sdk.pendingBatches,
            retryBatchCount = sdk.pendingBatchCount
        )
    }

    fun togglePin(beaconId: String) {
        val current = _state.value.pinnedBeaconIds
        val updated = if (beaconId in current) current - beaconId else current + beaconId
        _state.value = _state.value.copy(pinnedBeaconIds = updated)
        val sorted = sortBeacons(_state.value.beacons, _state.value.sortOption, updated)
        _state.value = _state.value.copy(beacons = sorted)
    }

    val scanDuration: Int
        get() = (sdk.currentScanDuration ?: 0L).toInt() / 1000

    val pauseDuration: Int
        get() = (sdk.currentPauseDuration ?: 0L).toInt() / 1000

    val scanMode: String
        get() = when (sdk.currentScanPrecision) {
            ScanPrecision.HIGH -> "Contínuo (HIGH)"
            ScanPrecision.MEDIUM -> "Periódico (MEDIUM)"
            ScanPrecision.LOW -> "Periódico (LOW)"
            null -> "---"
        }

    // region BeAroundSDKListener Implementation
    
    override fun onBeaconsUpdated(beacons: List<Beacon>) {
        viewModelScope.launch {
            val sortedBeacons = sortBeacons(beacons, _state.value.sortOption, _state.value.pinnedBeaconIds)
            val isNowInBeaconRegion = sortedBeacons.isNotEmpty()

            // Detect region entry for notification
            val shouldNotify = isNowInBeaconRegion && !wasInBeaconRegion
            if (shouldNotify) {
                scanStartTime?.let { startTime ->
                    val timeSinceStart = (Date().time - startTime.time) / 1000.0
                    if (timeSinceStart >= 2.0) {
                        notificationManager.notifyBeaconDetected(sortedBeacons.size, isBackground = false)
                    }
                }
            }

            wasInBeaconRegion = isNowInBeaconRegion

            _state.value = _state.value.copy(
                beacons = sortedBeacons,
                lastScanTime = Date(),
                statusMessage = if (sortedBeacons.isEmpty()) {
                    "Scaneando..."
                } else {
                    "${sortedBeacons.size} beacon${if (sortedBeacons.size == 1) "" else "s"}"
                }
            )
        }
    }

    override fun onError(error: Exception) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                statusMessage = "Erro: ${error.message}"
            )
        }
    }

    override fun onScanningStateChanged(isScanning: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isScanning = isScanning,
                statusMessage = if (isScanning) "Scaneando..." else "Parado"
            )
            
            // Notify scanning state change
            if (isScanning) {
                notificationManager.notifyScanningStarted()
            } else {
                notificationManager.notifyScanningStopped()
            }
        }
    }

    override fun onAppStateChanged(isInBackground: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isInBackground = isInBackground,
                currentSyncInterval = (sdk.currentSyncInterval ?: 0L).toInt() / 1000
            )
        }
    }
    
    override fun onSyncStarted(beaconCount: Int) {
        viewModelScope.launch {
            notificationManager.notifyAPISyncStarted(beaconCount)
        }
    }
    
    override fun onSyncCompleted(beaconCount: Int, success: Boolean, error: Exception?) {
        viewModelScope.launch {
            notificationManager.notifyAPISyncCompleted(beaconCount, success)
            refreshRetryQueue()
        }
    }
    
    override fun onBeaconDetectedInBackground(beaconCount: Int) {
        viewModelScope.launch {
            notificationManager.notifyBeaconDetected(beaconCount, isBackground = true)
        }
    }
    
    // endregion

    private fun sortBeacons(beacons: List<Beacon>, option: BeaconSortOption, pinnedIds: Set<String> = emptySet()): List<Beacon> {
        val baseComparator = when (option) {
            BeaconSortOption.PROXIMITY -> {
                compareBy<Beacon> { beacon ->
                    when (beacon.proximity) {
                        Beacon.Proximity.IMMEDIATE -> 0
                        Beacon.Proximity.NEAR -> 1
                        Beacon.Proximity.FAR -> 2
                        Beacon.Proximity.BT -> 3
                        Beacon.Proximity.UNKNOWN -> 4
                    }
                }.thenByDescending { it.rssi }
                    .thenBy { if (it.accuracy > 0) it.accuracy else Double.MAX_VALUE }
            }
            BeaconSortOption.ID -> {
                compareBy { "${it.major}.${it.minor}" }
            }
        }
        // Pinned beacons first, then sort normally within each group
        return beacons.sortedWith(
            compareByDescending<Beacon> { it.identifier in pinnedIds }.then(baseComparator)
        )
    }

    fun updatePermissionStatus() {
        val context = getApplication<Application>()

        // Location is recommended (OEM coverage on 12+) and required below 12; the SDK
        // never requests ACCESS_BACKGROUND_LOCATION — background runs via BLUETOOTH_SCAN.
        val locationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val locationPermission = if (locationGranted) {
            "Concedida (recomendado)"
        } else {
            "Negada — cobertura reduzida no 12+; obrigatória no Android ≤ 11"
        }

        _state.value = _state.value.copy(locationPermissionStatus = locationPermission)
    }

    private fun checkBluetoothStatus() {
        val context = getApplication<Application>()
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        val status = when {
            bluetoothAdapter == null -> "Não suportado"
            !bluetoothAdapter.isEnabled -> "Desligado"
            else -> "Ligado"
        }

        _state.value = _state.value.copy(bluetoothStatus = status)
    }

    private fun checkNotificationStatus() {
        val context = getApplication<Application>()
        val status = if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            "Autorizada"
        } else {
            "Negada"
        }

        _state.value = _state.value.copy(notificationStatus = status)
    }

    fun hasRequiredPermissions(): Boolean {
        val context = getApplication<Application>()

        // Mirrors the SDK gate (BeaconManager.checkPermissions): on Android 12+ the scan
        // runs on BLUETOOTH_SCAN alone; below 12, fine or coarse location unlocks it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        }

        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun isLocationEnabled(): Boolean {
        val context = getApplication<Application>()
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
}

