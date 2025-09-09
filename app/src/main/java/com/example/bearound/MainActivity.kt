package com.example.bearound

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.bearound.sdk.BeAround
import org.bearound.sdk.LogListener

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

        // Verifica permissão
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            beAround.initialize(
                iconNotification = R.drawable.ic_launcher_foreground,
                clientId = "",
                debug = true
            )
            beAround.addLogListener(this) // Registrar o listener
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    // Callback da permissão
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                beAround.initialize(
                    iconNotification = R.drawable.ic_launcher_foreground,
                    clientId = "",
                    debug = true
                )
                beAround.addLogListener(this) // Registrar o listener
            } else {
                Toast.makeText(this, "Permissão de localização é necessária.", Toast.LENGTH_LONG).show()
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

