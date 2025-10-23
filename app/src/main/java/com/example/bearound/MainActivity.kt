package com.example.bearound

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.bearound.sdk.BeAround
import io.bearound.sdk.LogListener

class MainActivity : AppCompatActivity(), LogListener {

    private val logs = mutableListOf<String>()
    private lateinit var beAround: BeAround

    private lateinit var logTextView: TextView
    private lateinit var clearLogsButton: Button

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Usa o layout XML
        setContentView(R.layout.activity_main)

        beAround = BeAround.getInstance(applicationContext)

        logTextView = findViewById(R.id.logTextView)

        clearLogsButton = findViewById<Button>(R.id.clearLogsButton)

        clearLogsButton.setOnClickListener {
            logs.clear()
            logTextView.text = ""
        }

        // Verifica permissões
        if (hasRequiredPermissions()) {
            initializeBeAround()
        } else {
            requestRequiredPermissions()
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val locationGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        // Para Android 12+ (API 31+), também precisamos das permissões de Bluetooth
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val bluetoothScanGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED

            val bluetoothConnectGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

            return locationGranted && bluetoothScanGranted && bluetoothConnectGranted
        }

        return locationGranted
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)

        // Para Android 12+ (API 31+), adiciona permissões de Bluetooth
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        ActivityCompat.requestPermissions(
            this,
            permissions.toTypedArray(),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    private fun initializeBeAround() {
        beAround.initialize(
            iconNotification = R.drawable.ic_launcher_foreground,
            clientId = "",
            debug = true
        )
        beAround.addLogListener(this)
    }

    // Callback da permissão
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                initializeBeAround()
            } else {
                val deniedPermissions = permissions.filterIndexed { index, _ ->
                    grantResults[index] != PackageManager.PERMISSION_GRANTED
                }
                Toast.makeText(
                    this,
                    "Permissões necessárias: ${deniedPermissions.joinToString(", ")}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onLogAdded(log: String) {
        logs.add(log)
        runOnUiThread {
            logTextView.text = logs.joinToString("\n")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Remove o listener para evitar memory leaks
        beAround.removeLogListener(this)
    }
}

