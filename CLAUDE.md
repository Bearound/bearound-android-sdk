# CLAUDE.md

Guidance for Claude Code when working in this repository (BeAround Android SDK).

## Modules

- `:sdk` — the library (minSdk 23). The only code clients ship.
- `:BearoundScan` — **the canonical example/bench app** (minSdk 23; runs on the whole
  device matrix, down to Galaxy S7 / Android 6). This is the reference client
  integration: FGS enabled, reconfigure via stop+start, honest Bluetooth-off UI.
- `:app` — legacy example. **Deprecated**: it does not use a ForegroundScanConfig and
  its `updateConfiguration()` does not restart the scan (precision changes silently
  don't apply). Do not extend it; point everything at `:BearoundScan`.

## Building & installing the example (bench playbook)

```bash
./gradlew :BearoundScan:assembleDebug          # build
adb -s <serial> install -r <apk>               # install (see macOS gotcha below)
```

### macOS gotcha — "Operation not permitted" on repo files
If the local checkout was touched by Android Studio, macOS App Management may attach
`com.apple.macl` to files — shell/adb/Read then fail with EPERM (intermittent,
per-file). Workarounds, in order:
1. Grant the Claude Code app **Full Disk Access** (definitive).
2. **Clone to /tmp and build there** (`git clone <repo> /tmp/bas-clean`); write
   `local.properties` with `sdk.dir=$HOME/Library/Android/sdk`.
3. If only the APK is unreadable: copy it via a Gradle `Copy` task (the daemon owns the
   file), or pull the already-installed APK from another bench device
   (`adb shell pm path <pkg>` → `adb pull` → install).

### Permissions per Android version (fresh installs)
- Android 12+: `pm grant <pkg> android.permission.BLUETOOTH_SCAN` (+ `POST_NOTIFICATIONS`,
  `ACCESS_FINE_LOCATION` recommended)
- Android ≤11: `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` (legacy gate)

### Bench automation that works
- **Tap by text** (Compose exposes little): `uiautomator dump` → grep `text="..."` for
  bounds → `input tap`. In zsh use `set -- ${=var}` for word-splitting.
- **Unfiltered scans (the BeadSniff sniffer) need the screen ON**:
  `svc power stayon usb; input keyevent KEYCODE_WAKEUP` — Android suspends unfiltered
  scans with the screen off.
- Sniffer app: `io.bearound.beadscan` (tag `BeadSniff`) — logs every raw frame as
  FUNDIDO / IBEACON_PURO / BEAD_PURO with minor/fw/rssi. The ground truth for "what is
  actually on the air".
- **Zombie scan clients** (registered, zero deliveries, `Filter 0 results` in
  `dumpsys bluetooth_manager`): `cmd bluetooth_manager disable` → `enable` clears the
  stack; the SDK re-arms itself via its BT state receiver.
- Boot marker of SDK ≥3.5.1: `Scan refresh timer armed (every 20 min)` in logcat.

### Device matrix quirks (bench)
- **Galaxy S7 (API 23)**: floor device. Legacy `pidof` lists every process — validate by
  logcat marker + crash buffer instead.
- **Moto G35 (Unisoc T760)**: **weak BLE receiver by hardware** — captures ~4-8% of
  frames the Galaxy A16 captures at the SAME RSSI (firmware scan duty/sensitivity).
  Expect larger gaps on it; use HIGH there; a bigger gap on the G35 alone is NOT an SDK
  regression. It is the bench's "low-end receiver" specimen.
- realme C61 / Moto G35 (Android 14): `neverForLocation` denylist discards any packet
  containing the iBeacon signature — only pure-0xBEAD frames (firmware v4+) deliver.

## Testing

```bash
./gradlew :sdk:testDebugUnitTest :sdk:lintDebug   # both gate the PR CI
```
Lint runs with warnings-as-errors on NEW errors: annotate permission-guarded BLE calls
with `@SuppressLint("MissingPermission")` (established pattern in the scanners).

## Release

Follow `PUBLISH.md` strictly. Highlights: version lives ONLY in `gradle.properties`
(`SDK_VERSION`); CHANGELOG entry `## [X.Y.Z]` is enforced by CI when the version
changes; README install-snippet pin is manual; tag from `main` AFTER merge; do NOT use
the `gradle-publish.yml` dispatch (parallel flow, double release). The release job's
JitPack trigger is a silent no-op — the `JITPACK_TOKEN` secret does not exist, so the POST
gets `Missing access token` / 401 and the step only warns. Force the build with a GET on
the artifact:
`https://jitpack.io/com/github/Bearound/bearound-android-sdk/vX.Y.Z/bearound-android-sdk-vX.Y.Z.pom`.
A failed build is cached per version+commit — retrying the GET, or re-tagging the same
commit, replays the same error. Move the tag to a newer commit to get a fresh build.

## Language

Bench/docs pt-BR; code and commits in English.
