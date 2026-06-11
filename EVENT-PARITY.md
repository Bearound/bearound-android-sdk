# Bearound SDK — Event & Field Parity

`sdk.technology` (campo no payload /ingest): `ios-native` | `android-native` | `react-native` | `flutter`. Definido pelo `configure()` nativo (default `ios-native`/`android-native`); os bridges RN/Flutter passam o seu valor.

## Eventos (todas as 4 libs)
| Conceito | iOS (delegate) | Android (listener) | RN (`bearound:*`) | Flutter (stream) | Paridade |
|---|---|---|---|---|---|
| Beacons | didUpdateBeacons | onBeaconsUpdated | beacons | beaconsStream | comum |
| Scanning | didChangeScanning | onScanningStateChanged | scanning | scanningStream | comum |
| Active scan | didChangeActiveScanState | onActiveScanStateChanged | activeScan | activeScanStream | comum |
| Sync start | willStartSync | onSyncStarted | syncLifecycle(started) | syncLifecycleStream | comum |
| Sync done | didCompleteSync | onSyncCompleted | syncLifecycle(completed) | syncLifecycleStream | comum |
| Beacon region enter/exit | didEnter/ExitBeaconRegion | onEnter/ExitBeaconRegion | beaconRegion | beaconRegionStream | comum |
| Background detection | didDetectBeaconInBackground(beacons) | onBeaconDetectedInBackground(count) | backgroundDetection {beaconCount} | backgroundDetectionStream | comum* |
| Error | didFailWithError | onError | error | errorStream | comum |
| Bluetooth zone enter/exit | didEnter/ExitBluetoothZone | — | bluetoothZone | bluetoothZoneStream | só iOS (two-eyes) |
| BT scan mode | didChangeBluetoothScanMode | — | bluetoothScanMode | bluetoothScanModeStream | só iOS |
| Push scan complete | didCompletePushScan | — | — | — | só iOS-nativo |
| App state changed | — | onAppStateChanged | — | — | só Android-nativo |
| Notification content (pull) | — | onProvideNotificationContent | pull | pull | só Android (foreground service) |
| BT adapter state | — | — | bluetoothState | bluetoothStateStream | só bridges (sintetizado) |

\* background-detection: a assinatura nativa difere (iOS `[Beacon]` vs Android `Int`) por design; os bridges expõem `{beaconCount}` idêntico nas duas plataformas.

Os 8 eventos "comuns" têm nomes por convenção de cada plataforma (iOS `didX`, Android `onX`) e payloads equivalentes. Os demais são divergências de capacidade de plataforma (iOS tem o BLE "two-eyes" + push-scan; Android tem foreground-service/app-state) e ficam documentados aqui.
