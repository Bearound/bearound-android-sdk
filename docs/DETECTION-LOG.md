# Detection log (persisted) — Android ↔ iOS parity

The detection log answers one question that logcat cannot: **"what did the SDK
see, and with the app in which state?"** — including events that happened while
the app was closed, which is exactly when a host app has nobody watching.

It is a diagnostic surface, not a product feature: no notifications, no user data
beyond beacon identifiers and RSSI.

## Why it exists on Android

The iOS SDK has had this since the state-restoration work, and its source even
documents *"Mirrors the Android `DetectionLogStore`"* — but that Android store
never existed. Consequences we hit in the field:

- The Flutter plugin answered `getPersistedLog()` with a hardcoded `"[]"` on
  Android (an explicit iOS-only stub), so the host's Log screen was **always
  empty** — indistinguishable from "background collection is broken", which cost
  a full debugging session.
- The native `BearoundScan` sample worked around it by building its own in-memory
  log in the ViewModel, which dies with the process — so `terminated` could never
  be shown, on any Android host.

This document describes the Android implementation, which is a 1:1 port of the
iOS contract.

## Contract (identical on both platforms)

Entry shape, newest first, capped at **500** entries:

```json
{ "id": "…", "timestamp": 1753812345678, "state": "terminated", "type": "Scan", "detail": "0.205 rssi=-61" }
```

### States

| `state` | Meaning |
|---|---|
| `foreground` | An Activity is resumed (iOS: `UIApplication.State.active`) |
| `background` | The UI ran before, nothing resumed now |
| `backgroundLocked` | Same as background, device screen locked |
| `terminated` | **The process is alive but the UI never became active** — the system started us with the app closed |

The `terminated` heuristic is the same on both platforms: *process alive, UI never
active*. On Android that means a broadcast-delivered scan result, `BOOT_COMPLETED`
or the watchdog alarm started the process; on iOS, a CoreBluetooth state
restoration or region-monitoring relaunch.

### Types and details

Verbatim strings, shared by both SDKs so a single host UI renders both:

| `type` | `detail` | When |
|---|---|---|
| `Scan` | `0.205 rssi=-61` (comma-joined for several beacons) | Ranging saw beacons — **throttled**: one entry per composition change or every 10 s |
| `Background` | `N beacon(s) detectado(s)` | Detection while the app is backgrounded |
| `Região` | `Entrou na zona do beacon` / `Saiu da zona do beacon` | Region transitions |
| `Sync OK` | `N beacon(s) enviados ao ingester` | Upload succeeded (normal + retry-chunk paths) |
| `Sync falhou` | `N beacon(s) · <erro>` | Upload failed (normal + retry-chunk paths) |

The throttle matters: without it a HIGH-precision scan writes several entries per
second and the 500-entry window covers barely a minute.

## Implementation (Android)

| File | Role |
|---|---|
| `utilities/AppStateMonitor.kt` | Classifies the process state. Registers `ActivityLifecycleCallbacks` to learn whether any Activity ever resumed (`wasEverActive`), and reads `KeyguardManager`/`PowerManager` for the locked case. |
| `utilities/DetectionLogStore.kt` | SharedPreferences-backed JSON array (the counterpart of iOS `UserDefaults`), with an in-memory mirror for cheap reads. |

Two details that are not cosmetic:

1. **`terminated` entries are flushed with `commit()`**, everything else with
   `apply()`. The system may kill the process immediately after the wake-up
   callback; an async write would lose exactly the evidence this log exists to
   capture. iOS does the same with `UserDefaults.synchronize()`.
2. **Diagnostics never break detection**: every store operation is wrapped, and a
   failure only logs a warning.

## Public API

```kotlin
BeAroundSDK.getInstance(context).getDetectionLogJson()  // JSON array string
BeAroundSDK.getInstance(context).clearDetectionLog()
```

Same names and semantics as iOS (`getDetectionLogJson()` / `clearDetectionLog()`),
so the Flutter plugin forwards both platforms through one code path — no
`Platform.isAndroid` branch in host apps.

## Reading it in a host app

The Flutter example's Log screen (`example/lib/log_modal.dart` in
`bearound-flutter-sdk`) is the reference host UI, mirroring the native iOS
`DetectionLogView`:

- Counters per state: **FG / BG / BG🔒 / Terminated**
- View modes: **Detalhado** (raw entries) and **Por Minuto** (aggregation)
- Per-minute row: `dd/MM HH:mm`, `N detecções`, per-state badges (`FG`/`BG`/`LK`/`T`,
  shown only when non-zero) and the number of distinct beacons in that minute
- State filters: Todos / Foreground / Background / BG bloqueado / Terminated

## Field validation (Galaxy A16, Android 16, 2026-07-29)

- **Background**: app backgrounded with the screen off → `FG 8 / BG 33`, with
  `Background`, `Scan` and `Sync OK` entries tagged `background`.
- **Terminated**: device rebooted, app never opened → the SDK came up headless via
  `BOOT_COMPLETED` (no Activity in the logs), detected and synced, and the log
  showed **5 `terminated` entries** (`Scan · 0.205 rssi=-61`, `Sync OK · 1
  beacon(s) enviados ao ingester`), which survived the process being started and
  stopped before the app was opened.

### Known OS limits (not SDK limits)

- **Force stop** (Settings → Force stop) on **Android 15+** leaves the package in
  the *stopped* state: broadcasts are not delivered and PendingIntents/alarms are
  cancelled until the user opens the app again. No detections happen, so there is
  nothing to log. Verified with a clean 5-minute window on the A16 (zero SDK
  activity, zero taps). Older versions (verified: realme C61, Android 14) do
  revive after force stop.
- With the **foreground service** enabled the process is effectively unkillable
  (`am kill` / `am kill-all` / swipe from Recents all fail to kill it), so
  `terminated` rarely appears in that configuration — the app simply keeps
  running in `background`.
