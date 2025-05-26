package com.example.beaconpoc

import android.app.Application
import android.app.Notification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import org.altbeacon.beacon.Beacon
import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.BeaconParser
import org.altbeacon.beacon.RangeNotifier
import org.altbeacon.beacon.Region
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import org.altbeacon.beacon.Identifier
import org.altbeacon.beacon.MonitorNotifier

class BeaconPocApplication : Application(), MonitorNotifier {
    private val beaconUUID = "e25b8d3c-947a-452f-a13f-589cb706d2e5"
    private lateinit var region: Region
    private lateinit var beaconManager: BeaconManager

    private var lastSeenBeacon: Beacon? = null
    private var advertisingId: String? = null
    private var advertisingIdFetchAttempted: Boolean = false

    companion object {
        const val TAG = "BeaconPocApplication"
        const val NOTIFICATION_CHANNEL_ID = "beacon_notifications"
        const val FOREGROUND_SERVICE_NOTIFICATION_ID = 3
        // Substituir pelo endpoint real da sua API
        const val API_ENDPOINT_URL = "https://api.bearound.io/ingest" // Exemplo de URL. Mude para seu endpoint!
    }

    private fun getAppState(): String {
        val state = ProcessLifecycleOwner.get().lifecycle.currentState
        return when {
            state.isAtLeast(Lifecycle.State.RESUMED) -> "foreground"
            state.isAtLeast(Lifecycle.State.STARTED) -> "background"
            else -> "inactive"
        }
    }

    override fun onCreate() {
        super.onCreate()
        beaconManager = BeaconManager.getInstanceForApplication(this)
        beaconManager.beaconParsers.add(BeaconParser().setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24"))

        val foregroundNotification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.foreground_service_title))
            .setContentText(getString(R.string.foreground_service_message))
            .setOngoing(true)
            .build()
        beaconManager.enableForegroundServiceScanning(foregroundNotification, FOREGROUND_SERVICE_NOTIFICATION_ID)
        beaconManager.setEnableScheduledScanJobs(false)
        beaconManager.setBackgroundScanPeriod(1100L)
        beaconManager.setBackgroundBetweenScanPeriod(20000L)
        beaconManager.setForegroundBetweenScanPeriod(20000L)
        region = Region(
            "BeaconPocRegion",
            Identifier.parse(beaconUUID),
            null,
            null
        )
        beaconManager.addMonitorNotifier(this)
        beaconManager.startMonitoring(region)

        fetchAdvertisingId()
    }

    private fun fetchAdvertisingId() {
        if (advertisingIdFetchAttempted && advertisingId != null) {
            Log.i(TAG, "Advertising ID já foi obtido: $advertisingId")
            return
        }
        advertisingIdFetchAttempted = true // Marcar que a tentativa foi feita
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val adInfo = AdvertisingIdClient.getAdvertisingIdInfo(applicationContext)
                advertisingId = adInfo.id
                Log.i(TAG, "Advertising ID obtido com sucesso: $advertisingId")
            } catch (e: Exception) {
                advertisingId = null // Garantir que seja nulo em caso de falha
                Log.e(TAG, "Falha ao obter Advertising ID: ${e.message}", e)
            }
        }
    }

    override fun didEnterRegion(region: Region) {
        Log.i(TAG, "didEnterRegion: Entrou na região ${region.uniqueId}")
        beaconManager.startRangingBeacons(region)
        beaconManager.addRangeNotifier(rangeNotifierForSync)
    }

    override fun didExitRegion(region: Region) {
        Log.i(TAG, "didExitRegion: Saiu da região ${region.uniqueId}")
        lastSeenBeacon?.let {
            syncWithApi(
                it,
                "exit"
            )
        }
        beaconManager.stopRangingBeacons(region)
        beaconManager.removeRangeNotifier(rangeNotifierForSync)
        lastSeenBeacon = null
    }

    override fun didDetermineStateForRegion(state: Int, region: Region) {
        val stateString = if (state == MonitorNotifier.INSIDE) "DENTRO" else "FORA"
        Log.i(TAG, "didDetermineStateForRegion: Estado ${region.uniqueId} para: $stateString")
    }

    private val rangeNotifierForSync = RangeNotifier { beacons, rangedRegion ->
        Log.i(TAG, "didRangeBeaconsInRegion: ${beacons.size} beacons encontrados na região: ${rangedRegion.uniqueId}")
        if (beacons.isNotEmpty()) {
            val beacon = beacons.first()

            if (beacon.id1.toString() == beaconUUID) { // Verificar se o UUID corresponde ao esperado
                lastSeenBeacon = beacon
                Log.i(
                    TAG,
                    "RangeNotifierForSync: " +
                            "Beacon da nossa região: UUID: ${beacon.id1}, " +
                            "Major: ${beacon.id2}, " +
                            "Minor: ${beacon.id3}, " +
                            "Distância: ${beacon.distance} metros"
                )
                syncWithApi(
                    beacon,
                    "enter"
                )
            }
        }
    }


    private fun syncWithApi(
        beacon: Beacon,
        eventType: String
    ) {
        if (advertisingId == null && !advertisingIdFetchAttempted) {
            Log.i(TAG, "Advertising ID não disponível, tentando buscar antes de sincronizar.")
            fetchAdvertisingId()
        }

        val currentAdvertisingId = advertisingId
        val currentAppState = getAppState()

        CoroutineScope(Dispatchers.IO).launch {
            Log.i(
                TAG,
                "Iniciando sincronização com API." +
                        "UUID: ${beacon.id1}, " +
                        "Major: ${beacon.id2}," +
                        "Minor: ${beacon.id3}, " +
                        "RSSI: ${beacon.rssi}, " +
                        "idfa: ${currentAdvertisingId}, " +
                        "eventType: ${eventType}, " +
                        "appState: ${currentAppState}, " +
                        "bluetoothName: ${beacon.bluetoothName}, " +
                        "bluetoothAddress: ${beacon.bluetoothAddress}, " +
                        "distance: ${beacon.distance}, " +
                        "packetCountCycle: ${beacon.packetCount}, " +
                        "firstCycleDetectionTimestamp: ${beacon.firstCycleDetectionTimestamp}, " +
                        "lastCycleDetectionTimestamp: ${beacon.lastCycleDetectionTimestamp}, " +
                        "txPower: ${beacon.txPower}, " +
                        "AAID: $currentAdvertisingId"
            )
            try {
                val url = URL(API_ENDPOINT_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.doOutput = true
                connection.connectTimeout = 15000 // 15 segundos timeout de conexão
                connection.readTimeout = 15000    // 15 segundos timeout de leitura

                beacon.firstCycleDetectionTimestamp
                beacon.lastCycleDetectionTimestamp
                beacon.txPower

                val jsonObject = JSONObject()
                jsonObject.put("uuid", beacon.id1)
                jsonObject.put("major", beacon.id2)
                jsonObject.put("minor", beacon.id3)
                jsonObject.put("rssi", beacon.rssi)
                jsonObject.put("deviceType", "Android")
                jsonObject.put("idfa", currentAdvertisingId ?: "N/A")
                jsonObject.put("eventType", eventType)
                jsonObject.put("appState", currentAppState)
                jsonObject.put("bluetoothName", beacon.bluetoothName)
                jsonObject.put("bluetoothAddress", beacon.bluetoothAddress)
                jsonObject.put("distance", beacon.distance)
                jsonObject.put("packetCountCycle", beacon.packetCount)
                jsonObject.put("firstCycleDetectionTimestamp", beacon.firstCycleDetectionTimestamp)
                jsonObject.put("lastCycleDetectionTimestamp", beacon.lastCycleDetectionTimestamp)
                jsonObject.put("txPower", beacon.txPower)

                val outputStreamWriter = OutputStreamWriter(connection.outputStream)
                outputStreamWriter.write(jsonObject.toString())
                outputStreamWriter.flush()
                outputStreamWriter.close()

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                    Log.i(TAG, "Sincronização com API bem-sucedida. Resposta: ${connection.responseMessage}")
                } else {
                    Log.e(TAG, "Falha na sincronização com API. Código: $responseCode, Mensagem: ${connection.responseMessage}")
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Erro durante a sincronização com API: ${e.message}", e)
            }
        }
    }
}
