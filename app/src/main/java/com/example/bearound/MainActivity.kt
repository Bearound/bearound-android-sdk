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
import org.bearound.sdk.BeAround

class MainActivity : AppCompatActivity() {

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Usa o layout XML
        setContentView(R.layout.activity_main)

        // Inicializa o SDK
        val beAround = BeAround(applicationContext)

        // Verifica permissão
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            beAround.initialize(
                iconNotification = R.drawable.ic_launcher_foreground,
                clientId = "",
                debug = true
            )
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }

        // Atualiza o texto na tela
        val greetingTextView: TextView = findViewById(R.id.greetingText)
        greetingTextView.text = "Hello Teste SDK Android!"
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
                val beAround = BeAround(applicationContext)
                beAround.initialize(
                    iconNotification = R.drawable.ic_launcher_foreground,
                    clientId = "",
                    debug = true
                )
            } else {
                Toast.makeText(this, "Permissão de localização é necessária.", Toast.LENGTH_LONG).show()
            }
        }
    }
}

