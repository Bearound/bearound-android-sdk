# Android radio recovery release regression

## Evidence and scope

PR 101's `:sdk:test` fails only in the minified release radio recovery suite. The CI log reports 368 release tests, seven failures, and one pre-existing skip. All seven failures originate at `BeaconManagerRadioRecoveryTest.kt:76`, where reflection looks up the private `bluetoothLeScanner` field. A targeted local release run reproduced seven failures out of seven tests. Debug reaches the intended assertions.

Evidence: `qa/sdk-performance-20261007/android-pr101-ci-failed.log` and `qa/sdk-performance-20261007/android-pr101-radio-release-baseline.log`, relative to the parent workspace. This spec repairs the test fixture while retaining the production radio recovery behavior defined in `docs/specs/android-idle-scan-log-throttle/design.md`, sections `Safe regular scan shutdown` and `Test additions`.

## Requirements

### REQ-001: Minification-compatible fixture

Current: WHEN the radio recovery suite uses minified release classes THEN private SDK name lookup fails during setup.

Expected: WHEN either unit-test variant runs THEN the suite SHALL initialize the real manager through public behavior and Android I/O fixtures.

Unchanged: WHEN debug runs THEN the suite SHALL CONTINUE TO exercise real recovery code.

- [ ] All seven tests run and pass in debug and release; none accesses private SDK fields or methods by name.
- [ ] Setup obtains regular and batch registrations through `startScanning()` and `startRanging()`, with permission and fresh persisted-zone state established.

### REQ-002: Full shutdown after radio stop failure

Current: WHEN release uses private cleanup fields THEN setup fails before shutdown assertions.

Expected: WHEN regular scanner stop throws THEN the suite SHALL prove full cleanup through public behavior and Android scheduling.

Unchanged: WHEN scanning stops THEN the system SHALL CONTINUE TO persist live zone state before clearing local state.

- [ ] Scanning, ranging, and region become false; exactly one false state callback occurs; both scanner callbacks receive stop attempts.
- [ ] RSSI stats clear, a subsequent session excludes old observations, and no retired timer registers a scan.
- [ ] The fresh in-zone snapshot and last-seen timestamp survive shutdown.

### REQ-003: Foreground ranging shutdown

Current: WHEN release tests inspect private timer fields THEN foreground cancellation cannot be asserted.

Expected: WHEN foreground `stopRanging()` encounters a stop exception THEN the suite SHALL prove ranging ends and recovery timers are absent through Android scheduling and scanner observations.

Unchanged: WHEN only ranging stops THEN the system SHALL CONTINUE TO preserve scanning and region state.

- [ ] `isRanging` becomes false while scanning and region stay true.
- [ ] Foreground transition cancels background refresh; `stopRanging()` cancels watchdog; advancing past their deadlines adds no regular registration.

### REQ-004: Background refresh retention

Current: WHEN release tests read the private refresh runnable THEN background retention cannot be asserted.

Expected: WHEN background `stopRanging()` encounters a stop exception THEN the suite SHALL prove the Android refresh callback remains scheduled while the watchdog is canceled.

Unchanged: WHEN background ranging is paused THEN the system SHALL CONTINUE TO retain the existing refresh policy.

- [ ] The recorded 120-second refresh callback remains pending after the stop and reposts at its next deadline.
- [ ] Ranging stays false, scanning and region stay true, and that dormant refresh adds no regular registration.

### REQ-005: Restart timing and continued recovery

Current: WHEN release invokes private restart methods THEN neither scenario reaches assertions.

Expected: WHEN the public lifecycle watchdog detects a silent regular scan THEN the suite SHALL prove recovery with and without a stop exception.

Unchanged: WHEN recovery starts THEN the system SHALL CONTINUE TO honor the existing backoff and recurring watchdog.

- [ ] The first 30-second watchdog tick attempts one regular stop and clears ranging; no replacement starts for 499 milliseconds, and one starts at 500 milliseconds.
- [ ] A later watchdog tick produces another recovery, proving the watchdog remains active.

### REQ-006: Quota-denied recovery

Current: WHEN release tests invoke restart privately with exhausted quota THEN session preservation is unverified.

Expected: WHEN the watchdog cannot reserve a replacement token THEN the suite SHALL prove the current registration and recovery schedule remain intact.

Unchanged: WHEN quota is denied THEN the system SHALL CONTINUE TO avoid stopping the current scan.

- [ ] A budget freeze established just before the watchdog tick prevents replacement and stop attempts while ranging remains true.
- [ ] After the freeze expires, a subsequent watchdog tick recovers normally using the existing retry and backoff rules.

### REQ-007: Cancel delayed restart on shutdown

Current: WHEN release tests invoke the private restart method THEN delayed-start cancellation is unverified.

Expected: WHEN full shutdown occurs between watchdog recovery and its delayed replacement THEN the suite SHALL prove that replacement never registers.

Unchanged: WHEN scanning is stopped THEN the system SHALL CONTINUE TO guard delayed starts by scanning and region state.

- [ ] Stop after the first watchdog tick and before its 500-millisecond backoff expires; no new regular start appears at or after the deadline.
- [ ] Scanning and ranging remain false and the watchdog callback is removed.

### REQ-008: Restore the original CI gate

Current: WHEN CI runs `:sdk:test` THEN seven release fixture failures fail the job.

Expected: WHEN this correction is validated THEN the original full SDK test command SHALL succeed alongside lint and debug/release builds.

Unchanged: WHEN release builds THEN the system SHALL CONTINUE TO use the existing minification and ProGuard configuration.

- [ ] Targeted debug/release reports contain seven passing tests each, with zero new skips.
- [ ] Full `:sdk:test`, `:sdk:lint`, `:sdk:assembleDebug`, and `:sdk:assembleRelease` pass; Kotlin compilation provides the project typecheck gate.
- [ ] Final changes cover only the recovery test and spec evidence.

## Assumptions

- R8 private-name instability is the confirmed defect; production recovery behavior already passes debug and prior radio validation.
- The seven existing test names and scenarios remain; platform-only reflection is allowed where Android provides no public constructor, following existing tests.
- Android Handler scheduling observations may verify timer retention without referencing SDK private members.
- The full SDK suite is required despite the fast profile because it is the exact failing CI command.
- This test-only fix adds no application flow; hardware validation already belongs to the earlier spec.

## Open questions

None. Public initialization, Android scheduling observations, and the existing Gradle variants establish the required contracts.

## Unchanged behavior

- Production sources and public SDK API.
- Release minification, ProGuard rules, Gradle configuration, and CI workflow.
- Scan filters, parser behavior, metadata, RSSI statistics, and start quota.
- Existing watchdog, backoff, batch liveness, region grace, and foreground/background policy.
- Existing specs, task completion state, execution ledgers, and unrelated changes.
