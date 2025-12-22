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
import io.bearound.sdk.BeaconData
import io.bearound.sdk.BeaconListener
import io.bearound.sdk.LogListener
import io.bearound.sdk.RegionListener
import io.bearound.sdk.SyncListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(),
    LogListener,
    BeaconListener,
    SyncListener,
    RegionListener {

    private val logs = mutableListOf<String>()
    private lateinit var beAround: BeAround

    // UI Components
    private lateinit var logTextView: TextView
    private lateinit var clearLogsButton: Button
    private lateinit var regionStatusTextView: TextView
    private lateinit var beaconCountTextView: TextView
    private lateinit var beaconsTextView: TextView
    private lateinit var syncStatusTextView: TextView
    private lateinit var scanIntervalButton: Button
    private lateinit var scanIntervalTextView: TextView

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private var currentScanIntervalIndex = 3 // Default TIME_20
    private val scanIntervals = BeAround.TimeScanBeacons.values()

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        beAround = BeAround.getInstance(applicationContext)

        // Initialize UI components
        logTextView = findViewById(R.id.logTextView)
        clearLogsButton = findViewById(R.id.clearLogsButton)
        regionStatusTextView = findViewById(R.id.regionStatusTextView)
        beaconCountTextView = findViewById(R.id.beaconCountTextView)
        beaconsTextView = findViewById(R.id.beaconsTextView)
        syncStatusTextView = findViewById(R.id.syncStatusTextView)
        scanIntervalButton = findViewById(R.id.scanIntervalButton)
        scanIntervalTextView = findViewById(R.id.scanIntervalTextView)

        // Update initial scan interval display
        updateScanIntervalDisplay()

        clearLogsButton.setOnClickListener {
            logs.clear()
            logTextView.text = "Logs cleared"
        }

        scanIntervalButton.setOnClickListener {
            currentScanIntervalIndex = (currentScanIntervalIndex + 1) % scanIntervals.size
            val newInterval = scanIntervals[currentScanIntervalIndex]
            beAround.setSyncInterval(newInterval)
            updateScanIntervalDisplay()
            addLog("Scan interval changed to ${newInterval.name} (${newInterval.seconds / 1000}s)")
            Toast.makeText(this, "Scan interval: ${newInterval.seconds / 1000}s", Toast.LENGTH_SHORT).show()
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
            clientToken = "",
            debug = true
        )

        // Add all listeners
        beAround.addLogListener(this)
        beAround.addBeaconListener(this)
        beAround.addSyncListener(this)
        beAround.addRegionListener(this)

        addLog("SDK initialized successfully")
    }

    private fun updateScanIntervalDisplay() {
        val currentInterval = scanIntervals[currentScanIntervalIndex]
        scanIntervalTextView.text = "Current: ${currentInterval.name}\n(${currentInterval.seconds / 1000}s)"
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

    // LogListener
    override fun onLogAdded(log: String) {
        addLog(log)
    }

    // BeaconListener
    override fun onBeaconsDetected(beacons: List<BeaconData>, eventType: String) {
        addLog("Beacons detected - Event: $eventType, Count: ${beacons.size}")

        runOnUiThread {
            beaconCountTextView.text = "Count: ${beacons.size} (Event: $eventType)"

            val beaconsText = beacons.joinToString("\n\n") { beacon ->
                """
                Major: ${beacon.major} | Minor: ${beacon.minor}
                RSSI: ${beacon.rssi} dBm
                BT: ${beacon.bluetoothName ?: "N/A"}
                Address: ${beacon.bluetoothAddress}
                Time: ${dateFormat.format(Date(beacon.lastSeen))}
                """.trimIndent()
            }

            beaconsTextView.text = if (beacons.isEmpty()) {
                "No beacons detected yet"
            } else {
                beaconsText
            }
        }
    }

    // SyncListener
    override fun onSyncSuccess(eventType: String, beaconCount: Int, message: String) {
        addLog("✓ Sync SUCCESS - Event: $eventType, Count: $beaconCount")

        runOnUiThread {
            syncStatusTextView.text = "✓ Success (${dateFormat.format(Date())})\n" +
                    "Event: $eventType\n" +
                    "Beacons: $beaconCount\n" +
                    "Response: $message"
            syncStatusTextView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        }
    }

    override fun onSyncError(eventType: String, beaconCount: Int, errorCode: Int?, errorMessage: String) {
        addLog("✗ Sync ERROR - Event: $eventType, Code: $errorCode, Message: $errorMessage")

        runOnUiThread {
            syncStatusTextView.text = "✗ Error (${dateFormat.format(Date())})\n" +
                    "Event: $eventType\n" +
                    "Beacons: $beaconCount\n" +
                    "Error Code: ${errorCode ?: "N/A"}\n" +
                    "Message: $errorMessage"
            syncStatusTextView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }
    }

    // RegionListener
    override fun onRegionEnter(regionName: String) {
        addLog("→ ENTERED region: $regionName")

        runOnUiThread {
            regionStatusTextView.text = "✓ Inside Region\n$regionName\nEntered at: ${dateFormat.format(Date())}"
            regionStatusTextView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))

            Toast.makeText(this, "Entered beacon region!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRegionExit(regionName: String) {
        addLog("← EXITED region: $regionName")

        runOnUiThread {
            regionStatusTextView.text = "○ Outside Region\n$regionName\nExited at: ${dateFormat.format(Date())}"
            regionStatusTextView.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))

            Toast.makeText(this, "Exited beacon region", Toast.LENGTH_SHORT).show()
        }
    }

    // Helper method to add logs with timestamp
    private fun addLog(message: String) {
        val timestamp = dateFormat.format(Date())
        val logEntry = "[$timestamp] $message"
        logs.add(logEntry)

        // Keep only last 50 logs
        if (logs.size > 50) {
            logs.removeAt(0)
        }

        runOnUiThread {
            logTextView.text = logs.takeLast(20).joinToString("\n")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Remove all listeners to prevent memory leaks
        beAround.removeLogListener(this)
        beAround.removeBeaconListener(this)
        beAround.removeSyncListener(this)
        beAround.removeRegionListener(this)
    }
}

