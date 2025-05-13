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

## 🍏 bearound-ios-sdk

Swift SDK for iOS — secure beacon proximity events and indoor location.

### 📦 Installation

Via Swift Package Manager:

```swift
.package(url: "https://github.com/bearound/bearound-ios-sdk.git", from: "1.0.0")
```

Or via CocoaPods:

```ruby
pod 'BearoundSDK'
```

### ⚙️ Required Permissions

Add to Info.plist:

- NSBluetoothAlwaysUsageDescription
- NSLocationWhenInUseUsageDescription

### 🚀 Features

- Beacon scanning using CoreBluetooth + CoreLocation
- Geofence-based proximity detection
- AES-GCM encryption
- iOS 12+ support, macOS Catalyst compatible

### 🛠️ Usage

```swift
BeaconDetector.shared.startScanning { beacon in
    print("Detected \(beacon.identifier) at \(beacon.distance)m")
}
```

### 🔐 Security

- End-to-end encrypted payloads
- Minimal local processing
- No analytics or tracking

### 🧪 Testing

- Test with real BLE beacons or simulators
- Enable Location & Bluetooth in Settings
- Ensure Info.plist is configured properly

### 📄 License

MIT © Bearound

## 🟣 bearound-react-native-sdk

React Native wrapper for cross-platform BLE beacon scanning with Bearound's SDK.

### 📦 Installation

```bash
npm install @bearound/react-native-sdk
npx pod-install
```

### ⚙️ Required Permissions

Same as native SDKs — refer to Android/iOS sections above.

### 🚀 Features

- Unified BLE API for Android and iOS
- TypeScript support
- Expo config plugin compatible
- Real-time events via EventEmitter

### 🛠️ Usage

```javascript
import { BearoundSDK, addBeaconListener } from '@bearound/react-native-sdk'

BearoundSDK.startScan()

addBeaconListener(beacon => {
  console.log(`Beacon ${beacon.id} at ${beacon.distance}m`)
})
```

### 📄 License

MIT © Bearound

## 🟡 bearound-flutter-sdk

Flutter plugin with unified Dart APIs for BLE beacon detection.

### 📦 Installation

Add to pubspec.yaml:

```yaml
dependencies:
  bearound_flutter_sdk: ^1.0.0
```

Then run:

```bash
flutter pub get
```

### ⚙️ Required Permissions

Android:
- android.permission.BLUETOOTH
- android.permission.ACCESS_FINE_LOCATION
- android.permission.BLUETOOTH_SCAN (API 31+)

iOS:
- NSBluetoothAlwaysUsageDescription
- NSLocationWhenInUseUsageDescription

### 🚀 Features

- BLE beacon scanning
- Real-time Dart stream of beacon events
- Unified cross-platform implementation
- Null-safety

### 🛠️ Usage

```dart
import 'package:bearound_flutter_sdk/bearound_flutter_sdk.dart'

BearoundFlutterSdk.startScan()

BearoundFlutterSdk.beaconStream.listen((beacon) {
  print("Beacon ${beacon.id} at ${beacon.distance}m")
})
```

### 📄 License

MIT © Bearound
