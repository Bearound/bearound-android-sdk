## Implementation Plan: Android radio recovery release regression
**Spec:** docs/specs/android-radio-release-regression/
**Date:** 2026-10-07

### Reuse analysis

| What | Source | Action |
|---|---|---|
| Seven recovery scenarios and scanner fault shadow | `sdk/src/test/java/io/bearound/sdk/BeaconManagerRadioRecoveryTest.kt` | adapt |
| Raw advertisement fixture | `sdk/src/test/java/io/bearound/sdk/utilities/IBeaconParserTest.kt` | reuse |
| Android ScanResult construction | `sdk/src/test/java/io/bearound/sdk/EncounterMeshManagerTest.kt` | reuse |
| Public lifecycle and budget | `sdk/src/main/java/io/bearound/sdk/BeaconManager.kt`, `sdk/src/main/java/io/bearound/sdk/utilities/ScanStartBudget.kt` | reuse unchanged |
| Original release and full-suite gate | `sdk/build.gradle`, `.github/workflows/ci.yml` | reuse unchanged |
| Private SDK reflection | Existing recovery fixture | avoid |

### Tasks

### Wave 1

- [x] F1-01: Rewrite release-compatible radio tests
  - req: REQ-001, REQ-002, REQ-003, REQ-004, REQ-005, REQ-006, REQ-007
  - layer: test
  - deps: none
  - writes: `sdk/src/test/java/io/bearound/sdk/BeaconManagerRadioRecoveryTest.kt`
  - reuse: `sdk/src/test/java/io/bearound/sdk/BeaconManagerRadioRecoveryTest.kt`, `sdk/src/test/java/io/bearound/sdk/utilities/IBeaconParserTest.kt`, `sdk/src/test/java/io/bearound/sdk/EncounterMeshManagerTest.kt`
  - design: Fixture initialization and isolation, Scanner and timer observation models, Cleanup scenarios, Watchdog-driven recovery scenarios, Error handling
  - contracts: BeaconManager, BluetoothLeScanner, Handler, ScanStartBudget
  - tests: unit (seven scenarios; stop failures; timer retention/recurrence)
  - validate: `./gradlew :sdk:testDebugUnitTest :sdk:testReleaseUnitTest --tests io.bearound.sdk.BeaconManagerRadioRecoveryTest`
  - cost: m
  - kind: required

### Wave 2

- [x] F2-01: Validate CI release gate
  - req: REQ-001, REQ-002, REQ-003, REQ-004, REQ-005, REQ-006, REQ-007, REQ-008
  - layer: test
  - deps: F1-01
  - writes: `docs/specs/android-radio-release-regression/validation.md`
  - reuse: `.github/workflows/ci.yml`, `sdk/build.gradle`
  - design: Test strategy and release gate, File-structure plan
  - contracts: Gradle debug/release variants, original :sdk:test gate
  - tests: integration (seven passes/variant; full CI; known-red; scope)
  - validate: `./gradlew :sdk:testDebugUnitTest :sdk:testReleaseUnitTest --tests io.bearound.sdk.BeaconManagerRadioRecoveryTest && ./gradlew :sdk:test :sdk:lint :sdk:assembleDebug :sdk:assembleRelease`
  - cost: s
  - kind: required

### Wave plan

| Wave | Tasks | Ordering |
|---|---|---|
| 1 | F1-01 | One shared fixture and seven cohesive scenarios |
| 2 | F2-01 | Consumes the corrected test and its targeted results |

### Risks

- Setup now creates both batch and regular registrations; raw total counts would weaken assertions.
- Quota tokens may expire at the first watchdog deadline; freeze immediately before it.
- Public mode changes create registrations and alter timer schedules; capture baselines after transitions.
- Timer observation must delegate actual Android scheduling and inspect callback identities rather than SDK names.
- Full CI testing is required here because targeted green alone does not repair the originally failing gate.
