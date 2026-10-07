## Implementation Plan: Android idle scan log throttle
**Spec:** docs/specs/android-idle-scan-log-throttle/
**Date:** 2026-10-06

### Reuse analysis
| What | Source | Action |
|---|---|---|
| Gate e relógio falso | `sdk/src/main/java/io/bearound/sdk/background/NotificationUpdateThrottle.kt`, teste correspondente | adapt |
| Callback e detalhe Scan | `sdk/src/main/java/io/bearound/sdk/BeAroundSDK.kt:364-409` | adapt |
| Persistência e regressões | `sdk/src/main/java/io/bearound/sdk/utilities/DetectionLogStore.kt`, teste correspondente | reuse |
| Identidade existente | `sdk/src/main/java/io/bearound/sdk/models/Beacon.kt` | reuse com UUID |
| Collector físico | `/Users/jotta/Documents/bearound/qa/sdk-performance-20261006/samsung-sdk314/idle-optimization/record_case.py` | reuse |

### Tasks

### Wave 1
- [x] F1-01: Native Scan diagnostic gate
  - req: REQ-001, REQ-002, REQ-003, REQ-004, REQ-005
  - layer: backend
  - deps: none
  - writes: `sdk/src/main/java/io/bearound/sdk/BeAroundSDK.kt`, `sdk/src/main/java/io/bearound/sdk/utilities/ScanLogThrottle.kt`, `sdk/src/test/java/io/bearound/sdk/utilities/ScanLogThrottleTest.kt`
  - reuse: `sdk/src/main/java/io/bearound/sdk/background/NotificationUpdateThrottle.kt`, `sdk/src/test/java/io/bearound/sdk/utilities/DetectionLogStoreTest.kt`
  - design: "Identity and timing rules", "Data models and interfaces", "Test strategy"
  - contracts: `ScanLogThrottle`
  - tests: unit: RSSI/order/UUID/empty/boundaries/lazy detail; store regression
  - validate: `./gradlew :sdk:compileDebugKotlin :sdk:testDebugUnitTest --tests 'io.bearound.sdk.utilities.ScanLogThrottleTest' --tests 'io.bearound.sdk.utilities.DetectionLogStoreTest' :sdk:lintDebug`
  - cost: m
  - kind: required

### Wave 2

- [x] F1-02: Radio-safe ranging shutdown and recovery
  - req: REQ-007
  - layer: backend
  - deps: F1-01
  - writes: `sdk/src/main/java/io/bearound/sdk/BeaconManager.kt`, `sdk/src/test/java/io/bearound/sdk/BeaconManagerRadioRecoveryTest.kt`
  - reuse: `sdk/src/main/java/io/bearound/sdk/BeaconManager.kt` protected mode-change/batch stops
  - design: "Safe regular scan shutdown", "Test additions"
  - contracts: `BeaconManager`
  - tests: Robolectric: three throwing stops, delayed restart, quota, cancellation
  - validate: `./gradlew :sdk:compileDebugKotlin :sdk:testDebugUnitTest --tests 'io.bearound.sdk.BeaconManagerRadioRecoveryTest' :sdk:lintDebug`
  - cost: m
  - kind: required

### Wave 3
- [x] F2-01: Root-owned Android idle ABBA regression
  - req: REQ-001, REQ-002, REQ-003, REQ-004, REQ-005, REQ-006, REQ-007
  - layer: e2e
  - deps: F1-01, F1-02
  - writes: `docs/specs/android-idle-scan-log-throttle/e2e/validate-idle.sh`, `docs/specs/android-idle-scan-log-throttle/device-validation.md`
  - reuse: `/Users/jotta/Documents/bearound/qa/sdk-performance-20261006/samsung-sdk314/idle-optimization/record_case.py`
  - design: "Native Android/ADB E2E", "Error handling", "File-structure plan"
  - contracts: `DeviceValidation`
  - tests: Android/ADB E2E: ABBA CPU, Scan rhythm, callbacks, metadata/stats, regions, sync, ANR
  - validate: `bash docs/specs/android-idle-scan-log-throttle/e2e/validate-idle.sh --serial "$IDLE_DEVICE_SERIAL" --baseline-apk "$IDLE_BASELINE_APK" --optimized-apk "$IDLE_OPTIMIZED_APK" --qa-root "$IDLE_QA_ROOT"`
  - cost: m
  - kind: required
