package org.bearound.sdk

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
import com.bearound.beacon.BuildConfig
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
    private var debug: Boolean = false
    private var clientId: String = ""
    private var sdkInitialized = false
    private var timeScanBeacons = TimeScanBeacons.TIME_20
    private var sizeListBackupLostBeacons = SizeBackupLostBeacons.SIZE_40

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
        private const val API_ENDPOINT_URL = "https://api.bearound.io/ingest"
        private const val NOTIFICATION_CHANNEL_ID = "beacon_notifications"
        private const val FOREGROUND_SERVICE_NOTIFICATION_ID = 3
        private const val EVENT_ENTER = "enter"
        private const val EVENT_EXIT = "exit"
        private const val EVENT_FAILED = "failed"
    }

    private val logListeners = mutableListOf<LogListener>()

    fun addLogListener(listener: LogListener) {
        if (!logListeners.contains(listener)) {
            logListeners.add(listener)
        }
    }

    fun removeLogListener(listener: LogListener) {
        logListeners.remove(listener)
    }

    enum class TimeScanBeacons(val seconds: Long) {
        TIME_5(5000L),
        TIME_10(10000L),
        TIME_15(15000L),
        TIME_20(20000L),
        TIME_25(25000L)
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
     * ⚠️ These functions **must be called before** invoking [initialize] to ensure the SDK is properly configured.
     *
     * Use `changeListSizeBackupLostBeacons` to set the size of the backup list for lost beacons,
     * and `changeScanTimeBeacons` to define the scan interval between beacon detections.
     *
     * If not called prior to `initialize`, the SDK will use default values.
     */

    /**
     * Sets the size of the backup list for lost beacons.
     */
    public fun changeListSizeBackupLostBeacons(size: SizeBackupLostBeacons) {
        sizeListBackupLostBeacons = size
    }

    /**
     * Sets the scan interval for beacon detection.
     */
    public fun changeScamTimeBeacons(time: TimeScanBeacons) {
        timeScanBeacons = time
    }

    /**
     * Initializes the SDK, sets up beacon monitoring and notification channel.
     *
     * @param iconNotification The resource ID of the small icon used in the foreground notification.
     * @param debug Enables or disables debug logging.
     */
    fun initialize(
        iconNotification: Int = context.applicationInfo.icon,
        clientId: String,
        debug: Boolean = false
    ) {
        if (!isInitialized()) {
            this.clientId = clientId
            this.debug = debug
            createNotificationChannel(context)

            beaconManager.beaconParsers.add(
                BeaconParser().setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24")
            )

            val foregroundNotification =
                NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(iconNotification)
                    .setContentTitle(
                        context.getString(com.bearound.beacon.R.string.title_notification_sdk)
                    )
                    .setContentText(
                        context.getString(com.bearound.beacon.R.string.subtitle_notification_sdk)
                    )
                    .setOngoing(true)
                    .build()

            beaconManager.enableForegroundServiceScanning(
                foregroundNotification,
                FOREGROUND_SERVICE_NOTIFICATION_ID
            )

            beaconManager.setEnableScheduledScanJobs(false)
            beaconManager.setRegionStatePersistenceEnabled(false)
            beaconManager.setBackgroundScanPeriod(1100L)
            beaconManager.setBackgroundBetweenScanPeriod(timeScanBeacons.seconds)
            beaconManager.setForegroundBetweenScanPeriod(timeScanBeacons.seconds)

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
                log("Successfully fetched Advertising ID: $advertisingId")
            } catch (e: Exception) {
                advertisingId = null
                Log.e(TAG, "Failed to fetch Advertising ID: ${e.message}")
            }
        }
    }

    override fun didEnterRegion(region: Region) {
        log("Beacons detected in the region")
        beaconManager.startRangingBeacons(region)
        beaconManager.addRangeNotifier(rangeNotifierForSync)
    }

    override fun didExitRegion(region: Region) {
        log("No sign of Beacons in the region")
        lastSeenBeacon?.let {
            syncWithApi(it, EVENT_EXIT)
        }
        beaconManager.stopRangingBeacons(region)
        beaconManager.removeRangeNotifier(rangeNotifierForSync)
        lastSeenBeacon = null
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
    private val rangeNotifierForSync = RangeNotifier { beacons, rangedRegion ->
        log("Beacons found in the region: ${beacons.size}")
        syncWithApi(beacons, EVENT_ENTER)
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
        val currentAdvertisingId = advertisingId
        val currentAppState = getAppState()
        val beaconsArray = JSONArray()

        CoroutineScope(Dispatchers.IO).launch {
            try {

                val matchingBeacons = beacons.filter {
                    it.id1.toString() == beaconUUID
                }

                if (matchingBeacons.isEmpty()) {
                    log("No beacon with matching UUID found.")
                    return@launch
                }

                lastSeenBeacon = matchingBeacons

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
                    val beaconJson = JSONObject().apply {
                        put("uuid", beacon.id1)
                        put("major", beacon.id2)
                        put("minor", beacon.id3)
                        put("rssi", beacon.rssi)
                        put("bluetoothName", beacon.bluetoothName)
                        put("bluetoothAddress", beacon.bluetoothAddress)
//                        put("distanceMeters", beacon.distance)
                        put("lastSeen", Date().time)
                    }
                    beaconsArray.put(beaconJson)
                }

                val jsonObject = JSONObject().apply {
                    put("deviceType", "Android")
                    put("clientToken", clientId)
                    put("sdkVersion", BuildConfig.SDK_VERSION)
                    put("idfa", currentAdvertisingId ?: "N/A")
                    put("eventType", eventType)
                    put("appState", currentAppState)
                    put("beacons", beaconsArray)
                }

                val url = URL(API_ENDPOINT_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
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
                } else {
                    Log.e(
                        TAG,
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
                }
                connection.disconnect()
            } catch (e: Exception) {
                // Todo add beacons com erro.
                for (i in 0 until beaconsArray.length()) {
                    if (syncFailedBeaconsArray.length() < sizeListBackupLostBeacons.size) {
                        syncFailedBeaconsArray.put(beaconsArray.getJSONObject(i))
                    }
                }
                log("List beacons backup size: ${syncFailedBeaconsArray.length()}")
                Log.e(TAG, "Exception during API sync: ${e.message}")
            }
        }
    }

    /**
     * Attempts to sync all previously failed list beacon data with the API.
     *
     * The event is sent using the EVENT_FAILED type.
     */
    private fun syncFailedBeaconsArrayWithApi() {
        val currentAdvertisingId = advertisingId
        val currentAppState = getAppState()

        CoroutineScope(Dispatchers.IO).launch {
            try {

                val jsonObject = JSONObject().apply {
                    put("deviceType", "Android")
                    put("idfa", currentAdvertisingId ?: "N/A")
                    put("eventType", EVENT_FAILED)
                    put("appState", currentAppState)
                    put("beacons", syncFailedBeaconsArray)
                }

                val url = URL(API_ENDPOINT_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
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
                    Log.e(
                        TAG,
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
                Log.e(TAG, "Exception during API sync: ${e.message}")
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