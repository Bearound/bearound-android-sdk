package io.bearound.bearoundscan.viewmodel

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.bearound.bearoundscan.notification.BeaconNotificationManager
import io.bearound.sdk.BeAroundSDK
import io.bearound.sdk.interfaces.BeAroundSDKListener
import io.bearound.sdk.models.ForegroundScanConfig
import io.bearound.sdk.models.Beacon
import io.bearound.sdk.models.BackgroundScanInterval
import io.bearound.sdk.models.ForegroundScanInterval
import io.bearound.sdk.models.MaxQueuedPayloads
import io.bearound.sdk.models.UserProperties
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
    val backgroundInterval: BackgroundScanInterval = BackgroundScanInterval.SECONDS_60,
    val maxQueuedPayloads: MaxQueuedPayloads = MaxQueuedPayloads.MEDIUM,
    val isInBackground: Boolean = false,
    val sortOption: BeaconSortOption = BeaconSortOption.PROXIMITY,
    // Sync info
    val lastSyncTime: Date? = null,
    val lastSyncBeaconCount: Int = 0,
    val lastSyncResult: String = "Aguardando...",
    val lastSyncSuccess: Boolean? = null,
    // User properties
    val userPropertyInternalId: String = "",
    val userPropertyEmail: String = "",
    val userPropertyName: String = "",
    val userPropertyCustom: String = "",
    // Settings sheet
    val showSettings: Boolean = false,
    // Pinned beacons
    val pinnedBeaconIds: Set<String> = emptySet()
)

class BeaconViewModel(application: Application) : AndroidViewModel(application), BeAroundSDKListener {
    private val _state = MutableStateFlow(BeAroundScanState())
    val state: StateFlow<BeAroundScanState> = _state.asStateFlow()

    private val sdk = BeAroundSDK.getInstance(application)
    private val notificationManager = BeaconNotificationManager(application)
    private val prefs: SharedPreferences = application.getSharedPreferences("bearoundscan_settings", Context.MODE_PRIVATE)

    private var wasInBeaconRegion = false
    private var scanStartTime: Date? = null
    private var previousListener: BeAroundSDKListener? = null

    init {
        previousListener = sdk.listener
        sdk.listener = this

        loadSavedSettings()
        updatePermissionStatus()
        checkBluetoothStatus()
        checkNotificationStatus()

        configureSDK(
            _state.value.foregroundInterval,
            _state.value.backgroundInterval,
            _state.value.maxQueuedPayloads
        )

        startScanning()
    }

    override fun onCleared() {
        super.onCleared()
        sdk.listener = previousListener
    }

    private fun loadSavedSettings() {
        val fgOrdinal = prefs.getInt("fg_interval", ForegroundScanInterval.SECONDS_15.ordinal)
        val bgOrdinal = prefs.getInt("bg_interval", BackgroundScanInterval.SECONDS_60.ordinal)
        val queueOrdinal = prefs.getInt("queue_size", MaxQueuedPayloads.MEDIUM.ordinal)

        val fg = ForegroundScanInterval.entries.getOrElse(fgOrdinal) { ForegroundScanInterval.SECONDS_15 }
        val bg = BackgroundScanInterval.entries.getOrElse(bgOrdinal) { BackgroundScanInterval.SECONDS_60 }
        val queue = MaxQueuedPayloads.entries.getOrElse(queueOrdinal) { MaxQueuedPayloads.MEDIUM }

        _state.value = _state.value.copy(
            foregroundInterval = fg,
            backgroundInterval = bg,
            maxQueuedPayloads = queue,
            userPropertyInternalId = prefs.getString("user_internal_id", "") ?: "",
            userPropertyEmail = prefs.getString("user_email", "") ?: "",
            userPropertyName = prefs.getString("user_name", "") ?: "",
            userPropertyCustom = prefs.getString("user_custom", "") ?: ""
        )
    }

    private fun saveSettings() {
        val s = _state.value
        prefs.edit()
            .putInt("fg_interval", s.foregroundInterval.ordinal)
            .putInt("bg_interval", s.backgroundInterval.ordinal)
            .putInt("queue_size", s.maxQueuedPayloads.ordinal)
            .putString("user_internal_id", s.userPropertyInternalId)
            .putString("user_email", s.userPropertyEmail)
            .putString("user_name", s.userPropertyName)
            .putString("user_custom", s.userPropertyCustom)
            .apply()
    }

    private fun configureSDK(
        foreground: ForegroundScanInterval,
        background: BackgroundScanInterval,
        maxQueued: MaxQueuedPayloads
    ) {
        sdk.configure(
            businessToken = "BUSINESS_TOKEN",
            foregroundScanInterval = foreground,
            backgroundScanInterval = background,
            maxQueuedPayloads = maxQueued
        )

        // Enable background scanning via ForegroundService
        sdk.enableForegroundScanning(
            ForegroundScanConfig(
                enabled = true,
                notificationTitle = "BeAroundSDK",
                notificationText = "Escaneando beacons em segundo plano"
            )
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

    fun applySettings(
        foreground: ForegroundScanInterval,
        background: BackgroundScanInterval,
        maxQueued: MaxQueuedPayloads,
        internalId: String,
        email: String,
        name: String,
        custom: String
    ) {
        val wasScanning = _state.value.isScanning

        if (wasScanning) {
            sdk.stopScanning()
        }

        _state.value = _state.value.copy(
            foregroundInterval = foreground,
            backgroundInterval = background,
            maxQueuedPayloads = maxQueued,
            userPropertyInternalId = internalId,
            userPropertyEmail = email,
            userPropertyName = name,
            userPropertyCustom = custom
        )

        configureSDK(foreground, background, maxQueued)

        // Set user properties
        val props = UserProperties(
            internalId = internalId.ifBlank { null },
            email = email.ifBlank { null },
            name = name.ifBlank { null },
            customProperties = if (custom.isNotBlank()) mapOf("custom" to custom) else emptyMap()
        )
        if (props.hasProperties) {
            sdk.setUserProperties(props)
        } else {
            sdk.clearUserProperties()
        }

        saveSettings()

        _state.value = _state.value.copy(
            currentSyncInterval = (sdk.currentSyncInterval ?: 0L).toInt() / 1000,
            statusMessage = "Configuração atualizada"
        )

        if (wasScanning) {
            sdk.startScanning()
            _state.value = _state.value.copy(isScanning = true, statusMessage = "Scaneando...")
        }
    }

    fun changeSortOption(option: BeaconSortOption) {
        val sorted = sortBeacons(_state.value.beacons, option)
        _state.value = _state.value.copy(sortOption = option, beacons = sorted)
    }

    fun togglePinBeacon(beacon: Beacon) {
        val id = beacon.identifier
        val current = _state.value.pinnedBeaconIds
        val updated = if (current.contains(id)) current - id else current + id
        val sorted = sortBeacons(_state.value.beacons, _state.value.sortOption, updated)
        _state.value = _state.value.copy(pinnedBeaconIds = updated, beacons = sorted)
    }

    fun showSettings() {
        _state.value = _state.value.copy(showSettings = true)
    }

    fun hideSettings() {
        _state.value = _state.value.copy(showSettings = false)
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

    // region BeAroundSDKListener

    override fun onBeaconsUpdated(beacons: List<Beacon>) {
        viewModelScope.launch {
            val sortedBeacons = sortBeacons(beacons, _state.value.sortOption)
            val isNowInBeaconRegion = sortedBeacons.isNotEmpty()

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
            _state.value = _state.value.copy(
                lastSyncTime = Date(),
                lastSyncBeaconCount = beaconCount,
                lastSyncResult = if (success) "Sucesso" else "Falha: ${error?.message ?: "erro desconhecido"}",
                lastSyncSuccess = success
            )
            notificationManager.notifyAPISyncCompleted(beaconCount, success)
        }
    }

    override fun onBeaconDetectedInBackground(beaconCount: Int) {
        viewModelScope.launch {
            notificationManager.notifyBeaconDetected(beaconCount, isBackground = true)
        }
    }

    // endregion

    private fun sortBeacons(
        beacons: List<Beacon>,
        option: BeaconSortOption,
        pinnedIds: Set<String> = _state.value.pinnedBeaconIds
    ): List<Beacon> {
        val baseSort: Comparator<Beacon> = when (option) {
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
        // Pinned beacons always first, then normal sort
        val pinFirst = compareBy<Beacon> { if (pinnedIds.contains(it.identifier)) 0 else 1 }
        return beacons.sortedWith(pinFirst.then(baseSort))
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
                "Negada"
            }
        }

        _state.value = _state.value.copy(locationPermissionStatus = locationPermission)
    }

    fun checkBluetoothStatus() {
        val context = getApplication<Application>()
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        val status = when {
            bluetoothAdapter == null -> "Não suportado"
            !bluetoothAdapter.isEnabled -> "Desligado"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_SCAN
                    ) != PackageManager.PERMISSION_GRANTED -> "Não autorizado"
            else -> "Ligado"
        }

        _state.value = _state.value.copy(bluetoothStatus = status)
    }

    fun checkNotificationStatus() {
        val context = getApplication<Application>()
        val status = when {
            NotificationManagerCompat.from(context).areNotificationsEnabled() -> "Autorizada"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> "Não solicitada"
            else -> "Negada"
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
}
