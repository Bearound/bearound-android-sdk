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
import org.altbeacon.beacon.Identifier

class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var uuidTextView: TextView
    private lateinit var majorTextView: TextView
    private lateinit var minorTextView: TextView
    private lateinit var advertisingIdTextView: TextView
    private lateinit var apiSyncStatusTextView: TextView
    private lateinit var requestPermissionButton: Button

    companion object {
        private const val PERMISSION_REQUEST_FINE_LOCATION = 1
        private const val PERMISSION_REQUEST_BACKGROUND_LOCATION = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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

        // Atualizar UI com valores iniciais da Application class
        runOnUiThread {
            // O status da API pode ser inicializado com um valor padrão ou o último conhecido
            apiSyncStatusTextView.text = getString(R.string.not_available_placeholder)
        }

        if (arePermissionsGranted()) {
            statusTextView.text = getString(R.string.permissions_granted)
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
            }
        } else {
            statusTextView.text = getString(R.string.permissions_granted)
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
//                    Log.d(TAG, "Permissões principais (Fine Location / Bluetooth Scan) concedidas")
                    checkAndRequestBackgroundLocationPermission()
                } else {
//                    Log.e(TAG, "Permissões principais negadas")
                    statusTextView.text = getString(R.string.permissions_needed_message)
                }
            }
            PERMISSION_REQUEST_BACKGROUND_LOCATION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    statusTextView.text = getString(R.string.permissions_granted)
                } else {
                    statusTextView.text = getString(R.string.background_location_denied_message)
                }
            }
        }
    }
}

