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
import io.bearound.sdk.interfaces.BeAroundSDKDelegate
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
    val enableBluetoothScanning: Boolean = false,
    val enablePeriodicScanning: Boolean = true,
    val isInBackground: Boolean = false,
    val sortOption: BeaconSortOption = BeaconSortOption.PROXIMITY,
    val secondsUntilNextSync: Int = 0,
    val isRanging: Boolean = false
)

class BeaconViewModel(application: Application) : AndroidViewModel(application), BeAroundSDKDelegate {
    private val _state = MutableStateFlow(BeAroundScanState())
    val state: StateFlow<BeAroundScanState> = _state.asStateFlow()

    private val sdk = BeAroundSDK.getInstance(application)
    private val notificationManager = NotificationManager(application)
    
    private var wasInBeaconRegion = false
    private var scanStartTime: Date? = null

    init {
        sdk.delegate = this
        
        updatePermissionStatus()
        checkBluetoothStatus()
        checkNotificationStatus()
        
        // Configure SDK
        configureSDK(
            _state.value.foregroundInterval,
            _state.value.backgroundInterval,
            _state.value.maxQueuedPayloads,
            _state.value.enableBluetoothScanning,
            _state.value.enablePeriodicScanning
        )
        
        // Auto-start scanning
        startScanning()
    }

    private fun configureSDK(
        foreground: ForegroundScanInterval,
        background: BackgroundScanInterval,
        maxQueued: MaxQueuedPayloads,
        enableBluetooth: Boolean,
        enablePeriodic: Boolean
    ) {
        sdk.configure(
            businessToken = "your-business-token-here",
            foregroundScanInterval = foreground,
            backgroundScanInterval = background,
            maxQueuedPayloads = maxQueued,
            enableBluetoothScanning = enableBluetooth,
            enablePeriodicScanning = enablePeriodic
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
        maxQueued: MaxQueuedPayloads,
        enableBluetooth: Boolean,
        enablePeriodic: Boolean
    ) {
        configureSDK(foreground, background, maxQueued, enableBluetooth, enablePeriodic)
        _state.value = _state.value.copy(
            foregroundInterval = foreground,
            backgroundInterval = background,
            maxQueuedPayloads = maxQueued,
            enableBluetoothScanning = enableBluetooth,
            enablePeriodicScanning = enablePeriodic,
            currentSyncInterval = (sdk.currentSyncInterval ?: 0L).toInt() / 1000,
            statusMessage = "Configuração atualizada"
        )
    }

    fun changeSortOption(option: BeaconSortOption) {
        _state.value = _state.value.copy(sortOption = option)
        // Re-sort current beacons
        val sorted = sortBeacons(_state.value.beacons, option)
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

    // BeAroundSDKDelegate methods
    override fun didUpdateBeacons(beacons: List<Beacon>) {
        android.util.Log.d("BeaconViewModel", "========================================")
        android.util.Log.d("BeaconViewModel", "didUpdateBeacons CALLED")
        android.util.Log.d("BeaconViewModel", "Received ${beacons.size} beacon(s) from SDK")
        android.util.Log.d("BeaconViewModel", "Current thread: ${Thread.currentThread().name}")

        viewModelScope.launch {
            android.util.Log.d("BeaconViewModel", "Inside viewModelScope.launch")

            val sortedBeacons = sortBeacons(beacons, _state.value.sortOption)
            val isNowInBeaconRegion = sortedBeacons.isNotEmpty()

            android.util.Log.d("BeaconViewModel", "Sorted beacons: ${sortedBeacons.size}")
            sortedBeacons.forEach { beacon ->
                android.util.Log.d("BeaconViewModel", "  - ${beacon.identifier}: rssi=${beacon.rssi}, proximity=${beacon.proximity}")
            }

            // Detect region entry
            val shouldNotify = isNowInBeaconRegion && !wasInBeaconRegion
            if (shouldNotify) {
                scanStartTime?.let { startTime ->
                    val timeSinceStart = (Date().time - startTime.time) / 1000.0
                    if (timeSinceStart >= 2.0) {
                        notificationManager.notifyBeaconRegionEntered(sortedBeacons.size)
                    }
                }
            }

            wasInBeaconRegion = isNowInBeaconRegion

            val newState = _state.value.copy(
                beacons = sortedBeacons,
                lastScanTime = Date(),
                statusMessage = if (sortedBeacons.isEmpty()) {
                    "Scaneando..."
                } else {
                    "${sortedBeacons.size} beacon${if (sortedBeacons.size == 1) "" else "s"}"
                }
            )

            android.util.Log.d("BeaconViewModel", "Updating state with ${sortedBeacons.size} beacons")
            android.util.Log.d("BeaconViewModel", "Old state beacons count: ${_state.value.beacons.size}")
            android.util.Log.d("BeaconViewModel", "New state beacons count: ${newState.beacons.size}")

            _state.value = newState

            android.util.Log.d("BeaconViewModel", "State updated successfully")
            android.util.Log.d("BeaconViewModel", "Current state beacons: ${_state.value.beacons.size}")
            android.util.Log.d("BeaconViewModel", "========================================")
        }
    }

    override fun didFailWithError(error: Exception) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                statusMessage = "Erro: ${error.message}"
            )
        }
    }

    override fun didChangeScanning(isScanning: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isScanning = isScanning,
                statusMessage = if (isScanning) "Scaneando..." else "Parado"
            )
        }
    }

    override fun didUpdateSyncStatus(secondsUntilNextSync: Int, isRanging: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                secondsUntilNextSync = secondsUntilNextSync,
                isRanging = isRanging,
                currentSyncInterval = (sdk.currentSyncInterval ?: 0L).toInt() / 1000
            )
        }
    }
    
    override fun didChangeAppState(isInBackground: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isInBackground = isInBackground,
                currentSyncInterval = (sdk.currentSyncInterval ?: 0L).toInt() / 1000
            )
        }
    }

    private fun sortBeacons(beacons: List<Beacon>, option: BeaconSortOption): List<Beacon> {
        return when (option) {
            BeaconSortOption.PROXIMITY -> {
                beacons.sortedWith(compareBy<Beacon> { beacon ->
                    when (beacon.proximity) {
                        Beacon.Proximity.IMMEDIATE -> 0
                        Beacon.Proximity.NEAR -> 1
                        Beacon.Proximity.FAR -> 2
                        Beacon.Proximity.UNKNOWN -> 3
                    }
                }.thenByDescending { it.rssi }
                    .thenBy { if (it.accuracy > 0) it.accuracy else Double.MAX_VALUE })
            }
            BeaconSortOption.ID -> {
                beacons.sortedBy { "${it.major}.${it.minor}" }
            }
        }
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

