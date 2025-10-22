# 🐻 BeAround SDKs Documentation

Official SDKs for integrating BeAround's secure BLE beacon detection and indoor location technology across Android, iOS, React Native, and Flutter.

## 📱 BeAround-android-sdk

Kotlin SDK for Android — secure BLE beacon detection and indoor positioning by BeAround.

## 🧩 Features

- Continuous region monitoring for beacons
- Sends `enter` and `exit` events to a remote API
- Captures distance, RSSI, UUID, major/minor, Advertising ID
- Foreground service support for background execution
- Sends telemetry data (if available)
- Built-in debug logging with tag BeAroundSdk

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
    implementation 'com.github.Bearound:bearound-android-sdk:1.0.16'
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

### ⚠️ After initializing it, it starts executing the service, you can follow this by activating the debug and looking at the Logs with the TAG: BeAroundSdk

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

## 📚 Documentation

For detailed technical documentation, see the [docs/](docs/) folder:

- **[JitPack Publishing Guide](docs/JITPACK_GUIDE.md)** - How to publish new versions (instant!)
- **[Quick Reference](docs/QUICK_REFERENCE.md)** - Commands and common workflows
- **[Build AAR Guide](docs/BUILD_AAR_GUIDE.md)** - How to generate .aar binaries
- **[ProGuard Configuration](docs/PROGUARD_CONFIG_EXPLAINED.md)** - Obfuscation details
- **[Changelog](CHANGELOG.md)** - Version history

### 📄 License

MIT © Bearound

