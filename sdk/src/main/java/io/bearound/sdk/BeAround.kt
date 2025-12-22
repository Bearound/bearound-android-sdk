package io.bearound.sdk

import android.annotation.SuppressLint
import android.app.Application.NOTIFICATION_SERVICE
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.altbeacon.beacon.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date
/**
 * BeAround SDK - Class for managing beacon monitoring and syncing events with a remote API.
 *
 * @param context Application context for initializing the beacon manager and accessing system services.
 */
class BeAround private constructor(private val context: Context) : MonitorNotifier {

    private val beaconUUID = "e25b8d3c-947a-452f-a13f-589cb706d2e5"
    private val beaconManager = BeaconManager.getInstanceForApplication(context.applicationContext)
    private var lastSeenBeacon: Collection<Beacon>? = null
    private var syncFailedBeaconsArray = JSONArray()
    private var advertisingId: String? = null
    private var advertisingIdFetchAttempted = false
    private var adTrackingEnabled: Boolean = true
    private var debug: Boolean = false
    private var clientToken: String = ""
    private var sdkInitialized = false
    private var timeScanBeacons = TimeScanBeacons.TIME_5
    private var sizeListBackupLostBeacons = SizeBackupLostBeacons.SIZE_40
    private var appStartTime: Long = 0L
    private var currentScanSessionId: String = ""

    // Collectors
    private val deviceInfoCollector by lazy { DeviceInfoCollector(context) }
    private val sdkInfoCollector by lazy { SdkInfoCollector(context) }
    private val scanContextCollector by lazy { ScanContextCollector() }

    companion object {

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: BeAround? = null

        fun getInstance(context: Context): BeAround {
            return instance ?: synchronized(this) {
                instance ?: BeAround(context.applicationContext).also { instance = it }
            }
        }

        fun isInitialized(): Boolean {
            return instance?.sdkInitialized == true
        }

        private const val TAG = "BeAroundSdk"
        private const val API_ENDPOINT_URL = "https://ingest.bearound.io/ingest"
        private const val NOTIFICATION_CHANNEL_ID = "beacon_notifications"
        private const val FOREGROUND_SERVICE_NOTIFICATION_ID = 3
        private const val EVENT_ENTER = "enter"
        private const val EVENT_EXIT = "exit"
        private const val EVENT_FAILED = "failed"
    }

    private val logListeners = mutableListOf<LogListener>()
    private val beaconListeners = mutableListOf<BeaconListener>()
    private val syncListeners = mutableListOf<SyncListener>()
    private val regionListeners = mutableListOf<RegionListener>()

    fun addLogListener(listener: LogListener) {
        if (!logListeners.contains(listener)) {
            logListeners.add(listener)
        }
    }

    fun removeLogListener(listener: LogListener) {
        logListeners.remove(listener)
    }

    fun addBeaconListener(listener: BeaconListener) {
        if (!beaconListeners.contains(listener)) {
            beaconListeners.add(listener)
        }
    }

    fun removeBeaconListener(listener: BeaconListener) {
        beaconListeners.remove(listener)
    }

    fun addSyncListener(listener: SyncListener) {
        if (!syncListeners.contains(listener)) {
            syncListeners.add(listener)
        }
    }

    fun removeSyncListener(listener: SyncListener) {
        syncListeners.remove(listener)
    }

    fun addRegionListener(listener: RegionListener) {
        if (!regionListeners.contains(listener)) {
            regionListeners.add(listener)
        }
    }

    fun removeRegionListener(listener: RegionListener) {
        regionListeners.remove(listener)
    }

    enum class TimeScanBeacons(val seconds: Long) {
        TIME_5(5000L),
        TIME_10(10000L),
        TIME_15(15000L),
        TIME_20(20000L),
        TIME_25(25000L),
        TIME_30(30000L),
        TIME_35(35000L),
        TIME_40(40000L),
        TIME_45(45000L),
        TIME_50(50000L),
        TIME_55(55000L),
        TIME_60(60000L)
    }

    enum class SizeBackupLostBeacons(val size: Int) {
        SIZE_5(5),
        SIZE_10(10),
        SIZE_15(15),
        SIZE_20(20),
        SIZE_25(25),
        SIZE_30(30),
        SIZE_35(35),
        SIZE_40(40),
        SIZE_45(45),
        SIZE_50(50)
    }

    /**
     * Configuration functions for the BeAround SDK.
     *
     * **[setBackupSize]**: ⚠️ Must be called **before** [initialize] to set the backup list size for failed beacons.
     *
     * **[setSyncInterval]**: Can be called **before or after** [initialize] to set or change the scan interval dynamically.
     * - Call before [initialize] to set the initial scan interval (default: 5 seconds)
     * - Call after [initialize] to change the scan interval at runtime
     *
     * **[getSyncInterval]**: Returns the current scan interval configuration.
     *
     * **[getBackupSize]**: Returns the current backup list size configuration.
     *
     * If not configured, the SDK uses default values: 5 seconds scan interval and 40 beacons backup size.
     */

    /**
     * Sets the size of the backup list for lost beacons.
     *
     * ⚠️ **Must be called before [initialize]**.
     *
     * @param size The backup size configuration (5 to 50 beacons).
     */
    fun setBackupSize(size: SizeBackupLostBeacons) {
        sizeListBackupLostBeacons = size
    }

    /**
     * Sets the size of the backup list for lost beacons.
     *
     * ⚠️ **Must be called before [initialize]**.
     *
     * @deprecated Use [setBackupSize] instead for consistency with iOS SDK.
     */
    @Deprecated("Use setBackupSize instead", ReplaceWith("setBackupSize(size)"))
    fun changeListSizeBackupLostBeacons(size: SizeBackupLostBeacons) {
        setBackupSize(size)
    }

    /**
     * Sets the scan interval for beacon detection.
     *
     * ✅ **Can be called before or after [initialize]** for dynamic configuration.
     *
     * When called after initialization, the new interval takes effect immediately,
     * allowing you to adjust scanning frequency based on battery level, user preferences,
     * or application state.
     *
     * @param interval The scan interval configuration (5 to 60 seconds).
     *
     * Example - Set initial interval before initialization:
     * ```kotlin
     * beAround.setSyncInterval(BeAround.TimeScanBeacons.TIME_30)
     * beAround.initialize(...)
     * ```
     *
     * Example - Change interval dynamically at runtime:
     * ```kotlin
     * // Later in your code, after initialization
     * beAround.setSyncInterval(BeAround.TimeScanBeacons.TIME_10) // Increase scan frequency
     * ```
     */
    fun setSyncInterval(interval: TimeScanBeacons) {
        timeScanBeacons = interval
        // If already initialized, update the beacon manager scan periods
        if (sdkInitialized) {
            beaconManager.backgroundBetweenScanPeriod = interval.seconds
            beaconManager.foregroundBetweenScanPeriod = interval.seconds
            log("Scan interval updated to ${interval.name} (${interval.seconds / 1000}s)")
        }
    }

    /**
     * Sets the scan interval for beacon detection.
     *
     * ✅ **Can be called before or after [initialize]** for dynamic configuration.
     *
     * @deprecated Use [setSyncInterval] instead for consistency with iOS SDK.
     */
    @Deprecated("Use setSyncInterval instead", ReplaceWith("setSyncInterval(time)"))
    fun changeScanTimeBeacons(time: TimeScanBeacons) {
        setSyncInterval(time)
    }

    /**
     * Gets the current scan interval configuration.
     *
     * @return The current [TimeScanBeacons] value representing the scan interval.
     *
     * Example:
     * ```kotlin
     * val currentInterval = beAround.getSyncInterval()
     * Log.d("BeAround", "Current scan interval: ${currentInterval.seconds / 1000}s")
     * ```
     */
    fun getSyncInterval(): TimeScanBeacons {
        return timeScanBeacons
    }

    /**
     * Gets the current backup size configuration.
     *
     * @return The current [SizeBackupLostBeacons] value representing the backup list size.
     *
     * Example:
     * ```kotlin
     * val currentBackupSize = beAround.getBackupSize()
     * Log.d("BeAround", "Current backup size: ${currentBackupSize.size} beacons")
     * ```
     */
    fun getBackupSize(): SizeBackupLostBeacons {
        return sizeListBackupLostBeacons
    }

    /**
     * Initializes the SDK, sets up beacon monitoring and notification channel.
     *
     * @param iconNotification The resource ID of the small icon used in the foreground notification.
     * @param debug Enables or disables debug logging.
     */
    fun initialize(
        iconNotification: Int = context.applicationInfo.icon,
        clientToken: String,
        debug: Boolean = false
    ) {
        if (!isInitialized()) {
            this.clientToken = clientToken
            this.debug = debug
            this.appStartTime = System.currentTimeMillis()
            createNotificationChannel(context)

            // Used to parse the iBeacon standard
            beaconManager.beaconParsers.add(
                BeaconParser().setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24")
            )

            val foregroundNotification =
                NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(iconNotification)
                    .setContentTitle(
                        context.getString(R.string.title_notification_sdk)
                    )
                    .setContentText(
                        context.getString(R.string.subtitle_notification_sdk)
                    )
                    .setOngoing(true)
                    .build()

            beaconManager.enableForegroundServiceScanning(
                foregroundNotification,
                FOREGROUND_SERVICE_NOTIFICATION_ID
            )

            beaconManager.setEnableScheduledScanJobs(false)
            beaconManager.isRegionStatePersistenceEnabled = false
            beaconManager.backgroundScanPeriod = 1100L
            beaconManager.backgroundBetweenScanPeriod = timeScanBeacons.seconds
            beaconManager.foregroundBetweenScanPeriod = timeScanBeacons.seconds

            beaconManager.addMonitorNotifier(this)
            beaconManager.startMonitoring(getRegion())

            fetchAdvertisingId()
            sdkInitialized = true
        }
    }

    /**
     * Stops beacon monitoring and clears all notifiers.
     */
    fun stop() {
        log("Stopped monitoring beacons region")
        beaconManager.stopMonitoring(getRegion())
        beaconManager.removeAllMonitorNotifiers()
        beaconManager.removeAllRangeNotifiers()
        instance = null
        sdkInitialized = false
    }

    /**
     * Retrieves the user's advertising ID asynchronously.
     */
    private fun fetchAdvertisingId() {
        if (advertisingIdFetchAttempted && advertisingId != null) {
            log("Advertising ID already fetched: $advertisingId")
            return
        }
        advertisingIdFetchAttempted = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val adInfo = AdvertisingIdClient.getAdvertisingIdInfo(context)
                advertisingId = adInfo.id
                adTrackingEnabled = !adInfo.isLimitAdTrackingEnabled
                log("Successfully fetched Advertising ID: $advertisingId, Ad Tracking: $adTrackingEnabled")
            } catch (e: Exception) {
                advertisingId = null
                adTrackingEnabled = false
                logError("Failed to fetch Advertising ID: ${e.message}")
            }
        }
    }

    override fun didEnterRegion(region: Region) {
        log("Beacons detected in the region")
        currentScanSessionId = scanContextCollector.generateScanSessionId()
        beaconManager.startRangingBeacons(region)
        beaconManager.addRangeNotifier(rangeNotifierForSync)

        // Notify region listeners
        regionListeners.forEach { it.onRegionEnter(region.uniqueId) }
    }

    override fun didExitRegion(region: Region) {
        log("No sign of Beacons in the region")
        lastSeenBeacon?.let {
            syncWithApi(it, EVENT_EXIT)
        }
        beaconManager.stopRangingBeacons(region)
        beaconManager.removeRangeNotifier(rangeNotifierForSync)
        lastSeenBeacon = null

        // Notify region listeners
        regionListeners.forEach { it.onRegionExit(region.uniqueId) }
    }

    override fun didDetermineStateForRegion(state: Int, region: Region) {
        val stateString = if (state == MonitorNotifier.INSIDE) EVENT_ENTER else EVENT_EXIT
        log("State of the region: $stateString")
    }

    /**
     * Handles beacons detected during ranging.
     *
     * Typically used to initiate a sync when a beacon "enter" event is detected.
     */
    private val rangeNotifierForSync = RangeNotifier { beacons, _ ->
        log("Beacons found in the region: ${beacons.size}")
        syncWithApi(beacons, EVENT_ENTER)
    }

    /**
     * Converts a collection of AltBeacon Beacon objects to BeaconData objects.
     */
    private fun convertToBeaconDataList(beacons: Collection<Beacon>): List<BeaconData> {
        return beacons.map { beacon ->
            BeaconData(
                uuid = beacon.id1.toString(),
                major = beacon.id2.toInt(),
                minor = beacon.id3.toInt(),
                rssi = beacon.rssi,
                bluetoothName = beacon.bluetoothName,
                bluetoothAddress = beacon.bluetoothAddress,
                lastSeen = Date().time
            )
        }
    }

    /**
     * Sends multiple beacon events to the remote API.
     *
     * Filters the provided collection of beacons to include only those matching the expected UUID,
     * constructs a JSON payload with relevant beacon data, and sends it to the remote API
     * via an HTTP POST request.
     *
     * The payload includes beacon identifiers, signal data (RSSI), estimated distance,
     * Bluetooth information, the device's advertising ID, and the current app state.
     *
     * If it fails, it saves beacons that were not sent and then tries to resend
     * them when it succeeds.
     *
     * @param beacons A collection of beacons detected during the scan.
     * @param eventType The type of event associated with the detection (e.g., "enter", "exit").
     */
    private fun syncWithApi(beacons: Collection<Beacon>, eventType: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {

                val matchingBeacons = beacons.filter {
                    it.id1.toString() == beaconUUID && it.rssi != 0
                }

                if (matchingBeacons.isEmpty()) {
                    log("No beacon with matching UUID found or all beacons have RSSI = 0.")
                    return@launch
                }

                lastSeenBeacon = matchingBeacons

                // Notify beacon listeners
                val beaconDataList = convertToBeaconDataList(matchingBeacons)
                beaconListeners.forEach { it.onBeaconsDetected(beaconDataList, eventType) }

                // Build beacons array with scan context
                val beaconsArray = JSONArray()
                for (beacon in matchingBeacons) {
                    if (eventType == EVENT_ENTER) {
                        log(
                            "Beacon detected" +
                                    " id: ${beacon.id1}," +
                                    " major: ${beacon.id2}," +
                                    " minor: ${beacon.id3}," +
                                    " rssi ${beacon.rssi}."
                        )
                    }

                    // Parse beacon name to extract firmware, battery, movements, and temperature
                    val beaconName = beacon.bluetoothName ?: ""

                    val beaconJson = JSONObject().apply {
                        put("uuid", beacon.id1.toString().uppercase())
                        put("name", beaconName)
                        put("rssi", beacon.rssi)
                        put("txPower", beacon.txPower)
                        put("approxDistanceMeters", scanContextCollector.calculateDistance(beacon.rssi, beacon.txPower))
                    }
                    beaconsArray.put(beaconJson)
                }

                // Collect SDK info
                val sdkInfo = sdkInfoCollector.collectSdkInfo()

                // Collect user device info
                val userDeviceInfo = deviceInfoCollector.collectUserDeviceInfo(
                    advertisingId,
                    adTrackingEnabled,
                    appStartTime
                )

                // Collect scan context
                val scanContext = if (matchingBeacons.isNotEmpty()) {
                    scanContextCollector.collectScanContext(currentScanSessionId)
                } else {
                    JSONObject()
                }

                // Build the new payload structure
                val jsonObject = JSONObject().apply {
                    put("clientToken", clientToken)
                    put("beacons", beaconsArray)
                    put("sdk", sdkInfo)
                    put("userDevice", userDeviceInfo)
                    put("scanContext", scanContext)
                }

                // Log complete JSON payload
                log("API Request Payload: ${jsonObject.toString(2)}")

                val url = URL(API_ENDPOINT_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("Authorization", "Bearer $clientToken")
                    doOutput = true
                    connectTimeout = 15000
                    readTimeout = 15000
                }

                val writer = OutputStreamWriter(connection.outputStream)
                writer.write(jsonObject.toString())
                writer.flush()
                writer.close()

                val responseCode = connection.responseCode
                if (responseCode in 200..299) {
                    if (syncFailedBeaconsArray.length() > 0) {
                        syncFailedBeaconsArrayWithApi()
                    }
                    log("Successfully call API. Response: ${connection.responseMessage}")

                    // Notify sync listeners of success
                    syncListeners.forEach {
                        it.onSyncSuccess(
                            eventType,
                            matchingBeacons.size,
                            connection.responseMessage
                        )
                    }
                } else {
                    logError(
                        "Error call API. " +
                                "Code: $responseCode, Message: ${connection.responseMessage}}"
                    )
                    // Todo add beacons com erro.
                    for (i in 0 until beaconsArray.length()) {
                        if (syncFailedBeaconsArray.length() < sizeListBackupLostBeacons.size) {
                            syncFailedBeaconsArray.put(beaconsArray.getJSONObject(i))
                        }
                    }
                    log(
                        "List beacons backup size: " +
                                "${syncFailedBeaconsArray.length()}"
                    )

                    // Notify sync listeners of error
                    syncListeners.forEach {
                        it.onSyncError(
                            eventType,
                            matchingBeacons.size,
                            responseCode,
                            connection.responseMessage
                        )
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                logError("Exception during API sync: ${e.message}")

                // Notify sync listeners of exception
                syncListeners.forEach {
                    it.onSyncError(
                        eventType,
                        0,
                        null,
                        e.message ?: "Unknown error"
                    )
                }
            }
        }
    }

    /**
     * Attempts to sync all previously failed list beacon data with the API.
     *
     * The event is sent using the EVENT_FAILED type.
     */
    private fun syncFailedBeaconsArrayWithApi() {
        CoroutineScope(Dispatchers.IO).launch {
            try {

                // Collect SDK info
                val sdkInfo = sdkInfoCollector.collectSdkInfo()

                // Collect user device info
                val userDeviceInfo = deviceInfoCollector.collectUserDeviceInfo(
                    advertisingId,
                    adTrackingEnabled,
                    appStartTime
                )

                // Build the new payload structure
                val jsonObject = JSONObject().apply {
                    put("clientToken", clientToken)
                    put("beacons", syncFailedBeaconsArray)
                    put("sdk", sdkInfo)
                    put("userDevice", userDeviceInfo)
                    put("scanContext", JSONObject()) // Empty scan context for failed beacons
                }

                // Log complete JSON payload for retry
                log("API Retry Request Payload (failed beacons): ${jsonObject.toString(2)}")

                val url = URL(API_ENDPOINT_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("Authorization", "Bearer $clientToken")
                    doOutput = true
                    connectTimeout = 15000
                    readTimeout = 15000
                }

                val writer = OutputStreamWriter(connection.outputStream)
                writer.write(jsonObject.toString())
                writer.flush()
                writer.close()

                val responseCode = connection.responseCode
                if (responseCode in 200..299) {
                    syncFailedBeaconsArray = JSONArray()
                    log(
                        "Successfully synced Failed beacons with API. Response:" +
                                " ${connection.responseMessage}"
                    )
                } else {
                    logError(
                        "API sync failed. " +
                                "Code: $responseCode, Message: ${connection.responseMessage}}"
                    )

                    log(
                        "List beacons that failed to sync size: " +
                                "${syncFailedBeaconsArray.length()}"
                    )
                }
                connection.disconnect()
            } catch (e: Exception) {
                log("List beacons that failed to sync size: ${syncFailedBeaconsArray.length()}")
                logError("Exception during API sync: ${e.message}")
            }
        }
    }

    /**
     * Returns the current application state as a string.
     */
    private fun getAppState(): String {
        val state = ProcessLifecycleOwner.get().lifecycle.currentState
        return when {
            state.isAtLeast(Lifecycle.State.RESUMED) -> "foreground"
            state.isAtLeast(Lifecycle.State.STARTED) -> "background"
            else -> "inactive"
        }
    }

    /**
     * Creates the required notification channel for foreground service notifications.
     */
    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Notificações de Beacon"
            val descriptionText = "Canal para notificações relacionadas a beacons."
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Utility method for debug logging.
     */
    private fun log(message: String) {
        if (debug) Log.d(TAG, message)

        val logEntry = message
        logListeners.forEach { it.onLogAdded(logEntry) }
    }

    /**
     * Utility method for error logging.
     * Respects the debug mode setting while ensuring critical errors are captured by listeners.
     */
    private fun logError(message: String) {
        if (debug) Log.e(TAG, message)

        val logEntry = "ERROR: $message"
        logListeners.forEach { it.onLogAdded(logEntry) }
    }

    /**
     * Creates and returns a new instance of the monitored beacon region.
     *
     * @return A [Region] configured with the SDK's predefined UUID and null values for major
     * and minor identifiers.
     */
    private fun getRegion(): Region {
        return Region(
            "BeAroundSdkRegion", Identifier.parse(beaconUUID), null, null
        )
    }
}

interface LogListener {
    fun onLogAdded(log: String)
}