# 🐻 BeAround Android SDK

[![JitPack](https://jitpack.io/v/Bearound/bearound-android-sdk.svg)](https://jitpack.io/#Bearound/bearound-android-sdk)
[![API](https://img.shields.io/badge/API-23%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=23)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Kotlin SDK for Android — secure BLE beacon detection and indoor positioning by BeAround.

## What the SDK detects

The SDK detects **Bearound proprietary BLE beacons only**. The scan filter matches
advertisements carrying the Bearound identifier `0xBEAD` — as **service data** (16-bit
service UUID `0xBEAD`, an 11-byte payload with major/minor, firmware, battery, motion and
temperature) or as **manufacturer data** (manufacturer ID `0xBEAD`).

> ⚠️ **Generic iBeacons are NOT detected.** An iPhone (or any app) advertising a classic
> iBeacon frame — even one using the Bearound UUID `E25B8D3C-947A-452F-A13F-589CB706D2E5` —
> will not show up. That UUID is a fixed label attached to the `Beacon` model after parsing;
> it plays no role in detection. To test the SDK you need a physical Bearound beacon.

## Requirements

- Android 6.0+ (API 23+)
- Bluetooth LE hardware (the SDK manifest declares `bluetooth_le` as a required feature)
- A Bearound **business token** (see [Getting a business token](#getting-a-business-token))
- **Android 12+**: the `BLUETOOTH_SCAN` runtime permission ("Nearby devices") is what unlocks
  beacon detection. It is declared with `neverForLocation` — **location permission is neither
  required nor useful on 12+; it does not unlock the scan**.
- **Android ≤ 11**: the legacy BLE gate is `ACCESS_FINE_LOCATION` (there is no
  `BLUETOOTH_SCAN` before API 31).

### Support matrix

| Android version | Detection without foreground service | Detection with foreground service | After force-stop / swipe-from-recents |
|---|---|---|---|
| 6.0 – 7.1 (API 23 – 25) | Foreground + background while the process is alive (no `PendingIntent` scan — requires API 26+) | ✅ persistent notification keeps the process scanning | ❌ |
| 8.0 – 13 (API 26 – 33) | ✅ kernel-registered `PendingIntent` scan wakes the app when a beacon appears | ✅ recommended for reliability | ❌ — force-stop cancels the scan registration |
| 14+ (API 34+) | ✅ `PendingIntent` scan | ✅ optional | ✅ — the kernel-registered scan survives force-stop and swipe-from-recents |

On every version, aggressive OEM battery managers (Xiaomi, Huawei, …) can still evict the
SDK — see [Background reliability](#background-reliability) for the shipped mitigations.

## Getting a business token

The **business token** authenticates your app against the Bearound ingest backend and ties
every detection to your business. It is provisioned by the **Bearound team** together with
your **Control Hub** account (the dashboard where registered devices and detections show up).
If you don't have one, ask your Bearound contact or write to `contact@bearound.com`.

**Behavior with a wrong or missing token:**

- An **empty/blank** token makes `configure()` throw `IllegalArgumentException` immediately.
- A **non-empty but invalid** token is not validated locally: scanning starts normally, but
  every upload to `https://ingest.bearound.io` fails with HTTP 401. You will see it as
  `BeAroundSDK-APIClient` errors in logcat, `onSyncCompleted(success = false, error = ...)`
  on the listener, and a growing `pendingBatchCount` — and the device never appears in the
  Control Hub. If nothing shows up in the Hub, check the token first.

> The sample app in [`app/`](app/README.md) reads the token from `local.properties`
> (`BUSINESS_TOKEN=...`) so it never gets committed.

## Installation

### JitPack

1. Add the JitPack repository to your root `build.gradle`:

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

2. Add the dependency (the version badge above always points at the latest release):

```gradle
dependencies {
    implementation 'com.github.Bearound:bearound-android-sdk:v3.4.5'
}
```

## Permissions

**You don't need to declare any permission yourself.** The SDK ships them in its own
`AndroidManifest.xml` and Gradle's manifest merge injects them into your app:

| Merged into your app | Purpose |
|---|---|
| `BLUETOOTH`, `BLUETOOTH_ADMIN` (`maxSdkVersion=30`) | Legacy Bluetooth, Android ≤ 11 |
| `BLUETOOTH_SCAN` (`neverForLocation`) | BLE scan on Android 12+, no location implied |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` (`maxSdkVersion=30`) | Legacy BLE-scan gate, Android ≤ 11 only |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Upload to the ingest API |
| `POST_NOTIFICATIONS` | Foreground-service notification on Android 13+ |
| `RECEIVE_BOOT_COMPLETED` | Re-arm scanning after reboot |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Optional foreground service (see [Google Play review](#google-play-review--what-the-manifest-merge-means-for-your-app)) |

The SDK deliberately does **not** declare `ACCESS_BACKGROUND_LOCATION` (background detection
on Android 12+ does not use location) and does **not** declare `BLUETOOTH_CONNECT` (add it to
your own manifest only if your app does GATT operations).

What you DO need to do is **request the runtime permissions** — see the Quick Start below.

## Quick Start

One activity, end to end: request the right runtime permission for the OS version, configure,
start scanning.

```kotlin
import android.Manifest
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import io.bearound.sdk.BeAroundSDK
import io.bearound.sdk.interfaces.BeAroundSDKListener
import io.bearound.sdk.models.Beacon

class MainActivity : AppCompatActivity(), BeAroundSDKListener {

    private lateinit var sdk: BeAroundSDK

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // The permission that actually unlocks detection:
        val scanUnlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            grants[Manifest.permission.BLUETOOTH_SCAN] == true
        } else {
            grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        }
        if (scanUnlocked) {
            sdk.startScanning()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sdk = BeAroundSDK.getInstance(this)
        sdk.listener = this

        // Throws IllegalArgumentException if the token is blank.
        sdk.configure(businessToken = "your-business-token")

        requestScanPermissions()
    }

    private fun requestScanPermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+: BLUETOOTH_SCAN ("Nearby devices") is THE detection permission.
                // Location does NOT unlock the scan on 12+.
                add(Manifest.permission.BLUETOOTH_SCAN)
            } else {
                // Android <= 11: FINE_LOCATION is the legacy BLE-scan gate.
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+: without this the (optional) foreground-service
                // notification is silently invisible.
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    // Minimal listener — see the API Reference for all 11 callbacks.
    override fun onBeaconsUpdated(beacons: List<Beacon>) {
        beacons.forEach { b -> Log.d("BeAround", "Beacon ${b.major}.${b.minor} rssi=${b.rssi}") }
    }

    override fun onError(error: Exception) {
        Log.e("BeAround", "SDK error: ${error.message}")
    }
}
```

Do **not** request `ACCESS_BACKGROUND_LOCATION`: background detection on Android 12+ runs on
`BLUETOOTH_SCAN`, and declaring/requesting background location drags your app into Google
Play's background-location review (declaration form + demo video) for zero detection benefit.

### User identity (`internalId`)

`internalId` is **your own id for the user** (e.g. from your CRM) — a user property. Set it
(and any other user data) via `setUserProperties` right after `configure()`, so every beacon
event is tied back to that user on the backend:

```kotlin
import io.bearound.sdk.models.UserProperties

sdk.configure(businessToken = "your-business-token")
sdk.setUserProperties(UserProperties(internalId = "user123"))

// Discovered more later? Call it again — fields you omit are kept:
sdk.setUserProperties(
    UserProperties(email = "user@example.com", name = "John Doe",
                   customProperties = mapOf("plan" to "premium"))
)

// Clear everything on logout (also clears the persisted id)
sdk.clearUserProperties()
```

- `setUserProperties` **merges** — omitted fields are kept, so adding `email`/`name` later
  does **not** wipe a previously-set `internalId`.
- `internalId` is **persisted** and restored when Android relaunches the SDK after an app
  kill / reboot, so background events stay attributed to the user.

### Push notifications (FCM)

If your app ships Firebase, `configure(...)` auto-collects the FCM token and sends it on the
next sync. Firebase is a soft dependency (`compileOnly`): apps without it are unaffected. If
you don't use Firebase, provide the token yourself:

```kotlin
sdk.setPushToken(token)
```

Calling `setPushToken` **after** `startScanning()` is fine — since 3.4.1 the SDK pushes a
new/changed token to the backend immediately instead of waiting for the next sync cycle.

## Background scanning

**Background scanning is automatically enabled** by `startScanning()`. On Android 8+ the SDK
registers a kernel-managed, low-power `PendingIntent` BLE scan filtered on `0xBEAD`: the OS
wakes the app (a fresh process if needed) when a Bearound beacon comes into range — the
Android equivalent of iOS's `CLBeaconRegion` monitoring. No notification required.

### Terminated app detection

| Scenario | Detection still works? | Mechanism |
|---|---|---|
| App in foreground | ✅ | `BluetoothLeScanner` with `ScanCallback` |
| App in background | ✅ | Same callback, OS keeps process alive briefly |
| App killed by **system** (memory / battery pressure) | ✅ on Android 8+ | `PendingIntent` broadcast scan → `BluetoothScanReceiver` wakes a fresh process |
| App killed by **user** (swipe from recents) | ✅ on **Android 14+** | Same `PendingIntent` scan — kernel-registered, survives swipe |
| App **force-stopped** in Settings | ✅ on **Android 14+** | Same `PendingIntent` scan, re-registered on next interaction |
| After device reboot | ✅ | `BOOT_COMPLETED` → `ScanWatchdogReceiver` re-arms the scan |

**Architecture under the hood:**

- **`BluetoothScanReceiver`** — wakes the app via `PendingIntent` when a `0xBEAD` beacon is
  observed by the OS scanner. Requires only `BLUETOOTH_SCAN` (`neverForLocation`) — **no
  Location authorization at all** on Android 12+.
- **`ScanWatchdogReceiver`** — `AlarmManager` heartbeat (inexact alarms — no exact-alarm
  permission, no Play policy impact) plus `BOOT_COMPLETED` listener. Re-registers the BLE
  scan filter if it ever gets evicted by the OS.
- **`BeaconScanService`** — optional `connectedDevice` foreground service for apps that need
  maximum reliability on Android 13 or below, or a visible "scanning" indicator.

### Foreground service (optional)

For a persistent notification while scanning in background, either pass the config to
`startScanning(...)` or call `enableForegroundScanning(...)`:

```kotlin
import io.bearound.sdk.models.ForegroundScanConfig

sdk.startScanning(
    ForegroundScanConfig(
        notificationTitle = "",                          // "" = host app name
        notificationText = "Scanning for nearby content",
        notificationIcon = R.drawable.ic_notification,   // optional
        notificationChannelId = "beacon_channel",        // optional
        notificationChannelName = "Beacon Monitoring"    // optional
    )
)

// Or toggle it independently of startScanning:
sdk.enableForegroundScanning(config)
sdk.disableForegroundScanning()
```

The service starts when the app goes to background and stops when it returns to foreground.
When beacons are detected in background, the SDK asks the listener for contextual
notification content via `onProvideNotificationContent(beacons)` — return a
`NotificationContent(title, text)` or `null` to keep the defaults.

Safety (3.4.4/3.4.5): the service checks Bluetooth permission before promoting itself and
catches `ForegroundServiceStartNotAllowedException` / `SecurityException` — revoking "Nearby
devices" no longer crash-loops the host app; detection falls back to the `PendingIntent` scan.

### Google Play review — what the manifest merge means for your app

Because of the manifest merge, **every app that embeds this SDK carries
`FOREGROUND_SERVICE_CONNECTED_DEVICE` in its merged manifest — even if it never uses the
foreground service.** Consequences on the Play Console:

- **targetSdk 34+**: Play requires you to **declare every foreground-service type** present
  in the manifest, including a **video demonstrating the user-facing feature** that justifies
  the `connectedDevice` type (e.g. a screen showing live beacon detection). Budget for this
  in your release plan — it surfaces at review time, not at build time.
- If your app does **not** use the foreground service (never calls
  `enableForegroundScanning()` / `startScanning(config)`), you can strip the permission and
  skip the declaration entirely:

```xml
<!-- In your app's AndroidManifest.xml (needs xmlns:tools) -->
<uses-permission
    android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"
    tools:node="remove" />
```

  After removing it, do **not** call the foreground-service APIs — the service cannot start
  without the permission. All other detection paths (foreground scan, `PendingIntent`
  background scan) are unaffected.
- The SDK also merges `<uses-feature android:name="android.hardware.bluetooth_le"
  android:required="true" />`, which **filters your app out of the Play Store on devices
  without BLE**. That is correct for beacon-driven apps; if your app must remain installable
  on such devices, override the feature with `tools:node` and `required="false"` and gate the
  SDK calls at runtime.

### Background reliability

Keeping the process eligible to wake under Doze and aggressive OEM battery managers is the
real Android equivalent of the resilience the iOS "second eye" (Location monitoring)
provides — and it needs **no location permission**. The SDK exposes:

```kotlin
// Battery optimization — opens the system Settings screen so the user can exempt the app.
// Uses ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS (the Settings list), NOT the restricted
// REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission — so it triggers no Google Play review.
if (!sdk.isIgnoringBatteryOptimizations()) {
    sdk.openBatteryOptimizationSettings()
}

// OEM autostart / protected-apps — deep-links to the manufacturer's screen when one exists
// (Xiaomi/MIUI, Huawei, Oppo/Vivo, OnePlus, Letv). Returns false on stock Android (Pixel)
// or unmapped OEMs — where the battery-optimization screen above already covers it.
if (sdk.isAutostartManageable()) {
    sdk.openManufacturerAutostartSettings()
}
```

> **Samsung** is intentionally not in the autostart deep-link list: its "app power
> management" screen requires the system permission `READ_SEARCH_INDEXABLES`, which
> third-party apps cannot hold. On Samsung, use `openBatteryOptimizationSettings()` (which
> works) and optionally guide the user to add the app to "Never sleeping apps" manually.

#### OEM caveat matrix

Stock Android (Pixel) honors the `PendingIntent` scan exactly as documented. Several OEMs
ship aggressive battery managers that kill third-party `PendingIntent` and broadcast
receivers regardless of Android version:

| OEM | Behavior | Mitigation |
|---|---|---|
| Samsung (One UI 6+) | Generally honors the scan; some restrictions on apps marked "Sleeping" | Ask user to add app to "Never sleeping apps" |
| Xiaomi / Redmi (MIUI / HyperOS) | Aggressively kills background broadcast receivers | `openManufacturerAutostartSettings()` + lock app in recents |
| Huawei / Honor (EMUI / HarmonyOS) | Same as Xiaomi | `openManufacturerAutostartSettings()` → "Manage manually" |
| OnePlus (OxygenOS 11+) | Less aggressive but still restricts | Disable "Deep optimization" for the app |
| Pixel / Stock | Honors PendingIntent scan | No action |

See [dontkillmyapp.com](https://dontkillmyapp.com) for the full per-vendor matrix.

## Configuration options

### Scan precision

Controls the duty cycle and sync interval:

```kotlin
sdk.configure(
    businessToken = "your-business-token",
    scanPrecision = ScanPrecision.MEDIUM // Default
)
```

| Mode | Scan pattern | Sync interval | Use case |
|------|-------------|---------------|----------|
| `HIGH` | Continuous scanning (no pauses) | Every 15s | Maximum detection, higher battery usage |
| `MEDIUM` | 3× (10s scan + 10s pause) per 60s window | Every 60s | Balanced detection and battery (default) |
| `LOW` | 1× (10s scan + 50s pause) per 60s window | Every 60s | Maximum battery savings |

> Note: the Android **native default is `MEDIUM`**. The iOS SDK and the Flutter/RN wrappers
> default to `HIGH` — set the precision explicitly if you need identical cadence cross-stack.

Active scanning is additionally **gated by beacon-region presence**: outside a region only
the kernel-level filter scan runs (~zero battery); the duty cycle starts on the first
detection (`onEnterBeaconRegion`) and stops when the zone goes silent (`onExitBeaconRegion`,
after a 5-minute grace).

### Retry queue size

Failed upload batches are persisted to disk (FIFO, survives kill/reboot, auto-cleanup after
7 days) up to a configurable limit:

```kotlin
sdk.configure(
    businessToken = "your-business-token",
    maxQueuedPayloads = MaxQueuedPayloads.MEDIUM // Default: 100
)
```

Available sizes: `SMALL` (50), `MEDIUM` (100, default), `LARGE` (200), `XLARGE` (500).

```kotlin
// Inspect the queue
val pendingCount: Int = sdk.pendingBatchCount
val batches: List<List<Beacon>> = sdk.pendingBatches
```

### Bluetooth metadata scanning

Beacon metadata (battery, temperature, firmware, movements) arrives in the same `0xBEAD`
service-data payload and is exposed via `Beacon.metadata`. No configuration needed.

## API Reference

### BeAroundSDK

Main SDK class (singleton).

```kotlin
// Instance
val sdk = BeAroundSDK.getInstance(context)
sdk.listener = myListener            // BeAroundSDKListener

// Configuration — throws IllegalArgumentException on blank token
sdk.configure(
    businessToken: String,
    scanPrecision: ScanPrecision = ScanPrecision.MEDIUM,
    maxQueuedPayloads: MaxQueuedPayloads = MaxQueuedPayloads.MEDIUM,
    technology: String = "android-native" // payload tag; wrappers override it
)

// Scanning
sdk.startScanning(foregroundScanConfig: ForegroundScanConfig? = null)
sdk.stopScanning()

// Foreground service
sdk.enableForegroundScanning(config: ForegroundScanConfig)
sdk.disableForegroundScanning()

// User identity & push
sdk.setUserProperties(properties: UserProperties)   // merges; internalId is persisted
sdk.clearUserProperties()
sdk.setPushToken(token: String)                     // re-registers immediately if scanning

// Background reliability (Doze / OEM killers)
sdk.isIgnoringBatteryOptimizations(): Boolean
sdk.openBatteryOptimizationSettings(): Boolean
sdk.isAutostartManageable(): Boolean
sdk.openManufacturerAutostartSettings(): Boolean

// Diagnostics & helpers
sdk.diagnostics(): BeAroundDiagnostics
sdk.isLocationAvailable(): Boolean                  // GPS/network provider enabled?
sdk.getLocationPermissionStatus(): String           // "authorized_always" | "authorized_when_in_use" | "denied"

// Status properties
val isScanning: Boolean = sdk.isScanning
val isConfigured: Boolean = sdk.isConfigured
val syncInterval: Long? = sdk.currentSyncInterval          // ms
val scanDuration: Long? = sdk.currentScanDuration          // ms
val scanPrecision: ScanPrecision? = sdk.currentScanPrecision
val pauseDuration: Long? = sdk.currentPauseDuration        // ms
val isPeriodic: Boolean = sdk.isPeriodicScanningEnabled    // true unless HIGH
val isFgs: Boolean = sdk.isForegroundScanningEnabled
val pendingBatchCount: Int = sdk.pendingBatchCount
val pendingBatches: List<List<Beacon>> = sdk.pendingBatches
```

### BeAroundSDKListener

All callbacks except `onBeaconsUpdated` have default no-op implementations — override what
you need:

```kotlin
interface BeAroundSDKListener {
    // Detection
    fun onBeaconsUpdated(beacons: List<Beacon>)                 // required
    fun onBeaconDetectedInBackground(beaconCount: Int) {}

    // Errors & state
    fun onError(error: Exception) {}
    fun onScanningStateChanged(isScanning: Boolean) {}
    fun onAppStateChanged(isInBackground: Boolean) {}

    // Sync lifecycle
    fun onSyncStarted(beaconCount: Int) {}
    fun onSyncCompleted(beaconCount: Int, success: Boolean, error: Exception?) {}

    // Beacon region (v2.5+) — the "geofence" signals
    fun onEnterBeaconRegion() {}                                // rising edge: first beacon
    fun onExitBeaconRegion() {}                                 // falling edge: zone silent
    fun onActiveScanStateChanged(isActive: Boolean) {}          // duty cycle on/off

    // Foreground-service notification (v2.4+)
    fun onProvideNotificationContent(beacons: List<Beacon>): NotificationContent? = null
}
```

### Diagnostics

`diagnostics()` returns a point-in-time snapshot for support/triage. `summary()` renders it
as a printable multi-line string.

```kotlin
data class BeAroundDiagnostics(
    val deviceId: String,
    val pushTokenMasked: String?,       // masked, never the raw token
    val pushTokenLastSentAt: Long?,     // epoch ms
    val isScanning: Boolean,
    val pendingBatches: Int,
    val lastScanAt: Long?,
    val lastScanBeaconCount: Int?,
    val lastSyncAt: Long?,
    val lastSyncSuccess: Boolean?,
    val lastSyncBeaconCount: Int?,
    val recentErrors: List<String>,
    val sdkVersion: Int                 // ⚠️ this is the OS API level (Build.VERSION.SDK_INT),
                                        // not the SDK release version
)
```

### Models

#### Beacon

```kotlin
data class Beacon(
    val uuid: UUID,                     // always E25B8D3C-… (fixed label, not a filter)
    val major: Int,
    val minor: Int,
    val rssi: Int,                      // smoothed
    val proximity: Proximity,
    val accuracy: Double,               // estimated distance (m); -1.0 when unknown
    val timestamp: Date = Date(),
    val metadata: BeaconMetadata? = null,
    val txPower: Int? = null,
    val alreadySynced: Boolean = false, // true after a successful upload
    val syncedAt: Date? = null,
    val rssiRaw: Int? = null,           // last unsmoothed sample
    val rssiSamples: RssiStats? = null, // per-sync-window RSSI stats
    val isStale: Boolean = false        // no packet for >5s but still within timeout
) {
    enum class Proximity { IMMEDIATE, NEAR, FAR, BT, UNKNOWN }
    val identifier: String              // "major.minor"
}
```

`Proximity.BT` marks detections surfaced by the auxiliary BLE scanner while the main ranging
is not active (e.g. woken from background) — distance is unknown (`accuracy = -1.0`).

#### RssiStats

Per-beacon RSSI statistics accumulated over each sync window (Welford, constant memory):
`count`, `min`, `max`, `avg`, `stdDev`, `firstSeen`, `lastSeen`.

#### BeaconMetadata

```kotlin
data class BeaconMetadata(
    val firmwareVersion: String,
    val batteryLevel: Int,
    val movements: Int,
    val temperature: Int,
    val txPower: Int? = null,
    val rssiFromBLE: Int? = null,
    val isConnectable: Boolean? = null
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

#### ForegroundScanConfig / NotificationContent

```kotlin
data class ForegroundScanConfig(
    val enabled: Boolean = false,                      // set true by enableForegroundScanning
    val notificationTitle: String = "",                // "" = host app name
    val notificationText: String = "Scanning for nearby content",
    val notificationIcon: Int? = null,
    val notificationChannelId: String? = null,
    val notificationChannelName: String = "Region monitoring service"
)

data class NotificationContent(val title: String, val text: String)
```

## Troubleshooting

### Beacons not detected

1. **Are you testing with a real Bearound beacon?** The SDK only detects `0xBEAD`
   service/manufacturer data. A phone simulating a generic iBeacon — even with the "right"
   UUID — is invisible to the scan filter (see [What the SDK detects](#what-the-sdk-detects)).
2. **Android 12+**: is `BLUETOOTH_SCAN` ("Nearby devices") granted? That is the only
   permission that unlocks the scan — granting location does **not** help on 12+.
   **Android ≤ 11**: is `ACCESS_FINE_LOCATION` granted and location services on?
3. Is Bluetooth enabled?
4. Are you on a physical device? Emulators have no usable BLE.
5. Call `sdk.diagnostics()` and check `lastScanAt` / `recentErrors`.

### Device doesn't appear in the Control Hub / sync fails

1. **Check the business token.** A wrong token fails every upload with HTTP 401 — filter
   logcat by `BeAroundSDK-APIClient`. There is no local validation of the token value.
2. Check internet connectivity; failed batches accumulate in `pendingBatchCount` and are
   retried with backoff.
3. Watch `onSyncCompleted(beaconCount, success, error)` on the listener.

### High battery usage

1. Use a lower scan precision (`ScanPrecision.LOW` scans 10s per minute; `MEDIUM` 30s).
2. Duty cycle and region gating are automatic — outside a beacon zone only the kernel filter
   scan runs.

### No beacons in background

1. Ensure `BLUETOOTH_SCAN` ("Nearby devices") is granted (Android 12+) — that is what
   unlocks detection, **not** location.
2. `PendingIntent` background scan requires Android 8+ (API 26+); force-stop survival
   requires Android 14+ (see the [support matrix](#support-matrix)).
3. On aggressive OEMs (Xiaomi, Huawei, Samsung…), run the
   [Background reliability](#background-reliability) flow: `openBatteryOptimizationSettings()`
   and `openManufacturerAutostartSettings()`.

### App crashes with SecurityException

- **Foreground-service crash (fixed in 3.4.4/3.4.5):** on Android 14+, enabling foreground
  scanning and then revoking "Nearby devices" used to crash the app in a loop (`Starting FGS
  with type connectedDevice ... requires ... BLUETOOTH_SCAN`). Since 3.4.4/3.4.5 the SDK
  checks the permission before starting the service and catches both `SecurityException` and
  `ForegroundServiceStartNotAllowedException`, degrading gracefully. If you see this crash,
  update the SDK.

## Migrating from 2.x to 3.x

3.x is the hybrid wake-up generation (kernel-registered `PendingIntent` scan, force-stop
survival on Android 14+, `neverForLocation`). Update the dependency to the latest 3.x tag,
then review:

1. **`BLUETOOTH_CONNECT` is no longer declared by the SDK manifest** (3.0.0). If your app
   uses GATT operations of its own, declare it in your own manifest.
2. **Default user-facing strings are English** (3.0.0). If you relied on the old Portuguese
   foreground-notification copy, pass your localized strings via `ForegroundScanConfig` /
   `NotificationContent`.
3. **Location-capture APIs were removed** (3.3.1): `LocationCaptureResult`,
   `onStartLocationCapture`, `onCompleteLocationCapture` no longer exist. Delete any
   overrides — the SDK no longer touches GPS at all.
4. **Permission requests**: stop requesting location on Android 12+; request `BLUETOOTH_SCAN`
   instead (see Quick Start). Remove any `ACCESS_BACKGROUND_LOCATION` requests made for the
   SDK's benefit.
5. Coming from **2.3.6 or older**: `configure()` takes `scanPrecision: ScanPrecision` instead
   of `foregroundScanInterval`/`backgroundScanInterval` (changed in 2.3.7).
6. Coming from **1.x**: the entry point is `BeAroundSDK.getInstance(context)` +
   `configure(businessToken = ...)` + `BeAroundSDKListener` — see the Quick Start; the old
   `BeAround.initialize`/`BeaconListener` API is gone.

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for the full version history. Recent highlights:

- **3.4.5** — hardened foreground-service start; `BLUETOOTH_SCAN` required (and sufficient) on Android 12+
- **3.4.4** — FGS crash-loop fix; background-reliability helpers (battery-opt + OEM autostart)
- **3.4.2** — inexact watchdog alarms; `USE_EXACT_ALARM` removed (Play policy)
- **3.4.1** — `setPushToken` pushes the token immediately when already scanning
- **3.4.0** — device register on `startScanning()`: the device appears in the Control Hub even before the first beacon

## License

MIT License - see LICENSE file for details

## Support

For issues, questions, or contributions, please visit:
https://github.com/Bearound/bearound-android-sdk
