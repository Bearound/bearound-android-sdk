# Android radio recovery release regression design

## Component design

Recommendation: rewrite the seven existing radio recovery tests around public lifecycle behavior and Android I/O observations. Why: the release failure occurs before any recovery assertion because R8 changes private SDK member names. Alternative considered: keep rules or disabled release minification, ruled out because they weaken the production artifact to accommodate a test fixture.

REQ-001 and REQ-008 keep the real `BeaconManager`, parser, budget, and timers. Adapt the existing `RadioScannerShadow` and add a narrow Handler scheduling observer in the same test file. No production change is required. The earlier spec remains the source of intended radio behavior; this spec supersedes only its permission to initialize these tests through private SDK reflection.

Initialization is one shared fixture. Individual tests drive public transitions and inspect scanner registrations, public flags and callbacks, SharedPreferences, RSSI consumption, and platform callback scheduling. Sequential implementation is deliberate: the CI evidence task consumes the rewritten fixture and its targeted results.

## Public and platform contracts

There are no HTTP routes or transport changes. Existing Kotlin contracts are consumed without modification.

| Contract | Input | Observable result | Test use |
|---|---|---|---|
| `BeaconManager(context)` | Android application context | Real manager | All scenarios |
| `startScanning()` | Granted `BLUETOOTH_SCAN`, enabled adapter, fresh zone snapshot | Batch registration; scanning callback; restored region; active-scan callback | Fixture setup |
| `onActiveScanShouldStart` | Callback calling `manager.startRanging()` | Regular registration and watchdog scheduling | Fixture setup |
| `setForegroundState(Boolean)` | Foreground or background | Effective mode registration and refresh scheduling policy | REQ-003, REQ-004 |
| `stopRanging()` | Existing ranging session | Ranging false; foreground-dependent timer cancellation | REQ-003, REQ-004 |
| `stopScanning()` | Existing scanning session | Full state cleanup, both stop attempts, persisted zone, false callback | REQ-002, REQ-007 |
| `processExternalScanResult(ScanResult)` | Fresh real 0xBEAD advertisement | Beacon callback, region and stats updates | REQ-002 |
| `onBeaconsUpdated` | Real parsed observations | Snapshot of tracked beacons | Cleanup assertions |
| `consumeRssiStats(Collection<String>)` | Actual observed beacon identifiers | Per-window stats or empty map | Cleanup assertions |
| `ScanStartBudget.reset()` | Existing internal test hook | Isolated quota between tests | Setup and teardown |
| `ScanStartBudget.freeze(durationMs)` | Freeze just before watchdog deadline | Quota denial followed by expiry | REQ-006 |
| `BluetoothLeScanner.startScan` | Filters, settings, callback | Recorded registration | Regular/batch distinction |
| `BluetoothLeScanner.stopScan` | Recorded callback | Recorded stop; injected regular-stop exception | Failure injection |
| `Handler.postDelayed` / `removeCallbacks` | Runnable and delay, or runnable | Actual queue operation plus identity observation | Timer retention and cancellation |

Validation occurs through the actual SDK permission and parsing paths. Grant `BLUETOOTH_SCAN` and adapter access permissions through the Robolectric application shadow; enable the adapter through its existing shadow. Check the public permission result before starting. Unexpected `onError` calls are fixture failures.

## Fixture initialization and isolation

REQ-001 uses SDK 34 and the existing paused main-looper configuration. Clear `com.bearound.sdk.config` between tests. Write `ble_zone_state_v1.inZone=true`, `ble_zone_state_v1.writtenAt=now`, and `ble_zone_state_v1.lastSeenAt=now` through SharedPreferences. Instantiate the manager, set `onActiveScanShouldStart`, then call `startScanning()`.

Assert scanning, ranging, and region are true. Assert exactly one regular and one batch registration initially. This replaces direct scanner injection, direct state mutation, and private timer invocation. Capture callback event baselines after setup so the initial true scanning callback and mode-change registrations cannot pollute later assertions.

Cleanup must run even if an assertion fails: disable injected scanner faults, stop scanning if the manager was initialized, reset the shared budget, clear persisted test state, and reset platform recording. Do not drain an indefinitely self-reposting looper; advance only bounded deadlines in tests. No callback or static recorder from one test may affect another.

## Scanner and timer observation models

The scanner shadow records registration objects containing `ScanCallback`, `ScanSettings`, and registration order. `settings.reportDelayMillis == 0` identifies regular registration; `2000` identifies batch registration. Stop assertions classify callbacks using recorded registrations rather than private SDK field names. Keep regular-only failure injection so batch shutdown remains observable; an exception is raised after recording the regular stop attempt.

Extend the test-local I/O fixture with a Handler observer that delegates to real Android scheduling and records `(handler, runnable, delayMillis)` and callback removals. Match SDK timers by the posted delay: watchdog `30000`, refresh `120000`; use actual runnable identity only after capturing it at the Android boundary. Use `Handler.hasCallbacks(recordedRunnable)` for pending assertions on SDK 34. Do not identify SDK runnables by class names, inspect their private fields, suppress real scheduling, or call them directly.

Implement the observer with Robolectric `@Implements(Handler::class)`, `@RealObject`, `@Implementation`, and `Shadow.directlyOn` for the existing two-argument `postDelayed(Runnable, long)` and `removeCallbacks(Runnable)` methods. Record main-looper operations only. This platform-only name lookup is stable under SDK R8 because Android framework classes are not minified with the library. Retain all other Handler behavior. The observer distinguishes true retention from a stop that silently cancels the background refresh, even though its dormant callback produces no radio work.

The test uses no reflection against SDK fields or SDK methods. Android `ScanRecord.parseFromBytes` reflection remains permitted, following `IBeaconParserTest.kt` and `EncounterMeshManagerTest.kt`.

## Cleanup scenarios

REQ-002 populates observations through `processExternalScanResult`, using the existing 11-byte 0xBEAD sensor payload precedent. Encode advertisement length, type `0x16`, UUID bytes `0xAD, 0xBE`, then the payload. Use a real remote device and a fresh `ScanResult` timestamp, following `EncounterMeshManagerTest.kt`. Capture the resulting identifier and stats snapshot from callbacks instead of constructing an unrelated UUID or writing maps.

With regular stop failure enabled, call `stopScanning()`. Assert scanning, ranging, and region false; exactly one false scanning callback; both original scanner callbacks stopped; empty `consumeRssiStats`; and all captured manager timers absent from the Android queue. Verify the persisted snapshot remains in-zone and records the sample's last-seen time. Advance beyond the watchdog and refresh deadlines and observe no new registration.

For observable map and sample cleanup, begin a fresh session and deliver a different beacon. The callback snapshot must contain only that beacon. Deliver the original beacon through the captured batch callback within five seconds of a fresh regular sample of that original beacon from the retired session, using a separate short-timeline cleanup assertion inside this same test. Acceptance proves that old regular-sample dedup state and RSSI aggregation do not leak into the new session. Avoid a long timer advance before this short-timeline check; perform it before the bounded no-restart check or use an isolated fixture restart within the scenario.

REQ-003 follows a public foreground lifecycle. Establish background refresh through `setForegroundState(false)` and record it, then return to foreground and prove its removal. `stopRanging()` under regular-stop failure preserves scanning and region but clears ranging and removes watchdog. Advance past deadlines and assert no additional regular registration. Public foreground transitions already prohibit a pending refresh; do not reconstruct the old fixture's artificial foreground state by private mutation.

REQ-004 enters background through the public setter, establishes the real 120-second timer, and records the new regular-registration baseline after any mode change. Under regular-stop failure, `stopRanging()` cancels watchdog but preserves the captured refresh callback. Advance to the refresh deadline: the callback reposts, ranging remains false, and regular registration count does not increase. Region cleanup remains part of scanning and must not be mistaken for a retained watchdog.

## Watchdog-driven recovery scenarios

REQ-005 keeps the fixture free of beacon deliveries so `lastBeaconUpdate` is naturally absent. Advancing the paused main looper by `30000` milliseconds reaches the first watchdog recovery. Test normal stop and injected failing stop separately, preserving both original scenarios. At the tick, regular stop count increases once and ranging becomes false. Advance `499` milliseconds with no regular replacement, then `1` millisecond: exactly one replacement appears and ranging becomes true. Distinguish regular registrations from the batch registration at every assertion.

Prove continued watchdog execution by advancing to the next 30-second tick. Assert another stop and eventual replacement; the second restart uses its existing incremented backoff of `1000` milliseconds. Keep this proof before the batch liveness deadline to avoid unrelated batch revival. Timer identity observations may supplement the behavior but cannot replace actual recurring recovery.

REQ-006 advances to `29999` milliseconds, then freezes the budget for `1500` milliseconds through its existing API. Advancing one millisecond reaches the watchdog with quota denied. Current regular registration is not stopped, ranging stays true, and no replacement appears across the initial backoff window. A later watchdog tick after freeze expiry must recover. Denied attempts still increment the existing restart counter, so the later accepted recovery may use the second-restart backoff; do not impose a new counter policy.

Do not exhaust only setup-time quota and advance 30 seconds: expiration at the watchdog deadline can make that test falsely claim quota denial. Do not cancel the watchdog to force the scenario.

REQ-007 advances to the first watchdog tick, confirms a restart is scheduled, then calls `stopScanning()` before the 500-millisecond deadline. Advance past the deadline and the next watchdog deadline. Regular start count stays at its original baseline, both public scanning flags stay false, and the captured watchdog is no longer pending.

## Error handling

Expected `IllegalStateException` comes only from Android regular-stop I/O. The actual SDK recovery path must swallow it and finish its documented state transitions; neither the test nor the fixture swallows an escaping SDK exception. Record the stop before throwing so an absent attempt cannot look successful. Setup permission errors, missing registrations, unexpected errors, malformed records, and teardown leaks must fail clearly.

REQ-002 through REQ-007 retain the existing start and quota guards. No error string, production log, catch boundary, timer constant, or API changes are proposed.

## Test strategy and release gate

| Layer | Coverage | Execution |
|---|---|---|
| Robolectric behavior | Seven existing scenarios, Android scanner faults, real parser/stats, scheduled deadlines; REQ-001 through REQ-007 | `./gradlew :sdk:testDebugUnitTest :sdk:testReleaseUnitTest --tests io.bearound.sdk.BeaconManagerRadioRecoveryTest` |
| Known-red baseline | Seven release `NoSuchFieldException` failures before rewrite; REQ-001, REQ-008 | Preserve existing CI and local baseline logs; do not rerun the obsolete fixture after correction |
| Fault sensitivity | Regular-stop exceptions actually thrown; scanner attempts and cleanup asserted; REQ-002 through REQ-004 | Run rewritten scenarios with real failure injection in both variants |
| Original CI integration | Entire SDK debug and minified release suites; REQ-008 | `./gradlew :sdk:test` |
| Compilation and artifact | Kotlin typecheck through compilation, lint, both AAR variants; REQ-008 | `./gradlew :sdk:lint :sdk:assembleDebug :sdk:assembleRelease` |

Seven existing tests stay enabled under their current names, including both restart variants. Confirm report counts and zero new skips for the targeted class. Save command exit status, variant counts, baseline paths, and artifact results in this spec's `validation.md`. Preserve the pre-existing skip in the full CI report rather than adding exclusions.

The known-red release baseline satisfies negative evidence without changing production or keep rules. Before claiming timer retention coverage, temporarily disable the test-local Handler observer's recording for the 120-second post and verify that the background retention scenario fails to find its required scheduled callback; restore the fixture and rerun the targeted class. This validates the observer's precondition, while the real pending-callback and recurring-behavior assertions prove SDK policy. Record this optional observer sensitivity check if performed; it is not a substitute for the baseline or required behavior assertions.

The full suite is a deliberate exception to the normal fast-profile targeted gate: it is the original command that failed. This correction changes no application behavior and adds no browser or device scenario. Existing physical-radio evidence belongs to the earlier spec.

## File-structure plan

| File | Action | Owner |
|---|---|---|
| `sdk/src/test/java/io/bearound/sdk/BeaconManagerRadioRecoveryTest.kt` | Adapt fixture, scanner observation, platform timer observation, and all seven scenarios | F1-01 |
| `docs/specs/android-radio-release-regression/validation.md` | Create command evidence and before/after release results | F2-01 |
| `sdk/src/main/java/io/bearound/sdk/BeaconManager.kt` | Reuse unchanged | Read only |
| `sdk/src/main/java/io/bearound/sdk/utilities/ScanStartBudget.kt` | Reuse unchanged | Read only |
| `sdk/src/test/java/io/bearound/sdk/utilities/IBeaconParserTest.kt` | Reuse advertisement-byte precedent | Read only |
| `sdk/src/test/java/io/bearound/sdk/EncounterMeshManagerTest.kt` | Reuse platform ScanResult construction precedent | Read only |
| `sdk/build.gradle`, `sdk/proguard-rules.pro`, `.github/workflows/ci.yml` | Preserve existing release and CI gates | Read only |

No migration, environment change, new dependency, production write, existing-spec update, or execution-ledger update is part of these tasks.
