# BeAround Android — AI agent setup prompt

Hover the block below and click the **copy icon** in its top-right corner to copy
the prompt, then paste it into your AI coding agent (Claude Code, Cursor, Copilot, …)
with your app's repo open. The agent reads the [SDK README](./README.md) and wires
the full Android background integration.

```text
Integrate the BeAround Android SDK (com.github.Bearound:bearound-android-sdk) into
this native Android (Kotlin/Java) app. First READ the SDK's README end to end —
especially "Permissions", "Quick Start", "Background scanning", and "Google Play
review" — then do ALL of the following (follow the README's Quick Start; the steps
below refine it).

1. Install: add the JitPack repository — `maven("https://jitpack.io")` under
   `dependencyResolutionManagement` in settings.gradle(.kts) (or `allprojects` in a
   legacy root build.gradle) — then add
   `implementation("com.github.Bearound:bearound-android-sdk:v3.6.2")` to the app
   module's build.gradle(.kts) and sync Gradle. If the app module's compileSdk is
   below 35 or the project AGP is below 8.6.0, RAISE them (compileSdk 35+, AGP 8.6.0+)
   BEFORE syncing — androidx.core 1.16.0 is pulled transitively and requires it, so
   Gradle sync fails otherwise. AGP 8.6.0+ also needs Gradle wrapper 8.7+ — bump
   `distributionUrl` in gradle/wrapper/gradle-wrapper.properties in lockstep, or the
   sync fails on the wrapper version.

2. Permissions: the SDK's manifest merge already injects every permission it needs
   (BLUETOOTH_SCAN with neverForLocation, ACCESS_FINE_LOCATION + ACCESS_COARSE_LOCATION,
   FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE, POST_NOTIFICATIONS, INTERNET,
   RECEIVE_BOOT_COMPLETED, com.google.android.gms.permission.AD_ID). Do NOT re-declare
   them, and do NOT add or request ACCESS_BACKGROUND_LOCATION — background detection on Android 12+ runs on
   BLUETOOTH_SCAN, and requesting it drags the app into Google Play's background-location
   review for zero benefit.
   ADVERTISING ID (AD_ID): the SDK reports the Google Advertising ID when the host app
   bundles Play Services — add
   `implementation("com.google.android.gms:play-services-ads-identifier:18.2.0")` to the
   app module if it is not already there (the SDK keeps it compileOnly, so without it the
   id is simply absent and nothing else changes). There is NO runtime prompt on Android:
   the user's choice lives in system settings and the platform enforces it. Declaring
   AD_ID obliges the app to tick "Device or other IDs" in the Play Data Safety form — TELL
   ME so I can update it. If this app targets children (Play Families policy forbids
   AD_ID), strip it in the app manifest with
   `<uses-permission android:name="com.google.android.gms.permission.AD_ID"
   tools:node="remove" />` and skip the dependency.
   AUDIT THE MERGED MANIFEST: this is an APPLICATION module on AGP 8.6+, so the task is
   `./gradlew :app:processDebugMainManifest` (NOT processDebugManifest — that is a
   library-module task). Inspect the merged output at
   `app/build/intermediates/merged_manifests/debug/.../AndroidManifest.xml` (exact folder
   varies by AGP), or scan the host app + every transitive library manifest, and CONFIRM
   BLUETOOTH_SCAN still keeps `usesPermissionFlags="neverForLocation"`. If ANY of
   them declares BLUETOOTH_SCAN WITHOUT `usesPermissionFlags="neverForLocation"`, the
   merge DROPS the flag — Android then withholds ALL scan results unless
   ACCESS_FINE_LOCATION is granted; foreground scanning still limps along but the
   background PendingIntent wake silently breaks. Fix by re-declaring it in the app
   manifest WITH the flag, forcing it to win the merge:
   `<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
   android:usesPermissionFlags="neverForLocation"
   tools:replace="android:usesPermissionFlags" />` — and add `xmlns:tools` to <manifest>.

3. Configure + start scanning (Quick Start): get the singleton with
   `BeAroundSDK.getInstance(context)`, set `listener`, call
   `configure(businessToken = <ASK ME FOR IT>)`, then request the runtime permissions
   with an ActivityResultContracts.RequestMultiplePermissions launcher — BLUETOOTH_SCAN
   on Android 12+ (S+), ACCESS_FINE_LOCATION + ACCESS_COARSE_LOCATION on all versions,
   POST_NOTIFICATIONS on Android 13+ — and call `startScanning()` once the scan gate is
   granted (BLUETOOTH_SCAN on 12+, ACCESS_FINE_LOCATION on <= 11). If the scan
   permission is denied, show a rationale and re-request — do not silently skip
   startScanning(). Implement onBeaconsUpdated, onError, and onSyncCompleted on the
   BeAroundSDKListener.
   TOKEN — copy ONLY the `BuildConfig.BUSINESS_TOKEN` plumbing from
   app/src/main/java/io/bearound/scan/BeaconViewModel.kt; do NOT copy its `isBlank()`
   pre-check or its stale "throws on blank" comment — pass the token straight into
   configure() (a blank token is a safe no-op). Back it with build-file plumbing:
     - Groovy (app/build.gradle):
       - `android { buildFeatures { buildConfig = true } }` — WITHOUT this you get an AGP
         error / "unresolved reference: BUSINESS_TOKEN".
       - `defaultConfig { buildConfigField "String", "BUSINESS_TOKEN", "\"${businessToken}\"" }`
       - at the TOP of build.gradle, load `businessToken` from local.properties (key
         BUSINESS_TOKEN) or the BUSINESS_TOKEN env var, FALLING BACK TO "" (empty string):
         `def businessToken = localProps.getProperty("BUSINESS_TOKEN", System.getenv("BUSINESS_TOKEN") ?: "")`
     - Kotlin DSL (app/build.gradle.kts): copy the FULL Properties-loading block (not just
       the one line) at the TOP — load local.properties via java.util.Properties into
       `val businessToken`, falling back to the env var then "":
         `val localProps = java.util.Properties().apply { val f = rootProject.file("local.properties"); if (f.exists()) f.inputStream().use { load(it) } }`
         `val businessToken = localProps.getProperty("BUSINESS_TOKEN", System.getenv("BUSINESS_TOKEN") ?: "")`
       then `buildFeatures { buildConfig = true }` and, in defaultConfig,
       `buildConfigField("String", "BUSINESS_TOKEN", "\"$businessToken\"")`.
   Then read `BuildConfig.BUSINESS_TOKEN` in the Activity/ViewModel. NEVER hardcode the
   token or commit it (local.properties is gitignored). Do NOT copy the BearoundScan
   sample's build.gradle — its fallback hardcodes a PUBLIC test token
   ("ee2ec9c46d2b2ad99bddcdd0afe224e6"), so a missing token would silently upload to
   BeAround's SHARED test business: every check passes while MY Control Hub stays empty.
   The empty-string fallback is deliberate — a missing token must be a VISIBLE no-op,
   never a silent upload.

4. Reliable background (Background scanning): by DEFAULT call plain `startScanning()`
   with no config — background scanning is already ON via a kernel-managed PendingIntent
   scan (no notification needed, API 26+) — and strip the connectedDevice foreground
   service you aren't using: remove FOREGROUND_SERVICE_CONNECTED_DEVICE in the app
   manifest with `<uses-permission
   android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"
   tools:node="remove" />` (needs xmlns:tools) so you can skip the Play connectedDevice
   review entirely. Only wire `startScanning(ForegroundScanConfig(...))` AND keep that
   permission if you need the visible scanning notification, must maximize reliability, or
   must support minSdk < 26 — and if so, ASK ME FIRST (it triggers a Play
   demonstration-video review). Note the FGS does NOT resurrect a dead process, so
   stripping it removes NO background coverage: full force-stop/swipe survival is Android
   14+ ONLY (the PendingIntent scan auto-restarts there). On Android 8–13 that scan
   survives a SYSTEM (memory/battery) kill but NOT a user force-stop/swipe, and keeping the
   connectedDevice FGS would not change that — it only keeps an ALREADY-running process
   scanning under memory/battery pressure (and below API 26, where there is no
   PendingIntent scan, that is its only role).
   After startScanning() succeeds, if reliabilityStatus().recommendsUserAction is true,
   show a one-time onboarding screen that calls openBatteryOptimizationSettings() and
   (when isAutostartManageable) openManufacturerAutostartSettings().

5. Silent-push wake-up (OPTIONAL — wire ONLY if the backend wakes the device by FCM;
   skip otherwise): it lets the backend trigger an on-demand scan + sync via a data-only
   push (the Android counterpart of the iOS silent push), and requires the host to already
   bundle Firebase. If the app bundles Firebase and does NOT have its own
   FirebaseMessagingService, register the SDK's in AndroidManifest.xml (inside
   <application>): `<service android:name="io.bearound.sdk.push.BearoundMessagingService"
   android:exported="false"><intent-filter><action
   android:name="com.google.firebase.MESSAGING_EVENT" /></intent-filter></service>`. If
   the app ALREADY has its own FirebaseMessagingService, do NOT register this one (the two
   would collide) — FORWARD to the SDK from yours instead: in onMessageReceived,
   `if (BeAroundSDK.getInstance(this).handleRemoteMessage(message.data)) return` before
   your own handling; in onNewToken, `BeAroundSDK.getInstance(this).setPushToken(token)`.
   handleRemoteMessage returns true ONLY for Bearound wake-up messages (third-party pushes
   pass through untouched); on a wake-up the SDK restores its config if the app was killed,
   restarts scanning (always — a backend wake-up overrides a previous stopScanning()),
   and flushes sync. See README → Silent-push wake-up.

6. Verify (build only — your deliverable ends here): build the project and confirm it
   COMPILES and the SDK is wired (singleton + listener + configure + permission launcher +
   startScanning). Report it as "compiles and wired", NOT as "detection observed" — the
   on-device beacon walk is HUMAN-ONLY: walking a PHYSICAL device (Bluetooth on) near a
   real BeAround beacon, confirming onBeaconsUpdated fires, and confirming the device
   appears in the Control Hub is MINE to run — you have no device or Hub access. Do not
   claim detection unless it actually ran on a device. To catch an invalid token in code
   (it is NOT validated locally — scanning starts and uploads silently return HTTP 401),
   override onSyncCompleted(beaconCount, success, error) / onError and flag success ==
   false or HttpException.statusCode == 401.

7. Fleet telemetry companion (RECOMMENDED — ask me; skip only if I say tracking-only):
   the Bearound platform ships a second SDK for beacon fleet health
   (com.github.Bearound:bearound-telemetry-android-sdk — battery, temperature, movement,
   firmware; needs NO location, keeps collecting even when the user denies it). Add its
   dependency next to this one, then wire it with the one-line instance handoff AFTER
   this SDK's configure() — it extracts the business token AND the device id so both
   report as the same device:
     val bearound = BeAroundSDK.getInstance(this).configure(businessToken = ...)
     BearoundTelemetrySDK.getInstance(this).configure(bearound)
     // io.bearound.telemetry.BearoundTelemetrySDK — then telemetry.startScanning()
   Both SDKs declare BLUETOOTH_SCAN with neverForLocation, so the manifest merge stays
   clean; no extra permission is added by the companion. Its own README covers details.

Guardrails — follow strictly:
- The SDK must NEVER crash the host app — a blank businessToken is a safe no-op
  (logged + onError), NEVER a throw. Do NOT wrap configure() in try/catch for that, and
  ignore the app-sample comment claiming configure() "throws on blank" — it is stale. An
  invalid (non-blank) token also never throws, but scanning runs while every upload 401s
  and the device never appears in the Control Hub. Keep it that way.
- Ask me for my businessToken; do not invent one, never hardcode/commit it, and never
  fall back to the BearoundScan public test token — the app/build.gradle fallback is "".
- Do NOT request ACCESS_BACKGROUND_LOCATION.
- Default to plain startScanning() and strip FOREGROUND_SERVICE_CONNECTED_DEVICE
  (tools:node="remove"). Only wire the connectedDevice foreground service after asking me.
- STOP and hand me click-by-click steps for anything only a human can do: the on-device
  beacon walk + Control Hub check, the battery-optimization exemption + OEM autostart /
  protected-apps grants on aggressive ROMs, and — ONLY IF we deliberately keep the
  connectedDevice foreground service — the Google Play Console foreground-service
  declaration + demonstration video. Do not attempt the Play/on-device steps yourself.
```

Web-capable agents can fetch this prompt directly from its raw URL:
`https://raw.githubusercontent.com/Bearound/bearound-android-sdk/main/AI-AGENT-SETUP.md`
