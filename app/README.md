# app/ — Exemplo básico de integração (BeAround Scan)

App de exemplo em **Jetpack Compose** que demonstra a integração mínima do BeAround Android
SDK: permissões em runtime, `configure()` + `startScanning()`, listener com UI ao vivo e a
fila de retry offline.

> Para o sample completo de diagnóstico (foreground service com notificação customizada,
> log de detecções, debug de geofence), veja [`BearoundScan/`](../BearoundScan/README.md).

## 🔑 Business token (obrigatório antes de rodar)

O app lê o `BUSINESS_TOKEN` de `local.properties` (gitignored) via `BuildConfig`:

```properties
# local.properties (na raiz do repositório)
BUSINESS_TOKEN=seu-business-token-aqui
```

Alternativamente, defina a variável de ambiente `BUSINESS_TOKEN` antes do build.

> ⚠️ **Sem o token o app crasha na abertura**: `configure()` lança
> `IllegalArgumentException` para token vazio, e o `BeaconViewModel` configura o SDK no
> `init`. Se o app fechar imediatamente ao abrir, confira o `local.properties`.

O token é fornecido pelo time Bearound junto com o acesso ao Control Hub — veja
["Getting a business token"](../README.md#getting-a-business-token) no README raiz.

## 📱 O que o app demonstra

### Aba Beacons
- **Status de permissões** — localização, Bluetooth e notificações, com indicação colorida
- **Solicitação automática de permissões** ao abrir (localização; `BLUETOOTH_SCAN` no
  Android 12+; `POST_NOTIFICATIONS` no 13+) e **auto-start** do scan após concedidas
- **Configuração ao vivo** — seletor de `ScanPrecision` (HIGH/MEDIUM/LOW) e
  `MaxQueuedPayloads`; reflete `currentSyncInterval`/`currentScanDuration` do SDK
- **Lista de beacons** com Major.Minor, proximidade colorida (🟢 imediato / 🟠 perto /
  🔴 longe), distância estimada e RSSI
- **Ordenação** por proximidade ou ID, e **pin** de beacons no topo da lista

### Aba Retry Queue
- Visualiza `sdk.pendingBatches` — os lotes que falharam no envio e aguardam retry
- Badge com `sdk.pendingBatchCount` na navegação, refresh automático ao abrir a aba e após
  cada sync

### Notificações locais de eventos do SDK
O app converte callbacks do listener em notificações locais para facilitar o teste em
background: beacon detectado (foreground e background), scan iniciado/parado e sync
iniciado/concluído.

### Listener de background
`BeAroundScanApplication` registra um `SDKBackgroundListener` global no `Application`, que
permanece ativo quando a Activity é destruída — é ele que notifica quando o SDK acorda o
processo em background. O `BeaconViewModel` assume o listener enquanto a UI está viva e o
restaura no `onCleared()`.

## 🎯 Casos de uso (roteiro de teste)

### Permissões
1. Abrir o app e ver o status de cada permissão (localização, Bluetooth, notificações)
2. Testar com permissões negadas/concedidas
3. Verificar o comportamento do SDK em cada combinação (no 12+, `BLUETOOTH_SCAN` é o que destrava)

### Modos de scan
1. Alternar a precisão **HIGH** (contínuo, detecção máxima) e **MEDIUM/LOW** (duty cycle, economia)
2. Observar as transições foreground/background
3. Verificar ranging ativo/pausado

### Sync e fila offline
1. Observar o intervalo de sync por precisão e o countdown até o próximo envio
2. Deixar sem rede e ver os lotes acumularem na aba **Retry Queue**
3. Monitorar o consumo de bateria por modo

### Detecção
1. Aproximar de um beacon Bearound real
2. Ver distância e RSSI em tempo real
3. Testar a ordenação por proximidade/ID e os indicadores de cor

### Notificações
1. Sair e entrar na região dos beacons
2. Verificar a notificação local (detecção, scan, sync)
3. Confirmar o cooldown (sem spam)

## 🚀 Integração demonstrada (código real)

```kotlin
val sdk = BeAroundSDK.getInstance(application)
sdk.listener = this // BeAroundSDKListener

sdk.configure(
    businessToken = BuildConfig.BUSINESS_TOKEN,
    scanPrecision = ScanPrecision.MEDIUM,
    maxQueuedPayloads = MaxQueuedPayloads.MEDIUM
)

sdk.startScanning()
```

Callbacks usados (interface `BeAroundSDKListener`):

```kotlin
override fun onBeaconsUpdated(beacons: List<Beacon>) { /* atualiza a lista */ }
override fun onError(error: Exception) { /* mostra na status bar */ }
override fun onScanningStateChanged(isScanning: Boolean) { /* notificação local */ }
override fun onAppStateChanged(isInBackground: Boolean) { /* atualiza estado */ }
override fun onSyncStarted(beaconCount: Int) { /* notificação local */ }
override fun onSyncCompleted(beaconCount: Int, success: Boolean, error: Exception?) {
    /* notificação local + refresh da retry queue */
}
override fun onBeaconDetectedInBackground(beaconCount: Int) { /* notificação local */ }
```

## 📦 Estrutura

```
app/src/main/java/io/bearound/scan/
├── BeAroundScanApplication.kt  # Registra o listener global de background
├── MainActivity.kt             # Activity única com Compose
├── BeAroundScanApp.kt          # UI (tabs Beacons + Retry Queue, permissões)
├── BeaconViewModel.kt          # Estado + implementação do BeAroundSDKListener
├── SDKBackgroundListener.kt    # Listener que sobrevive à Activity
├── NotificationManager.kt      # Notificações locais dos eventos do SDK
└── ui/theme/Theme.kt           # Material Design 3
```

## 📊 Comparação com o sample iOS

Os apps de exemplo Android e iOS foram construídos com paridade de funcionalidade:

| Feature | iOS BeAroundScan | Android BeAroundScan |
|---------|------------------|----------------------|
| UI moderna | SwiftUI | Jetpack Compose |
| Status de permissões | ✅ | ✅ |
| Info de scan ao vivo | ✅ | ✅ |
| Controles (precisão, fila) | ✅ | ✅ |
| Lista de beacons | ✅ | ✅ |
| Notificações locais | ✅ | ✅ |
| Auto-start | ✅ | ✅ |
| Indicadores de cor | ✅ | ✅ |

## 📱 Requisitos

- Android 6.0+ (API 23+)
- Dispositivo físico com Bluetooth LE (emulador não tem BLE utilizável)
- **Beacon Bearound real** — o SDK só detecta advertisements `0xBEAD`; iBeacon genérico
  (inclusive iPhone simulando beacon) não aparece. Veja
  ["What the SDK detects"](../README.md#what-the-sdk-detects).

## 🔍 Observações sobre permissões no Android 12+

No Android 12+ a permissão que destrava a detecção é **`BLUETOOTH_SCAN`** ("Dispositivos
por perto") — localização **não** destrava o scan (o SDK declara `neverForLocation`). O app
ainda solicita localização para exibir o status na UI e cobrir Android ≤ 11, mas o scan
inicia se `BLUETOOTH_SCAN` for concedida, mesmo com localização negada.
