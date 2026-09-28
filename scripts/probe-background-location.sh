#!/usr/bin/env bash
# Background location probe.
#
# Question: without ACCESS_BACKGROUND_LOCATION, and with the SDK foreground service
# typed `connectedDevice`, does LocationCollector.lastKnown() (LocationManager
# getLastKnownLocation) still return a fresh fix while the host app is in background?
#
# Measures three states on an emulator:
#   a) app in foreground (TOP)
#   b) app backgrounded with HOME, SDK foreground service running
#   c) process killed (`am kill`, falling back to SIGKILL when the FGS keeps it alive)
#      and woken again by the SDK's own watchdog receiver / WorkManager job
#
# Evidence comes from the probe hook `LocationProbe` (tag BeAroundLocProbe, inert unless
# `log.tag.BeAroundLocProbe` is DEBUG), fired at the start of ScanWatchdogReceiver and
# BeaconSyncWorker, plus dumpsys (process capabilities, appops, location).
#
# Usage: scripts/probe-background-location.sh [--avd <name>] [--skip-build] [--keep-emulator]
# Output: build/probe-background-location/evidence.txt (path printed at the end).

set -uo pipefail

AVD="bearound_test"
SKIP_BUILD=0
KEEP_EMULATOR=0
while [ $# -gt 0 ]; do
  case "$1" in
    --avd) AVD="$2"; shift 2 ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    --keep-emulator) KEEP_EMULATOR=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

REPO="$(cd "$(dirname "$0")/.." && pwd)"
SDK_DIR="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK_DIR/platform-tools/adb"
EMULATOR="$SDK_DIR/emulator/emulator"
PKG="io.bearound.bearoundscan"
WATCHDOG_RECEIVER="$PKG/io.bearound.sdk.background.ScanWatchdogReceiver"
WATCHDOG_ACTION="io.bearound.sdk.ACTION_WATCHDOG"
PROBE_TAG="BeAroundLocProbe"
APK="$REPO/BearoundScan/build/outputs/apk/debug/BearoundScan-debug.apk"
OUT_DIR="$REPO/build/probe-background-location"
EVIDENCE="$OUT_DIR/evidence.txt"
SERIAL=""

mkdir -p "$OUT_DIR"
: > "$EVIDENCE"

log() { echo "[probe $(date +%H:%M:%S)] $*" | tee -a "$EVIDENCE"; }
section() { printf '\n===== %s =====\n' "$*" | tee -a "$EVIDENCE"; }

# Run a command with a hard wall-clock cap (SIGKILL when exceeded).
bounded() {
  local secs="$1"; shift
  ( "$@" ) & local pid=$!
  # The watchdog must not inherit stdout: an orphaned sleep holding a pipe open would
  # stall every `bounded ... | grep` for the full timeout.
  ( sleep "$secs"; kill -9 "$pid" 2>/dev/null ) >/dev/null 2>&1 < /dev/null & local wd=$!
  wait "$pid"; local rc=$?
  kill "$wd" 2>/dev/null; wait "$wd" 2>/dev/null
  return "$rc"
}

a() { bounded 60 "$ADB" -s "$SERIAL" "$@"; }
sh_() { a shell "$@"; }

find_serial() {
  local s
  for s in $(bounded 20 "$ADB" devices | awk '/^emulator-[0-9]+\tdevice/{print $1}'); do
    if bounded 20 "$ADB" -s "$s" emu avd name 2>/dev/null | head -1 | tr -d '\r' | grep -qx "$AVD"; then
      echo "$s"; return 0
    fi
  done
  return 1
}

boot_emulator() {
  SERIAL="$(find_serial || true)"
  if [ -n "$SERIAL" ]; then
    log "emulator for $AVD already running: $SERIAL"
  else
    log "booting $AVD headless"
    nohup "$EMULATOR" -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot-save \
      > "$OUT_DIR/emulator.log" 2>&1 &
    local i
    for i in $(seq 1 60); do
      sleep 3
      SERIAL="$(find_serial || true)"
      [ -n "$SERIAL" ] && break
    done
    [ -z "$SERIAL" ] && { log "emulator did not register with adb"; exit 1; }
  fi
  local booted=""
  for i in $(seq 1 80); do
    booted="$(sh_ getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    [ "$booted" = "1" ] && break
    sleep 3
  done
  [ "$booted" = "1" ] || { log "boot did not complete"; exit 1; }
  log "boot completed on $SERIAL (API $(sh_ getprop ro.build.version.sdk | tr -d '\r'))"
  # Root lets the shell deliver the SDK's non-exported watchdog broadcast.
  bounded 30 "$ADB" -s "$SERIAL" root >/dev/null 2>&1 || true
  sleep 3
  bounded 60 "$ADB" -s "$SERIAL" wait-for-device
}

shutdown_emulator() {
  teardown_test_provider
  if [ "$KEEP_EMULATOR" = "1" ] || [ -z "$SERIAL" ]; then return; fi
  log "shutting down $SERIAL"
  bounded 30 "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
}
trap shutdown_emulator EXIT

build_and_install() {
  if [ "$SKIP_BUILD" = "0" ]; then
    log "building :BearoundScan:assembleDebug"
    ( cd "$REPO" && bounded 280 ./gradlew -q :BearoundScan:assembleDebug ) \
      > "$OUT_DIR/gradle.log" 2>&1 || { log "build failed (see $OUT_DIR/gradle.log)"; exit 1; }
  fi
  [ -f "$APK" ] || { log "apk not found: $APK"; exit 1; }
  sh_ am force-stop "$PKG" >/dev/null 2>&1 || true
  bounded 180 "$ADB" -s "$SERIAL" install -r "$APK" | tee -a "$EVIDENCE"
  # Foreground-only location: FINE granted, BACKGROUND explicitly revoked.
  sh_ pm revoke "$PKG" android.permission.ACCESS_BACKGROUND_LOCATION 2>/dev/null || true
  local p
  for p in ACCESS_FINE_LOCATION BLUETOOTH_SCAN POST_NOTIFICATIONS BLUETOOTH_ADVERTISE BLUETOOTH_CONNECT; do
    sh_ pm grant "$PKG" "android.permission.$p" 2>/dev/null || true
  done
  section "granted runtime permissions"
  sh_ dumpsys package "$PKG" | grep -E "android.permission.(ACCESS_(FINE|COARSE|BACKGROUND)_LOCATION|BLUETOOTH_SCAN|POST_NOTIFICATIONS): granted" \
    | sort -u | tee -a "$EVIDENCE"
}

prepare_device() {
  sh_ svc power stayon true >/dev/null 2>&1 || true
  sh_ input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  sh_ wm dismiss-keyguard >/dev/null 2>&1 || true
  if ! sh_ cmd location is-location-enabled 2>/dev/null | grep -q true; then
    sh_ cmd location set-location-enabled true >/dev/null 2>&1 || true
  fi
  log "location enabled: $(sh_ cmd location is-location-enabled 2>/dev/null | tr -d '\r')"
  sh_ setprop "log.tag.$PROBE_TAG" DEBUG
}

# `adb emu geo fix` only reaches the GNSS HAL; with no client holding an active request
# the framework never records it, so getLastKnownLocation stays null for EVERY caller
# (measured: `dumpsys location` shows last location=null). The positive control is a
# test provider shadowing `gps`: its location lands in the framework's last-location
# cache immediately, fresh (time=now), so a null in the app can only mean "denied".
setup_test_provider() {
  sh_ appops set com.android.shell android:mock_location allow >/dev/null 2>&1 || true
  sh_ cmd location providers add-test-provider gps >/dev/null 2>&1 || true
  sh_ cmd location providers set-test-provider-enabled gps true >/dev/null 2>&1 || true
}

teardown_test_provider() {
  [ -n "$SERIAL" ] || return 0
  sh_ cmd location providers remove-test-provider gps >/dev/null 2>&1 || true
}

feed_fix() {
  # Emulator console order is longitude, latitude.
  local lng="$1" lat="$2" i
  for i in 1 2 3; do
    bounded 20 "$ADB" -s "$SERIAL" emu geo fix "$lng" "$lat" >/dev/null 2>&1 || true
    sleep 1
  done
  sh_ cmd location providers set-test-provider-location gps --location "$lat,$lng" --accuracy 5 \
    >/dev/null 2>&1 || true
  log "fed fix lng=$lng lat=$lat (geo fix + test provider gps) at device time $(sh_ date +%s | tr -d '\r')"
}

top_activity() {
  sh_ dumpsys activity activities 2>/dev/null | grep -m1 -E "topResumedActivity|mResumedActivity" | tr -d '\r'
}

bring_app_to_top() {
  sh_ am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
  local i
  for i in 1 2 3 4; do
    sleep 4
    if top_activity | grep -q "$PKG/"; then return 0; fi
    # First launch shows the background-location / permission dialog: deny it with BACK.
    log "top is not the app ($(top_activity)); pressing BACK"
    sh_ input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
  done
  top_activity | grep -q "$PKG/"
}

# WorkManager jobs live in the `androidx.work.systemjobscheduler` namespace (API 34+).
WM_NAMESPACE="androidx.work.systemjobscheduler"
worker_job_ids() {
  sh_ dumpsys jobscheduler 2>/dev/null | tr -d '\r' \
    | grep -E "^  JOB ($WM_NAMESPACE:)?u0a[0-9]+/[0-9]+: .*$PKG/androidx.work" \
    | sed -E 's/^  JOB [^ ]*\/([0-9]+):.*/\1/' | sort -u
}

snapshot_state() {
  local label="$1"
  echo "--- process ($label)" >> "$EVIDENCE"
  sh_ dumpsys activity processes "$PKG" 2>/dev/null | tr -d '\r' \
    | grep -E "ProcessRecord\{|curProcState=|procStateCapability|curCapability=|setCapability=" \
    | head -6 | tee -a "$EVIDENCE"
  echo "--- foreground service ($label)" >> "$EVIDENCE"
  sh_ dumpsys activity services "$PKG" 2>/dev/null | tr -d '\r' \
    | grep -E "ServiceRecord\{|isForeground=|foregroundServiceType=" | head -4 | tee -a "$EVIDENCE"
  echo "--- appops ($label)" >> "$EVIDENCE"
  sh_ cmd appops get "$PKG" FINE_LOCATION 2>/dev/null | tr -d '\r' | tee -a "$EVIDENCE"
  echo "--- dumpsys location ($label)" >> "$EVIDENCE"
  sh_ dumpsys location 2>/dev/null | tr -d '\r' \
    | grep -iE "last location|last coarse|$PKG|bearoundscan" | head -12 | tee -a "$EVIDENCE"
}

# Fires the SDK wake paths and returns the probe lines they produced.
measure() {
  local label="$1"
  sh_ logcat -c >/dev/null 2>&1 || true
  sh_ am broadcast -a "$WATCHDOG_ACTION" -n "$WATCHDOG_RECEIVER" >/dev/null 2>&1 || true
  sleep 5
  local jobs job; jobs="$(worker_job_ids)"
  if [ -n "$jobs" ]; then
    for job in $jobs; do
      sh_ cmd jobscheduler run -f -n "$WM_NAMESPACE" "$PKG" "$job" >/dev/null 2>&1 \
        || sh_ cmd jobscheduler run -f "$PKG" "$job" >/dev/null 2>&1 || true
    done
    log "forced WorkManager jobs: $(echo $jobs)"
    sleep 8
  else
    log "no WorkManager job found for $PKG"
  fi
  echo "--- probe lines ($label)" >> "$EVIDENCE"
  sh_ logcat -d -v time -s "$PROBE_TAG:D" 2>/dev/null | tr -d '\r' | grep "probe origin=" \
    | tee -a "$EVIDENCE" > "$OUT_DIR/probe-$label.txt"
  snapshot_state "$label"
}

verdict_for() {
  local f="$OUT_DIR/probe-$1.txt"
  if [ ! -s "$f" ]; then echo "sem linha de sonda"; return; fi
  if grep -q "lastKnown=null" "$f" && ! grep -qE "lastKnown=-?[0-9]" "$f"; then echo "cego"; else echo "recebe fix"; fi
}

main() {
  section "background location probe · avd=$AVD · $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  boot_emulator
  prepare_device
  setup_test_provider
  build_and_install

  section "state a: app foreground"
  sh_ am force-stop "$PKG" >/dev/null 2>&1 || true
  bring_app_to_top || log "WARNING: app is not TOP"
  sleep 8   # let the view model configure the SDK and start the FGS
  feed_fix -46.6560 -23.5610
  log "top: $(top_activity)"
  measure a

  section "state b: HOME, SDK foreground service running"
  sh_ input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
  sleep 15  # past the appops foreground settle time
  feed_fix -46.6570 -23.5620
  log "top: $(top_activity)"
  measure b

  section "state c: process killed, woken by watchdog / WorkManager"
  local pid_before; pid_before="$(sh_ pidof "$PKG" | tr -d '\r')"
  sh_ am kill "$PKG" >/dev/null 2>&1 || true
  sleep 3
  local pid_after; pid_after="$(sh_ pidof "$PKG" | tr -d '\r')"
  if [ -n "$pid_after" ] && [ "$pid_after" = "$pid_before" ]; then
    log "am kill left pid $pid_after alive (FGS keeps it out of the background bucket); sending SIGKILL"
    sh_ kill -9 "$pid_after" >/dev/null 2>&1 || true
    sleep 3
  fi
  log "pid before=$pid_before after kill=$(sh_ pidof "$PKG" | tr -d '\r')"
  feed_fix -46.6580 -23.5630
  measure c
  log "pid after wake=$(sh_ pidof "$PKG" | tr -d '\r')"

  section "summary"
  local s
  for s in a b c; do
    printf '%s | %s | %s\n' "$s" "$(verdict_for "$s")" "$(tail -1 "$OUT_DIR/probe-$s.txt" 2>/dev/null)" | tee -a "$EVIDENCE"
  done
  sh_ setprop "log.tag.$PROBE_TAG" "" >/dev/null 2>&1 || true
  log "evidence: $EVIDENCE"
}

main
