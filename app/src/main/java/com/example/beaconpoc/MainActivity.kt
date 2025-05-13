package com.example.beaconpoc

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.altbeacon.beacon.Beacon
import org.altbeacon.beacon.BeaconManager
// BeaconParser não é mais necessário aqui se configurado globalmente na Application
import org.altbeacon.beacon.MonitorNotifier
import org.altbeacon.beacon.RangeNotifier
import org.altbeacon.beacon.Region // Adicionar esta importação que estava faltando

class MainActivity : AppCompatActivity() {

    private lateinit var beaconManager: BeaconManager
    private lateinit var region: Region
    // UUID é gerenciado pela Application class, mas podemos usá-lo para criar a Region aqui se necessário
    // private val beaconUUID = "e25b8d3c-947a-452f-a13f-589cb706d2e5"

    private lateinit var statusTextView: TextView
    private lateinit var uuidTextView: TextView
    private lateinit var majorTextView: TextView
    private lateinit var minorTextView: TextView
    private lateinit var advertisingIdTextView: TextView
    private lateinit var apiSyncStatusTextView: TextView
    private lateinit var requestPermissionButton: Button

    private var beaconPocApplication: BeaconPocApplication? = null

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_FINE_LOCATION = 1
        private const val PERMISSION_REQUEST_BACKGROUND_LOCATION = 2
        // BLUETOOTH_SCAN e CONNECT são para Android 12+ (API 31+)
        // No AndroidManifest, BLUETOOTH e BLUETOOTH_ADMIN são para < API 31
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        beaconPocApplication = application as? BeaconPocApplication

        statusTextView = findViewById(R.id.statusTextView)
        uuidTextView = findViewById(R.id.uuidTextView)
        majorTextView = findViewById(R.id.majorTextView)
        minorTextView = findViewById(R.id.minorTextView)
        advertisingIdTextView = findViewById(R.id.advertisingIdTextView)
        apiSyncStatusTextView = findViewById(R.id.apiSyncStatusTextView)
        requestPermissionButton = findViewById(R.id.requestPermissionButton)

        requestPermissionButton.setOnClickListener {
            checkAndRequestPermissions()
        }

        // A instância do BeaconManager é obtida da Application class
        beaconManager = beaconPocApplication?.beaconManager ?: BeaconManager.getInstanceForApplication(this)

        // A região também pode ser obtida da Application class ou recriada aqui
        // Para consistência, usamos o UUID da Application class
        val appBeaconUUID = (application as BeaconPocApplication).beaconUUID // Acessar o UUID da Application
        region = Region("BeaconPocRegionMainActivity", appBeaconUUID, null, null) // Passar o UUID como String diretamente

        // Configurar callbacks da Application class
        beaconPocApplication?.onAdvertisingIdFetched = {
            runOnUiThread {
                advertisingIdTextView.text = it ?: getString(R.string.not_available_placeholder)
                Log.d(TAG, "Advertising ID atualizado na UI: $it")
            }
        }
        beaconPocApplication?.onApiSyncStatusChanged = {
            runOnUiThread {
                apiSyncStatusTextView.text = it
                Log.d(TAG, "Status da API Sync atualizado na UI: $it")
            }
        }

        // Atualizar UI com valores iniciais da Application class
        runOnUiThread {
            advertisingIdTextView.text = beaconPocApplication?.advertisingId ?: getString(R.string.not_available_placeholder)
            // O status da API pode ser inicializado com um valor padrão ou o último conhecido
            apiSyncStatusTextView.text = getString(R.string.not_available_placeholder)
        }

        if (arePermissionsGranted()) {
            statusTextView.text = getString(R.string.permissions_granted)
            startBeaconScanning()
        } else {
            statusTextView.text = getString(R.string.permissions_needed_message)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            // BLUETOOTH_CONNECT pode ser necessário para algumas operações, mas não para scan simples.
            // if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            // permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            // }
        }
        // Para Android < 12, BLUETOOTH e BLUETOOTH_ADMIN são declaradas no Manifest e geralmente concedidas na instalação.

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_FINE_LOCATION)
        } else {
            checkAndRequestBackgroundLocationPermission()
        }
    }

    private fun checkAndRequestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // Android 10+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    PERMISSION_REQUEST_BACKGROUND_LOCATION
                )
            } else {
                statusTextView.text = getString(R.string.permissions_granted)
                startBeaconScanning()
            }
        } else {
            statusTextView.text = getString(R.string.permissions_granted)
            startBeaconScanning() // Em versões anteriores, ACCESS_FINE_LOCATION já cobre o background
        }
    }

    private fun arePermissionsGranted(): Boolean {
        val fineLocationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        var bluetoothScanGranted = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothScanGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        }
        // Não verificar ACCESS_BACKGROUND_LOCATION aqui, pois é solicitado separadamente e é opcional para a funcionalidade básica de foreground.
        return fineLocationGranted && bluetoothScanGranted
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_FINE_LOCATION -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    Log.d(TAG, "Permissões principais (Fine Location / Bluetooth Scan) concedidas")
                    checkAndRequestBackgroundLocationPermission()
                } else {
                    Log.e(TAG, "Permissões principais negadas")
                    statusTextView.text = getString(R.string.permissions_needed_message)
                }
            }
            PERMISSION_REQUEST_BACKGROUND_LOCATION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Permissão de localização em background concedida")
                    statusTextView.text = getString(R.string.permissions_granted)
                } else {
                    Log.e(TAG, "Permissão de localização em background negada")
                    statusTextView.text = getString(R.string.background_location_denied_message)
                }
                // Iniciar a varredura independentemente da permissão de background, se as de foreground foram concedidas
                if (arePermissionsGranted()) {
                   startBeaconScanning()
                }
            }
        }
    }

    private val rangeNotifier = RangeNotifier { beacons, rangedRegion ->
        Log.d(TAG, "didRangeBeaconsInRegion: ${beacons.size} beacons encontrados na região: ${rangedRegion.uniqueId}")
        if (beacons.isNotEmpty()) {
            val firstBeacon = beacons.first()
            // Verificar se o beacon detectado corresponde à nossa região de interesse (UUID)
            if (rangedRegion.uniqueId == region.uniqueId) {
                Log.i(TAG, "Beacon da nossa região: UUID: ${firstBeacon.id1}, Major: ${firstBeacon.id2}, Minor: ${firstBeacon.id3}, Distância: ${firstBeacon.distance} metros")
                runOnUiThread {
                    statusTextView.text = getString(R.string.beacon_detected_status)
                    uuidTextView.text = "${firstBeacon.id1}"
                    majorTextView.text = "${firstBeacon.id2}"
                    minorTextView.text = "${firstBeacon.id3}"
                }
                // A sincronização com a API é gerenciada pela Application class quando entra na região e faz ranging.
                // Se quisermos forçar uma sincronização aqui, podemos chamar:
                // beaconPocApplication?.syncWithApi(firstBeacon.id1.toString(), firstBeacon.id2.toString(), firstBeacon.id3.toString())
            }
        } else {
            // Não limpar os campos imediatamente, pois o ranging pode não detectar em todos os ciclos.
            // A lógica de "saiu da região" no MonitorNotifier é mais apropriada para limpar.
        }
    }

    private val monitorNotifier = object : MonitorNotifier {
        override fun didEnterRegion(notificationRegion: Region?) {
            Log.i(TAG, "MonitorNotifier: Entrou na região: ${notificationRegion?.uniqueId}")
            runOnUiThread {
                statusTextView.text = getString(R.string.entered_beacon_region_status, notificationRegion?.uniqueId ?: "")
                // O ranging será iniciado pela Application class (BootstrapNotifier)
                // ou podemos iniciar aqui se a Application class não o fizer.
            }
        }

        override fun didExitRegion(notificationRegion: Region?) {
            Log.i(TAG, "MonitorNotifier: Saiu da região: ${notificationRegion?.uniqueId}")
            runOnUiThread {
                statusTextView.text = getString(R.string.exited_beacon_region_status, notificationRegion?.uniqueId ?: "")
                uuidTextView.text = getString(R.string.not_available_placeholder)
                majorTextView.text = getString(R.string.not_available_placeholder)
                minorTextView.text = getString(R.string.not_available_placeholder)
            }
        }

        override fun didDetermineStateForRegion(state: Int, notificationRegion: Region?) {
            val stateString = if (state == MonitorNotifier.INSIDE) "DENTRO" else "FORA"
            Log.i(TAG, "MonitorNotifier: Estado da região ${notificationRegion?.uniqueId} mudou para: $stateString")
        }
    }

    private fun startBeaconScanning() {
        if (!arePermissionsGranted()) {
            Log.w(TAG, "Tentativa de iniciar varredura sem permissões suficientes.")
            statusTextView.text = getString(R.string.permissions_needed_message)
            return
        }
        statusTextView.text = getString(R.string.looking_for_beacons_status)
        Log.d(TAG, "Iniciando varredura de beacons (ranging e monitoring) para a região: ${region.uniqueId}")

        beaconManager.addRangeNotifier(rangeNotifier)
        beaconManager.startRangingBeacons(region)

        beaconManager.addMonitorNotifier(monitorNotifier)
        // O monitoramento via RegionBootstrap já está ativo na Application class.
        // Se quiséssemos controle apenas na Activity, faríamos:
        // beaconManager.startMonitoring(region)
        Log.d(TAG, "RangeNotifier e MonitorNotifier adicionados.")
    }

    override fun onResume() {
        super.onResume()
        // Tentar buscar o AAID novamente caso não tenha sido obtido antes e a activity esteja visível
        if (beaconPocApplication?.advertisingId == null && beaconPocApplication?.advertisingIdFetchAttempted == false) {
            beaconPocApplication?.fetchAdvertisingId()
        }
        // Atualizar a UI com o último beacon visto, se houver
        beaconPocApplication?.lastSeenBeacon?.let {
            if (it.id1.toString() == region.id1.toString()) { // Verificar se o UUID corresponde
                 uuidTextView.text = "${it.id1}"
                 majorTextView.text = "${it.id2}"
                 minorTextView.text = "${it.id3}"
                 statusTextView.text = getString(R.string.beacon_detected_status)
            }
        }
        // Garantir que o scan comece se as permissões estiverem ok
        if (arePermissionsGranted()) {
            startBeaconScanning()
        }
    }

    override fun onPause() {
        super.onPause()
        // Parar o ranging da Activity para economizar bateria quando não estiver visível.
        // O monitoramento em background (BootstrapNotifier) continuará ativo.
        Log.d(TAG, "Pausando ranging de beacons na onPause da Activity")
        beaconManager.stopRangingBeacons(region)
        beaconManager.removeRangeNotifier(rangeNotifier)
        // Não remover o MonitorNotifier se quisermos que a Application class continue a usá-lo ou se ele for compartilhado.
        // Se o MonitorNotifier for específico da Activity, podemos removê-lo:
        // beaconManager.removeMonitorNotifier(monitorNotifier)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Limpar callbacks para evitar memory leaks
        beaconPocApplication?.onAdvertisingIdFetched = null
        beaconPocApplication?.onApiSyncStatusChanged = null
        Log.d(TAG, "MainActivity destruída.")
        // O BeaconManager e o monitoramento de background são gerenciados pela Application class,
        // então não precisamos pará-los aqui explicitamente, a menos que seja um requisito específico.
    }
}

