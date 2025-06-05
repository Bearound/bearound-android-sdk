package com.example.sdk

import android.app.Application.NOTIFICATION_SERVICE
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.altbeacon.beacon.*
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class BeAround(private val context: Context) : MonitorNotifier {

    private val beaconUUID = "e25b8d3c-947a-452f-a13f-589cb706d2e5"
    private lateinit var region: Region
    private val beaconManager = BeaconManager.getInstanceForApplication(context.applicationContext)
    private var lastSeenBeacon: Beacon? = null
    private var advertisingId: String? = null
    private var advertisingIdFetchAttempted = false
    private var debug: Boolean = false

    companion object {
        private const val TAG = "BeAroundSdk"
        const val API_ENDPOINT_URL = "https://api.bearound.io/ingest"
        const val NOTIFICATION_CHANNEL_ID = "beacon_notifications"
        const val FOREGROUND_SERVICE_NOTIFICATION_ID = 3
    }

    fun initialize(iconNotification: Int, debug: Boolean = false) {
        this.debug = debug
        createNotificationChannel(context)

        beaconManager.beaconParsers.add(
            BeaconParser().setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24")
        )

        val foregroundNotification: Notification =
            NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(iconNotification)
                .setContentTitle("Monitoramento de Beacons")
                .setContentText("Execução contínua em segundo plano")
                .setOngoing(true)
                .build()
        beaconManager.enableForegroundServiceScanning(
            foregroundNotification,
            FOREGROUND_SERVICE_NOTIFICATION_ID
        )

        beaconManager.setEnableScheduledScanJobs(false)
        beaconManager.setRegionStatePersistenceEnabled(false)
        beaconManager.setBackgroundScanPeriod(1100L)
        beaconManager.setBackgroundBetweenScanPeriod(20000L)
        beaconManager.setForegroundBetweenScanPeriod(20000L)

        region = Region(
            "BeAroundSdkRegion", Identifier.parse(beaconUUID), null, null
        )

        beaconManager.addMonitorNotifier(this)
        beaconManager.startMonitoring(region)

        fetchAdvertisingId()
    }

    fun stop() {
        beaconManager.stopMonitoring(region)
        beaconManager.removeAllMonitorNotifiers()
        beaconManager.removeAllRangeNotifiers()
    }

    private fun fetchAdvertisingId() {
        if (advertisingIdFetchAttempted && advertisingId != null) {
            log("Advertising ID já foi obtido: $advertisingId")
            return
        }
        advertisingIdFetchAttempted = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val adInfo = AdvertisingIdClient.getAdvertisingIdInfo(context)
                advertisingId = adInfo.id
                log("Advertising ID obtido com sucesso: $advertisingId")
            } catch (e: Exception) {
                advertisingId = null
                Log.e(TAG, "Erro ao obter Advertising ID: ${e.message}")
            }
        }
    }

    override fun didEnterRegion(region: Region) {
        log("didEnterRegion: Entrou na região ${region.uniqueId}")
        beaconManager.startRangingBeacons(region)
        beaconManager.addRangeNotifier(rangeNotifierForSync)
    }

    override fun didExitRegion(region: Region) {
        log("didExitRegion: Saiu da região ${region.uniqueId}")
        lastSeenBeacon?.let {
            syncWithApi(it, "exit")
        }
        beaconManager.stopRangingBeacons(region)
        beaconManager.removeRangeNotifier(rangeNotifierForSync)
        lastSeenBeacon = null
    }

    override fun didDetermineStateForRegion(state: Int, region: Region) {
        val stateString = if (state == MonitorNotifier.INSIDE) "DENTRO" else "FORA"
        log("didDetermineStateForRegion: Estado ${region.uniqueId} para: $stateString")
    }

    private val rangeNotifierForSync = RangeNotifier { beacons, rangedRegion ->
        log("didRangeBeaconsInRegion: ${beacons.size}," +
                " beacons encontrados na região: ${rangedRegion.uniqueId}")
        if (beacons.isNotEmpty()) {
            //Todo tratar quando tiver multiplos beacons
            val beacon = beacons.first()
            if (beacon.id1.toString() == beaconUUID) {
                log("RangeNotifierForSync: " +
                        "Beacon da nossa região: UUID: ${beacon.id1}, " +
                        "Major: ${beacon.id2}, " +
                        "Minor: ${beacon.id3}, " +
                        "Distância: ${beacon.distance} metros")
                lastSeenBeacon = beacon
                syncWithApi(beacon, "enter")
            }
        }
    }

    private fun syncWithApi(beacon: Beacon, eventType: String) {
        val currentAdvertisingId = advertisingId
        val currentAppState = getAppState()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val jsonObject = JSONObject().apply {
                    put("uuid", beacon.id1)
                    put("major", beacon.id2)
                    put("minor", beacon.id3)
                    put("rssi", beacon.rssi)
                    put("deviceType", "Android")
                    put("idfa", currentAdvertisingId ?: "N/A")
                    put("eventType", eventType)
                    put("appState", currentAppState)
                    put("bluetoothName", beacon.bluetoothName)
                    put("bluetoothAddress",beacon.bluetoothAddress)
                    put("distance", beacon.distance)
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
                    log("Sincronização com API bem-sucedida. " +
                            "Resposta: ${connection.responseMessage}")
                } else {
                    Log.e(
                        TAG,
                        "Falha na sincronização com API. Código: $responseCode, " +
                                "Mensagem: ${connection.responseMessage}"
                    )
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Erro durante a sincronização com API: ${e.message}")
            }
        }
    }

    private fun getAppState(): String {
        val state = ProcessLifecycleOwner.get().lifecycle.currentState
        return when {
            state.isAtLeast(Lifecycle.State.RESUMED) -> "foreground"
            state.isAtLeast(Lifecycle.State.STARTED) -> "background"
            else -> "inactive"
        }
    }

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

    private fun log(message: String) {
        if (debug) Log.d(TAG, message)
    }
}