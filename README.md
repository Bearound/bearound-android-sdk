# 🐻 BeAround SDKs Documentation

[![Release](https://github.com/Bearound/bearound-android-sdk/actions/workflows/release.yml/badge.svg)](https://github.com/Bearound/bearound-android-sdk/actions/workflows/release.yml)

Official SDKs for integrating BeAround's secure BLE beacon detection and indoor location technology across Android, iOS, React Native, and Flutter.

## 📱 BeAround-android-sdk

Kotlin SDK for Android — secure BLE beacon detection and indoor positioning by BeAround.

## 🧩 Features

- **Continuous region monitoring** for BLE beacons
- **🎉 NEW: Comprehensive Event Listener System** (v1.1.0)
  - `BeaconListener` - Real-time beacon detection callbacks
  - `SyncListener` - API synchronization status monitoring
  - `RegionListener` - Beacon region entry/exit notifications
- **Automatic API synchronization** for beacon enter/exit events
- **Rich beacon data capture**:
  - Distance estimation and RSSI
  - UUID, major, minor identifiers
  - Bluetooth MAC address and name
  - Timestamp and app state (foreground/background/inactive)
  - Google Advertising ID
- **Foreground service** support for background execution
- **Built-in debug logging** with tag BeAroundSdk
- **Privacy-first architecture** with encrypted API communication

---

## ⚙️ Requirements

- **Minimum SDK**: 21 (Android 5.0 Lollipop)
- **Bluetooth LE** must be enabled on the device

### ⚙️ Required Permissions

Add the following to AndroidManifest.xml:

```xml
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```
✅ Important: Starting with Android 10 (API 29) and Android 12 (API 31), location and Bluetooth permissions must be requested at runtime, with clear justification to the user.

### 📦 Installation

[![](https://jitpack.io/v/Bearound/bearound-android-sdk.svg)](https://jitpack.io/#Bearound/bearound-android-sdk)

**Step 1:** Add JitPack repository to your `settings.gradle` (root level):

```gradle
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

**Step 2:** Add the dependency to your app's `build.gradle`:

```gradle
dependencies {
    implementation 'com.github.Bearound:bearound-android-sdk:1.0.4'
}
```

### Initialization
Initialize the SDK inside your Application class after checking the required permissions:

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val beAround = BeAround.getInstance(applicationContext)
        // Check permissions before initializing
        beAround.initialize(
            iconNotification = R.drawable.ic_notification_icon, //icon show notification service
            clientId = "Client Id", //Set Client Id
            debug = true // optional, enable debug logging
        )
    }
}
```
☝️ You must prompt the user for permissions before initializing the SDK — see example below.

### 🔐 Runtime Permissions

You need to manually request permissions from the user, especially:

- ACCESS_FINE_LOCATION (Android 6+)
- ACCESS_BACKGROUND_LOCATION (Android 10+)
- BLUETOOTH_SCAN (Android 12+)

📌 Without these permissions, the SDK will not function properly and will not be able to detect beacons in the background.

### 🎉 Event Listener System (v1.1.0)

The SDK provides three powerful listener interfaces for comprehensive beacon monitoring:

#### 1. BeaconListener - Track Detected Beacons

Receive real-time callbacks when beacons are detected:

```kotlin
import io.bearound.sdk.BeAround
import io.bearound.sdk.BeaconListener
import io.bearound.sdk.BeaconData

class MyActivity : AppCompatActivity(), BeaconListener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val beAround = BeAround.getInstance(applicationContext)
        beAround.addBeaconListener(this)
    }

    override fun onBeaconsDetected(beacons: List<BeaconData>, eventType: String) {
        // Called when beacons are detected
        // eventType: "enter", "exit", or "failed"
        beacons.forEach { beacon ->
            Log.d("BeAround", "Beacon detected: Major ${beacon.major}, Minor ${beacon.minor}")
            Log.d("BeAround", "RSSI: ${beacon.rssi}, Address: ${beacon.bluetoothAddress}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        BeAround.getInstance(applicationContext).removeBeaconListener(this)
    }
}
```

#### 2. SyncListener - Monitor API Synchronization

Track the status of API synchronization operations:

```kotlin
import io.bearound.sdk.SyncListener

class MyActivity : AppCompatActivity(), SyncListener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val beAround = BeAround.getInstance(applicationContext)
        beAround.addSyncListener(this)
    }

    override fun onSyncSuccess(eventType: String, beaconCount: Int, message: String) {
        // Called when sync succeeds
        Log.d("BeAround", "✓ Sync successful: $beaconCount beacons synced")
        Toast.makeText(this, "Beacons synced successfully!", Toast.LENGTH_SHORT).show()
    }

    override fun onSyncError(eventType: String, beaconCount: Int, errorCode: Int?, errorMessage: String) {
        // Called when sync fails
        Log.e("BeAround", "✗ Sync failed: $errorMessage (Code: $errorCode)")
    }

    override fun onDestroy() {
        super.onDestroy()
        BeAround.getInstance(applicationContext).removeSyncListener(this)
    }
}
```

#### 3. RegionListener - Track Region Entry/Exit

Get notified when entering or exiting beacon regions:

```kotlin
import io.bearound.sdk.RegionListener

class MyActivity : AppCompatActivity(), RegionListener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val beAround = BeAround.getInstance(applicationContext)
        beAround.addRegionListener(this)
    }

    override fun onRegionEnter(regionName: String) {
        // Called when entering a beacon region
        Log.d("BeAround", "→ Entered region: $regionName")
        Toast.makeText(this, "Welcome! Entered beacon region", Toast.LENGTH_SHORT).show()
    }

    override fun onRegionExit(regionName: String) {
        // Called when exiting a beacon region
        Log.d("BeAround", "← Exited region: $regionName")
        Toast.makeText(this, "Goodbye! Exited beacon region", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        BeAround.getInstance(applicationContext).removeRegionListener(this)
    }
}
```

#### Combined Usage - All Listeners

You can implement multiple listeners in a single class:

```kotlin
class MainActivity : AppCompatActivity(),
    BeaconListener,
    SyncListener,
    RegionListener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val beAround = BeAround.getInstance(this)
        beAround.initialize(
            iconNotification = R.drawable.ic_notification,
            clientToken = "your-client-token",
            debug = true
        )

        // Add all listeners
        beAround.addBeaconListener(this)
        beAround.addSyncListener(this)
        beAround.addRegionListener(this)
    }

    // BeaconListener
    override fun onBeaconsDetected(beacons: List<BeaconData>, eventType: String) {
        // Handle beacon detection
    }

    // SyncListener
    override fun onSyncSuccess(eventType: String, beaconCount: Int, message: String) {
        // Handle sync success
    }

    override fun onSyncError(eventType: String, beaconCount: Int, errorCode: Int?, errorMessage: String) {
        // Handle sync error
    }

    // RegionListener
    override fun onRegionEnter(regionName: String) {
        // Handle region enter
    }

    override fun onRegionExit(regionName: String) {
        // Handle region exit
    }

    override fun onDestroy() {
        super.onDestroy()
        val beAround = BeAround.getInstance(this)
        beAround.removeBeaconListener(this)
        beAround.removeSyncListener(this)
        beAround.removeRegionListener(this)
    }
}
```

### 📊 BeaconData Model

Each detected beacon contains rich information:

```kotlin
data class BeaconData(
    val uuid: String,              // Beacon UUID
    val major: Int,                // Major identifier
    val minor: Int,                // Minor identifier
    val rssi: Int,                 // Signal strength (RSSI)
    val bluetoothName: String?,    // Bluetooth device name (nullable)
    val bluetoothAddress: String,  // Bluetooth MAC address
    val lastSeen: Long            // Detection timestamp (milliseconds)
)
```

### 💡 Important Notes

- **Thread Safety**: All listener callbacks are executed on background threads (IO dispatcher). Use `runOnUiThread` when updating UI.
- **Memory Leaks**: Always remove listeners in `onDestroy()` to prevent memory leaks.
- **Multiple Listeners**: You can register multiple listeners of the same type.
- **Event Types**: BeaconListener receives events with type: `"enter"`, `"exit"`, or `"failed"`

### ⚠️ Automatic Background Monitoring

After initialization, the SDK automatically:

- Starts a foreground service for continuous monitoring
- Detects beacons with the configured UUID
- Sends enter/exit events to the remote API with AES-GCM encryption
- Triggers registered event listeners in real-time
- Captures telemetry data including Google Advertising ID (if available)

💡 Enable debug mode to see detailed logs with tag `BeAroundSdk`

### 🔐 Security

- AES-GCM encrypted payloads
- Obfuscated beacon identifiers
- Privacy-first architecture

### 🧪 Testing

- Use physical beacons or nRF Connect
- Check logs tagged BeaconScanner
- Ensure runtime permissions are granted

### 📄 License

MIT © Bearound

