package com.example.beaconpoc

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import org.altbeacon.beacon.powersave.BackgroundPowerSaver
import org.altbeacon.beacon.startup.RegionBootstrap
import org.altbeacon.beacon.startup.BootstrapNotifier
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL // Adicionar esta importação
import org.altbeacon.beacon.Identifier

class BeaconPocApplication : Application(), BootstrapNotifier {
    val beaconUUID = "e25b8d3c-947a-452f-a13f-589cb706d2e5" // Tornar público
    private lateinit var region: Region
    lateinit var beaconManager: BeaconManager // Tornar público para acesso da MainActivity
    private var backgroundPowerSaver: BackgroundPowerSaver? = null
    private var regionBootstrap: RegionBootstrap? = null

    var lastSeenBeacon: Beacon? = null
    var advertisingId: String? = null
    var advertisingIdFetchAttempted: Boolean = false

    // Callback para notificar a MainActivity quando o Advertising ID estiver disponível
    var onAdvertisingIdFetched: ((String?) -> Unit)? = null
    var onApiSyncStatusChanged: ((String) -> Unit)? = null

    companion object {
        const val TAG = "BeaconPocApplication"
        const val NOTIFICATION_CHANNEL_ID = "beacon_notifications"
        const val ENTER_NOTIFICATION_ID = 1
        const val EXIT_NOTIFICATION_ID = 2
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
        region = Region("BeaconPocRegion", Identifier.parse(beaconUUID), null, null) // Passar o UUID como String diretamente
        regionBootstrap = RegionBootstrap(this, region)
        backgroundPowerSaver = BackgroundPowerSaver(this)
        // Habilitar escaneamento em serviço de primeiro plano para manter o app ativo em segundo plano
        val foregroundNotification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.foreground_service_title))
            .setContentText(getString(R.string.foreground_service_message))
            .setOngoing(true)
            .build()
        beaconManager.enableForegroundServiceScanning(foregroundNotification, FOREGROUND_SERVICE_NOTIFICATION_ID)
        beaconManager.setEnableScheduledScanJobs(false)
        // BeaconManager.setDebug(true) // Para depuração

        // Iniciar a obtenção do Advertising ID
        fetchAdvertisingId()
    }

    fun fetchAdvertisingId() {
        if (advertisingIdFetchAttempted && advertisingId != null) {
            Log.d(TAG, "Advertising ID já foi obtido: $advertisingId")
            onAdvertisingIdFetched?.invoke(advertisingId)
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
            // Notificar a MainActivity ou qualquer outro listener
            onAdvertisingIdFetched?.invoke(advertisingId)
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
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun didEnterRegion(region: Region) {
        Log.d(TAG, "didEnterRegion: Entrou na região ${region.uniqueId}")
        sendNotification(
            getString(R.string.notification_enter_title),
            getString(R.string.notification_enter_body, region.uniqueId),
            ENTER_NOTIFICATION_ID
        )
        // Iniciar ranging para obter Major/Minor e então sincronizar
        beaconManager.startRangingBeacons(region)
        beaconManager.addRangeNotifier(rangeNotifierForSync)
    }

    override fun didExitRegion(region: Region) {
        Log.d(TAG, "didExitRegion: Saiu da região ${region.uniqueId}")
        sendNotification(
            getString(R.string.notification_exit_title),
            getString(R.string.notification_exit_body, region.uniqueId),
            EXIT_NOTIFICATION_ID
        )
        lastSeenBeacon?.let {
            syncWithApi(it.id1.toString(), it.id2.toString(), it.id3.toString(), "exit")
        }
        beaconManager.stopRangingBeacons(region)
        beaconManager.removeRangeNotifier(rangeNotifierForSync)
        lastSeenBeacon = null
    }

    override fun didDetermineStateForRegion(state: Int, region: Region) {
        val stateString = if (state == BootstrapNotifier.INSIDE) "DENTRO" else "FORA"
        Log.d(TAG, "didDetermineStateForRegion: Estado ${region.uniqueId} para: $stateString")
    }

    private val rangeNotifierForSync = RangeNotifier { beacons, _ -> // region parameter is not used here
        if (beacons.isNotEmpty()) {
            val beacon = beacons.first()
            if (beacon.id1.toString() == beaconUUID) { // Verificar se o UUID corresponde ao esperado
                lastSeenBeacon = beacon
                Log.d(TAG, "RangeNotifierForSync: Beacon detectado - UUID: ${beacon.id1} Major: ${beacon.id2}, Minor: ${beacon.id3}")
                syncWithApi(beacon.id1.toString(), beacon.id2.toString(), beacon.id3.toString(), "enter")
            }
        }
    }

    fun syncWithApi(uuid: String, major: String, minor: String, eventType: String) {
        if (API_ENDPOINT_URL == "https://your.api.endpoint/beacon_data" || API_ENDPOINT_URL.isBlank() || API_ENDPOINT_URL == "YOUR_API_ENDPOINT_HERE") {
            Log.w(TAG, "API Endpoint não configurado ou inválido. Sincronização abortada.")
            onApiSyncStatusChanged?.invoke(getString(R.string.api_sync_failure_config))
            return
        }
        if (advertisingId == null && !advertisingIdFetchAttempted) {
            Log.i(TAG, "Advertising ID não disponível, tentando buscar antes de sincronizar.")
            fetchAdvertisingId()
            // A sincronização será tentada novamente quando o AAID estiver disponível ou a tentativa falhar.
            // Para evitar múltiplas chamadas, a MainActivity pode gerenciar o estado e chamar syncWithApi explicitamente.
            return // Abortar esta tentativa de sincronização, será chamada novamente se necessário.
        }

        val currentAdvertisingId = advertisingId
        val currentAppState = getAppState()
        onApiSyncStatusChanged?.invoke(getString(R.string.api_sync_inprogress))

        CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "Iniciando sincronização com API. UUID: $uuid, Major: $major, Minor: $minor, AAID: $currentAdvertisingId")
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
                jsonObject.put("idfa", currentAdvertisingId ?: "N/A")
                jsonObject.put("eventType", eventType)
                jsonObject.put("appState", currentAppState)

                val outputStreamWriter = OutputStreamWriter(connection.outputStream)
                outputStreamWriter.write(jsonObject.toString())
                outputStreamWriter.flush()
                outputStreamWriter.close()

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                    Log.i(TAG, "Sincronização com API bem-sucedida. Resposta: ${connection.responseMessage}")
                    onApiSyncStatusChanged?.invoke(getString(R.string.api_sync_success))
                } else {
                    Log.e(TAG, "Falha na sincronização com API. Código: $responseCode, Mensagem: ${connection.responseMessage}")
                    onApiSyncStatusChanged?.invoke(getString(R.string.api_sync_failure))
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Erro durante a sincronização com API: ${e.message}", e)
                onApiSyncStatusChanged?.invoke(getString(R.string.api_sync_failure))
            }
        }
    }

    private fun sendNotification(title: String, message: String, notificationId: Int) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, notificationId, intent, pendingIntentFlags)

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Certifique-se que este drawable existe
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        notificationManager.notify(notificationId, builder.build())
        Log.d(TAG, "Notificação enviada: ID=$notificationId, Título=\"$title\"")
    }
}
