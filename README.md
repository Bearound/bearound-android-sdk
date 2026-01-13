# 🐻 BeAround Android SDK

[![JitPack](https://jitpack.io/v/Bearound/bearound-android-sdk.svg)](https://jitpack.io/#Bearound/bearound-android-sdk)
[![API](https://img.shields.io/badge/API-23%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=23)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Kotlin SDK for Android — secure BLE beacon detection and indoor positioning by BeAround.

## Version 2.1.0

Latest version with configurable scan intervals and enhanced control. Separate foreground/background scan intervals, configurable retry queue, and improved lifecycle detection using ProcessLifecycleOwner.

## Features

- ✅ Native Android Bluetooth LE beacon scanning (no external dependencies)
- ✅ Background and foreground beacon detection
- ✅ Automatic beacon metadata collection via BLE (battery, firmware, temperature)
- ✅ Periodic and continuous scanning modes
- ✅ Comprehensive device information collection
- ✅ Secure storage for device identifiers
- ✅ Exponential backoff retry logic with circuit breaker
- ✅ Battery-optimized scanning strategies
- ✅ Thread-safe beacon caching and sync
- ✅ Real-time UI updates via delegate pattern
- ✅ **Background scanning when app is closed** (no notification required)

## Architecture

This SDK follows the same architecture as the iOS version:

- **BeAroundSDK**: Main singleton class for SDK operations
- **BeaconManager**: Handles beacon scanning using native Android Bluetooth LE APIs
- **BluetoothManager**: Manages BLE scanning for beacon metadata
- **APIClient**: Handles communication with BeAround backend
- **DeviceInfoCollector**: Collects comprehensive device information
- **SecureStorage**: Encrypted storage for sensitive data (similar to iOS Keychain)

## Installation

### JitPack

1. Add JitPack repository to your root `build.gradle`:

```gradle
allprojects {
    repositories {
        ...
        maven { url 'https://jitpack.io' }
    }
}
```

Or if using `settings.gradle` (newer projects):

```gradle
dependencyResolutionManagement {
    repositories {
        ...
        maven { url 'https://jitpack.io' }
    }
}
```

2. Add the dependency:

```gradle
dependencies {
    implementation 'com.github.Bearound:bearound-android-sdk:v2.1.0'
}
```

## Requirements

- Android API 23+ (Android 6.0 Marshmallow)
- Kotlin 1.8+
- Bluetooth LE support
- Location permissions

## Permissions

Add these permissions to your `AndroidManifest.xml`:

```xml
<!-- Bluetooth (Android 11 and below) -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />

<!-- Bluetooth (Android 12+) -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<!-- Location -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />

<!-- Internet -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- Notifications (Android 13+) -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />

<!-- Bluetooth (Android 12+) -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<!-- Location -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />

<!-- Internet -->
<uses-permission android:name="android.permission.INTERNET" />

<!-- Notifications (Android 13+) -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

## Quick Start

### 1. Initialize the SDK

```kotlin
import io.bearound.sdk.BeAroundSDK
import io.bearound.sdk.interfaces.BeAroundSDKDelegate
import io.bearound.sdk.models.Beacon

class MainActivity : AppCompatActivity(), BeAroundSDKDelegate {
    
    private lateinit var sdk: BeAroundSDK
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Get SDK instance
        sdk = BeAroundSDK.getInstance(this)
        sdk.delegate = this
        
        // Configure SDK
        sdk.configure(
            businessToken = "your-business-token",
            foregroundScanInterval = ForegroundScanInterval.SECONDS_15, // Default: 15s
            backgroundScanInterval = BackgroundScanInterval.SECONDS_30, // Default: 30s
            maxQueuedPayloads = MaxQueuedPayloads.MEDIUM, // Default: 100 payloads
            enableBluetoothScanning = true,
            enablePeriodicScanning = true
        )
        // Note: appId is automatically extracted from context.packageName
    }
    
    // Implement delegate methods
    override fun didUpdateBeacons(beacons: List<Beacon>) {
        Log.d("BeAround", "Detected ${beacons.size} beacons")
        beacons.forEach { beacon ->
            Log.d("BeAround", "Beacon: ${beacon.major}.${beacon.minor}, RSSI: ${beacon.rssi}")
        }
    }
    
    override fun didFailWithError(error: Exception) {
        Log.e("BeAround", "Error: ${error.message}")
    }
    
    override fun didChangeScanning(isScanning: Boolean) {
        Log.d("BeAround", "Scanning: $isScanning")
    }
    
    override fun didUpdateSyncStatus(secondsUntilNextSync: Int, isRanging: Boolean) {
        Log.d("BeAround", "Next sync in: ${secondsUntilNextSync}s, Ranging: $isRanging")
    }
}
```

### 2. Request Permissions

```kotlin
private fun requestPermissions() {
    val permissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
    
    ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_CODE)
}
```

### 3. Start Scanning

```kotlin
fun startScanning() {
    sdk.startScanning()
}

fun stopScanning() {
    sdk.stopScanning()
}
```

### 4. Set User Properties (Optional)

```kotlin
import io.bearound.sdk.models.UserProperties

val userProps = UserProperties(
    internalId = "user123",
    email = "user@example.com",
    name = "John Doe",
    customProperties = mapOf(
        "plan" to "premium",
        "age" to "30"
    )
)

sdk.setUserProperties(userProps)
```

### 5. Background Scanning 🆕

**Background scanning is automatically enabled** when you call `configure()`. The SDK will continue detecting beacons even when the app is closed.

**Optional:** You can manually control it:

```kotlin
// Disable background scanning
sdk.disableBackgroundScanning()

// Re-enable background scanning
sdk.enableBackgroundScanning()
```

**Complete Example:**

```kotlin
class MainActivity : AppCompatActivity(), BeAroundSDKDelegate {
    
    private lateinit var sdk: BeAroundSDK
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Get SDK instance
        sdk = BeAroundSDK.getInstance(this)
        sdk.delegate = this
        
        // Configure SDK
        sdk.configure(
            businessToken = "your-business-token",
            foregroundScanInterval = ForegroundScanInterval.SECONDS_15,
            backgroundScanInterval = BackgroundScanInterval.SECONDS_30,
            maxQueuedPayloads = MaxQueuedPayloads.MEDIUM,
            enableBluetoothScanning = true,
            enablePeriodicScanning = true
        )
        
        // Start foreground scanning
        sdk.startScanning()
        
        // Background scanning is automatically enabled!
    }
    
    override fun didUpdateBeacons(beacons: List<Beacon>) {
        Log.d("BeAround", "Detected ${beacons.size} beacons")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Note: Background scanning continues even after app is closed
        // Call disableBackgroundScanning() if you want to stop it
    }
}
```

**How it works:**
- System automatically wakes up the app when iBeacon is detected
- No notification required
- Real-time detection
- No foreground service required

**Important Notes:**
- Background scanning requires `ACCESS_BACKGROUND_LOCATION` permission
- WorkManager minimum interval is 15 minutes (Android limitation)
- Android may delay scans in battery saver mode
- Background scanning continues even after device reboot

## Configuration Options

### Scan Intervals

Configure separate scan intervals for foreground and background:

```kotlin
sdk.configure(
    businessToken = "your-business-token",
    foregroundScanInterval = ForegroundScanInterval.SECONDS_15, // 5-60s options
    backgroundScanInterval = BackgroundScanInterval.SECONDS_30  // 15-120s options
)
```

**Available Foreground Intervals:**
- `SECONDS_5` to `SECONDS_60` (5s increments)

**Available Background Intervals:**
- `SECONDS_15`, `SECONDS_30`, `SECONDS_60`, `SECONDS_90`, `SECONDS_120`

### Retry Queue Size

Configure how many failed batches to queue before discarding:

```kotlin
sdk.configure(
    businessToken = "your-business-token",
    maxQueuedPayloads = MaxQueuedPayloads.MEDIUM // Default: 100
)
```

**Available Sizes:**
- `SMALL` (50 payloads)
- `MEDIUM` (100 payloads) - Default
- `LARGE` (200 payloads)
- `XLARGE` (500 payloads)

### Periodic Scanning

When enabled, the SDK scans for beacons periodically instead of continuously:

```kotlin
sdk.configure(
    businessToken = "your-business-token",
    enablePeriodicScanning = true // Default: true
)
```

- **Foreground**: Scans for 100ms before each sync interval
- **Background**: Scans for 100ms before each sync interval

### Bluetooth Metadata Scanning

Enable BLE scanning to collect additional beacon metadata (battery, temperature, etc.):

```kotlin
sdk.configure(
    businessToken = "your-business-token",
    enableBluetoothScanning = true // Default: false
)
```

## API Reference

### BeAroundSDK

Main SDK class (Singleton pattern).

```kotlin
// Get instance
val sdk = BeAroundSDK.getInstance(context)

// Configure SDK
sdk.configure(
    businessToken: String,
    foregroundScanInterval: ForegroundScanInterval = ForegroundScanInterval.SECONDS_15,
    backgroundScanInterval: BackgroundScanInterval = BackgroundScanInterval.SECONDS_30,
    maxQueuedPayloads: MaxQueuedPayloads = MaxQueuedPayloads.MEDIUM,
    enableBluetoothScanning: Boolean = false,
    enablePeriodicScanning: Boolean = true
)

// Control scanning
sdk.startScanning()
sdk.stopScanning()

// User properties
sdk.setUserProperties(properties: UserProperties)
sdk.clearUserProperties()

// Bluetooth scanning
sdk.setBluetoothScanning(enabled: Boolean)

// Status
val isScanning: Boolean = sdk.isScanning
val syncInterval: Long? = sdk.currentSyncInterval
val scanDuration: Long? = sdk.currentScanDuration
val isPeriodicScanningEnabled: Boolean = sdk.isPeriodicScanningEnabled
```

### BeAroundSDKDelegate

Implement this interface to receive SDK events:

```kotlin
interface BeAroundSDKDelegate {
    fun didUpdateBeacons(beacons: List<Beacon>)
    fun didFailWithError(error: Exception) {}
    fun didChangeScanning(isScanning: Boolean) {}
    fun didUpdateSyncStatus(secondsUntilNextSync: Int, isRanging: Boolean) {}
}
```

### Models

#### Beacon

```kotlin
data class Beacon(
    val uuid: UUID,
    val major: Int,
    val minor: Int,
    val rssi: Int,
    val proximity: Proximity,
    val accuracy: Double,
    val timestamp: Date,
    val metadata: BeaconMetadata?,
    val txPower: Int?
)

enum class Proximity {
    IMMEDIATE,
    NEAR,
    FAR,
    UNKNOWN
}
```

#### BeaconMetadata

```kotlin
data class BeaconMetadata(
    val firmwareVersion: String,
    val batteryLevel: Int,
    val movements: Int,
    val temperature: Int,
    val txPower: Int?,
    val rssiFromBLE: Int?,
    val isConnectable: Boolean?
)
```

#### UserProperties

```kotlin
data class UserProperties(
    val internalId: String? = null,
    val email: String? = null,
    val name: String? = null,
    val customProperties: Map<String, String> = emptyMap()
)
```

## Background Scanning

The SDK offers multiple background scanning modes:

### 1. Standard Background (App in Memory)

When the app is in background but not killed:
- **Foreground**: Uses periodic or continuous scanning based on configuration
- **Background**: Automatically switches to continuous scanning for better beacon detection
- Implements watchdog timers and periodic restarts to prevent Android throttling

### 2. Advanced Background (App Closed) 🆕

Enable beacon detection even when the app is fully closed:

```kotlin
sdk.enableBackgroundScanning(scanIntervalMinutes = 15)
```

**Features:**
- ✅ **Real-time detection** - System wakes app when beacon is detected
- ✅ **Zero notifications** - No foreground service required
- ✅ **Very battery efficient** - System manages scanning
- ✅ **Survives app kill** - Continues working after force-stop
- ✅ **Survives reboot** - Automatically resumes after device restart

**Requirements:**
- `ACCESS_BACKGROUND_LOCATION` permission must be granted
- SDK must be configured before enabling background scanning

**Limitations:**
- System may delay scans in extreme battery saver mode

### Best Practices

- Request `ACCESS_BACKGROUND_LOCATION` permission for background operation
- Call `enableBackgroundScanning()` after SDK configuration
- Adjust `scanIntervalMinutes` based on use case (15-60 minutes recommended)
- Monitor battery usage and adjust sync intervals accordingly
- Inform users about background location usage in your privacy policy

## Migration from 1.x

Version 2.0.x introduces breaking changes. Follow these steps to migrate:

### 1. Update Dependencies

```gradle
// OLD (v1.x)
implementation 'com.github.Bearound:bearound-android-sdk:1.3.2'

// NEW (v2.1+)
implementation 'com.github.Bearound:bearound-android-sdk:v2.1.0'
```

### 2. Update AndroidManifest.xml

Add the missing `ACCESS_NETWORK_STATE` permission:

```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

### 3. Update Initialization Code

```kotlin
// OLD (v1.x)
val beAround = BeAround.getInstance(context)
beAround.initialize(
    iconNotification = R.drawable.ic_notification,
    clientToken = "your-token",
    debug = true
)

// NEW (v2.1)
val sdk = BeAroundSDK.getInstance(context)
sdk.delegate = this // implement BeAroundSDKDelegate
sdk.configure(
    businessToken = "your-business-token",
    foregroundScanInterval = ForegroundScanInterval.SECONDS_15,
    backgroundScanInterval = BackgroundScanInterval.SECONDS_30,
    maxQueuedPayloads = MaxQueuedPayloads.MEDIUM,
    enableBluetoothScanning = true,
    enablePeriodicScanning = true
)
sdk.startScanning()
```

### 4. Replace Listeners with Delegate

```kotlin
// OLD (v1.x)
beAround.addBeaconListener(object : BeaconListener {
    override fun onBeaconsDetected(beacons: List<BeaconData>) { }
})

// NEW (v2.0)
class MainActivity : AppCompatActivity(), BeAroundSDKDelegate {
    override fun didUpdateBeacons(beacons: List<Beacon>) { }
    override fun didUpdateSyncStatus(secondsUntilSync: Int, isScanning: Boolean) { }
    override fun didFailWithError(error: Throwable) { }
}
```

### 5. Update Beacon Data Access

The `Beacon` model has changed:

```kotlin
// OLD (v1.x)
beacon.uuid
beacon.major
beacon.minor
beacon.distance

// NEW (v2.0)
beacon.uuid
beacon.major
beacon.minor
beacon.accuracy  // replaces distance
beacon.proximity // IMMEDIATE, NEAR, FAR, UNKNOWN
beacon.metadata  // new: battery, firmware, temperature
```

## Troubleshooting

### Beacons not detected

1. Ensure all permissions are granted (Location, Bluetooth)
2. Check that Bluetooth is enabled
3. Verify location services are enabled
4. Ensure the beacon UUID matches: `E25B8D3C-947A-452F-A13F-589CB706D2E5`

### Beacons detected but not syncing to API

1. Check logcat for `BeAroundSDK-APIClient` logs
2. Verify `ACCESS_NETWORK_STATE` permission is granted
3. Ensure internet connectivity
4. Check API base URL configuration

### High battery usage

1. Increase scan intervals:
   - `foregroundScanInterval = ForegroundScanInterval.SECONDS_30`
   - `backgroundScanInterval = BackgroundScanInterval.SECONDS_60` or higher
2. Enable `enablePeriodicScanning = true` (enabled by default)
3. Disable `enableBluetoothScanning` if metadata is not needed

### No beacons in background

1. Ensure `ACCESS_BACKGROUND_LOCATION` permission is granted
2. For better results, ask users to disable battery optimization for your app in device settings

### App crashes with SecurityException

- Ensure `ACCESS_NETWORK_STATE` permission is in AndroidManifest.xml
- This was a known issue in pre-2.0 versions

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for detailed version history.

### Version 2.1.0 (2026-01-13)

- 🆕 **Configurable Scan Intervals**: Separate foreground/background intervals
  - `ForegroundScanInterval`: 5-60 seconds (5s increments)
  - `BackgroundScanInterval`: 15-120 seconds
- 🆕 **Configurable Retry Queue**: Control failed payload queue size
  - Small (50), Medium (100), Large (200), XLarge (500)
- 🔧 **Improved Lifecycle Detection**: Using `ProcessLifecycleOwner` for better reliability
  - Better compatibility with Samsung OneUI and other Android skins
- 🐛 **Bug Fixes**:
  - Fixed background/foreground state detection on various Android versions
  - Fixed Bluetooth Scan Broadcast activation timing
  - Improved periodic scanning reliability in background

### Version 2.0.2 (2026-01-08)

- 🆕 **Background Scanning**: Beacon detection when app is closed
  - Real-time detection with no notification required
  - Very battery efficient
  - Automatically enabled when SDK is configured
- 🐛 Fixed scanning restart bug after `stopScanning()` is called
- 🐛 Fixed "scanning too frequently" error in background mode

### Version 2.0.1 (2026-01-07)

- ⚠️ **Breaking Change**: `configure()` now requires `businessToken` instead of `appId`
- ✨ `appId` automatically extracted from `context.packageName`
- 🔒 Authorization header now sends business token directly (without Bearer prefix)

### Version 2.0.0 (2025-12-30)

- 🚀 Complete SDK rewrite with new architecture
- ✨ Native Android Bluetooth LE (no external dependencies)
- 🔧 Fixed beacon sync race condition
- 🔧 Fixed missing `ACCESS_NETWORK_STATE` permission
- 🔧 Fixed UI not updating with beacon data
- 📱 New Jetpack Compose sample app
- ⚠️ Breaking changes - not compatible with v1.x

### Version 1.3.2 (Previous)

- Legacy version with AltBeacon library
- See old documentation for v1.x usage

## License

MIT License - see LICENSE file for details

## Support

For issues, questions, or contributions, please visit:
https://github.com/Bearound/bearound-android-sdk
