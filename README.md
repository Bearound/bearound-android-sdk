# 🐻 BeAround SDKs Documentation

[![Release](https://github.com/Bearound/bearound-android-sdk/actions/workflows/release.yml/badge.svg)](https://github.com/Bearound/bearound-android-sdk/actions/workflows/release.yml)
[![JitPack](https://jitpack.io/v/Bearound/bearound-android-sdk.svg)](https://jitpack.io/#Bearound/bearound-android-sdk)
[![GitHub Release](https://img.shields.io/github/v/release/Bearound/bearound-android-sdk?label=Latest%20Release)](https://github.com/Bearound/bearound-android-sdk/releases)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Official SDKs for integrating BeAround's secure BLE beacon detection and indoor location technology across Android, iOS, React Native, and Flutter.

## 📱 BeAround-android-sdk

Kotlin SDK for Android — secure BLE beacon detection and indoor positioning by BeAround.

## 🧩 Features

- **Continuous region monitoring** for BLE beacons
- **🎉 NEW: Comprehensive Event Listener System** (v1.0.4)
  - `BeaconListener` - Real-time beacon detection callbacks
  - `SyncListener` - API synchronization status monitoring
  - `RegionListener` - Beacon region entry/exit notifications
- **⚙️ NEW: Configurable Scan Intervals** (v1.3.1)
  - Customizable beacon scan frequency from 5 to 60 seconds
  - Balance between battery life and detection speed
  - Configurable failed beacon backup list size
- **🔍 Smart Beacon Filtering** (v1.3.1)
  - Automatically filters invalid beacons (RSSI = 0)
  - Improved data quality and reduced unnecessary API calls
- **Automatic API synchronization** for beacon enter/exit events
- **Rich beacon data capture**:
  - Distance estimation and RSSI
  - UUID, major, minor identifiers
  - Bluetooth MAC address and name
  - Timestamp and app state (foreground/background/inactive)
  - Google Advertising ID
- **Foreground service** support for background execution
- **Built-in debug logging** with tag BeAroundSdk
  - Debug mode controls all logs (info and error)
  - Listeners receive all log events regardless of debug mode
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
    implementation 'com.github.Bearound:bearound-android-sdk:1.3.1'
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

---

## ⚙️ Configuration Options (v1.3.1)

### 🕐 Configurable Scan Interval

You can customize the beacon scan interval to balance between battery consumption and detection speed. The SDK supports intervals from **5 to 60 seconds**.

#### Configuration Timing:

- **`setBackupSize()`**: ⚠️ Must be called **before** `initialize()`
- **`setSyncInterval()`**: ✅ Can be called **before or after** `initialize()` for dynamic configuration

#### Example - Initial Configuration:

```kotlin
import io.bearound.sdk.BeAround

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        val beAround = BeAround.getInstance(this)
        
        // Set backup size (must be before initialize)
        beAround.setBackupSize(BeAround.SizeBackupLostBeacons.SIZE_20)
        
        // Set initial scan interval (optional - can also be changed later)
        beAround.setSyncInterval(BeAround.TimeScanBeacons.TIME_30)
        
        // NOW initialize the SDK
        beAround.initialize(
            iconNotification = R.drawable.ic_notification,
            clientToken = "your-client-token",
            debug = true
        )
    }
}
```

#### Example - Dynamic Scan Interval Changes:

```kotlin
class MainActivity : AppCompatActivity() {
    
    private lateinit var beAround: BeAround
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        beAround = BeAround.getInstance(this)
        
        // Change scan interval based on battery level
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        
        when {
            batteryLevel < 20 -> {
                // Low battery: reduce scan frequency
                beAround.setSyncInterval(BeAround.TimeScanBeacons.TIME_60)
            }
            batteryLevel > 80 -> {
                // High battery: increase scan frequency
                beAround.setSyncInterval(BeAround.TimeScanBeacons.TIME_10)
            }
            else -> {
                // Normal battery: balanced scan frequency
                beAround.setSyncInterval(BeAround.TimeScanBeacons.TIME_30)
            }
        }
    }
}
```

**Alternative Methods (Deprecated):**
- `changeScanTimeBeacons()` → Use `setSyncInterval()` instead
- `changeListSizeBackupLostBeacons()` → Use `setBackupSize()` instead

#### Available Scan Intervals

| Option | Interval | Use Case |
|--------|----------|----------|
| `TIME_5` | 5 seconds | High-frequency detection (⚡ higher battery usage) |
| `TIME_10` | 10 seconds | Frequent updates with moderate battery impact |
| `TIME_15` | 15 seconds | Balanced approach |
| `TIME_20` | 20 seconds | ⭐ **Default** - Good balance between accuracy and battery |
| `TIME_25` | 25 seconds | Slightly relaxed monitoring |
| `TIME_30` | 30 seconds | Less frequent updates |
| `TIME_35` | 35 seconds | Power-saving mode |
| `TIME_40` | 40 seconds | Extended battery life |
| `TIME_45` | 45 seconds | Minimal battery impact |
| `TIME_50` | 50 seconds | Very relaxed monitoring |
| `TIME_55` | 55 seconds | Maximum battery savings |
| `TIME_60` | 60 seconds | Minimal scan frequency |

#### Backup List Size Options

Control how many failed beacon detections are stored for retry:

- `SIZE_5` to `SIZE_50` (default: `SIZE_40`)
- Higher values = more failed beacons stored but increased memory usage
- ⚠️ Must be configured **before** `initialize()`

**Example - Battery-Optimized Configuration:**
```kotlin
// Configure before initialization
beAround.setSyncInterval(BeAround.TimeScanBeacons.TIME_45)
beAround.setBackupSize(BeAround.SizeBackupLostBeacons.SIZE_10)
beAround.initialize(...)
```

**Example - High-Performance Configuration:**
```kotlin
// Configure before initialization
beAround.setSyncInterval(BeAround.TimeScanBeacons.TIME_5)
beAround.setBackupSize(BeAround.SizeBackupLostBeacons.SIZE_50)
beAround.initialize(...)
```

---

### 🎉 Event Listener System (v1.0.4)

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

