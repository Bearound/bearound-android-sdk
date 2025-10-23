# Changelog

All notable changes to the BeAround Android SDK will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.17] - 2025-10-23

### Fixed

### Changed
- Simplified ProGuard rules using wildcard pattern `*` for better reliability
- Updated both `proguard-rules.pro` and `consumer-rules.pro` for consistent behavior
- All nested classes and companion objects now explicitly preserved

### Technical Details
- Changed from `public <methods>` to `public *` for comprehensive preservation
- Added explicit rule: `-keep class io.bearound.sdk.BeAround$Companion { * }`
- Added wildcard rule for all nested classes: `-keep class io.bearound.sdk.BeAround$* { * }`
- Ensures Flutter, React Native, and native Android can access all SDK features without "Unresolved reference" errors

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
- **BeaconEventListener Interface**: New comprehensive listener system for receiving real-time beacon events and API sync status updates
  - `onBeaconsDetected(beacons, eventType)`: Notifies when beacons are detected with ENTER or EXIT event type
  - `onBeaconRegionEnter(beacons)`: Triggered when entering a beacon region
  - `onBeaconRegionExit(beacons)`: Triggered when exiting a beacon region
  - `onSyncSuccess(result)`: Called when API sync completes successfully with event details
  - `onSyncError(result)`: Called when API sync fails with error details

- **BeaconData Data Class**: New structured representation of beacon information
  - Includes: `uuid`, `major`, `minor`, `rssi`, `bluetoothName`, `bluetoothAddress`, `lastSeen`
  - Provides type-safe access to beacon properties for consumers

- **BeaconEventType Enum**: Defines beacon event types (`ENTER`, `EXIT`)

- **SyncResult Sealed Class**: Represents API sync operation results
  - `SyncResult.Success`: Contains event type and beacon count
  - `SyncResult.Error`: Contains event type, error message, and beacon count

- **Listener Management Methods**:
  - `addBeaconEventListener(listener)`: Register a listener for beacon events
  - `removeBeaconEventListener(listener)`: Unregister a specific listener
  - `removeAllBeaconEventListeners()`: Clear all registered listeners

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
