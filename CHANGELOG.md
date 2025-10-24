# Changelog

All notable changes to the BeAround Android SDK will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.4] - 2025-01-23

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
