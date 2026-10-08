# Validation: minified radio recovery regression

The seven radio recovery scenarios now execute through public SDK behavior in
debug and the unchanged R8-minified release variant. The previous release fixture
failed before its assertions because it depended on private member names.

## Before and after

| Evidence | Debug | Release |
|---|---:|---:|
| Original fixture | 7 passed, 0 skipped | 7 failed, 0 skipped |
| Public fixture, targeted gate | 7 passed, 0 skipped | 7 passed, 0 skipped |

Commands and XML evidence are retained under
`/Users/jotta/Documents/bearound/qa/sdk-performance-20261007/` with the
`android-pr101-radio-` prefix. The known-red baseline is
`android-pr101-radio-release-red.xml`; the final targeted command is recorded in
`android-pr101-radio-public-green-final.log`.

## Requirement coverage

| Requirement | Executed evidence |
|---|---|
| REQ-001 | All seven tests execute in both variants without private SDK reflection or skips. |
| REQ-002 | Actual raw advertisement parsing, full stop cleanup, persisted zone, stats drain, fresh batch ingestion after restart, canceled timers. |
| REQ-003 | Foreground ranging stop cancels watchdog/refresh and does not register a later replacement. |
| REQ-004 | Background refresh remains scheduled when ranging stops; actual Handler callback identity and queue are observed. |
| REQ-005 | Normal and throwing radio stops recover after the 499/500 ms boundary, with continued watchdog execution. |
| REQ-006 | Frozen public budget retains current session and watchdog; explicit public unfreeze allows a later tick to recover. |
| REQ-007 | Full stop between watchdog scheduling and delayed replacement prevents a later registration. |
| REQ-008 | Original CI suite and artifact/lint gates are being checked separately below. |

The Handler observer delegates actual Android queue operations. It records
platform callback identities, not SDK member names. The Bluetooth shadow records
regular and batch registrations separately and throws only at the regular-stop
I/O boundary. SDK logic, release minification, dependencies, and keep rules are
unchanged.

## Execution variances

Two focused fixture assertions required integration repair. The persisted zone's
last-seen timestamp is bounded by the real ingestion interval rather than assumed
equal to the beacon construction timestamp. Paused-looper advancement does not
advance `System.currentTimeMillis`, so the quota test explicitly releases its
public freeze before the later watchdog tick. This checks watchdog retention
without pretending to test real-time freeze expiry.

The first full CI command found one failure in the unchanged push image deadline
test: 2,156 ms exceeded its 2,000 ms assertion. That exact test passed in isolation
without a source or limit change. The full gate passed on its one retry without a source or assertion-limit change. The initial failed run remains retained.

## Gates

Targeted debug/release gate: passed. Full suite, Kotlin compilation, lint, and both AAR
gates: passed. Debug and release each executed 368 tests, with 367 passing, zero
failures/errors, and the same one existing skip. All seven radio tests passed in
each variant with no skips. The final command and 107-task result are retained in
`android-pr101-full-ci-gate-retry.log`, and XML totals in
`android-pr101-final-validation.json`. No device validation is claimed for this test-only
correction. Physical radio evidence remains in the earlier idle-scan spec.

Profile fast. Formal review and new device/browser regression are skipped. The
original full CI suite is deliberately executed because that is the gate being
repaired.
