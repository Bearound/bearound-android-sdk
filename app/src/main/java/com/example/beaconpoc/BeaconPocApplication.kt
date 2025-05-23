package com.example.beaconpoc

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
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
import org.altbeacon.beacon.startup.RegionBootstrap
import org.altbeacon.beacon.startup.BootstrapNotifier
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import org.altbeacon.beacon.Identifier

class BeaconPocApplication : Application(), BootstrapNotifier {
    private val beaconUUID = "e25b8d3c-947a-452f-a13f-589cb706d2e5"
    private lateinit var region: Region
    private lateinit var beaconManager: BeaconManager
    private var regionBootstrap: RegionBootstrap? = null

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
        createNotificationChannel()

        // Habilitar escaneamento em serviço de primeiro plano para manter o app ativo em segundo plano
        val foregroundNotification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.foreground_service_title))
            .setContentText(getString(R.string.foreground_service_message))
            .setOngoing(true)
            .build()
        beaconManager.enableForegroundServiceScanning(foregroundNotification, FOREGROUND_SERVICE_NOTIFICATION_ID)
        beaconManager.setEnableScheduledScanJobs(false)
        beaconManager.setBackgroundScanPeriod(10000L)
        beaconManager.setBackgroundBetweenScanPeriod(15000L) //Timer de 15 segundos
        beaconManager.setForegroundScanPeriod(10000L)
        beaconManager.setForegroundBetweenScanPeriod(15000L) //Timer de 15 segundos
        region = Region("BeaconPocRegion", Identifier.parse(beaconUUID), null, null) // Passar o UUID como String diretamente
        regionBootstrap = RegionBootstrap(this, region)

        // BeaconManager.setDebug(true) // Para depuração

        // Iniciar a obtenção do Advertising ID
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel_name)
            val descriptionText = getString(R.string.notification_channel_description)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun didEnterRegion(region: Region) {
        Log.i(TAG, "didEnterRegion: Entrou na região ${region.uniqueId}")
        // Iniciar ranging para obter Major/Minor e então sincronizar
        beaconManager.startRangingBeacons(region)
        beaconManager.addRangeNotifier(rangeNotifierForSync)
    }

    override fun didExitRegion(region: Region) {
        Log.i(TAG, "didExitRegion: Saiu da região ${region.uniqueId}")
        lastSeenBeacon?.let {
            syncWithApi(
                it.id1.toString(),
                it.id2.toString(),
                it.id3.toString(),
                it.rssi,
                "exit"
            )
        }
        beaconManager.stopRangingBeacons(region)
        beaconManager.removeRangeNotifier(rangeNotifierForSync)
        lastSeenBeacon = null
    }

    override fun didDetermineStateForRegion(state: Int, region: Region) {
        val stateString = if (state == BootstrapNotifier.INSIDE) "DENTRO" else "FORA"
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
                    "RangeNotifierForSync: Beacon da nossa região: UUID: ${beacon.id1}, Major: ${beacon.id2}, Minor: ${beacon.id3}, Distância: ${beacon.distance} metros"
                )

                syncWithApi(
                    beacon.id1.toString(),
                    beacon.id2.toString(),
                    beacon.id3.toString(),
                    beacon.rssi,
                    "enter"
                )
            }
        }
    }

    private fun syncWithApi(
        uuid: String,
        major: String,
        minor: String,
        rssi: Int,
        eventType: String,
    ) {
        if (advertisingId == null && !advertisingIdFetchAttempted) {
            Log.i(TAG, "Advertising ID não disponível, tentando buscar antes de sincronizar.")
            //Tenta recuperar Advertising ID
            fetchAdvertisingId()
        }

        val currentAdvertisingId = advertisingId
        val currentAppState = getAppState()

        CoroutineScope(Dispatchers.IO).launch {
            Log.i(
                TAG,
                "Iniciando sincronização com API. UUID: $uuid, Major: $major, Minor: $minor, RSSI: $rssi, AAID: $currentAdvertisingId"
            )
            try {
                val url = URL(API_ENDPOINT_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.doOutput = true
                connection.connectTimeout = 15000 // 15 segundos timeout de conexão
                connection.readTimeout = 15000    // 15 segundos timeout de leitura

                val jsonObject = JSONObject()
                jsonObject.put("uuid", uuid)
                jsonObject.put("major", major)
                jsonObject.put("minor", minor)
                jsonObject.put("rssi", rssi)
                jsonObject.put("deviceType", "Android")
                jsonObject.put("idfa", currentAdvertisingId ?: "N/A")
                jsonObject.put("eventType", eventType)
                jsonObject.put("appState", currentAppState)
                //COMO PEGAR O NOME DO Beacon

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
