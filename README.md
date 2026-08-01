# 🐻 BeAround Android SDK

> **Example apps:** o app de referência para integração é o **`:BearoundScan`** (roda em API 23+). O módulo `:app` é legado/deprecado — ver `app/DEPRECATED.md`.

[![JitPack](https://jitpack.io/v/Bearound/bearound-android-sdk.svg)](https://jitpack.io/#Bearound/bearound-android-sdk)
[![API](https://img.shields.io/badge/API-23%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=23)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Kotlin SDK for Android — secure BLE beacon detection and indoor positioning by BeAround.

**The Bearound platform ships as two SDKs**, and the recommended setup installs **both**:

| SDK | Domain | Needs from the user |
|---|---|---|
| **Bearound SDK** (this repo) | Tracking — detection, proximity, indoor positioning | Location + Nearby devices |
| [**Bearound Telemetry SDK**](https://github.com/Bearound/bearound-telemetry-android-sdk) | Fleet health — beacon battery, temperature, movement, firmware | Nearby devices only |

They are plug & play: one dependency line each, one `configure()` handoff between them,
and they coexist without conflicts. Both keep working even when the user **denies
location**: detection degrades to Bluetooth-only (reduced OEM coverage — see
[Permission model](#permission-model-neverforlocation-and-location)) and fleet telemetry
is unaffected, since it never needed location — see
[Bearound Telemetry SDK (companion)](#bearound-telemetry-sdk-companion).

> [!TIP]
> **⚡ Set it up with an AI agent.** Don't wire the Android background integration by hand — hand [one prompt](./AI-AGENT-SETUP.md) to your AI coding agent (Claude Code, Cursor, Copilot) and let it pilot the whole install, pausing only for the few human-only steps. → [Set up with an AI agent](#set-up-with-an-ai-agent)

[![Agent setup prompt](https://img.shields.io/badge/Agent_setup_prompt-open_%26_copy-2563eb?style=for-the-badge)](./AI-AGENT-SETUP.md)

## What the SDK detects

The SDK detects **Bearound BLE beacons** — every hardware generation. Detection matches any
of the beacon's Bearound signatures in the advertisement:

- the `0xBEAD` **service data** (11-byte payload with major/minor, firmware, battery, motion
  and temperature),
- the `0xBEAD` **manufacturer data**, or
- the **Bearound iBeacon frame** — which also covers beacons that advertise the sensor
  payload only in the scan response (their identity is picked up from the primary
  advertisement, and battery/temperature fill in as soon as a full frame is captured).

To validate the integration end to end, test with a **physical Bearound beacon** paired to
your Control Hub account.

## Requirements

- Android 6.0+ (API 23+)
- Bluetooth LE hardware
- Your app's build: `compileSdk` 35+ and Android Gradle Plugin 8.6.0+ (required
  transitively by `androidx.core` 1.16.0)
- A Bearound **business token** (see [Getting a business token](#getting-a-business-token))
- **Android 12+**: `BLUETOOTH_SCAN` ("Nearby devices") is all detection needs — the SDK
  asserts `neverForLocation`, so scan results flow with Bluetooth alone. Granting
  `ACCESS_FINE_LOCATION` alongside it is still recommended for maximum OEM coverage (see
  [Permission model](#permission-model-neverforlocation-and-location)).
- **Android ≤ 11**: `ACCESS_FINE_LOCATION` granted and Location Services on (the legacy BLE
  gate — there is no `BLUETOOTH_SCAN` before API 31).

### Recommended setup — keep Bluetooth AND Location fully on

For the best detection coverage on **every** Android version, we recommend encouraging your
users to keep the following on:

1. **Bluetooth on** — detection runs entirely over BLE, so it's best to keep Bluetooth
   enabled for continuous detection. (If it gets turned off and back on, the SDK re-arms the
   scan on its own.)
2. **Location Services on** — keeping the system Location toggle enabled improves detection
   across OEM devices (several ROMs tie BLE scan delivery to it), and on Android ≤ 11 it's
   required.
3. **Both permissions granted** — "Nearby devices" (Android 12+) and location. Keeping
   location granted alongside Bluetooth gives the most reliable detection across devices and
   the richest positioning data.

In short: the more of these the user keeps on, the better and more consistently the SDK
works. The Quick Start below requests exactly this set. On aggressive ROMs (Xiaomi/HyperOS,
Huawei, Oppo, Vivo…) also run the [Background reliability](#background-reliability)
onboarding — the SDK detects those devices automatically via `reliabilityStatus()`.

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

- An **empty/blank** token makes `configure()` a safe no-op: the SDK logs the problem, reports it to error telemetry, emits `onError`, and stays inactive — it never throws into the host.
- A **non-empty but invalid** token is not validated locally: scanning starts normally, but
  every upload to `https://ingest.bearound.io` fails with HTTP 401. You will see it as
  `BeAroundSDK-APIClient` errors in logcat, `onSyncCompleted(success = false, error = ...)`
  on the listener, and a growing `pendingBatchCount` — and the device never appears in the
  Control Hub. If nothing shows up in the Hub, check the token first.

> The sample app in [`app/`](app/README.md) reads the token from `local.properties`
> (`BUSINESS_TOKEN=...`) so it never gets committed.

## Installation

### JitPack

1. Add the JitPack repository under `dependencyResolutionManagement` in your
   `settings.gradle(.kts)` (the standard layout in current projects):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        ...
        maven("https://jitpack.io")
    }
}
```

```gradle
// settings.gradle
dependencyResolutionManagement {
    repositories {
        ...
        maven { url 'https://jitpack.io' }
    }
}
```

Legacy projects that still declare repositories in the root `build.gradle`:

```gradle
allprojects {
    repositories {
        ...
        maven { url 'https://jitpack.io' }
    }
}
```

2. Add the dependency (the version badge above always points at the latest release):

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.github.Bearound:bearound-android-sdk:v3.7.1")
}
```

```gradle
// build.gradle
dependencies {
    implementation 'com.github.Bearound:bearound-android-sdk:v3.7.1'
}
```

**Recommended — full Bearound (tracking + fleet telemetry).** Install both SDKs; they are
built to run side by side (see [the companion section](#bearound-telemetry-sdk-companion)
for how they wire together with one line):

```gradle
dependencies {
    implementation 'com.github.Bearound:bearound-android-sdk:v3.7.1'
    implementation 'com.github.Bearound:bearound-telemetry-android-sdk:v0.1.2'
}
```

## Set up with an AI agent

Instead of wiring the Android background setup by hand, hand it to an **AI coding agent** (Claude Code, Cursor, Copilot, …). This README is written to be **agent-readable** — the agent reads it and does the whole integration. There's one ready-made prompt to give it:

[![Agent setup prompt](https://img.shields.io/badge/Agent_setup_prompt-open_%26_copy-2563eb?style=for-the-badge)](./AI-AGENT-SETUP.md)

Open [`AI-AGENT-SETUP.md`](./AI-AGENT-SETUP.md) and click the **copy icon** on its code block — GitHub shows one on every code block, and it drops the prompt on your clipboard. Then paste it into your agent with your app's repo open. Web-capable agents can fetch its [raw URL](https://raw.githubusercontent.com/Bearound/bearound-android-sdk/main/AI-AGENT-SETUP.md) directly.

**The agent will pause for these human-only steps** — they need your Google Play account and a physical device, so no SDK or agent can do them:

- **Google Play Console:** the `connectedDevice` foreground-service declaration + demonstration video required at review — the SDK's manifest merge carries `FOREGROUND_SERVICE_CONNECTED_DEVICE` even if you never start the service (see [Google Play review](#google-play-review--what-the-manifest-merge-means-for-your-app)).
- **On device:** grant the battery-optimization exemption and the OEM autostart / protected-apps permission on aggressive ROMs (Xiaomi/HyperOS, Huawei, Oppo, Vivo…) — see [Background reliability](#background-reliability).

Prefer to wire it by hand? Everything the prompt references is spelled out in the sections below.

## Bearound Telemetry SDK (companion)

Fleet-health telemetry (beacon battery, temperature, movement, firmware) is a **separate
plug & play artifact**:
[`bearound-telemetry-android-sdk`](https://github.com/Bearound/bearound-telemetry-android-sdk).
Add it alongside this SDK and both run side by side — this SDK owns the person/tracking
domain, the telemetry SDK owns the beacon-health domain, with independent pipelines. It can
also run **standalone** in apps that cannot ask for location (`neverForLocation`, no
location permission): the manifest merge and its runtime detection sort the regime out
automatically — no configuration needed.

**Wiring both — credentials handoff.** `configure()` returns the instance (self); hand it
straight to the telemetry SDK, which extracts the business token **and the device id**
from it — both SDKs then report as the **same device**. Plain fill-in also works:

```kotlin
// tracking first — configure() returns self
val bearound = BeAroundSDK
    .getInstance(this)
    .configure(businessToken = "your-business-token")

// companion one-liner: credentials + deviceId handoff from the instance
BearoundTelemetrySDK
    .getInstance(this)
    .configure(bearound)

// …or fill it in normally (standalone style):
BearoundTelemetrySDK.getInstance(this).configure(businessToken = "your-business-token")
```

**Companion regime.** Both SDKs declare `BLUETOOTH_SCAN` **with** `neverForLocation`, so
the manifest merge stays clean and the flag is preserved. The recommended runtime ask
stays this SDK's usual one — **location + Nearby devices**:

- user grants both → tracking detects (and the companion reports fleet telemetry too);
- user denies location → tracking stops, but **fleet telemetry keeps flowing** through
  the companion (Bluetooth alone is enough for it on Android 12+);
- Nearby devices denied → neither can scan (platform rule).

The two SDKs are engineered to coexist without stepping on each other: independent
broadcast actions, WorkManager unique names, SharedPreferences namespaces, notification
channels/ids and offline batch directories. If a **third-party** library drops the flag
from the merge, both SDKs detect it at runtime and report it (fix with `tools:replace` —
see the [AI setup prompt](./AI-AGENT-SETUP.md), step 2).

## Permissions

**You don't need to declare any permission yourself.** The SDK ships them in its own
`AndroidManifest.xml` and Gradle's manifest merge injects them into your app:

| Merged into your app | Purpose |
|---|---|
| `BLUETOOTH`, `BLUETOOTH_ADMIN` (`maxSdkVersion=30`) | Legacy Bluetooth, Android ≤ 11 |
| `BLUETOOTH_SCAN` | BLE scan on Android 12+ — **with** `neverForLocation` (see below) |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Beacon detection on **all** versions (foreground) |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Upload to the ingest API |
| `POST_NOTIFICATIONS` | Foreground-service notification on Android 13+ |
| `RECEIVE_BOOT_COMPLETED` | Re-arm scanning after reboot |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Optional foreground service (see [Google Play review](#google-play-review--what-the-manifest-merge-means-for-your-app)) |
| `ACCESS_WIFI_STATE` | Read the connected access point and the system's cached scan results (install-time, no prompt) |
| `NEARBY_WIFI_DEVICES` | Alternative to location for reading neighbouring access points on Android 13+ — see [Wi-Fi observations](#wi-fi-observations) |
| `com.google.android.gms.permission.AD_ID` | Google Advertising ID — see [Advertising identifier](#advertising-identifier-aaid) |

`CHANGE_WIFI_STATE` is **not** declared: the SDK reads the system's cached scan results and
never triggers a scan of its own.

### Wi-Fi observations

Alongside each beacon sighting the SDK reports the **access points visible at that moment**.
The purpose is positioning coverage: an access point seen repeatedly next to a known beacon
gets a position of its own, and from then on it can place a device even where no beacon
reaches.

The identity that matters is `apId` — a one-way SHA-256 hash of the access point's hardware
address, canonicalised so that the same router produces the same identifier on Android and
on iOS.

| Field | Meaning |
|---|---|
| `apId` | Hashed access point identity (16 hex chars) |
| `rssi` | Signal strength in dBm |
| `connected` | Whether this is the access point the device is joined to |
| `frequencyMhz` | Channel frequency |
| `timestamp` | When the access point was seen (not when the payload was sent) |
| `ssid` | Network name — **temporary**, see below |

> **`ssid` and `network.wifiSSID` are transitional.** They ride along so the collection can
> be validated against real networks while the access-point map is being built. Nothing
> downstream consumes them — `apId` is the identity. They are marked for removal in the
> source, so dropping them later is a single grep.

Each payload also carries the device's **last known** location as context — the SDK never
requests an active fix, so there is no extra GPS wake-up and no battery cost.

**It is entirely opt-in, and the SDK never prompts.** Collection happens only if your app
already holds the permissions:

| Your app grants | What the SDK reports |
|---|---|
| Nothing | No `wifis[]` at all — payload identical to before |
| `ACCESS_WIFI_STATE` only | The connected access point |
| Location **or** `NEARBY_WIFI_DEVICES` (13+) | The connected access point **and** its neighbours |

Two privacy behaviours are built in: networks whose name ends in `_nomap` (the opt-out
convention honoured by Google and Mozilla) are dropped on the device, and placeholder
addresses that Android returns when a permission is missing are discarded rather than hashed.

> **Migration note:** `network.apId` joins `network.wifiSSID` and is the field consumers
> should read — a stable identity that survives the SSID being dropped later.

### Advertising identifier (AAID)

The SDK reports the **Google Advertising ID** — the resettable identifier that lets the same
person be recognised across apps for advertising. It is what makes audiences built from
beacon visits usable in ad platforms.

**There is no runtime prompt on Android.** The user's choice lives in system settings, and
the platform enforces it: opting out of ad personalisation turns the id into zeros, and the
SDK reports none. The `AD_ID` permission is a *normal* permission — granted at install, no
dialog — but required from `targetSdk` 33+, otherwise the platform zeroes the id even for
users who allow it.

To actually receive an id, your app needs Google Play Services on the classpath:

```gradle
implementation 'com.google.android.gms:play-services-ads-identifier:18.2.0'
```

The SDK keeps this dependency `compileOnly` — the same soft-dependency pattern it uses for
Firebase — so **nothing is forced on you**. Apps that already bundle Play Services (most apps
with FCM already do) get the id automatically; apps that don't simply report none, and every
other feature works the same.

The payload carries `device.permissions.advertisingId` when available, plus `limitAdTracking`
— so a user opt-out is distinguishable from Play Services being absent.

> **Google Play Data Safety:** declaring `AD_ID` means ticking **"Device or other IDs"** in
> your Data Safety form.
>
> **Apps for children:** Play Families policy forbids `AD_ID`. Strip it from the merged
> manifest and the SDK degrades to reporting no id:
> ```xml
> <uses-permission android:name="com.google.android.gms.permission.AD_ID"
>     tools:node="remove" />
> ```

### Permission model: `neverForLocation` and location

`BLUETOOTH_SCAN` is declared **with** the `neverForLocation` assertion, and location
(`ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION`, foreground only) is declared on every
version. The combination is deliberate, and validated on real devices:

- **`neverForLocation` is what makes Bluetooth-only detection possible on Android 12+.**
  Without the assertion, Android classifies the scan as location-deriving and silently
  withholds **every** scan result unless the app also holds `ACCESS_FINE_LOCATION` —
  `startScan()` reports no error and simply delivers nothing. Verified on-device: with the
  assertion, a beacon was detected and synced with "Nearby devices" alone; without it, even
  an unfiltered scan returned 0 results while location was denied.
- Android's docs caution that the assertion *can filter beacons out of the scan results* on
  some devices. Verified **not** to affect the Bearound signatures (`0xBEAD` service data
  and the iBeacon frame) on One UI and HyperOS test devices.
- **Location still matters:** on Android ≤ 11 it is the OS's BLE-scan gate (no location =
  no detection there), and keeping it granted on 12+ improves delivery on some OEM ROMs —
  hence the [recommended setup](#recommended-setup--keep-bluetooth-and-location-fully-on)
  and the Quick Start requesting both.

> 📋 **Google Play Data Safety:** if your app requests the location permission (as the Quick
> Start does), declare **Location** in your app's Data Safety form. This is the standard
> declaration — the SDK uses **foreground** location only and never declares
> `ACCESS_BACKGROUND_LOCATION`, so it does **not** trigger the heavier background-location
> review (prominent-disclosure video).

The SDK does **not** declare `BLUETOOTH_CONNECT` (the SDK only scans; it never connects to
the beacon — add it to your own manifest only if your app does GATT operations).

What you DO need to do is **request the runtime permissions** — see the Quick Start below.

## Quick Start

One activity, end to end: request the right runtime permission for the OS version, configure,
start scanning.

```kotlin
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.bearound.sdk.BeAroundSDK
import io.bearound.sdk.interfaces.BeAroundSDKListener
import io.bearound.sdk.models.Beacon

class MainActivity : ComponentActivity(), BeAroundSDKListener {

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

        // A blank token is a safe no-op (logged + onError) — the SDK never throws into the host.
        sdk.configure(businessToken = "your-business-token")

        requestScanPermissions()
    }

    private fun requestScanPermissions() {
        // Recommended setup: request "Nearby devices" AND location together. BLUETOOTH_SCAN
        // is what unlocks detection on 12+; location covers Android ≤ 11 and maximizes
        // reliability across OEM ROMs (several gate BLE delivery on the Location toggle).
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            // Android 12+ ignores a FINE request unless COARSE is requested together.
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
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

If your app ships Firebase, `configure(...)` auto-collects the FCM token and pushes it to
the backend immediately. Firebase is a soft dependency (`compileOnly`): apps without it are
unaffected. If you don't use Firebase, provide the token yourself:

```kotlin
sdk.setPushToken(token)
```

Calling `setPushToken` **after** `startScanning()` is fine — since 3.4.1 the SDK pushes a
new/changed token to the backend immediately instead of waiting for the next sync cycle.

#### Silent-push wake-up (optional)

The backend can trigger an **on-demand scan + sync** by sending a data-only, high-priority FCM
message — the Android counterpart of the iOS silent push. Wire it one of two ways:

**A. No `FirebaseMessagingService` of your own** — register the SDK's in your manifest:

```xml
<service
    android:name="io.bearound.sdk.push.BearoundMessagingService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

**B. You already have a `FirebaseMessagingService`** — forward to the SDK from yours:

```kotlin
override fun onMessageReceived(message: RemoteMessage) {
    if (BeAroundSDK.getInstance(this).handleRemoteMessage(message.data)) return
    // ...your own push handling...
}
override fun onNewToken(token: String) {
    BeAroundSDK.getInstance(this).setPushToken(token)
}
```

`handleRemoteMessage` returns `true` only for Bearound wake-up messages (marked `bearound`);
third-party pushes pass through untouched. On a wake-up the SDK restores its config if the app
was killed, restarts scanning (always — a backend wake-up overrides a previous
`stopScanning()`) and flushes pending sync. Requires the host to bundle
Firebase — the SDK never auto-registers the service (`compileOnly`, so auto-registering would
crash apps without Firebase).

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
  observed by the OS scanner. Requires only `BLUETOOTH_SCAN` on Android 12+ — thanks to the
  `neverForLocation` assertion it works with Bluetooth alone, no location grant needed (see
  [Permission model](#permission-model-neverforlocation-and-location)).
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
- The SDK merges `<uses-feature android:name="android.hardware.bluetooth_le"
  android:required="false" />` — it does **not** restrict your app's Play Store availability.
  If your app genuinely requires BLE, override it to `required="true"` in your own manifest.

### Detection log (persisted, with app state)

Logcat cannot answer "did the SDK see anything while the app was closed?" — nobody
is attached at that moment. The SDK keeps a **persisted** diagnostic log for that,
tagging every event with the process state at write time:

```kotlin
val json = sdk.getDetectionLogJson()  // JSON array, newest first, max 500 entries
sdk.clearDetectionLog()
```

```json
{ "id": "…", "timestamp": 1753812345678, "state": "terminated", "type": "Scan", "detail": "0.205 rssi=-61" }
```

`state` is `foreground`, `background`, `backgroundLocked` or **`terminated`** — the
last one meaning the process is alive but the UI never became active, i.e. the
system started the app (broadcast-delivered scan result, boot, watchdog) with the
app closed. Because it is written to disk, those entries are still there when the
user finally opens the app.

The entry `type`/`detail` strings are identical to the iOS SDK (`Scan`,
`Background`, `Região`, `Sync OK`, `Sync falhou`), so one host UI renders both
platforms with no platform branch. Full contract, implementation notes and field
validation: [docs/DETECTION-LOG.md](docs/DETECTION-LOG.md).

### Background reliability

Keeping the process eligible to wake under Doze and aggressive OEM battery managers is the
number-one field factor for reliable detection. Since 3.4.5 the SDK **detects the device's
ROM automatically** and tells you when user action is needed — gate your onboarding on it
instead of hardcoding manufacturer lists:

```kotlin
val status = sdk.reliabilityStatus()
// status.oemRom            → "HyperOS", "MIUI", "One UI", "ColorOS", … (null on stock Android)
// status.oemAggressiveness → "standard" | "moderate" | "aggressive"
// status.recommendsUserAction → true when this device likely needs the flow below

if (status.recommendsUserAction) {
    // 1. Battery optimization — opens the system Settings screen so the user can exempt the
    //    app. Uses ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS (the Settings list), NOT the
    //    restricted REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission — no Google Play review.
    if (!status.isIgnoringBatteryOptimizations) {
        sdk.openBatteryOptimizationSettings()
    }
    // 2. OEM autostart / protected-apps — deep-links to the manufacturer's screen when one
    //    exists (Xiaomi/HyperOS, Huawei, Oppo/Vivo, OnePlus…). Returns false on stock
    //    Android, where the battery screen above already covers it.
    if (status.isAutostartManageable) {
        sdk.openManufacturerAutostartSettings()
    }
}
```

On aggressive ROMs the SDK also logs the detected profile once at `configure()` (e.g.
`OEM power profile: HyperOS OS3.0 (AGGRESSIVE)`), and it self-heals at runtime: a central
scan-start budget prevents the silent OS scan-quota starvation, dead scan clients are
revived by the watchdog, and a Bluetooth off→on toggle re-arms everything immediately.

> **Samsung** is intentionally not in the autostart deep-link list: its "app power
> management" screen requires the system permission `READ_SEARCH_INDEXABLES`, which
> third-party apps cannot hold. On Samsung, use `openBatteryOptimizationSettings()` (which
> works) and optionally guide the user to add the app to "Never sleeping apps" manually.

#### OEM caveat matrix

Stock Android (Pixel) honors the `PendingIntent` scan exactly as documented. Several OEMs
ship aggressive battery managers that kill third-party `PendingIntent` and broadcast
receivers regardless of Android version:

| OEM | Detected as | Behavior | Mitigation |
|---|---|---|---|
| Samsung (One UI 6+) | `One UI` (moderate) | Generally honors the scan; restricts apps marked "Sleeping" | Ask user to add app to "Never sleeping apps" |
| Xiaomi / Redmi / POCO (MIUI / **HyperOS**) | `MIUI` / `HyperOS` (aggressive) | Kills background broadcast receivers; autostart off by default; may deliver batched scans without the scan response (handled since 3.4.5) | `openManufacturerAutostartSettings()` + battery "No restrictions" + lock app in recents |
| Huawei / Honor (EMUI / MagicOS) | `EMUI` / `MagicOS` (aggressive) | Same as Xiaomi | `openManufacturerAutostartSettings()` → "Manage manually" |
| Oppo / Realme (ColorOS) | `ColorOS` (aggressive) | Restricts background services like Xiaomi | `openManufacturerAutostartSettings()` |
| Vivo / iQOO (Funtouch / OriginOS) | `Funtouch/OriginOS` (aggressive) | Restricts background services like Xiaomi | `openManufacturerAutostartSettings()` |
| OnePlus (OxygenOS 11+) | `OxygenOS`/`ColorOS` (aggressive) | Aggressive doze beyond AOSP | Disable "Deep optimization" for the app |
| Pixel / Stock | *(null, standard)* | Honors PendingIntent scan | No action — `recommendsUserAction` is false |

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

### Periodic reconciliation (WorkManager)

A best-effort safety net: a unique `PeriodicWorkRequest` that periodically self-heals the
background scan registration, waits a short collection window for the continuous scanners
to deliver, and drains pending data through the existing sync pipeline. The
**PendingIntent scan remains the primary detection mechanism** — this layer only
complements it.

```kotlin
sdk.configure(
    businessToken = "your-business-token",
    periodicReconciliationEnabled = true,                     // default: true
    periodicReconciliationIntervalMillis = 30L * 60L * 1000L, // default: 20 min
    periodicScanDurationMillis = 12_000L                      // default: 12 s
)
```

To disable the layer (the pending periodic work is cancelled):

```kotlin
sdk.configure(
    businessToken = "your-business-token",
    periodicReconciliationEnabled = false
)
```

**What the interval means — read carefully.** The value is only the *minimum* interval
between eligible executions. Android alone decides the actual timing: Doze, battery
optimizations, WorkManager flex windows and OEM policies can all delay the worker —
sometimes by a lot. Force-stop suspends it entirely until the app is launched again.
Never read it as "runs every N minutes".

**Guard rails.** Accepted interval range is **15 minutes (WorkManager's hard minimum for
periodic work) to 24 hours**; the scan window accepts **3–30 seconds**. Out-of-range
values are clamped with an ERROR-level logcat warning (never silently, never a crash —
note that WorkManager itself silently raises sub-15-min intervals, so the SDK warns
where the platform would stay quiet). Non-positive values fall back to the defaults.

**What one execution does.** Honoring the host's intent and the device state: after
`stopScanning()` it only drains pending data (never touches scan state); in Battery
Saver or serious/critical thermal state it skips the collection window; otherwise it
re-registers a dead PendingIntent client if needed (one budget-guarded start), waits up
to the configured window for the continuous scanners to deliver (data already present
short-circuits immediately), and syncs through the normal single-flight pipeline. The
worker never registers scanners, PendingIntents, or foreground services of its own, and
runs without any network constraint — offline it persists and completes, leaving
delivery to the retry pipeline.

## API Reference

### BeAroundSDK

Main SDK class (singleton).

```kotlin
// Instance
val sdk = BeAroundSDK.getInstance(context)
sdk.listener = myListener            // BeAroundSDKListener

// Configuration — a blank token is a safe no-op (onError), never a throw
sdk.configure(
    businessToken: String,
    scanPrecision: ScanPrecision = ScanPrecision.MEDIUM,
    maxQueuedPayloads: MaxQueuedPayloads = MaxQueuedPayloads.MEDIUM,
    technology: String = "android-native", // payload tag; wrappers override it
    periodicReconciliationEnabled: Boolean = true,
    periodicReconciliationIntervalMillis: Long = 20 * 60 * 1000L, // 15 min–24 h
    periodicScanDurationMillis: Long = 12_000L                    // 3–30 s
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
sdk.setPushToken(token: String)                     // re-registers immediately if configured

// Background reliability (Doze / OEM killers)
sdk.reliabilityStatus(): ReliabilityStatus          // OEM ROM + aggressiveness + recommendsUserAction
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

All callbacks are dispatched on the main thread, except `onProvideNotificationContent`,
which runs synchronously on the SDK scan thread — keep it lightweight.

### Diagnostics

`diagnostics()` returns a point-in-time snapshot for support/triage. `summary()` renders it
as a printable multi-line string.

```kotlin
data class BeAroundDiagnostics(
    val deviceId: String,                        // stable per-install device id
    val pushTokenMasked: String?,                // masked, never the raw token
    val pushTokenLastSentAt: Long?,              // epoch ms, null if never sent
    val isScanning: Boolean,                     // scanning currently active
    val pendingBatches: Int,                     // upload batches waiting for retry
    val lastScanAt: Long?,                       // epoch ms of the last scan cycle
    val lastScanBeaconCount: Int?,               // beacons seen in that cycle
    val lastSyncAt: Long?,                       // epoch ms of the last upload attempt
    val lastSyncSuccess: Boolean?,               // whether that upload succeeded
    val lastSyncBeaconCount: Int?,               // beacons in that upload
    val recentErrors: List<String>,              // most recent SDK errors
    val sdkVersion: String,                      // SDK release version, e.g. "3.4.5"
    val osApiLevel: Int,                         // Android OS API level (Build.VERSION.SDK_INT)
    val hasBluetoothScanPermission: Boolean,     // BLUETOOTH_SCAN granted (always true on ≤ 11)
    val bluetoothEnabled: Boolean,               // Bluetooth adapter powered on
    val foregroundServiceActive: Boolean,        // foreground scan service running
    val backgroundScanRegistered: Boolean,       // low-power PendingIntent scan registered
    val isIgnoringBatteryOptimizations: Boolean  // exempt from battery optimizations (Doze)
)
```

### Error telemetry

The SDK reports its own errors (never the host app's) to the Bearound backend so field
issues can be diagnosed without adb access. Enabled by default.

- **What is collected:** the error itself (exception type, message, stack trace truncated
  to 8 000 chars, originating SDK component) plus basic device info — model, manufacturer,
  OS version/API level, OEM ROM (e.g. HyperOS), locale, battery level and app state
  (foreground/background). It also captures a **full snapshot of the SDK-relevant runtime
  permissions and device system state at the exact moment of the error**, so field issues
  can be triaged by permission/state (e.g. "all Location-denied crashes") without adb access:
  - `device.permissions` — `bluetoothScan`, `bluetoothConnect`, `bluetoothAdvertise`
    (Android 12+), `fineLocation`, `coarseLocation`, `backgroundLocation` (Android 10+) and
    `postNotifications` (Android 13+), each reported as `granted`, `denied` or
    `not_applicable` (the permission does not exist on that OS version).
  - `device.systemState` — `bluetoothEnabled`, `locationServicesEnabled`,
    `notificationsEnabled`, `ignoringBatteryOptimizations`, `powerSaveMode` and
    `foregroundServiceActive` (each optional; a field that cannot be read is omitted).

  No location coordinates, no personal data beyond the SDK's stable device id.
- **What triggers a report:** uncaught exceptions whose stack contains SDK frames (the
  handler always delegates to the previously-installed one — your own crash reporting is
  untouched), SDK coroutine failures, and errors already caught inside SDK components.
  Reports are rate-limited (max 20/h) and deduplicated (5 min per identical error).
- **Endpoint:** `POST https://ingest.bearound.io/sdk-errors`, authenticated with your
  business token, fire-and-forget with 5 s timeouts.

To opt out at any time (before or after `configure()`):

```kotlin
sdk.setErrorReportingEnabled(false)
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

#### ReliabilityStatus

```kotlin
data class ReliabilityStatus(
    val oemRom: String?,                        // "HyperOS", "MIUI", "One UI", "ColorOS"… (null on stock)
    val oemRomVersion: String?,                 // e.g. "OS3.0"
    val oemAggressiveness: String,              // "standard" | "moderate" | "aggressive"
    val isIgnoringBatteryOptimizations: Boolean,
    val isAutostartManageable: Boolean,
    val recommendsUserAction: Boolean           // gate your reliability onboarding on this
)
```

#### HttpException

`io.bearound.sdk.network.HttpException` is delivered via `onError` whenever the ingest API
responds with a non-2xx status (e.g. HTTP 401 on a wrong business token):

```kotlin
class HttpException(
    val statusCode: Int,   // HTTP status, e.g. 401
    val body: String       // raw response body (best-effort)
) : Exception()
```

## Troubleshooting

### Beacons not detected

1. **Are you testing with a physical Bearound beacon?** That is the supported way to
   validate the integration (see [What the SDK detects](#what-the-sdk-detects)).
2. **Follow the [recommended setup](#recommended-setup--keep-bluetooth-and-location-fully-on)**:
   Bluetooth ON, Location Services ON, and both "Nearby devices" (12+) and location granted.
   On Android 12+ `BLUETOOTH_SCAN` is the permission that unlocks the scan; on ≤ 11 it is
   `ACCESS_FINE_LOCATION` + Location Services.
3. Check `sdk.reliabilityStatus()` — on aggressive ROMs (`recommendsUserAction == true`) run
   the [Background reliability](#background-reliability) onboarding.
4. Are you on a physical device? Emulators have no usable BLE.
5. Call `sdk.diagnostics()` and check `lastScanAt` / `recentErrors`.

### Device doesn't appear in the Control Hub / sync fails

1. **Check the business token.** A wrong token fails every upload with HTTP 401 — filter
   logcat by `BeAroundSDK-APIClient`, or check programmatically in `onError`:
   `(error as? HttpException)?.statusCode == 401`. There is no local validation of the
   token value.
2. Check internet connectivity; failed batches accumulate in `pendingBatchCount` and are
   retried with backoff.
3. Watch `onSyncCompleted(beaconCount, success, error)` on the listener.

### High battery usage

1. Use a lower scan precision (`ScanPrecision.LOW` scans 10s per minute; `MEDIUM` 30s).
2. Duty cycle and region gating are automatic — outside a beacon zone only the kernel filter
   scan runs.

### No beacons in background

1. Ensure the [recommended setup](#recommended-setup--keep-bluetooth-and-location-fully-on)
   is in place — "Nearby devices" granted (12+), location granted, Bluetooth and Location
   Services ON.
2. `PendingIntent` background scan requires Android 8+ (API 26+); force-stop survival
   requires Android 14+ (see the [support matrix](#support-matrix)).
3. Check `sdk.reliabilityStatus()` — when `recommendsUserAction` is true (Xiaomi/HyperOS,
   Huawei, Oppo, Vivo, Samsung…), run the [Background reliability](#background-reliability)
   flow: `openBatteryOptimizationSettings()` and `openManufacturerAutostartSettings()`.

### App crashes with SecurityException

- **Foreground-service crash (fixed in 3.4.4/3.4.5):** on Android 14+, enabling foreground
  scanning and then revoking "Nearby devices" used to crash the app in a loop (`Starting FGS
  with type connectedDevice ... requires ... BLUETOOTH_SCAN`). Since 3.4.4/3.4.5 the SDK
  checks the permission before starting the service and catches both `SecurityException` and
  `ForegroundServiceStartNotAllowedException`, degrading gracefully. If you see this crash,
  update the SDK.

## Migrating from 2.x to 3.x

3.x is the hybrid wake-up generation (kernel-registered `PendingIntent` scan, force-stop
survival on Android 14+, `BLUETOOTH_SCAN`-gated scanning with location declared on all
versions). Update the dependency to the latest 3.x tag, then review:

1. **`BLUETOOTH_CONNECT` is no longer declared by the SDK manifest** (3.0.0). If your app
   uses GATT operations of its own, declare it in your own manifest.
2. **Default user-facing strings are English** (3.0.0). If you relied on the old Portuguese
   foreground-notification copy, pass your localized strings via `ForegroundScanConfig` /
   `NotificationContent`.
3. **Location-capture APIs were removed** (3.3.1): `LocationCaptureResult`,
   `onStartLocationCapture`, `onCompleteLocationCapture` no longer exist. Delete any
   overrides — the SDK no longer touches GPS at all.
4. **Permission requests**: on Android 12+, add `BLUETOOTH_SCAN` to your permission request
   and keep requesting `ACCESS_FINE_LOCATION` (+ `ACCESS_COARSE_LOCATION`) alongside it (see
   Quick Start). What you should drop is `ACCESS_BACKGROUND_LOCATION` — remove any requests
   made for the SDK's benefit.
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
