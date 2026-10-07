#!/usr/bin/env bash
set -euo pipefail

serial=''
baseline_apk=''
optimized_apk=''
qa_root=''
verify_only=0
while (( $# )); do
  case "$1" in
    --serial) serial="$2"; shift 2 ;;
    --baseline-apk) baseline_apk="$2"; shift 2 ;;
    --optimized-apk) optimized_apk="$2"; shift 2 ;;
    --qa-root) qa_root="$2"; shift 2 ;;
    --verify-only) verify_only=1; shift ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done
if [[ -z "$serial" || ! -f "$baseline_apk" || ! -f "$optimized_apk" || ! -d "$qa_root" ]]; then
  echo 'Required: --serial SERIAL --baseline-apk APK --optimized-apk APK --qa-root DIR' >&2
  exit 2
fi
if [[ "$serial" != RQCXB06SBVY ]]; then
  echo 'The reused QA collectors currently support Samsung RQCXB06SBVY only.' >&2
  exit 2
fi
for script in run_idle.py launch_idle.py analyze_idle.py validate_region.py check_region_errors.py; do
  [[ -f "$qa_root/$script" ]] || { echo "Missing QA collector: $script" >&2; exit 2; }
done

if (( ! verify_only )); then
  adb_path="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
  [[ "$("$adb_path" -s "$serial" get-state)" == device ]]
  for label in on-published-idle-a1 on-candidate-idle-b1 on-candidate-idle-b2 on-published-idle-a2; do
    [[ ! -e "$qa_root/$label-capture.json" ]] || { echo "Refusing to overwrite evidence: $label" >&2; exit 2; }
  done
  python3 "$qa_root/run_idle.py" on-published-idle-a1 --apk "$baseline_apk"
  python3 "$qa_root/run_idle.py" on-candidate-idle-b1 --apk "$optimized_apk"
  python3 "$qa_root/run_idle.py" on-candidate-idle-b2 --apk "$optimized_apk"
  python3 "$qa_root/run_idle.py" on-published-idle-a2 --apk "$baseline_apk"
  python3 "$qa_root/launch_idle.py" candidate-region --apk "$optimized_apk" --settle 60
  python3 "$qa_root/validate_region.py"
  python3 "$qa_root/check_region_errors.py"
fi
python3 "$qa_root/analyze_idle.py"
python3 - "$qa_root" "$baseline_apk" "$optimized_apk" <<'PY'
import hashlib
import json
from pathlib import Path
import sys
import zipfile

root, baseline, candidate = map(Path, sys.argv[1:])
with zipfile.ZipFile(baseline) as left, zipfile.ZipFile(candidate) as right:
    for member in ['lib/arm64-v8a/libapp.so', 'lib/arm64-v8a/libflutter.so']:
        assert left.read(member) == right.read(member), member
hashes = {'published': hashlib.sha256(baseline.read_bytes()).hexdigest(),
          'candidate': hashlib.sha256(candidate.read_bytes()).hexdigest()}
analysis = json.loads((root / 'idle-analysis.json').read_text())
expected = {'on-published-idle-a1', 'on-candidate-idle-b1',
            'on-candidate-idle-b2', 'on-published-idle-a2'}
assert {case['name'] for case in analysis['cases']} == expected
captures = []
for case in analysis['cases']:
    name = case['name']
    launch = json.loads((root / f'{name}-launch.json').read_text())
    capture = json.loads((root / f'{name}-capture.json').read_text())
    assert launch['apkSha256'] == hashes[case['artifact']]
    assert 60 <= case['warmupSeconds'] < 70
    assert 43 <= case['spanSeconds'] <= 46
    assert case['sameProcessSurvived'] and not case['hasAnrExit'] and not case['hasCrashExit']
    assert not case['traceErrors']
    assert abs(case['cpuPercentOneCore'] - case['cpuPercentScheduler']) < 1
    if case['artifact'] == 'candidate':
        assert case['diagnostics']['sameCompositionScanPairsBelow10s'] == 0
    captures.append(capture)
captures.sort(key=lambda item: item['startEpoch'])
assert [item['name'] for item in captures] == [
    'on-published-idle-a1', 'on-candidate-idle-b1',
    'on-candidate-idle-b2', 'on-published-idle-a2']
assert all(left['endEpoch'] < right['startEpoch'] for left, right in zip(captures, captures[1:]))
region = json.loads((root / 'region-validation.json').read_text())['summary']
assert region['passed'] and region['sameProcess'] and region['bluetoothRestored']
assert region['apkSha256'] == hashes['candidate']
assert not region['hasAnrExit'] and not region['hasCrashExit']
assert not region['hasAnrEvent'] and not region['hasCrashEvent'] and not region['lastAnrContainsApp']
assert all(region[key] for key in ['bleScanAlwaysRestored', 'airplaneRestored', 'wifiRestored'])
for key in ['regionExitIncrease', 'regionEnterIncrease', 'metadataRecoveryIncrease', 'syncRecoveryIncrease']:
    assert region[key] > 0
permission_path = root / 'permission-e2e-candidate/results.json'
if permission_path.exists():
    permissions = json.loads(permission_path.read_text())
    assert permissions['apkSha256'] == hashes['candidate']
    assert len(permissions['cases']) == 7
    assert all(case['passed'] and case['homeReached'] and not case['settingsOpened'] for case in permissions['cases'])
result = {'passed': True, 'serial': 'RQCXB06SBVY', 'apkSha256': hashes,
          'caseCount': len(captures), 'flutterBinariesIdentical': True,
          'regionErrorEvents': region['errorEventsInWindow'],
          'permissionCaseCount': len(permissions['cases']) if permission_path.exists() else 0,
          'scope': 'Existing physical ABBA captures and Bluetooth region recovery; no CPU improvement threshold.'}
(root / 'e2e-validation.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result))
PY
