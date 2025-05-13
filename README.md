# 🐻 Bearound SDKs Documentation

Official SDKs for integrating Bearound's secure BLE beacon detection and indoor location technology across Android, iOS, React Native, and Flutter.

## 📱 bearound-android-sdk

Kotlin SDK for Android — secure BLE beacon detection and indoor positioning by Bearound.

### 📦 Installation

Add the following to your build.gradle dependencies block:

```gradle
implementation "com.bearound:sdk:"
```

Sync your Gradle project after adding the dependency.

### ⚙️ Required Permissions

Add the following to AndroidManifest.xml:

```xml
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

For Android 12+ (API 31+), also include:

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

### 🚀 Features

- BLE beacon scanning and filtering
- Indoor geofence-based proximity detection
- Real-time enter/exit events
- AES-GCM encryption
- Battery-efficient scanning
- Android 5.0+ support

### 🛠️ Usage

```kotlin
// Start scanning:
BeaconScanner.startScan { beacon ->
    Log.d("Beacon", "Detected: ${beacon.id} at ${beacon.distance} meters")
}

// Register enter/exit:
BeaconScanner.onEnterZone = { zoneId -> Log.i("Geofence", "Entered zone $zoneId") }
BeaconScanner.onExitZone = { zoneId -> Log.i("Geofence", "Exited zone $zoneId") }
```

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

