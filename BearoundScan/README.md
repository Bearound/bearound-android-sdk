# BearoundScan/ — App de diagnóstico e teste avançado

Sample **completo** do BeAround Android SDK em Jetpack Compose. É a ferramenta interna de
QA/diagnóstico: além da integração básica (permissões → configure → startScanning →
listener), demonstra o **foreground service com notificação customizada**, o **log de
detecções** filtrado e o **debug de geofence** (região BLE + scan ativo).

> Para a integração mínima de referência, veja [`app/`](../app/README.md).

## 🔑 Business token

Este sample lê o token de `BuildConfig.BUSINESS_TOKEN`, com **fallback para o token
público de demonstração** (veja `BearoundScan/build.gradle`), permitindo testar o SDK sem
nenhum setup. Os dados vão para o business de demonstração da Bearound.

Para ver as detecções no **seu** Control Hub, defina `BUSINESS_TOKEN=<seu-token>` no
`local.properties` (gitignorado) ou como variável de ambiente — **sem mudar código**.
Token fornecido pelo time Bearound — veja
["Getting a business token"](../README.md#getting-a-business-token).

## 📱 O que o app demonstra

### Aba Beacons (`ContentScreen`)
- **Solicitação automática de permissões** no launch: localização (FINE/COARSE),
  `BLUETOOTH_SCAN` no Android 12+, `POST_NOTIFICATIONS` no 13+. No Android 12+, o gate
  técnico do scan é `BLUETOOTH_SCAN` (sem `neverForLocation`) — o scan inicia **mesmo com
  localização negada**. O SDK declara localização em todas as versões e recomenda
  concedê-la junto para cobertura máxima (alguns OEMs); no Android ≤ 11 ela é obrigatória
  para o scan BLE.
- **Status de permissões** (localização / Bluetooth / notificações) com cores.
- **Card "Debug Geofence"**: estado da região BLE (DENTRO/fora via
  `onEnterBeaconRegion`/`onExitBeaconRegion`), timestamps do último enter/exit, estado do
  scan ativo (`onActiveScanStateChanged` — duty cycle ligado/desligado) e os últimos 30
  eventos de região.
- **Lista de beacons** com proximidade colorida, RSSI, distância, ordenação
  (proximidade/ID) e pin.

### Foreground service com notificação customizada (⚙️ Settings)
Bottom sheet de configurações, persistido em `SharedPreferences`:
- **Toggle do foreground service** — liga/desliga o `BeaconScanService`; quando ligado, o
  config é passado em `sdk.startScanning(ForegroundScanConfig(...))`; quando desligado,
  chama `sdk.disableForegroundScanning()`.
- **Título e texto da notificação** do serviço (`ForegroundScanConfig`), e o **texto
  contextual** exibido quando beacons são detectados em background — devolvido ao SDK via
  `onProvideNotificationContent(beacons): NotificationContent`.
- **`ScanPrecision`** (HIGH/MEDIUM/LOW) e **`MaxQueuedPayloads`** — reconfigura o SDK e
  reinicia o scan preservando o estado.
- **User properties** (`internalId`, e-mail, nome, propriedade custom) via
  `sdk.setUserProperties(...)` / `sdk.clearUserProperties()`.

### Aba Log (`DetectionLogScreen`)
Log de todas as detecções (até 50.000 entradas em memória), com:
- Filtro **FG / BG / todos** (detecções em foreground vs. background)
- Filtro por **origem da detecção** (Service UUID / iBeacon / ambos)
- Visualização **detalhada** ou **agrupada por minuto** (com contagem FG/BG por grupo)

### Notificações locais de eventos
`BeaconNotificationManager` converte os callbacks do SDK em notificações locais (beacon
detectado em FG/BG, scan iniciado/parado, sync iniciado/concluído) — útil para validar o
comportamento em background/terminated sem adb.

## 🚀 APIs do SDK exercitadas

```kotlin
sdk.configure(businessToken, scanPrecision, maxQueuedPayloads)
sdk.startScanning(foregroundScanConfig)   // FGS opcional via config
sdk.stopScanning()
sdk.disableForegroundScanning()
sdk.setUserProperties(...) / sdk.clearUserProperties()
```

Listener (`BeAroundSDKListener`) — callbacks implementados:
`onBeaconsUpdated`, `onError`, `onScanningStateChanged`, `onAppStateChanged`,
`onSyncStarted`, `onSyncCompleted`, `onBeaconDetectedInBackground`,
`onProvideNotificationContent`, `onEnterBeaconRegion`, `onExitBeaconRegion`,
`onActiveScanStateChanged`.

## 📦 Estrutura

```
BearoundScan/src/main/java/io/bearound/bearoundscan/
├── BeAroundScanApplication.kt        # Listener global de background
├── MainActivity.kt                   # Tabs: Beacons + Log
├── SDKBackgroundListener.kt          # Notificações com Activity destruída
├── model/DetectionLogEntry.kt        # Entrada do log de detecções
├── notification/BeaconNotificationManager.kt
├── ui/
│   ├── ContentScreen.kt              # Aba principal (permissões, geofence, beacons)
│   ├── SettingsScreen.kt             # Bottom sheet (FGS, precision, user props)
│   ├── DetectionLogScreen.kt         # Log com filtros e agrupamento
│   ├── GeofenceDebugCard.kt          # Debug de região BLE / scan ativo
│   └── BeaconRow.kt
└── viewmodel/BeaconViewModel.kt      # Estado + BeAroundSDKListener + configure
```

## 🧪 Como testar background / terminated

1. Rode em **device físico** com um **beacon Bearound (0xBEAD)** por perto — iBeacon
   genérico/iPhone **não** é detectado (veja
   ["What the SDK detects"](../README.md#what-the-sdk-detects)).
2. Conceda "Dispositivos por perto" (12+) e notificações (13+).
3. Coloque o app em background → a notificação do foreground service aparece (se o toggle
   estiver ligado) e muda para o texto contextual ao detectar beacons.
4. Force-stop no Android 14+ → aproxime o beacon → o `PendingIntent` scan acorda o processo
   e o `SDKBackgroundListener` notifica.
5. Use a aba **Log** (filtro BG) e o card **Debug Geofence** para confirmar as detecções.

## 📱 Requisitos e build

- Android 8.0+ (API 26+ — `minSdk` deste sample; o SDK em si suporta 23+)
- Dispositivo físico com BLE
- O build `release` é assinado com o **debug keystore** para facilitar instalação direta em
  device de teste — não use essa variante para distribuição real.
