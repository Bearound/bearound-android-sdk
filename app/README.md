# BeAroundScan - App de Teste do SDK Android

Este é o app de teste equivalente ao **BeAroundScan do iOS**, desenvolvido com Jetpack Compose para testar e demonstrar as funcionalidades do BeAround Android SDK.

## 📱 Características

O app oferece uma interface moderna e completa para:

### 1. **Monitoramento de Permissões**
- ✅ Localização (Sempre / Quando em uso / Negada)
- ✅ Bluetooth (Ligado / Desligado)
- ✅ Notificações (Autorizada / Negada)
- Indicadores visuais com cores (verde/laranja/vermelho)

### 2. **Informações Detalhadas do Scan**
- **Modo de Scanning**: Periódico ou Contínuo
- **Intervalo de Sync**: Tempo entre envios para API
- **Duração do Scan**: Tempo ativo de scanning
- **Tempo de Pausa**: Intervalo entre scans (modo periódico)
- **Countdown**: Segundos até próximo sync
- **Status de Ranging**: Indicador visual (Ativo/Pausado)

### 3. **Controles**
- Botão **Iniciar/Parar Scan** com cores diferentes
- Seletor de **Intervalo de Sync**: 5, 10, 15, 20, 30, 60 segundos
- Seletor de **Ordenação**: Por Proximidade ou por ID

### 4. **Lista de Beacons**
Para cada beacon detectado:
- Major.Minor (ID do beacon)
- UUID completo
- **Indicador de Proximidade** com cor:
  - 🟢 Verde: Imediato (muito perto)
  - 🟠 Laranja: Perto
  - 🔴 Vermelho: Longe
  - ⚪ Cinza: Desconhecido
- **Distância estimada** em metros
- **RSSI** (força do sinal) em dBm

### 5. **Notificações**
- Notifica quando entrar em região de beacons
- Cooldown de 5 minutos para evitar spam
- Delay de 2 segundos ao iniciar para evitar notificações indevidas

### 6. **Auto-Start**
- Solicita permissões automaticamente ao abrir
- Inicia scanning automaticamente após permissões concedidas

## 🎨 Interface

O app usa **Jetpack Compose** (equivalente ao SwiftUI do iOS) para uma interface:
- ✨ Moderna e fluida
- 🎯 Responsiva
- 🌓 Suporte a tema claro/escuro
- 📱 Material Design 3

## 🔑 Configuração do Business Token

O app lê o `BUSINESS_TOKEN` de `local.properties` (gitignored) via `BuildConfig`. Antes de rodar:

```properties
# local.properties
BUSINESS_TOKEN=seu-business-token-aqui
```

Alternativamente, defina via variável de ambiente `BUSINESS_TOKEN` antes do build.

## 🚀 Funcionalidades do SDK Demonstradas

### Inicialização e Configuração
```kotlin
val sdk = BeAroundSDK.getInstance(context)
sdk.delegate = this

sdk.configure(
    businessToken = BuildConfig.BUSINESS_TOKEN,
    foregroundScanInterval = ForegroundScanInterval.SECONDS_15,
    backgroundScanInterval = BackgroundScanInterval.SECONDS_30,
    maxQueuedPayloads = MaxQueuedPayloads.MEDIUM
    // Bluetooth scanning and periodic scanning are now automatic in v2.2.0
)
```

### Controle de Scanning
```kotlin
sdk.startScanning()
sdk.stopScanning()
```

### Delegate Callbacks
```kotlin
override fun didUpdateBeacons(beacons: List<Beacon>) {
    // Atualiza UI com beacons detectados
}

override fun didFailWithError(error: Exception) {
    // Trata erros
}

override fun didChangeScanning(isScanning: Boolean) {
    // Atualiza estado de scanning
}

// onSyncStatusUpdated removed in v2.2.0 for battery optimization
```

### Informações do SDK
```kotlin
val syncInterval = sdk.currentSyncInterval // milissegundos
val scanDuration = sdk.currentScanDuration // milissegundos
val isPeriodicMode = sdk.isPeriodicScanningEnabled
val isScanning = sdk.isScanning
```

## 📦 Estrutura do App

```
app/src/main/java/io/bearound/scan/
├── MainActivity.kt              # Activity principal com Compose
├── BeAroundScanApp.kt          # Interface principal (UI)
├── BeaconViewModel.kt          # ViewModel com lógica de negócio
├── NotificationManager.kt      # Gerenciador de notificações
└── ui/theme/
    └── Theme.kt                # Tema Material Design 3
```

## 🔧 Dependências

```gradle
// Jetpack Compose
implementation platform("androidx.compose:compose-bom:2024.11.00")
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.material3:material3")
implementation("androidx.compose.material:material-icons-extended")
implementation("androidx.activity:activity-compose:1.9.3")
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")

// BeAround SDK
implementation project(":sdk")
```

## 🎯 Casos de Uso

### Teste de Permissões
1. Abrir app
2. Ver status de cada permissão
3. Testar com permissões negadas/concedidas
4. Verificar comportamento do SDK

### Teste de Scanning Modes
1. Testar modo **Periódico** (economia de bateria)
2. Testar modo **Contínuo** (detecção máxima)
3. Observar transições foreground/background
4. Verificar ranging ativo/pausado

### Teste de Intervalos
1. Testar diferentes intervalos (5s a 60s)
2. Observar countdown
3. Verificar envios para API
4. Monitorar consumo de bateria

### Teste de Detecção
1. Aproximar de beacons
2. Ver distância e RSSI em tempo real
3. Testar ordenação por proximidade/ID
4. Verificar indicadores de cor

### Teste de Notificações
1. Sair e entrar na região de beacons
2. Verificar notificação
3. Testar cooldown (não spam)

## 📊 Comparação com iOS

| Feature | iOS BeAroundScan | Android BeAroundScan | Status |
|---------|------------------|----------------------|--------|
| UI Moderna | SwiftUI | Jetpack Compose | ✅ Equivalente |
| Permissões | ✅ | ✅ | ✅ Equivalente |
| Scan Info | ✅ | ✅ | ✅ Equivalente |
| Controles | ✅ | ✅ | ✅ Equivalente |
| Lista Beacons | ✅ | ✅ | ✅ Equivalente |
| Notificações | ✅ | ✅ | ✅ Equivalente |
| Auto-start | ✅ | ✅ | ✅ Equivalente |
| Indicadores Cor | ✅ | ✅ | ✅ Equivalente |

## 🔍 Debug

O app mostra em tempo real:
- Número de beacons detectados
- Status de cada permissão
- Tempo até próximo sync
- Status de ranging
- Informações detalhadas de cada beacon

## 📱 Requisitos

- Android 5.0+ (API 21+)
- Bluetooth LE
- Permissões de localização
- Dispositivo físico com BLE (não funciona em emulador)

## 🎓 Uso como Referência

Este app serve como:
1. ✅ **Teste do SDK** - Valida todas as funcionalidades
2. ✅ **Exemplo de Integração** - Mostra como usar o SDK
3. ✅ **Referência de UI** - Demonstra boas práticas de Compose
4. ✅ **Debug Tool** - Facilita desenvolvimento e testes

---

**Desenvolvido para testar o BeAround Android SDK**  
Equivalente ao BeAroundScan do iOS

