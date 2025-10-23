# 🐻 BeAround SDKs Documentation

Official SDKs for integrating BeAround's secure BLE beacon detection and indoor location technology across Android, iOS, React Native, and Flutter.

## 📱 BeAround-android-sdk

Kotlin SDK for Android — secure BLE beacon detection and indoor positioning by BeAround.

## 🧩 Features

- **Continuous region monitoring** for BLE beacons
- **Real-time beacon events** with `BeaconEventListener` system
- **Automatic API synchronization** for beacon enter/exit events
- **Rich beacon data capture**:
  - Distance estimation and RSSI
  - UUID, major, minor identifiers
  - Bluetooth MAC address
  - Timestamp and app state (foreground/background/inactive)
  - Google Advertising ID
- **Event listener callbacks**:
  - `onBeaconEnter` - Triggered when entering beacon range
  - `onBeaconExit` - Triggered when leaving beacon range
  - `onBeaconSync` - Confirmation of successful API sync
- **Foreground service** support for background execution
- **Built-in debug logging** with tag BeAroundSdk
- **Privacy-first architecture** with AES-GCM encryption

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
    implementation 'com.github.Bearound:bearound-android-sdk:1.0.17'
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

### 📡 Beacon Event Listeners

The SDK provides a powerful event system to receive real-time beacon detection updates:

```kotlin
import io.bearound.sdk.BeAround
import io.bearound.sdk.listeners.BeaconEventListener
import io.bearound.sdk.model.BeaconEventData

class MyActivity : AppCompatActivity() {

    private val beaconListener = object : BeaconEventListener {
        override fun onBeaconEnter(beacon: BeaconEventData) {
            // Called when entering a beacon's range
            Log.d("BeAround", "Entered beacon: ${beacon.uuid}")
            Log.d("BeAround", "Distance: ${beacon.distance}m, RSSI: ${beacon.rssi}")
        }

        override fun onBeaconExit(beacon: BeaconEventData) {
            // Called when leaving a beacon's range
            Log.d("BeAround", "Exited beacon: ${beacon.uuid}")
        }

        override fun onBeaconSync(beacon: BeaconEventData, success: Boolean) {
            // Called after API synchronization attempt
            if (success) {
                Log.d("BeAround", "Beacon event synced to API")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val beAround = BeAround.getInstance(applicationContext)
        beAround.addBeaconEventListener(beaconListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        BeAround.getInstance(applicationContext).removeBeaconEventListener(beaconListener)
    }
}
```

### 📊 Beacon Event Data

Each beacon event contains rich information:

```kotlin
data class BeaconEventData(
    val uuid: String,           // Beacon UUID
    val major: Int,             // Major identifier
    val minor: Int,             // Minor identifier
    val rssi: Int,              // Signal strength
    val distance: Double,       // Estimated distance in meters
    val bluetoothAddress: String, // MAC address
    val timestamp: Long,        // Event timestamp
    val eventType: String,      // "enter" or "exit"
    val appState: String        // "foreground", "background", or "inactive"
)
```

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

## 📚 Documentation

For detailed technical documentation, see the [docs/](docs/) folder:

- **[Battery Optimization Guide](docs/BATTERY_OPTIMIZATION.md)** - Handle battery restrictions and background execution
- **[JitPack Publishing Guide](docs/JITPACK_GUIDE.md)** - How to publish new versions (instant!)
- **[Quick Reference](docs/QUICK_REFERENCE.md)** - Commands and common workflows
- **[Build AAR Guide](docs/BUILD_AAR_GUIDE.md)** - How to generate .aar binaries
- **[ProGuard Configuration](docs/PROGUARD_CONFIG_EXPLAINED.md)** - Obfuscation details
- **[Changelog](CHANGELOG.md)** - Version history

### 📄 License

MIT © Bearound

