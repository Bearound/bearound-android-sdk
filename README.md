# 🐻 BeAround-android-sdkDocumentation

**BeAround** is an Android SDK for monitoring Bluetooth LE beacons and sending `enter` and `exit` events to a remote API. It's ideal for proximity detection and indoor tracking applications.

## 🧩 Features

- Continuous region monitoring for beacons
- Sends `enter` and `exit` events to a remote API
- Captures distance, RSSI, UUID, major/minor, Advertising ID
- Runs as a foreground service in the background
- Supports notifications and telemetry data (if available)

---

## ⚙️ Requirements

- **Minimum SDK**: 21 (Android 5.0 Lollipop)
- **Bluetooth LE** must be enabled

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

### 🚀 Features

- BLE beacon scanning and filtering
- Indoor geofence-based proximity detection
- Real-time enter/exit events
- AES-GCM encryption
- Battery-efficient scanning
- Android 5.0+ support

### 🛠️ Usage

### Initialization
Initialize the SDK inside your Application class after checking the required permissions:

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val beAround = BeAround(applicationContext)
        // Check permissions before initializing
        beAround.initialize(
            iconNotification = R.drawable.ic_notification_icon, //icon show notification service
            debug = true // optional, enable debug logging
        )
    }
}
```

- The SDK automatically monitors beacons with the UUID
- When entering or exiting beacon regions, it sends a JSON payload to the remote API.
- Events include beacon identifiers, RSSI, distance, app state (foreground/background/inactive), Bluetooth details, and Google Advertising ID.


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

