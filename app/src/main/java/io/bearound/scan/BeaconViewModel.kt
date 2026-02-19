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
import io.bearound.sdk.models.BackgroundScanInterval
import io.bearound.sdk.models.ForegroundScanInterval
import io.bearound.sdk.models.MaxQueuedPayloads
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
    val currentSyncInterval: Int = 15,
    val foregroundInterval: ForegroundScanInterval = ForegroundScanInterval.SECONDS_15,
    val backgroundInterval: BackgroundScanInterval = BackgroundScanInterval.SECONDS_30,
    val maxQueuedPayloads: MaxQueuedPayloads = MaxQueuedPayloads.MEDIUM,
    val isInBackground: Boolean = false,
    val sortOption: BeaconSortOption = BeaconSortOption.PROXIMITY,
    val pinnedBeaconIds: Set<String> = emptySet(),
    val retryBatches: List<List<Beacon>> = emptyList(),
    val retryBatchCount: Int = 0
)

class BeaconViewModel(application: Application) : AndroidViewModel(application), BeAroundSDKListener {
    private val _state = MutableStateFlow(BeAroundScanState())
    val state: StateFlow<BeAroundScanState> = _state.asStateFlow()

    private val sdk = BeAroundSDK.getInstance(application)
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
        
        // Configure SDK
        configureSDK(
            _state.value.foregroundInterval,
            _state.value.backgroundInterval,
            _state.value.maxQueuedPayloads
        )
        
        // Auto-start scanning
        startScanning()
    }
    
    override fun onCleared() {
        super.onCleared()
        // Restore background listener when ViewModel is destroyed
        sdk.listener = previousListener
        android.util.Log.d("BeaconViewModel", "ViewModel cleared - restored background listener")
    }

    private fun configureSDK(
        foreground: ForegroundScanInterval,
        background: BackgroundScanInterval,
        maxQueued: MaxQueuedPayloads
    ) {
        sdk.configure(
            businessToken = "your-business-token-here",
            foregroundScanInterval = foreground,
            backgroundScanInterval = background,
            maxQueuedPayloads = maxQueued
            // Bluetooth scanning and periodic scanning are now automatic in v2.2.0
        )
    }

    fun startScanning() {
        if (!hasRequiredPermissions()) {
            _state.value = _state.value.copy(
                statusMessage = "Permissões necessárias",
                isScanning = false
            )
            return
        }

        sdk.startScanning()
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
        scanStartTime = null
        wasInBeaconRegion = false
        
        _state.value = _state.value.copy(
            isScanning = false,
            statusMessage = "Parado"
        )
    }

    fun updateConfiguration(
        foreground: ForegroundScanInterval,
        background: BackgroundScanInterval,
        maxQueued: MaxQueuedPayloads
    ) {
        configureSDK(foreground, background, maxQueued)
        _state.value = _state.value.copy(
            foregroundInterval = foreground,
            backgroundInterval = background,
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
        get() {
            val interval = (sdk.currentSyncInterval ?: 0L).toInt() / 1000
            val scan = scanDuration
            return maxOf(0, interval - scan)
        }

    val scanMode: String
        get() = if (sdk.isPeriodicScanningEnabled) {
            if (pauseDuration > 0) "Periódico" else "Contínuo"
        } else {
            "Contínuo"
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
        
        val locationPermission = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context, 
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                "Sempre (Background habilitado)"
            }
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                "Quando em uso (Background não funciona)"
            }
            else -> {
                "Negada (SDK não funcionará)"
            }
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
        
        val locationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val bluetoothScanGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED

            return locationGranted && bluetoothScanGranted
        }

        return locationGranted
    }

    fun isLocationEnabled(): Boolean {
        val context = getApplication<Application>()
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
}

