# 🐻 BeAround Android SDK

[![JitPack](https://jitpack.io/v/Bearound/bearound-android-sdk.svg)](https://jitpack.io/#Bearound/bearound-android-sdk)
[![API](https://img.shields.io/badge/API-23%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=23)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Kotlin SDK for Android — secure BLE beacon detection and indoor positioning by BeAround.

## Version 3.0.0

Hybrid wake-up parity with iOS SDK 3.0.0: kernel-registered `PendingIntent` BLE scan survives force-stop and swipe-from-recents on Android 14+ without a foreground service. `BLUETOOTH_SCAN` is declared with `neverForLocation`, so beacon detection no longer implies Location authorization. Default SDK user-facing strings are now English; precision-based scanning, duty-cycle architecture, foreground service support, and beacon-gated GPS from 2.4.0 are preserved.

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
- ✅ Real-time UI updates via listener pattern
- ✅ **Background scanning when app is closed** (no notification required)

## Architecture

- **BeAroundSDK**: Main singleton class for SDK operations
- **BeaconManager**: Handles beacon scanning using native Android Bluetooth LE APIs
- **BluetoothManager**: Manages BLE scanning for beacon metadata
- **APIClient**: Handles communication with BeAround backend
- **DeviceInfoCollector**: Collects comprehensive device information
- **SecureStorage**: Encrypted storage for sensitive data

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
    implementation 'com.github.Bearound:bearound-android-sdk:v3.0.0'
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
<!-- BLUETOOTH_CONNECT is NOT required by the SDK as of v3.0.0.
     Declare it only if your own app uses GATT operations. -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation"
    tools:targetApi="s" />

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

## Quick Start

### 1. Initialize the SDK

```kotlin
import io.bearound.sdk.BeAroundSDK
import io.bearound.sdk.interfaces.BeAroundSDKListener
import io.bearound.sdk.models.Beacon
import io.bearound.sdk.models.ScanPrecision
import io.bearound.sdk.models.MaxQueuedPayloads

class MainActivity : AppCompatActivity(), BeAroundSDKListener {

    private lateinit var sdk: BeAroundSDK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get SDK instance
        sdk = BeAroundSDK.getInstance(this)
        sdk.listener = this

        // Configure SDK
        sdk.configure(
            businessToken = "your-business-token",
            scanPrecision = ScanPrecision.MEDIUM, // Default: MEDIUM
            maxQueuedPayloads = MaxQueuedPayloads.MEDIUM // Default: 100 payloads
        )
        // Note: appId is automatically extracted from context.packageName
    }

    // Implement listener methods
    override fun onBeaconsUpdated(beacons: List<Beacon>) {
        Log.d("BeAround", "Detected ${beacons.size} beacons")
        beacons.forEach { beacon ->
            Log.d("BeAround", "Beacon: ${beacon.major}.${beacon.minor}, RSSI: ${beacon.rssi}")
        }
    }

    override fun onError(error: Exception) {
        Log.e("BeAround", "Error: ${error.message}")
    }

    override fun onScanningStateChanged(isScanning: Boolean) {
        Log.d("BeAround", "Scanning: $isScanning")
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
        // Only BLUETOOTH_SCAN is required by the SDK (v3.0.0+).
        // Declared with neverForLocation, so it does not imply Location authorization.
        permissions.add(Manifest.permission.BLUETOOTH_SCAN)
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

**Complete Example:**

```kotlin
class MainActivity : AppCompatActivity(), BeAroundSDKListener {

    private lateinit var sdk: BeAroundSDK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get SDK instance
        sdk = BeAroundSDK.getInstance(this)
        sdk.listener = this

        // Configure SDK
        sdk.configure(
            businessToken = "your-business-token",
            scanPrecision = ScanPrecision.MEDIUM,
            maxQueuedPayloads = MaxQueuedPayloads.MEDIUM
        )

        // Start scanning
        sdk.startScanning()

        // Background scanning is automatically enabled!
    }

    override fun onBeaconsUpdated(beacons: List<Beacon>) {
        Log.d("BeAround", "Detected ${beacons.size} beacons")
    }

    override fun onDestroy() {
        super.onDestroy()
        // Note: Background scanning continues even after app is closed
    }
}
```

**How it works:**
- System automatically wakes up the app when an iBeacon is detected
- No notification required
- Real-time detection
- No foreground service required for Android 14+

**Important Notes:**
- Background scanning requires `ACCESS_BACKGROUND_LOCATION` permission
- WorkManager's minimum interval is 15 minutes (Android limitation)
- Android may delay scans in battery saver mode
- Background scanning continues even after device reboot via `BOOT_COMPLETED`

### Terminated App Detection

The SDK is designed to survive every state where the app is not running — including a user **force-stop** or **swipe from recents** — without any code change on the host side. This is the same posture the iOS SDK delivers only when the user opts into the Location eye; on Android it is the default.

| Scenario | Detection still works? | Mechanism |
|---|---|---|
| App in foreground | ✅ | `BluetoothLeScanner` with `ScanCallback` |
| App in background | ✅ | Same callback, OS keeps process alive briefly |
| App killed by **system** (memory / battery pressure) | ✅ | `PendingIntent` broadcast scan → `BluetoothScanReceiver` wakes a fresh process |
| App killed by **user** (swipe from recents) | ✅ on **Android 14+** | Same `PendingIntent` scan — kernel-registered, survives swipe |
| App **force-stopped** in Settings | ✅ on **Android 14+** | Same `PendingIntent` scan, re-registered on next interaction |
| After device reboot | ✅ | `BOOT_COMPLETED` → `ScanWatchdogReceiver` re-arms the scan |

**Architecture under the hood:**

- **`BluetoothScanReceiver`** — wakes the app via `PendingIntent` when a matching iBeacon is observed by the OS scanner. Equivalent to iOS's `CLBeaconRegion` `didEnterRegion` callback, but **does not require Location authorization beyond `ACCESS_BACKGROUND_LOCATION`** (which is required for any BLE scan in background on Android).
- **`ScanWatchdogReceiver`** — `AlarmManager` heartbeat every 15 min, plus `BOOT_COMPLETED` listener. Re-registers the BLE scan filter if it ever gets evicted by the OS.
- **`BeaconScanService`** — optional foreground service for apps targeting Android 13 or below, or for apps that need a visible "scanning" indicator. Not needed on Android 14+.

#### Android version baseline

| Android version | Force-stop survival without foreground service |
|---|---|
| 14+ (API 34+) | ✅ Default behavior |
| 8 – 13 (API 26 – 33) | Partial — recommend enabling the foreground service (`enableForegroundScanning(...)`) |
| < 8 (API < 26) | Not supported — the SDK requires API 26+ |

#### OEM caveat (the part not in the docs of any beacon SDK)

Stock Android (Pixel) honors the `PendingIntent` scan exactly as documented. Several OEMs ship aggressive battery managers that kill third-party `PendingIntent` and broadcast receivers regardless of Android version:

| OEM | Behavior | Mitigation |
|---|---|---|
| Samsung (One UI 6+) | Generally honors the scan; some restrictions on apps marked "Sleeping" | Ask user to add app to "Never sleeping apps" |
| Xiaomi / Redmi (MIUI / HyperOS) | Aggressively kills background broadcast receivers | Ask user to enable "Autostart" + lock app in recents |
| Huawei / Honor (EMUI / HarmonyOS) | Same as Xiaomi | "Manage manually" in battery settings |
| OnePlus (OxygenOS 11+) | Less aggressive but still restricts | Disable "Deep optimization" for the app |
| Pixel / Stock | Honors PendingIntent scan | No action |

For any of these vendors, the host app should request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and link to the relevant settings page. Without that, the OEM's killer can evict the SDK even on Android 14+. See [dontkillmyapp.com](https://dontkillmyapp.com) for the full per-vendor matrix.

## Configuration Options

### Scan Precision

Controls the duty cycle and sync interval for beacon scanning:

```kotlin
sdk.configure(
    businessToken = "your-business-token",
    scanPrecision = ScanPrecision.MEDIUM // Default
)
```

**Available Precision Modes:**

| Mode | Scan Pattern | Sync Interval | Use Case |
|------|-------------|---------------|----------|
| `HIGH` | Continuous scanning (no pauses) | Every 15s | Maximum detection, higher battery usage |
| `MEDIUM` | 3x (10s scan + 10s pause) per 60s window | Every 60s | Balanced detection and battery (default) |
| `LOW` | 1x (10s scan + 50s pause) per 60s window | Every 60s | Maximum battery savings |

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

### Duty Cycle Scanning

Scanning uses a duty cycle architecture based on `ScanPrecision`:

- **HIGH**: Continuous BLE + Beacon scanning with sync every 15 seconds
- **MEDIUM**: 3 cycles of (10s scan + 10s pause) per 60-second window, sync at end of window
- **LOW**: 1 cycle of (10s scan + 50s pause) per 60-second window, sync at end of window

The SDK automatically manages scan/pause cycles for optimal battery usage.

### Foreground Service (Optional)

For apps that need a persistent notification while scanning in background:

```kotlin
import io.bearound.sdk.models.ForegroundScanConfig

// Enable foreground service with notification
sdk.enableForegroundScanning(
    ForegroundScanConfig(
        notificationTitle = "Monitoring region",
        notificationText = "Scanning for beacons in background",
        notificationIcon = R.drawable.ic_notification, // optional
        notificationChannelId = "beacon_channel", // optional
        notificationChannelName = "Beacon Monitoring" // optional
    )
)

// Disable foreground service
sdk.disableForegroundScanning()
```

The foreground service automatically starts when the app goes to background and stops when returning to foreground.

### Bluetooth Metadata Scanning

Bluetooth metadata scanning (battery, temperature, firmware, etc.) is **always enabled** when permissions are granted. The SDK automatically attempts to connect to nearby beacons to collect this data.

No configuration needed - the SDK adapts automatically!

### Offline Batch Storage

Failed beacon batches are now **persistently stored** to survive app kills and device reboots:

**Features:**
- ✅ **Persistent**: Saved as JSON files in app's private directory
- ✅ **FIFO ordering**: Oldest batches sent first
- ✅ **Auto-cleanup**: Removes batches older than 7 days
- ✅ **Survives**: App kill, device reboot, crashes
- ✅ **Thread-safe**: Uses `ReentrantLock` for concurrent access
- ✅ **Respects limits**: Honors `maxQueuedPayloads` configuration

**How it works:**

```kotlin
// 1. Network fails → batch saved to disk automatically
// 2. WorkManager/AlarmManager triggers background sync
// 3. SDK loads oldest batch from storage (FIFO)
// 4. Success → removes batch from disk
// 5. Failure → batch remains for next retry

// Check pending batches
val pendingCount = sdk.pendingBatchCount
println("Waiting to sync: $pendingCount batches")
```

**Storage location:** `/data/data/your.package.name/app_com.bearound.sdk.batches/`

**Filename format:** `timestamp_uuid.json` (sorted by timestamp for FIFO)

## API Reference

### BeAroundSDK

Main SDK class (Singleton pattern).

```kotlin
// Get instance
val sdk = BeAroundSDK.getInstance(context)

// Configure SDK
sdk.configure(
    businessToken: String,
    scanPrecision: ScanPrecision = ScanPrecision.MEDIUM,
    maxQueuedPayloads: MaxQueuedPayloads = MaxQueuedPayloads.MEDIUM
)

// Control scanning
sdk.startScanning()
sdk.stopScanning()

// Foreground service (optional)
sdk.enableForegroundScanning(config: ForegroundScanConfig)
sdk.disableForegroundScanning()

// User properties
sdk.setUserProperties(properties: UserProperties)
sdk.clearUserProperties()

// Status
val isScanning: Boolean = sdk.isScanning
val isConfigured: Boolean = sdk.isConfigured
val syncInterval: Long? = sdk.currentSyncInterval
val scanDuration: Long? = sdk.currentScanDuration
val scanPrecision: ScanPrecision? = sdk.currentScanPrecision
val pauseDuration: Long? = sdk.currentPauseDuration
val isPeriodicScanningEnabled: Boolean = sdk.isPeriodicScanningEnabled
val isForegroundScanningEnabled: Boolean = sdk.isForegroundScanningEnabled
val pendingBatchCount: Int = sdk.pendingBatchCount
```

### BeAroundSDKListener

Implement this interface to receive SDK events:

```kotlin
interface BeAroundSDKListener {
    // Required
    fun onBeaconsUpdated(beacons: List<Beacon>)
    
    // Optional (with default implementations)
    fun onError(error: Exception) {}
    fun onScanningStateChanged(isScanning: Boolean) {}
    fun onAppStateChanged(isInBackground: Boolean) {}
    
    // Sync Lifecycle (v2.2.0)
    fun onSyncStarted(beaconCount: Int) {}
    fun onSyncCompleted(beaconCount: Int, success: Boolean, error: Exception?) {}
    
    // Background Events (v2.2.0)
    fun onBeaconDetectedInBackground(beaconCount: Int) {}
}
```

**New in v2.2.0:**

The SDK now provides callbacks for sync lifecycle and background detection:

```kotlin
class MainActivity : AppCompatActivity(), BeAroundSDKListener {
    
    override fun onSyncStarted(beaconCount: Int) {
        // Called before sending beacons to API
        // Useful for showing "syncing..." UI or sending notifications
        Log.d("BeAround", "Starting sync of $beaconCount beacons")
    }
    
    override fun onSyncCompleted(beaconCount: Int, success: Boolean, error: Exception?) {
        // Called after API sync completes (success or failure)
        if (success) {
            sendNotification("Sync Complete", "$beaconCount beacons sent")
        } else {
            sendNotification("Sync Failed", error?.message ?: "Unknown error")
        }
    }
    
    override fun onBeaconDetectedInBackground(beaconCount: Int) {
        // Called when beacons detected while app is in background
        // Perfect for sending notifications when app is closed
        sendNotification("Beacon Detected", "$beaconCount beacons nearby")
    }
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
    val proximity: Beacon.Proximity,
    val accuracy: Double,
    val timestamp: Date,
    val metadata: BeaconMetadata?,
    val txPower: Int?
) {
enum class Proximity {
    IMMEDIATE,
    NEAR,
    FAR,
    UNKNOWN
    }
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

Background scanning is **automatically enabled** when SDK is configured.

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
- Choose the appropriate `ScanPrecision` for your use case (HIGH for real-time, MEDIUM for balanced, LOW for battery savings)
- Use `enableForegroundScanning()` if you need reliable background scanning with a notification
- Inform users about background location usage in your privacy policy

## Migration from 1.x

Version 2.0.x introduces breaking changes. Follow these steps to migrate:

### 1. Update Dependencies

```gradle
// OLD (v1.x)
implementation 'com.github.Bearound:bearound-android-sdk:1.3.2'

// NEW (v3.0+)
implementation 'com.github.Bearound:bearound-android-sdk:v3.0.0'
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

// NEW (v2.3+)
val sdk = BeAroundSDK.getInstance(context)
sdk.listener = this // implement BeAroundSDKListener
sdk.configure(
    businessToken = "your-business-token",
    scanPrecision = ScanPrecision.MEDIUM,
    maxQueuedPayloads = MaxQueuedPayloads.MEDIUM
)
sdk.startScanning()
```

### 4. Replace Listeners

```kotlin
// OLD (v1.x)
beAround.addBeaconListener(object : BeaconListener {
    override fun onBeaconsDetected(beacons: List<BeaconData>) { }
})

// NEW (v2.3+)
class MainActivity : AppCompatActivity(), BeAroundSDKListener {
    override fun onBeaconsUpdated(beacons: List<Beacon>) { }
    override fun onError(error: Exception) { }
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

// NEW (v2.2)
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

1. Use a lower scan precision:
   - `scanPrecision = ScanPrecision.LOW` — scans only 10s per minute
   - `scanPrecision = ScanPrecision.MEDIUM` — scans 30s per minute (default)
2. Duty cycle scanning is automatic — the SDK manages scan/pause cycles for battery efficiency

### No beacons in background

1. Ensure `ACCESS_BACKGROUND_LOCATION` permission is granted
2. For better results, ask users to disable battery optimization for your app in device settings

### App crashes with SecurityException

- Ensure `ACCESS_NETWORK_STATE` permission is in AndroidManifest.xml
- This was a known issue in pre-2.0 versions

## ⚠️ Technical Pending Issues

Due to Android system restrictions and manufacturer-specific behaviors, the following limitations currently apply:

### 1. Background scanning with app fully closed (Android version)

- **Background beacon scanning when the app is fully closed is supported only on Android 14 (API 34) and above.**
- On Android versions **below 14**, the system does not reliably wake the app for BLE beacon detection without a foreground service or notification.
- This is a platform-level limitation imposed by Android background execution policies.

**Impact:** Devices running Android 13 or lower may not detect beacons when the app is fully closed.

---

### 2. Battery Saver / Power Optimization modes

- When **Battery Saver**, **Power Saving**, or manufacturer-specific optimization modes are enabled (e.g. Samsung, Xiaomi, Oppo),
  the system may place the app into a **hibernation / deep sleep state** when the device is locked.
- In this state, **BLE scanning is automatically interrupted by the system**, even if background scanning is enabled.
- This behavior is **outside the SDK’s control** and varies by device manufacturer and OS version.

**Impact:** Beacon scanning may stop when the device screen is locked with battery optimization enabled.

---

### Summary

| Scenario | Supported |
|--------|---------|
| App in foreground | ✅ Yes |
| App in background (in memory) | ✅ Yes |
| App closed (Android 14+) | ✅ Yes |
| App closed (Android <= 13) | ❌ No |
| Battery saver / hibernation enabled | ⚠️ Limited |

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for detailed version history.

### Version 3.0.0 (Latest)

- 🆕 **Hybrid wake-up parity with iOS SDK 3.0.0**: kernel-registered `PendingIntent` BLE scan survives force-stop and swipe-from-recents on Android 14+ without a foreground service
- 🆕 **`BLUETOOTH_SCAN` with `neverForLocation`**: beacon detection no longer implies Location authorization on Android 12+
- 🆕 **Surface BT detections via listener**: `BluetoothManager` now feeds `onBeaconsUpdated` / `onBeaconDetectedInBackground` even when the SDK is not actively ranging, with `Beacon.Proximity.BT`
- 🆕 **OEM caveat matrix** in README (Samsung, Xiaomi, Huawei, OnePlus) + `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` mitigation
- ⚠️ **Breaking**: SDK manifest no longer declares `BLUETOOTH_CONNECT` — host apps that need it must declare it themselves
- ⚠️ **Breaking**: default user-facing SDK strings (foreground service notification, `ForegroundScanConfig`, `SDKConfigStorage` fallbacks) are now English — override via `ForegroundScanConfig`/`NotificationContent` if you need a specific locale

### Version 2.4.0 (2026-05-22)

- 🆕 **Beacon-gated GPS + active BLE scan**: only run while inside a beacon region (kernel filter scan stays on, ~0 battery outside)
- 🆕 **Listener surface**: `onEnterBeaconRegion`, `onExitBeaconRegion`, `onActiveScanStateChanged`, `onStartLocationCapture`, `onCompleteLocationCapture`
- 🆕 **`LocationCaptureResult` public model** + `BeaconManager.isInBeaconRegion`

### Version 2.3.7 (2026-02-26)

- 🆕 **Scan Precision**: Replaced `foregroundScanInterval`/`backgroundScanInterval` with `ScanPrecision` enum (HIGH, MEDIUM, LOW)
- 🆕 **Duty Cycle Architecture**: Scan/pause cycle model for battery-efficient scanning
- 🆕 **Foreground Service**: Optional `ForegroundScanConfig` for persistent background scanning with notification
- 🆕 **enableForegroundScanning/disableForegroundScanning**: New methods for foreground service control
- ⚠️ **Breaking**: `configure()` no longer accepts `foregroundScanInterval` or `backgroundScanInterval` — use `scanPrecision` instead

### Version 2.2.1 (2026-01-20)

- ⚠️ **Breaking**: Removed `onSyncStatusUpdated` callback (battery optimization - was firing every second)
- 🔧 Fixed lint warnings in sync timer logic

### Version 2.2.0 (2026-01-17)

- 🆕 **WorkManager Integration**: Periodic background sync every 15 minutes
- 🆕 **AlarmManager Watchdog**: Secondary mechanism for reliable background operation
- 🆕 **Boot Completed Receiver**: Automatic restart after device reboot
- 🆕 **Persistent Batch Storage**: Failed batches saved to disk (survives app kill)
- 🆕 **Sync Lifecycle Callbacks**: `onSyncStarted` and `onSyncCompleted` methods
- 🆕 **Background Detection Callback**: `onBeaconDetectedInBackground` method
- ⚠️ **Breaking**: Removed `enableBluetoothScanning` parameter (now automatic)
- ⚠️ **Breaking**: Removed `enablePeriodicScanning` parameter (now automatic)

### Version 2.1.0 (2026-01-13)

- 🆕 **Configurable Scan Intervals**: Separate foreground/background intervals
- 🆕 **Configurable Retry Queue**: Control failed payload queue size
- 🔧 **Improved Lifecycle Detection**: Using `ProcessLifecycleOwner`

### Version 2.0.2 (2026-01-08)

- 🆕 **Background Scanning**: Beacon detection when app is closed
- 🐛 Fixed scanning restart bug after `stopScanning()` is called

### Version 2.0.0 (2025-12-30)

- 🚀 Complete SDK rewrite with new architecture
- ✨ Native Android Bluetooth LE (no external dependencies)
- ⚠️ Breaking changes - not compatible with v1.x

## License

MIT License - see LICENSE file for details

## Support

For issues, questions, or contributions, please visit:
https://github.com/Bearound/bearound-android-sdk
