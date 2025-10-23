# BeAround SDK - Usage Examples

This document provides examples of how to use the new listener features in the BeAround SDK.

## Overview

The SDK now provides three types of listeners to monitor different events:

1. **BeaconListener** - Receive callbacks when beacons are detected
2. **SyncListener** - Receive callbacks about API sync operations (success/failure)
3. **RegionListener** - Receive callbacks when entering/exiting beacon regions

## Setup

### Initialize the SDK

```kotlin
val beAroundSdk = BeAround.getInstance(context)
beAroundSdk.initialize(
    iconNotification = R.drawable.ic_notification,
    clientToken = "your-client-token",
    debug = true
)
```

## Using BeaconListener

Receive notifications when beacons are detected with their detailed information.

```kotlin
class MainActivity : AppCompatActivity(), BeaconListener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val beAroundSdk = BeAround.getInstance(this)
        beAroundSdk.addBeaconListener(this)
    }

    override fun onBeaconsDetected(beacons: List<BeaconData>, eventType: String) {
        Log.d("BeaconListener", "Event: $eventType, Beacons count: ${beacons.size}")

        beacons.forEach { beacon ->
            Log.d("BeaconListener", """
                UUID: ${beacon.uuid}
                Major: ${beacon.major}
                Minor: ${beacon.minor}
                RSSI: ${beacon.rssi}
                Bluetooth Name: ${beacon.bluetoothName}
                Bluetooth Address: ${beacon.bluetoothAddress}
                Last Seen: ${beacon.lastSeen}
            """.trimIndent())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        BeAround.getInstance(this).removeBeaconListener(this)
    }
}
```

## Using SyncListener

Monitor the status of API synchronization operations.

```kotlin
class MainActivity : AppCompatActivity(), SyncListener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val beAroundSdk = BeAround.getInstance(this)
        beAroundSdk.addSyncListener(this)
    }

    override fun onSyncSuccess(eventType: String, beaconCount: Int, message: String) {
        Log.d("SyncListener", "Sync successful!")
        Log.d("SyncListener", "Event Type: $eventType")
        Log.d("SyncListener", "Beacons synced: $beaconCount")
        Log.d("SyncListener", "Response: $message")

        // Update UI or show success notification
        runOnUiThread {
            Toast.makeText(this, "Synced $beaconCount beacons", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSyncError(eventType: String, beaconCount: Int, errorCode: Int?, errorMessage: String) {
        Log.e("SyncListener", "Sync failed!")
        Log.e("SyncListener", "Event Type: $eventType")
        Log.e("SyncListener", "Beacons count: $beaconCount")
        Log.e("SyncListener", "Error Code: $errorCode")
        Log.e("SyncListener", "Error Message: $errorMessage")

        // Handle error, show retry option, etc.
        runOnUiThread {
            Toast.makeText(this, "Sync error: $errorMessage", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        BeAround.getInstance(this).removeSyncListener(this)
    }
}
```

## Using RegionListener

Get notified when entering or exiting a beacon region.

```kotlin
class MainActivity : AppCompatActivity(), RegionListener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val beAroundSdk = BeAround.getInstance(this)
        beAroundSdk.addRegionListener(this)
    }

    override fun onRegionEnter(regionName: String) {
        Log.d("RegionListener", "Entered region: $regionName")

        // Show notification, trigger action, etc.
        runOnUiThread {
            Toast.makeText(this, "Welcome! Entered beacon region", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRegionExit(regionName: String) {
        Log.d("RegionListener", "Exited region: $regionName")

        // Clean up, save data, etc.
        runOnUiThread {
            Toast.makeText(this, "Goodbye! Exited beacon region", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        BeAround.getInstance(this).removeRegionListener(this)
    }
}
```

## Using Multiple Listeners

You can combine all listeners in a single activity or use separate classes.

```kotlin
class MainActivity : AppCompatActivity(),
    BeaconListener,
    SyncListener,
    RegionListener {

    private lateinit var beAroundSdk: BeAround

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        beAroundSdk = BeAround.getInstance(this)

        // Initialize SDK
        beAroundSdk.initialize(
            iconNotification = R.drawable.ic_notification,
            clientToken = "your-client-token",
            debug = true
        )

        // Add all listeners
        beAroundSdk.addBeaconListener(this)
        beAroundSdk.addSyncListener(this)
        beAroundSdk.addRegionListener(this)
    }

    // BeaconListener
    override fun onBeaconsDetected(beacons: List<BeaconData>, eventType: String) {
        updateBeaconsList(beacons)
    }

    // SyncListener
    override fun onSyncSuccess(eventType: String, beaconCount: Int, message: String) {
        updateSyncStatus("Success: $beaconCount beacons synced")
    }

    override fun onSyncError(eventType: String, beaconCount: Int, errorCode: Int?, errorMessage: String) {
        updateSyncStatus("Error: $errorMessage")
    }

    // RegionListener
    override fun onRegionEnter(regionName: String) {
        updateRegionStatus("Inside region: $regionName")
    }

    override fun onRegionExit(regionName: String) {
        updateRegionStatus("Outside region: $regionName")
    }

    private fun updateBeaconsList(beacons: List<BeaconData>) {
        // Update your UI with beacon data
    }

    private fun updateSyncStatus(status: String) {
        // Update sync status in UI
    }

    private fun updateRegionStatus(status: String) {
        // Update region status in UI
    }

    override fun onDestroy() {
        super.onDestroy()
        beAroundSdk.removeBeaconListener(this)
        beAroundSdk.removeSyncListener(this)
        beAroundSdk.removeRegionListener(this)
    }
}
```

## Using Separate Listener Classes

For better code organization, you can create separate listener implementations:

```kotlin
class MyBeaconListener(private val context: Context) : BeaconListener {
    override fun onBeaconsDetected(beacons: List<BeaconData>, eventType: String) {
        // Handle beacon detection
    }
}

class MySyncListener(private val context: Context) : SyncListener {
    override fun onSyncSuccess(eventType: String, beaconCount: Int, message: String) {
        // Handle sync success
    }

    override fun onSyncError(eventType: String, beaconCount: Int, errorCode: Int?, errorMessage: String) {
        // Handle sync error
    }
}

// In your Activity or Service:
class MainActivity : AppCompatActivity() {
    private lateinit var beaconListener: MyBeaconListener
    private lateinit var syncListener: MySyncListener

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val beAroundSdk = BeAround.getInstance(this)

        beaconListener = MyBeaconListener(this)
        syncListener = MySyncListener(this)

        beAroundSdk.addBeaconListener(beaconListener)
        beAroundSdk.addSyncListener(syncListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        val beAroundSdk = BeAround.getInstance(this)
        beAroundSdk.removeBeaconListener(beaconListener)
        beAroundSdk.removeSyncListener(syncListener)
    }
}
```

## Event Types

The SDK uses the following event types:

- **"enter"** - When beacons are first detected (entering region)
- **"exit"** - When leaving a beacon region
- **"failed"** - When retrying previously failed sync operations

## Notes

- Always remember to remove listeners in `onDestroy()` to prevent memory leaks
- Listeners are called on background threads (IO dispatcher), so use `runOnUiThread` for UI updates
- Multiple listeners of the same type can be registered
- The SDK maintains a list of all registered listeners and notifies them all