# Changelog

All notable changes to the BeAround Android SDK will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [2.3.7] - 2026-02-26

### ⚠️ Breaking Changes

- **Replaced `ForegroundScanInterval`/`BackgroundScanInterval` with `ScanPrecision`**: Single unified enum (HIGH/MEDIUM/LOW) replaces separate foreground/background interval enums for cross-platform consistency with iOS SDK
  - `ScanPrecision.HIGH` — Continuous BLE+Beacon scan, sync every 15s
  - `ScanPrecision.MEDIUM` — 3x (10s scan + 10s pause) per minute, sync every 60s
  - `ScanPrecision.LOW` — 1x (10s scan + 50s pause) per minute, sync every 60s
- **`configure()` API changed**: Now takes `scanPrecision: ScanPrecision` instead of `foregroundScanInterval`/`backgroundScanInterval`

### Added

- **Duty cycle system**: New scan/pause scheduling with `pauseRanging()`/`resumeRanging()` on BeaconManager and `pauseScanning()`/`resumeScanning()` on BluetoothManager
- **Detection Log screen** (BearoundScan app): Full log of beacon detections with FG/BG mode filter, Service UUID/iBeacon type filter, detail and grouped-by-minute views
- **Tab navigation** (BearoundScan app): Bottom navigation with Beacons and Log tabs
- **Pending/Synced beacon sections** (BearoundScan app): Beacons split into color-coded sections by sync status
- **Beacon age indicator** (BearoundScan app): Detection time, age in seconds (color-coded), and sync timestamp on each beacon row

### Changed

- **SDKConfiguration**: Replaced `foregroundScanInterval`/`backgroundScanInterval` fields with `scanPrecision`, added computed properties (`precisionScanDuration`, `precisionPauseDuration`, `precisionCycleCount`, `syncInterval`)
- **SDKConfigStorage**: New `scan_precision` key with automatic migration from legacy `foreground_interval`/`background_interval` keys
- **BeAroundSDK**: Rewrote `startSyncTimer()` with duty cycle support (`startHighPrecision()`/`startDutyCycle()`), new public properties `currentScanPrecision` and `currentPauseDuration`

## [2.3.6] - 2026-02-20

### Added

- **Retry Queue tab**: New bottom navigation tab in BeAroundScan app to visualize pending offline batches
  - Badge with pending batch count on navigation item
  - Batch cards with red-tinted background showing individual beacons
  - Refresh button and empty state ("Fila vazia")
  - Auto-refresh on tab selection
- **`pendingBatches` property**: Exposed `List<List<Beacon>>` on `BeAroundSDK` for reading all stored offline batches
- **`refreshRetryQueue()` method**: Added to `BeaconViewModel` to refresh retry queue state, auto-called after sync

### Changed

- **Chunked retry sync**: Failed batches are now sent in chunks of 5 (instead of one-by-one), sequentially — stops on first failure, successfully sent chunks are removed immediately
- **Beacon model**: Added `getDistance()` method and distance calculation
- **Background scanning**: Added background scanning callbacks
- **Sync completed callback**: Added `onSyncCompleted` listener callback

## [2.3.5] - 2026-02-19

### Changed

- Version bump to 2.3.5 for stable release

## [2.3.2] - 2026-02-19

### Fixed

- **Lint NewApi error**: Added `@RequiresApi(Build.VERSION_CODES.O)` to `enableBluetoothScanBroadcast()` and `disableBluetoothScanBroadcast()` in `BackgroundScanManager` to fix lint errors for PendingIntent-based BLE scan methods requiring API 26+

### Added

- **BearoundScan app**: New sample/diagnostic app for BLE beacon scanning with Jetpack Compose UI

### Changed

- **Beacon structure**: Major changes to beacon data structure

## [2.2.2] - 2026-01-22

### Changed

- **5s Interval Continuous Mode**: When `foregroundScanInterval` is set to 5 seconds, the SDK now operates in continuous mode (scanDuration = 5s, pauseDuration = 0s) for real-time beacon detection without pauses.
- **Beacon Persistence**: Collected beacons are no longer cleared after sync. This allows continuous tracking of beacon presence and prevents gaps in detection during rapid scans.

### Technical Details

- Modified `SDKConfiguration.scanDuration()` to return full interval when interval == 5s in foreground
- Removed `collectedBeacons.clear()` from sync operations to maintain beacon state

---

## [2.2.1] - 2026-01-20

### ⚠️ Breaking Changes

- **Removed `onSyncStatusUpdated` callback**: Countdown updates every second were consuming unnecessary battery. Apps should no longer rely on this callback for UI updates.

## [2.2.0] - 2026-01-17

### ⚠️ Breaking Changes

- **Removed `enableBluetoothScanning` parameter**: Bluetooth metadata scanning is now always enabled when available
- **Removed `enablePeriodicScanning` parameter**: Periodic scanning behavior is now automatic based on app state

**Before (v2.1.x):**
```kotlin
sdk.configure(
    businessToken = "token",
    foregroundScanInterval = ForegroundScanInterval.SECONDS_30,
    backgroundScanInterval = BackgroundScanInterval.SECONDS_90,
    maxQueuedPayloads = MaxQueuedPayloads.LARGE,
    enableBluetoothScanning = true,    // ❌ REMOVED
    enablePeriodicScanning = true      // ❌ REMOVED
)
```

**After (v2.2.0):**
```kotlin
sdk.configure(
    businessToken = "token",
    foregroundScanInterval = ForegroundScanInterval.SECONDS_30,
    backgroundScanInterval = BackgroundScanInterval.SECONDS_90,
    maxQueuedPayloads = MaxQueuedPayloads.LARGE
)
// Bluetooth scanning: always attempts to connect
// Periodic scanning: automatic (enabled in foreground, disabled in background)
```

### Added

- **WorkManager Integration**: Periodic background sync every 15 minutes
  - Uses `androidx.work:work-runtime-ktx:2.9.0`
  - Network-aware: only syncs when network is available
  - Survives app kill and device reboot
  - Complements Bluetooth Scan Broadcast for Android < 14
  
- **AlarmManager Watchdog**: Secondary mechanism for reliable background operation
  - Fires every 15 minutes using `setExactAndAllowWhileIdle`
  - Restarts scanning if it was stopped unexpectedly
  - Syncs pending beacons as fallback
  - Works in Doze mode
  
- **Boot Completed Receiver**: Automatic restart after device reboot
  - Restores SDK configuration from storage
  - Re-enables scanning if it was active before reboot
  - Re-schedules WorkManager and AlarmManager tasks

- **Automatic Bluetooth Scanning**: Bluetooth metadata collection is now always enabled
  - SDK automatically attempts to connect to beacons for metadata (firmware, battery, etc.)
  - No configuration needed - works automatically when permissions are granted
  
- **Automatic Periodic Scanning**: Smart scanning based on app state
  - **Foreground**: Periodic scanning enabled (battery efficient)
  - **Background**: Continuous scanning (better detection for WorkManager/Broadcast triggers)
  - No manual configuration required - adapts automatically

- **Persistent Batch Storage**: Failed batches now saved to disk (like iOS)
  - New `OfflineBatchStorage` class for persistent failed batch storage
  - Stores batches as JSON files in app's private directory
  - FIFO ordering (oldest batch sent first)
  - Auto-cleanup of batches older than 7 days
  - Survives app kill, device reboot, and crashes
  - Thread-safe operations with `ReentrantLock`
  - Respects `maxQueuedPayloads` from configuration

- **Sync Lifecycle Callbacks**: New listener methods for sync monitoring
  - `onSyncStarted(beaconCount: Int)` - Called before sync starts
  - `onSyncCompleted(beaconCount: Int, success: Boolean, error: Exception?)` - Called after sync
  - Enables apps to show notifications or UI updates for sync events
  
- **Background Detection Callback**: New listener method for background beacon detection
  - `onBeaconDetectedInBackground(beaconCount: Int)` - Called when beacons detected in background
  - Enables apps to send notifications even when app is closed

- **Scanning State Persistence**: New methods in `SDKConfigStorage`
  - `saveScanningEnabled(context, enabled)` - Persist scanning state
  - `loadScanningEnabled(context)` - Restore scanning state
  - Enables proper recovery after app kill/reboot

- **BackgroundScheduler**: Unified manager for all background mechanisms
  - `enableAll()` - Enable WorkManager + AlarmManager
  - `disableAll()` - Disable all background tasks
  - `schedulePeriodicSync()` - Configure WorkManager
  - `scheduleWatchdogAlarm()` - Configure AlarmManager
  - Singleton pattern with proper lifecycle management

### Removed

- **`enableBluetoothScanning` parameter from `configure()`**: Bluetooth scanning now automatic
- **`enablePeriodicScanning` parameter from `configure()`**: Periodic scanning now automatic based on app state
- **`setBluetoothScanning(enabled)` method**: No longer needed (always enabled)
- **`isBluetoothScanningEnabled` property**: No longer relevant

### Changed

- **startScanning()**: Now also enables WorkManager and AlarmManager
- **Bluetooth scanning**: Now always attempts to start (no manual toggle needed)
- **Periodic scanning**: Automatically enabled in foreground, disabled in background
- **stopScanning()**: Now also disables WorkManager and AlarmManager
- **Failed batch handling**: Replaced in-memory `failedBatches` list with persistent `OfflineBatchStorage`
  - Batches are now saved to disk instead of RAM
  - Survives app termination and device reboot
  - Better reliability for offline scenarios
- **AndroidManifest.xml**: Added new permissions and receivers
  - `RECEIVE_BOOT_COMPLETED` - For restart after reboot
  - `SCHEDULE_EXACT_ALARM` (Android 12-13)
  - `USE_EXACT_ALARM` (Android 14+)
  - `ScanWatchdogReceiver` for AlarmManager and Boot events

### Dependencies

- **WorkManager**: `androidx.work:work-runtime-ktx:2.9.0`
- **Gson**: `com.google.code.gson:gson:2.10.1` (for offline batch storage)

### Background Architecture

```
┌──────────────────────────────────────────────────────────────┐
│               Android Background Mechanisms                   │
├──────────────┬──────────────┬──────────────┬────────────────┤
│ Bluetooth    │  WorkManager │ AlarmManager │ Boot Completed │
│ Scan Broad-  │  (periodic)  │  (watchdog)  │  (reboot)      │
│ cast (14+)   │              │              │                │
└──────┬───────┴──────┬───────┴──────┬───────┴───────┬────────┘
       │              │              │               │
       ▼              ▼              ▼               ▼
┌──────────────────────────────────────────────────────────────┐
│                      BeAroundSDK                             │
│                                                              │
│  - processBroadcastResults() ← Bluetooth Broadcast           │
│  - performBackgroundSync()   ← WorkManager/AlarmManager      │
│  - restartScanningFromBackground() ← Boot/Watchdog           │
│                                                              │
│                    ↓ All converge to ↓                       │
│                      syncBeacons()                           │
│                           ↓                                  │
│            [Success] → API → Remove from storage             │
│            [Failure] → OfflineBatchStorage (persistent)      │
│                           ↓                                  │
│              timestamp_uuid.json files (FIFO)                │
└──────────────────────────────────────────────────────────────┘
```

### Timing Behavior

| Mechanism | Interval | Guaranteed? | Works in Doze? |
|-----------|----------|-------------|----------------|
| Bluetooth Scan Broadcast | Real-time | ✅ Yes (Android 14+) | ✅ Yes |
| WorkManager | ~15 min | ⚠️ Opportunistic | ✅ Yes |
| AlarmManager | ~15 min | ✅ Exact* | ✅ Yes |

*Exact alarms may be limited to 1/15min in Doze mode

### Notes

- No notification required for any of these mechanisms
- All mechanisms are complementary and work together
- Battery impact is minimal due to intelligent scheduling

---

## [2.1.0] - 2026-01-13

### ⚠️ Breaking Changes

**Configurable Scan Intervals**: SDK now supports separate foreground and background scan intervals with configurable retry queue.

### Added

- **Configurable Scan Intervals**: New enums for fine-grained control over scan behavior
  - `ForegroundScanInterval`: Configure foreground scan intervals from 5 to 60 seconds (in 5-second increments)
  - `BackgroundScanInterval`: Configure background scan intervals (15s, 30s, 60s, 90s, or 120s)
  - Default: 15 seconds for foreground, 30 seconds for background
  
- **Configurable Retry Queue**: New `MaxQueuedPayloads` enum to control retry queue size
  - `.SMALL` (50 failed batches)
  - `.MEDIUM` (100 failed batches) - default
  - `.LARGE` (200 failed batches)
  - `.XLARGE` (500 failed batches)
  - Replaces fixed limit of 10 with configurable options
  - Each batch can contain multiple beacons from a single sync

- **App State Listener**: New `onAppStateChanged(isInBackground: Boolean)` callback in `BeAroundSDKListener`
  - Notifies when app transitions between foreground and background

### Changed

- **Configuration API**: `configure()` method now accepts enum parameters instead of `Long` milliseconds
  - `foregroundScanInterval: ForegroundScanInterval = ForegroundScanInterval.SECONDS_15`
  - `backgroundScanInterval: BackgroundScanInterval = BackgroundScanInterval.SECONDS_30`
  - `maxQueuedPayloads: MaxQueuedPayloads = MaxQueuedPayloads.MEDIUM`
  - Old `syncInterval` parameter removed in favor of separate foreground/background intervals

- **Dynamic Interval Switching**: SDK now automatically switches between foreground and background intervals based on app state
  - Optimizes battery usage in background
  - Provides faster updates in foreground

- **Improved Resilience**: Increased default retry queue from 10 to 100 failed batches

- **SDKConfiguration**: Updated to use enums with new methods:
  - `syncInterval(isInBackground: Boolean): Long` - Get interval based on app state
  - `scanDuration(isInBackground: Boolean): Long` - Get scan duration based on app state
  - Legacy properties deprecated but still available for compatibility

- **SDKConfigStorage**: Updated to persist enum values as numbers with automatic migration from old format

### Migration

**Before (v2.0.2):**
```kotlin
sdk.configure(
    businessToken = "your-business-token-here",
    syncInterval = 30000L  // 30 seconds in milliseconds
)
```

**After (v2.1.0):**
```kotlin
// Using defaults (recommended)
sdk.configure(
    businessToken = "your-business-token-here"
    // FG: 15s, BG: 30s, Queue: 100
)

// Custom configuration
sdk.configure(
    businessToken = "your-business-token-here",
    foregroundScanInterval = ForegroundScanInterval.SECONDS_30,
    backgroundScanInterval = BackgroundScanInterval.SECONDS_90,
    maxQueuedPayloads = MaxQueuedPayloads.LARGE
)
```

### Technical Details

- Scan duration formula unchanged: `scanDuration = max(5s, min(syncInterval / 3, 10s))`
- Backoff retry logic unchanged: exponential backoff with max 60s delay
- All existing scanning and sync behaviors preserved
- Type-safe enum-based configuration for better developer experience
- Automatic configuration migration from v2.0.x format

### Compatibility

- Configuration from v2.0.x is automatically migrated to v2.1.0 format
- Old `syncInterval` value is used as `foregroundScanInterval` during migration
- Background interval defaults to 30s for migrated configurations

---

## [2.0.2] - 2026-01-08

### Added

- **Background Scanning** 🆕 - Beacon detection when app is closed
  - Automatically enabled when SDK is configured
  - Real-time detection without notification
  - Works even after app is killed or device reboot
  - Zero notification intrusion
  - Very battery efficient
- **Configuration Persistence**: SDK configuration saved to SharedPreferences
  - Automatically restored when app wakes up in background
  - Enables background scanning without re-configuration

### Changed

- **Background scanning auto-enabled**: No need to call `enableBackgroundScanning()` manually
- **Real-time beacon detection**: Optimized for immediate beacon detection in background

### Fixed

- **Beacon Scanning**: Fixed issue where scanning would restart after `stopScanning()` was called
  - Added guard clause in `startRanging()` to check `isScanning` state
  - Prevents delayed callbacks from restarting scanning after user stops it
- **"Scanning Too Frequently" Error**: Fixed background scanning throttling
  - Process ScanResults from Intent directly instead of starting new scan
  - Prevents Android throttling when app wakes up via system broadcast

### Notes

- Background scanning tested and working reliably
- Configuration persists across app restarts and device reboots

---

## [2.0.1] - 2026-01-07

### ⚠️ Breaking Changes

**Authentication Update**: SDK now requires business token instead of appId for authentication.

### Added

- **Background Scanning** 🆕 - Beacon detection when app is closed
  - `enableBackgroundScanning()` - Enable background scanning (automatically called by configure)
  - `disableBackgroundScanning()` - Disable background scanning
  - Real-time detection without notification
  - Works even after app is killed or device reboot
  - Zero notification intrusion
  - Very battery efficient

### ⚠️ Breaking Changes

**Authentication Update**: SDK now requires business token instead of appId for authentication.

### Changed

- **Configuration**: `configure()` now requires `businessToken` parameter (replaces `appId` parameter)
- **Auto-detection**: `appId` automatically extracted from `context.packageName`
- **Authorization**: Business token sent in `Authorization` header for all API requests (without "Bearer" prefix)

### Migration

**Before (v2.0.0):**
```kotlin
sdk.configure(
    appId = "com.example.app",
    syncInterval = 30000L
)
```

**After (v2.0.1):**
```kotlin
sdk.configure(
    businessToken = "your-business-token-here",
    syncInterval = 30000L
)
// Note: appId is now automatically extracted from context.packageName
```

---

## [2.0.0] - 2025-12-30

### 🚀 Major Rewrite - Complete SDK Architecture Overhaul

This is a **major breaking release** with a complete rewrite of the BeAround Android SDK. The entire architecture has been redesigned to match the iOS SDK implementation, providing better performance, reliability, and cross-platform consistency.

#### ⚠️ BREAKING CHANGES

**Complete API Change:**
- **Old API** (`BeAround` singleton) has been replaced with **`BeAroundSDK`**
- All class names, method signatures, and package structure have changed
- This version is **NOT backward compatible** with v1.x
- See Migration Guide below for upgrading from v1.x

#### 🎯 New Architecture

**Core Components:**
- **`BeAroundSDK`** - Main singleton class (replaces `BeAround`)
- **`BeAroundSDKListener`** - Listener interface for SDK callbacks
- **`BeaconManager`** - Native Android Bluetooth LE scanning (no external dependencies)
- **`BluetoothManager`** - BLE metadata collection (battery, firmware, temperature)
- **`APIClient`** - HTTP communication with BeAround backend
- **`DeviceInfoCollector`** - Comprehensive device telemetry
- **`SecureStorage`** - Encrypted storage for sensitive data (Android Keychain equivalent)
- **`DeviceIdentifier`** - Persistent device ID generation

#### 🆕 New Features

**Beacon Detection:**
- Native Android Bluetooth LE implementation (no AltBeacon library dependency)
- Foreground and background scanning support
- Periodic scanning mode (battery-optimized)
- Continuous scanning mode
- Automatic beacon metadata collection via BLE connection
- iBeacon-specific filtering and parsing
- Beacon proximity calculation (IMMEDIATE, NEAR, FAR, UNKNOWN)

**Metadata Collection:**
- Battery level (0-100%)
- Firmware version
- TX Power (dBm)
- Movement counter
- Temperature (Celsius)
- RSSI from BLE connection
- Connectable status

**Device Information:**
- Persistent device identifier (UUID)
- Device manufacturer, model, OS version
- Battery level and charging status
- Network type (WiFi/Cellular) and connectivity details
- Location permissions status
- Bluetooth state
- App foreground/background state
- Screen dimensions and RAM info
- Cold start detection

**API Integration:**
- RESTful HTTP client with JSON payloads
- Exponential backoff retry logic
- Failed batch queuing and retry
- Comprehensive request/response logging
- Circuit breaker pattern for API failures

**Configuration:**
- Configurable sync intervals
- Enable/disable Bluetooth metadata scanning
- Enable/disable periodic scanning
- Custom API base URL
- User properties (internal ID, email, name, custom fields)

#### 🔧 Technical Improvements

**Performance:**
- No external beacon library dependencies (reduced APK size)
- Optimized memory usage with beacon caching
- Thread-safe operations with Mutex locks
- Coroutine-based async operations
- Efficient background scanning strategies

**Reliability:**
- Proper lifecycle management
- Automatic cleanup on app destroy
- Bluetooth state monitoring
- Permission handling
- Error recovery mechanisms
- Watchdog timers for scan health

**Security:**
- Encrypted SharedPreferences for sensitive data
- Secure device ID storage
- API authentication support

**Code Quality:**
- 100% Kotlin implementation
- Comprehensive logging with DEBUG tags
- Null-safety throughout
- Proper resource management
- Memory leak prevention

#### 📦 New Dependencies

```gradle
dependencies {
    // Kotlin
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
    
    // AndroidX
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'androidx.lifecycle:lifecycle-process:2.7.0'
    implementation 'androidx.security:security-crypto:1.1.0-alpha06'
}
```

#### 🐛 Critical Fixes

**Permission Issues:**
- **Fixed**: Added missing `ACCESS_NETWORK_STATE` permission (was causing SecurityException crash)
- **Fixed**: Proper permission checks before network operations

**Beacon Sync Race Condition:**
- **Fixed**: Beacons were being cleared before sync could collect them
- **Solution**: Delayed beacon clearing until after sync completes
- **Impact**: Beacons now reliably sync to API on every interval

**UI Update Issues:**
- **Fixed**: UI not updating when beacons detected
- **Solution**: Proper listener callback flow from BeaconManager → BeAroundSDK → App
- **Impact**: Real-time beacon updates now work correctly

#### 📱 New Sample App

The example app has been completely rewritten to demonstrate the new SDK:
- Jetpack Compose UI (modern Android UI framework)
- Real-time beacon list with RSSI and proximity
- Sync countdown timer
- Comprehensive logging panel
- Permission request flow
- Material Design 3 theming

#### 🔄 Migration Guide

**From v1.x to v2.0:**

**1. Update Dependencies:**
```gradle
// OLD (v1.x)
implementation 'com.github.Bearound:bearound-android-sdk:1.3.2'

// NEW (v2.0)
implementation 'com.github.Bearound:bearound-android-sdk:2.0.0'
```

**2. Update Initialization:**
```kotlin
// OLD (v1.x)
val beAround = BeAround.getInstance(context)
beAround.initialize(
    iconNotification = R.drawable.ic_notification,
    clientToken = "your-token",
    debug = true
)

// NEW (v2.2)
val sdk = BeAroundSDK.getInstance(context)
sdk.listener = this // implement BeAroundSDKListener
sdk.configure(
    businessToken = "your-business-token",
    foregroundScanInterval = ForegroundScanInterval.SECONDS_15,
    backgroundScanInterval = BackgroundScanInterval.SECONDS_30,
    maxQueuedPayloads = MaxQueuedPayloads.MEDIUM
    // Bluetooth and periodic scanning are now automatic
)
sdk.startScanning()
```

**3. Implement New Listener:**
```kotlin
class MainActivity : AppCompatActivity(), BeAroundSDKListener {
    
    override fun onBeaconsUpdated(beacons: List<Beacon>) {
        // Handle beacon updates
        beacons.forEach { beacon ->
            Log.d("Beacon", "${beacon.identifier}: rssi=${beacon.rssi}, proximity=${beacon.proximity}")
        }
    }
    
    override fun onError(error: Exception) {
        // Handle errors
    }
}
```

**4. Update Permissions (AndroidManifest.xml):**
```xml
<!-- NEW - Add this permission -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- Already required (no change) -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.INTERNET" />
```

**5. Removed Features:**
- Listener interfaces (BeaconListener, SyncListener, RegionListener) - replaced with single BeAroundSDKListener
- Notification management - now handled by app
- Event type system - simplified to listener callbacks
- Backup list configuration - replaced with internal retry logic

#### 📚 Documentation

All documentation has been updated:
- New README.md with v2.0 examples
- Updated architecture diagrams
- Comprehensive code documentation
- Sample app with working examples

#### 🔗 Resources

- **GitHub**: https://github.com/Bearound/bearound-android-sdk
- **JitPack**: https://jitpack.io/#Bearound/bearound-android-sdk/2.0.0
- **Documentation**: See README.md in repository

---

## [1.3.2] - 2025-12-22

### Added
- **Getter Methods for Configuration**: Added `getSyncInterval()` and `getBackupSize()` methods
  - `getSyncInterval()` returns the current configured scan interval (`TimeScanBeacons`)
  - `getBackupSize()` returns the current configured backup list size (`SizeBackupLostBeacons`)
  - Allows developers to retrieve and verify current SDK configuration at runtime
  - Useful for displaying current settings in UI or logging configuration state

### Changed
- **Default Scan Interval**: Changed default scan interval from 20 seconds to 5 seconds
  - Provides faster beacon detection out of the box
  - Improves user experience with more responsive beacon scanning
  - Updated all documentation to reflect new default value

## [1.3.1] - 2025-12-22

### Added
- **Configurable Scan Interval**: Expanded `TimeScanBeacons` enum to support scan intervals from 5 to 60 seconds
  - `TIME_5` (5s - default in v1.3.2+), `TIME_10` (10s), `TIME_15` (15s), `TIME_20` (20s)
  - `TIME_25` (25s), `TIME_30` (30s), `TIME_35` (35s), `TIME_40` (40s)
  - `TIME_45` (45s), `TIME_50` (50s), `TIME_55` (55s), `TIME_60` (60s)
  - **NEW**: `setSyncInterval()` method for consistent API with iOS SDK
  - **NEW**: `setBackupSize()` method for consistent API with iOS SDK
  - Allows developers to balance between battery consumption and detection speed
- **Dynamic Scan Interval Control**: `setSyncInterval()` can now be called at runtime to change scan frequency dynamically
  - Can be called before `initialize()` to set initial interval
  - Can be called after `initialize()` to adjust scanning based on battery level, user preferences, or app state
  - Changes take effect immediately on the beacon manager
  - Demonstrated in sample app with interactive scan interval control
  - Note: `setBackupSize()` must still be called before `initialize()`

### Fixed
- **Invalid Beacon Filter**: Beacons with RSSI = 0 are now properly filtered out and not sent to the API
  - Prevents sending invalid or unreliable beacon detection data
  - Improves data quality and reduces unnecessary API calls
  - Log message updated to indicate when beacons are filtered due to RSSI = 0
- **Debug Mode Logs**: All logs (including error logs) now properly respect the debug mode setting
  - Error logs (`Log.e()`) now use `logError()` function that respects debug flag
  - When `debug = false`, no logs will appear in Logcat (both info and error logs)
  - Listeners still receive all log events regardless of debug mode

### Changed
- **Improved Logging**: Updated log messages to better indicate filtering reasons
  - "No beacon with matching UUID found or all beacons have RSSI = 0" provides clearer feedback
- **Log Architecture**: Created dedicated `logError()` function for error logging that respects debug mode
- **API Consistency**: Aligned method naming with iOS SDK for better cross-platform development experience

### Deprecated
- `changeScamTimeBeacons()` - Use `setSyncInterval()` instead (typo in original method name)
- `changeListSizeBackupLostBeacons()` - Use `setBackupSize()` instead (for consistency with iOS SDK)

## [1.2.1] - 2025-12-10

### Added
- **Client Token in Payload**: Added `clientToken` field to the API request payload for enhanced authentication
- **Complete JSON Logging**: Added detailed logging of the complete JSON payload being sent to the API for better debugging and monitoring
  - Logs show formatted JSON (indented) for both regular beacon sync and failed beacon retry attempts
  - Helps developers verify the exact data being transmitted to the BeAround ingest endpoint
- **Beacon-Specific Signal Data**: Each beacon in the `beacons` array now includes its own signal information:
  - `rssi` - Signal strength in dBm
  - `txPower` - Transmission power in dBm
  - `approxDistanceMeters` - Calculated distance in meters

### Changed
- **Payload Structure Optimization**: Reorganized payload structure for better data organization
  - `clientToken` now at top-level
  - `beacons` array now includes `rssi`, `txPower`, and `approxDistanceMeters` for each beacon
  - `scanContext` simplified to only include `scanSessionId` and `detectedAt`
  - Removed duplicate signal data from `scanContext` (now in individual beacons)
- **Multi-Beacon Support**: When multiple beacons are detected, each one has its own signal strength and distance values

### Fixed
- Removed data duplication between `beacons` array and `scanContext`

## [1.2.0] - 2025-02-08

### 🚀 Major Update - Enhanced Device Context & Telemetry

This version introduces a comprehensive device information collection system, significantly enriching the data sent to the BeAround API with detailed device, network, and scan context information.

#### 🎯 New Payload Structure

The API payload has been completely restructured to include four main sections:

1. **`beacons`** - Beacon detection data (UUID and name)
2. **`sdk`** - SDK metadata (version, platform, app ID, build number)
3. **`userDevice`** - Comprehensive device information (see below)
4. **`scanContext`** - Real-time scan details (RSSI, TX power, distance, session ID, timestamp)

#### 📱 New Device Information Collected

**Device & System:**
- Manufacturer (e.g., "Samsung", "Google", "Xiaomi")
- Model (e.g., "SM-G991B", "Pixel 6")
- OS version and SDK level
- Timestamp and timezone

**Battery & Power:**
- Battery level (0.0 to 1.0)
- Charging status
- Power save mode (Android)
- Low power mode (iOS placeholder)

**Connectivity:**
- Bluetooth state (on/off/unauthorized/unknown)
- Network type (wifi/cellular/ethernet/none)
- WiFi SSID and BSSID
- Cellular generation (2g/3g/4g/5g)
- Roaming status
- Connection metered/expensive status

**Permissions & Privacy:**
- Location permission (authorized_always/authorized_when_in_use/denied)
- Location accuracy (full/reduced)
- Notification permission (authorized/denied)
- Advertising ID (AAID/GAID)
- Ad tracking enabled status

**Device Resources:**
- Total RAM (MB)
- Available RAM (MB)
- Screen width and height (pixels)

**App State:**
- App in foreground status
- App uptime (milliseconds)
- Cold start detection

**Scan Context:**
- RSSI (signal strength)
- TX Power
- Approximate distance in meters
- Unique scan session ID
- Detection timestamp

#### 🛠️ New Internal Components

- **`DeviceInfoCollector`** - Collects comprehensive device and system information
- **`SdkInfoCollector`** - Gathers SDK metadata and app information
- **`ScanContextCollector`** - Processes beacon scan data and calculates distances
- Scan session tracking for better event correlation

#### 🔧 Technical Improvements

- API level compatibility checks for newer Android APIs
- Graceful fallbacks for unavailable data
- Proper permission handling with `@SuppressLint` annotations
- Deprecation warnings suppressed for backward compatibility
- Distance calculation using log-distance path loss model

#### 📝 Breaking Changes

- API payload structure has changed - **ensure backend is updated to handle new format**
- Authorization header now uses `Bearer` token format
- Beacon payload simplified to only include UUID and name

#### 🐛 Bug Fixes

- Fixed advertising ID tracking status not being captured
- Improved error handling for device info collection
- Added null safety for all device info fields

### Migration Guide

**For Backend Integration:**

The new payload structure is:
```json
{
  "beacons": [{ "uuid": "...", "name": "..." }],
  "sdk": { "version": "1.2.0", "platform": "android", "appId": "...", "build": 123 },
  "userDevice": { /* 30+ device fields */ },
  "scanContext": { "rssi": -63, "txPower": -59, "approxDistanceMeters": 1.8, "scanSessionId": "scan_ABC123", "detectedAt": 1234567890 }
}
```

**For SDK Users:**

No changes required to your integration code. The SDK automatically collects and sends the enhanced data.

## [1.1.0] - 2025-01-24

### 🎉 New Features - Event Listeners System

The SDK now provides comprehensive listener interfaces for real-time monitoring of beacon detection, region events, and API synchronization status.

#### Added Listener Interfaces
- **`BeaconListener`** - Receive callbacks when beacons are detected
  - `onBeaconsDetected(beacons: List<BeaconData>, eventType: String)` - Called when beacons are found
  - Provides complete beacon information (UUID, major, minor, RSSI, Bluetooth details, timestamp)
  - Event types: "enter", "exit", "failed"

- **`SyncListener`** - Monitor API synchronization operations
  - `onSyncSuccess(eventType: String, beaconCount: Int, message: String)` - Called on successful sync
  - `onSyncError(eventType: String, beaconCount: Int, errorCode: Int?, errorMessage: String)` - Called on sync failure
  - Track both HTTP errors and network exceptions

- **`RegionListener`** - Track beacon region entry and exit
  - `onRegionEnter(regionName: String)` - Called when entering a beacon region
  - `onRegionExit(regionName: String)` - Called when exiting a beacon region

#### New Data Models
- **`BeaconData`** - Data class representing detected beacon information
  - `uuid: String` - Beacon UUID
  - `major: Int` - Major identifier
  - `minor: Int` - Minor identifier
  - `rssi: Int` - Signal strength
  - `bluetoothName: String?` - Bluetooth device name
  - `bluetoothAddress: String` - MAC address
  - `lastSeen: Long` - Detection timestamp

### Usage Example

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
            clientToken = "your-token",
            debug = true
        )

        // Add listeners
        beAround.addBeaconListener(this)
        beAround.addSyncListener(this)
        beAround.addRegionListener(this)
    }

    // BeaconListener
    override fun onBeaconsDetected(beacons: List<BeaconData>, eventType: String) {
        beacons.forEach { beacon ->
            Log.d("Beacon", "Major: ${beacon.major}, Minor: ${beacon.minor}, RSSI: ${beacon.rssi}")
        }
    }

    // SyncListener
    override fun onSyncSuccess(eventType: String, beaconCount: Int, message: String) {
        Log.d("Sync", "Success: $beaconCount beacons synced")
    }

    override fun onSyncError(eventType: String, beaconCount: Int, errorCode: Int?, errorMessage: String) {
        Log.e("Sync", "Error: $errorMessage")
    }

    // RegionListener
    override fun onRegionEnter(regionName: String) {
        Toast.makeText(this, "Entered beacon region!", Toast.LENGTH_SHORT).show()
    }

    override fun onRegionExit(regionName: String) {
        Toast.makeText(this, "Exited beacon region", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Remove listeners to prevent memory leaks
        val beAround = BeAround.getInstance(this)
        beAround.removeBeaconListener(this)
        beAround.removeSyncListener(this)
        beAround.removeRegionListener(this)
    }
}
```

### Enhanced Example App

The example app has been completely redesigned to demonstrate all new features:
- **Modern UI** with Material CardView components
- **Real-time beacon detection** display with detailed information
- **Sync status monitoring** with visual success/error indicators
- **Region status tracking** with entry/exit timestamps
- **Comprehensive SDK logs** with timestamps and automatic cleanup
- **Color-coded status** (green for success, red for errors)

### Technical Details
- All listener callbacks are executed on background threads (IO dispatcher)
- Use `runOnUiThread` when updating UI from listener callbacks
- Multiple listeners of the same type can be registered simultaneously
- Listeners are properly managed to prevent memory leaks
- Thread-safe listener management with mutableListOf

### Fixed

### Changed
- Simplified ProGuard rules using wildcard pattern `*` for better reliability
- Updated both `proguard-rules.pro` and `consumer-rules.pro` for consistent behavior
- All nested classes and companion objects now explicitly preserved
- **Enhanced Event Notifications**: All beacon detection events now trigger listener callbacks in addition to API sync
- **Improved Error Handling**: Listener callbacks are wrapped in try-catch blocks to prevent single listener failures from affecting others
- **Thread Safety**: Sync callbacks are dispatched on background threads using `CoroutineScope(Dispatchers.IO)`

### ProGuard Configuration
- Changed from `public <methods>` to `public *` for comprehensive preservation
- Added explicit rule: `-keep class io.bearound.sdk.BeAround$Companion { * }`
- Added wildcard rule for all nested classes: `-keep class io.bearound.sdk.BeAround$* { * }`
- Ensures Flutter, React Native, and native Android can access all SDK features without "Unresolved reference" errors
- New listener interfaces are fully preserved and accessible

### ⚠️ BREAKING CHANGES
- **Package Name Changed**: `org.bearound.sdk` → `io.bearound.sdk`
  - Aligns with company domain (bearound.io)
  - Update all imports in your code:
    ```kotlin
    // Before
    import org.bearound.sdk.BeAround

    // After
    import io.bearound.sdk.BeAround
    ```

### Added

### Changed
- **Enhanced Event Notifications**: All beacon detection events now trigger listener callbacks in addition to API sync
- **Improved Error Handling**: Listener callbacks are wrapped in try-catch blocks to prevent single listener failures from affecting others
- **Thread Safety**: Sync callbacks are dispatched on the main thread using `CoroutineScope(Dispatchers.Main)`
- **Stop Method**: Now clears all beacon event listeners when SDK is stopped

### Technical Details
- Beacon detection events are now filtered and converted to `BeaconData` before notifying listeners
- Listeners receive notifications for both successful and failed API sync operations
- All listener callbacks include detailed context (beacon data, event types, sync results)

### Build & Distribution
- **JitPack Publishing**: SDK now uses JitPack for instant, automatic publishing
  - Zero configuration required - no credentials, GPG, or approvals
  - Instant publishing via GitHub tags/releases (2-5 minutes)
  - Installation: `implementation 'com.github.Bearound:bearound-android-sdk:1.0.16'`
  - See `docs/JITPACK_GUIDE.md` for complete guide
  - Removed Maven Central configuration (too complex and slow)

- **AAR Binary Generation**: Automated task to generate and copy `.aar` file to `release/` directory
  - Command: `./gradlew :sdk:assembleRelease`
  - Output: `release/bearound-android-sdk-{version}.aar`
  - See `docs/BUILD_AAR_GUIDE.md` for complete instructions

- **Code Obfuscation with ProGuard**:
  - Enabled `minifyEnabled true` for release builds
  - Private methods and internal implementations are obfuscated
  - Public APIs remain accessible and unobfuscated
  - Debug logs removed in release builds

- **ProGuard Configuration**:
  - `proguard-rules.pro`: SDK-level obfuscation rules
  - `consumer-rules.pro`: Rules applied to apps consuming the SDK
  - Protects internal implementation while exposing public APIs
  - See `docs/PROGUARD_CONFIG_EXPLAINED.md` for detailed explanation

### Protected (Obfuscated)
- All private methods in `BeAround` class
- Internal helper methods
- Implementation details
- Private constants and fields

### Exposed (Public APIs)
- `BeAround` - All public methods
- `BeaconEventListener` - Complete interface
- `LogListener` - Complete interface
- `BeaconData` - Data class
- `BeaconEventType` - Enum
- `SyncResult`, `SyncResult.Success`, `SyncResult.Error` - Sealed classes
- `TimeScanBeacons` - Enum
- `SizeBackupLostBeacons` - Enum

### Documentation
- **Organized Documentation**: All technical documentation moved to `docs/` folder
  - `docs/JITPACK_GUIDE.md` - JitPack publishing guide (new!)
  - `docs/BUILD_AAR_GUIDE.md` - Complete guide for generating .aar binaries
  - `docs/PROGUARD_CONFIG_EXPLAINED.md` - Detailed ProGuard configuration
  - `docs/QUICK_REFERENCE.md` - Quick reference for common tasks
  - `docs/README.md` - Documentation index

### Known Issues
- **BuildConfig.SDK_VERSION Lint Warning**: The IDE lint may show an "Unresolved reference" warning for `BuildConfig.SDK_VERSION`, but this is a false positive. The field is correctly generated at build time and the project compiles successfully.

---

## [1.0.3] - 2025-12-10

### Changed
- Updated client identification from `clientId` to `clientToken` in API requests

### Features
- Initial stable release
- Continuous region monitoring for beacons
- Enter and exit event tracking
- API sync with remote endpoint
- Foreground service support
- Debug logging system
- Failed beacon backup and retry mechanism
- Configurable scan intervals and backup list sizes

---

## Migration Guide

### For Existing Users

To use the new listener system, implement the `BeaconEventListener` interface:

```kotlin
import io.bearound.sdk.BeAround
import io.bearound.sdk.BeaconEventListener
import io.bearound.sdk.BeaconData
import io.bearound.sdk.BeaconEventType
import io.bearound.sdk.SyncResult

class MyBeaconListener : BeaconEventListener {
    override fun onBeaconsDetected(beacons: List<BeaconData>, eventType: BeaconEventType) {
        // Handle detected beacons
        beacons.forEach { beacon ->
            println("Beacon: ${beacon.uuid}, Major: ${beacon.major}, Minor: ${beacon.minor}, RSSI: ${beacon.rssi}")
        }
    }

    override fun onBeaconRegionEnter(beacons: List<BeaconData>) {
        // Handle region enter
        println("Entered beacon region with ${beacons.size} beacons")
    }

    override fun onBeaconRegionExit(beacons: List<BeaconData>) {
        // Handle region exit
        println("Exited beacon region with ${beacons.size} beacons")
    }

    override fun onSyncSuccess(result: SyncResult.Success) {
        // Handle successful API sync
        println("Sync successful: ${result.eventType}, ${result.beaconsCount} beacons")
    }

    override fun onSyncError(result: SyncResult.Error) {
        // Handle API sync error
        println("Sync error: ${result.errorMessage}")
    }
}

// Register the listener
val beAround = BeAround.getInstance(context)
beAround.addBeaconEventListener(MyBeaconListener())
```

### Breaking Changes
None - this is a backward-compatible addition to the SDK.
