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

## Encounter layer (device-to-device)

Contrato compartilhado entre os SDKs nativos (branch `feat/encounter-mesh` nos dois repos):

| Constante | Valor | Uso |
|---|---|---|
| Service UUID | `B3A20001-0000-4000-8000-BEA0BEA0BEA0` | Anunciado por todo host com o SDK; filtro de scan nas duas plataformas |
| Characteristic (RPI) | `B3A20002-0000-4000-8000-BEA0BEA0BEA0` | Read-only; responde os 16 bytes CRUS do identificador rotativo atual |
| Rotação do RPI | 15 min | `[atual, anterior]` reportados como `encounterIds` no payload |
| **Major reservado (beacon virtual)** | **65535 (`0xFFFF`)** | Ver abaixo — NUNCA é uma detecção |

**Major 65535 (`0xFFFF`) — beacon virtual.** Hosts iOS em foreground intercalam, além do
service UUID, um frame iBeacon no UUID Bearound com major `0xFFFF` e minor derivado do RPI
(2 primeiros bytes). O frame existe para que o region monitoring dos vizinhos dispare na
aproximação de um host (inclusive relançando apps force-quit). **Todo caminho de recepção
DEVE filtrar esse major antes do pipeline de detecção** — um host nunca pode aparecer como
beacon físico:

- iOS: `BluetoothManager` (parser iBeacon do CoreBluetooth) + `BeaconManager.processBeacons`
  (ranging do CoreLocation) descartam `major == 0xFFFF`.
- Android: `IBeaconParser.parseIBeaconFrame` retorna `null` para `major == 0xFFFF`
  (constante `VIRTUAL_ENCOUNTER_MAJOR`), cobrindo o scan ativo e o PendingIntent.

Payload (aditivo, omitido quando a camada não tem nada a reportar):
`encounters[] = {rpi, rssi, rssiSamples{count,min,max,avg}, firstSeen, lastSeen}` + `encounterIds[]`.
O gate de sync de "sem beacons novos" TAMBÉM abre para avistamentos identificados frescos
(throttle: 1 upload encounters-only por 60s).

Diferenças de plataforma (por capacidade, não por design):
- Android anuncia o service UUID sempre (fg/bg, enquanto o processo viver); iOS em bg move o
  advertisement para a overflow area (visível só a scanners iOS filtrando o UUID) e app
  encerrado não transmite em nenhuma plataforma.
- iOS em bg NÃO é visível para Android (overflow area é proprietária).
- Permissões: Android 12+ requer `BLUETOOTH_ADVERTISE`/`BLUETOOTH_CONNECT` (runtime-checked,
  degrada sem lançar); iOS usa a permissão de Bluetooth existente.
