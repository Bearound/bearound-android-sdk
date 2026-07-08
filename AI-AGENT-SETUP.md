# BeAround Android — AI agent setup prompt

Hover the block below and click the **copy icon** in its top-right corner to copy
the prompt, then paste it into your AI coding agent (Claude Code, Cursor, Copilot, …)
with your app's repo open. The agent reads the [SDK README](./README.md) and wires
the full Android background integration.

```text
Integrate the BeAround Android SDK (com.github.Bearound:bearound-android-sdk) into
this native Android (Kotlin/Java) app. First READ the SDK's README end to end —
especially "Permissions", "Quick Start", and "Background scanning" — then do ALL of
the following, matching the README's proven-working example EXACTLY:

1. Install: add the JitPack repository — `maven("https://jitpack.io")` under
   `dependencyResolutionManagement` in settings.gradle(.kts) (or `allprojects` in a
   legacy root build.gradle) — then add
   `implementation("com.github.Bearound:bearound-android-sdk:v3.4.5")` to the app
   module's build.gradle(.kts) and sync Gradle. Your app build needs compileSdk 35+
   and AGP 8.6.0+.

2. Permissions: the SDK's manifest merge already injects every permission it needs
   (BLUETOOTH_SCAN with neverForLocation, ACCESS_FINE_LOCATION + ACCESS_COARSE_LOCATION,
   FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE, POST_NOTIFICATIONS, INTERNET,
   RECEIVE_BOOT_COMPLETED). Do NOT re-declare them. If you must redeclare BLUETOOTH_SCAN,
   keep the `neverForLocation` flag and add `xmlns:tools` to the <manifest> tag. Do NOT
   add or request ACCESS_BACKGROUND_LOCATION.

3. Configure + start scanning (Quick Start): get the singleton with
   `BeAroundSDK.getInstance(context)`, set `listener`, call
   `configure(businessToken = <ASK ME FOR IT>)`, then request the runtime permissions
   with an ActivityResultContracts.RequestMultiplePermissions launcher — BLUETOOTH_SCAN
   on Android 12+ (S+), ACCESS_FINE_LOCATION + ACCESS_COARSE_LOCATION on all versions,
   POST_NOTIFICATIONS on Android 13+ — and call `startScanning()` once the scan gate is
   granted (BLUETOOTH_SCAN on 12+, ACCESS_FINE_LOCATION on <= 11). Implement
   `onBeaconsUpdated` and `onError` on the BeAroundSDKListener.

4. Reliable background (Background scanning): background scanning is ON automatically
   after startScanning() (kernel-managed PendingIntent scan — no notification needed).
   For maximum reliability or a visible indicator, enable the connectedDevice foreground
   service via `startScanning(ForegroundScanConfig(...))` or `enableForegroundScanning(...)`.
   Gate any OEM battery/autostart onboarding on `sdk.reliabilityStatus().recommendsUserAction`,
   calling `openBatteryOptimizationSettings()` / `openManufacturerAutostartSettings()`
   only when it is true.

5. Verify: build and run on a PHYSICAL device with Bluetooth on, walk near a real
   BeAround beacon, and confirm `onBeaconsUpdated` fires and the device appears in the
   Control Hub.

Guardrails — follow strictly:
- The SDK must NEVER crash the host app — a blank/invalid businessToken is a safe no-op
  (logged + onError), not a throw. Keep it that way.
- Ask me for my businessToken; do not invent one.
- Do NOT request ACCESS_BACKGROUND_LOCATION — background detection on Android 12+ runs on
  BLUETOOTH_SCAN, and requesting it drags the app into Google Play's background-location
  review for zero detection benefit.
- STOP and hand me click-by-click steps for anything only a human can do: the Google Play
  Console connectedDevice foreground-service declaration + demonstration video (required at
  review because the merged manifest carries FOREGROUND_SERVICE_CONNECTED_DEVICE on
  targetSdk 34+), and the on-device battery-optimization exemption + OEM autostart /
  protected-apps grants on aggressive ROMs. Do not attempt those yourself.
```

Web-capable agents can fetch this prompt directly from its raw URL:
`https://raw.githubusercontent.com/Bearound/bearound-android-sdk/main/AI-AGENT-SETUP.md`
