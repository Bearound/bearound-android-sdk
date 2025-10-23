# 📋 Análise de Consumo de Bateria - BeAround Android SDK

## **Situação Atual**

O SDK está funcionando corretamente, mas identificamos diversos pontos de otimização que podem reduzir significativamente o consumo de bateria.

---

## 🔴 **PROBLEMAS CRÍTICOS DE BATERIA**

### **1. Scan Bluetooth Contínuo e Agressivo**

**Localização:** `BeAround.kt:213-215`

```kotlin
beaconManager.setBackgroundScanPeriod(1100L)  // 1.1 segundos
beaconManager.setBackgroundBetweenScanPeriod(timeScanBeacons.seconds)  // Padrão: 20s
beaconManager.setForegroundBetweenScanPeriod(timeScanBeacons.seconds)
```

**Problemas:**
- Scan period de 1.1s é **extremamente agressivo**
- Mesmo com intervalo de 20s, o scan ativo de 1.1s consome muita bateria
- Foreground e background usando os mesmos intervalos

**Impacto:** ⚡⚡⚡⚡⚡ **MUITO ALTO** - Bluetooth scanning é uma das operações que mais consomem bateria

---

### **2. Foreground Service Always-On**

**Localização:** `BeAround.kt:206-209`

```kotlin
beaconManager.enableForegroundServiceScanning(
    foregroundNotification,
    FOREGROUND_SERVICE_NOTIFICATION_ID
)
```

**Problemas:**
- Foreground service rodando permanentemente desde `initialize()`
- Sem opção de pausar/resumir baseado em contexto
- Impede o sistema de aplicar otimizações de bateria

**Impacto:** ⚡⚡⚡⚡ **ALTO** - Foreground services evitam que o app entre em Doze Mode

---

### **3. Network Requests Síncronas e Repetitivas**

**Localização:** `BeAround.kt:339-486`

**Problemas:**
- Request HTTP para cada evento de beacon (enter/exit)
- Sem batching de eventos
- Sem controle de frequência de envio
- Array de backup cresce sem limpeza automática
- Timeout de 15s pode bloquear threads

**Impacto:** ⚡⚡⚡ **MÉDIO-ALTO** - Network é custosa, especialmente em 4G/5G

---

### **4. Location Permission em Background**

**Localização:** `AndroidManifest.xml:16`

```xml
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
```

**Problemas:**
- Background location aumenta consumo significativamente
- Beacons Bluetooth não precisam de GPS contínuo
- Android aplica penalidades de bateria para apps com background location

**Impacto:** ⚡⚡⚡⚡ **ALTO** - Background location é monitorado e penalizado pelo Android

---

### **5. Advertising ID Fetch em Main Thread**

**Localização:** `BeAround.kt:242-258`

**Problemas:**
- Embora use Coroutine, pode causar wake locks
- Chamado toda vez que SDK é inicializado
- Não há persistência local

**Impacto:** ⚡⚡ **BAIXO-MÉDIO** - Uma operação, mas pode atrasar sleep

---

## 🟡 **PROBLEMAS MODERADOS**

### **6. Notification Channel com IMPORTANCE_HIGH**

**Localização:** `BeAround.kt:569`

```kotlin
val importance = NotificationManager.IMPORTANCE_HIGH
```

**Problema:** Notificações HIGH podem impedir deep sleep

**Impacto:** ⚡⚡ **MÉDIO**

---

### **7. Sem Adaptive Scanning**

**Problema:** Não ajusta scan intervals baseado em:
- Estado da bateria
- Presença recente de beacons
- Tempo de dia
- Movimento do usuário

**Impacto:** ⚡⚡⚡ **MÉDIO-ALTO**

---

### **8. ProcessLifecycleOwner Chamado em Cada Sync**

**Localização:** `BeAround.kt:554`

```kotlin
private fun getAppState(): String {
    val state = ProcessLifecycleOwner.get().lifecycle.currentState
    ...
}
```

**Problema:** Chamado em cada sync (potencialmente várias vezes por minuto)

**Impacto:** ⚡ **BAIXO**

---

### **9. JSONArray e JSONObject Repetitivos**

**Problema:** Criação constante de objetos JSON pesados sem pool de objetos

**Impacto:** ⚡ **BAIXO** - Mas contribui para garbage collection

---

## ✅ **RECOMENDAÇÕES DE OTIMIZAÇÃO**

### **🔥 PRIORIDADE CRÍTICA**

#### **1. Otimizar Scan Bluetooth**

```kotlin
// Valores recomendados para economia de bateria
beaconManager.setBackgroundScanPeriod(500L)  // Reduzir de 1100ms para 500ms
beaconManager.setBackgroundBetweenScanPeriod(60000L)  // Aumentar para 60s
beaconManager.setForegroundBetweenScanPeriod(15000L)  // 15s em foreground
```

**Economia estimada:** 30-40% de bateria

---

#### **2. Implementar Adaptive Scanning**

```kotlin
enum class ScanMode {
    AGGRESSIVE,  // Scan period: 500ms, interval: 15s (quando beacon recente detectado)
    NORMAL,      // Scan period: 500ms, interval: 60s (uso padrão)
    ECO,         // Scan period: 300ms, interval: 120s (bateria baixa)
    DEEP_ECO     // Scan period: 200ms, interval: 300s (bateria crítica)
}

fun adjustScanBasedOnBattery() {
    val batteryLevel = getBatteryLevel()
    when {
        batteryLevel < 15 -> setScanMode(ScanMode.DEEP_ECO)
        batteryLevel < 30 -> setScanMode(ScanMode.ECO)
        batteryLevel < 50 -> setScanMode(ScanMode.NORMAL)
        else -> setScanMode(ScanMode.NORMAL)
    }
}
```

**Economia estimada:** 20-30% adicional

---

#### **3. Implementar Event Batching**

```kotlin
private val eventBatchQueue = mutableListOf<BeaconEvent>()
private var lastSyncTime = 0L
private val BATCH_INTERVAL = 60000L  // 1 minuto

fun syncWithApi(beacons: Collection<Beacon>, eventType: String) {
    // Adicionar ao batch
    eventBatchQueue.add(BeaconEvent(beacons, eventType, System.currentTimeMillis()))

    // Enviar apenas se:
    // 1. Passou tempo suficiente OU
    // 2. Batch está cheio OU
    // 3. É evento crítico (exit)
    val shouldSync = System.currentTimeMillis() - lastSyncTime > BATCH_INTERVAL
                    || eventBatchQueue.size >= 10
                    || eventType == EVENT_EXIT

    if (shouldSync) {
        sendBatchToApi()
    }
}
```

**Economia estimada:** 15-25% de bateria

---

#### **4. Implementar Pause/Resume do Scanning**

```kotlin
fun pauseScanning() {
    beaconManager.stopMonitoring(getRegion())
    log("Scanning paused to save battery")
}

fun resumeScanning() {
    beaconManager.startMonitoring(getRegion())
    log("Scanning resumed")
}

// Auto-pause quando app em background por muito tempo
private var backgroundTimer: Timer? = null

override fun didEnterBackground() {
    backgroundTimer = Timer().schedule(300000L) {  // 5 minutos
        if (getAppState() == "background") {
            pauseScanning()
        }
    }
}
```

**Economia estimada:** 50%+ quando em background

---

### **🟡 PRIORIDADE ALTA**

#### **5. Remover Background Location (se possível)**

```xml
<!-- Remover ou tornar opcional -->
<!-- <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" /> -->

<!-- Usar apenas: -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

**Nota:** Beacons Bluetooth funcionam sem GPS ativo. Background location só é necessária se houver lógica de geofencing adicional.

**Economia estimada:** 10-15% de bateria

---

#### **6. Implementar WorkManager para Syncs**

```kotlin
class BeaconSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Enviar batch de eventos
        return Result.success()
    }
}

// Agendar sync periódico
val syncWork = PeriodicWorkRequestBuilder<BeaconSyncWorker>(
    15, TimeUnit.MINUTES,  // Mínimo permitido
    PeriodicWorkRequest.MIN_PERIODIC_FLEX_MILLIS, TimeUnit.MILLISECONDS
).setConstraints(
    Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)  // Importante!
        .build()
).build()
```

**Economia estimada:** 10-20% de bateria

---

#### **7. Cache do Advertising ID**

```kotlin
private fun fetchAdvertisingId() {
    // Tentar carregar do cache primeiro
    val cachedId = sharedPreferences.getString("advertising_id", null)
    if (cachedId != null && System.currentTimeMillis() -
        sharedPreferences.getLong("advertising_id_timestamp", 0) < 86400000L) {
        advertisingId = cachedId
        return
    }

    // Se não, buscar e cachear
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val adInfo = AdvertisingIdClient.getAdvertisingIdInfo(context)
            advertisingId = adInfo.id

            sharedPreferences.edit()
                .putString("advertising_id", advertisingId)
                .putLong("advertising_id_timestamp", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            // ...
        }
    }
}
```

**Economia estimada:** 1-2% de bateria

---

### **🟢 PRIORIDADE MÉDIA**

#### **8. Reduzir Notification Importance**

```kotlin
val importance = NotificationManager.IMPORTANCE_LOW  // ou MIN
```

---

#### **9. Implementar Debouncing para Beacons**

```kotlin
private val beaconDebounceMap = mutableMapOf<String, Long>()
private val DEBOUNCE_INTERVAL = 5000L  // 5 segundos

fun shouldProcessBeacon(beacon: Beacon): Boolean {
    val key = "${beacon.id1}_${beacon.id2}_${beacon.id3}"
    val lastSeen = beaconDebounceMap[key] ?: 0L

    return if (System.currentTimeMillis() - lastSeen > DEBOUNCE_INTERVAL) {
        beaconDebounceMap[key] = System.currentTimeMillis()
        true
    } else {
        false
    }
}
```

---

#### **10. Usar ScanSettings para BLE Efficiency**

```kotlin
// AltBeacon permite configurar ScanSettings
beaconManager.setScannerInSameProcess(true)
beaconManager.setIntentScanningStrategyEnabled(false)  // Mais eficiente
```

---

## 📊 **RESUMO DE IMPACTO**

| Otimização | Economia Estimada | Complexidade | Prioridade |
|-----------|-------------------|--------------|------------|
| Otimizar scan periods | 30-40% | Baixa | 🔴 Crítica |
| Adaptive scanning | 20-30% | Média | 🔴 Crítica |
| Event batching | 15-25% | Média | 🔴 Crítica |
| Pause/Resume API | 50%+ (quando pausado) | Média | 🔴 Crítica |
| Remover background location | 10-15% | Baixa | 🟡 Alta |
| WorkManager para syncs | 10-20% | Alta | 🟡 Alta |
| Cache Advertising ID | 1-2% | Baixa | 🟢 Média |
| Reduzir notification importance | 2-5% | Baixa | 🟢 Média |
| Beacon debouncing | 5-10% | Baixa | 🟢 Média |

**Economia total potencial: 60-80% de redução no consumo de bateria** 🎯

---

## 🛠️ **IMPLEMENTAÇÃO SUGERIDA - ROADMAP**

### **Fase 1 - Quick Wins (1-2 dias)**
1. Ajustar scan periods (BeAround.kt:213-215)
2. Reduzir notification importance
3. Cache do Advertising ID
4. Beacon debouncing

### **Fase 2 - Core Optimizations (1 semana)**
1. Implementar adaptive scanning baseado em bateria
2. Event batching system
3. Pause/Resume API
4. Remover background location permission

### **Fase 3 - Advanced (2 semanas)**
1. WorkManager para syncs
2. Smart scanning (baseado em movimento, tempo, etc.)
3. Analytics de bateria para usuários
4. Testes A/B de diferentes configurações

---

## 🧪 **COMO MEDIR O IMPACTO**

```kotlin
// Adicionar ao SDK
class BatteryMonitor(context: Context) {
    fun getBatteryDrainRate(): Float {
        // Implementar tracking de bateria
    }

    fun logBatteryImpact() {
        // Log para analytics
    }
}
```

Use ferramentas:
- **Android Battery Historian**
- **Profiler do Android Studio**
- **Dumpsys batterystats**

---

## ⚠️ **CONSIDERAÇÕES IMPORTANTES**

1. **Trade-offs:** Menos scanning = menos precisão em tempo real
2. **Configurabilidade:** Permitir que desenvolvedores escolham perfis (Precision vs Battery)
3. **Documentação:** Explicar impacto de bateria nas docs
4. **Testes:** Testar em diferentes dispositivos e versões Android
5. **Compliance:** Algumas otimizações podem afetar SLAs de detecção

---

## 📝 **NOTAS DE IMPLEMENTAÇÃO**

### **Compatibilidade com API Atual**

As otimizações devem ser implementadas de forma retrocompatível:

```kotlin
// Adicionar novos métodos sem quebrar API existente
fun initialize(
    iconNotification: Int,
    clientId: String,
    debug: Boolean = false,
    batteryOptimizationMode: BatteryMode = BatteryMode.BALANCED  // Novo parâmetro opcional
)

enum class BatteryMode {
    PERFORMANCE,  // Máxima precisão, maior consumo
    BALANCED,     // Padrão atual
    ECO,          // Economia de bateria
    ULTRA_ECO     // Máxima economia
}
```

### **Backward Compatibility**

- Manter comportamento padrão atual para não quebrar apps existentes
- Novos parâmetros opcionais com valores padrão
- Documentar mudanças de comportamento no changelog
- Versionar corretamente (considerar major version se breaking changes)

---

## 🔍 **BENCHMARKS SUGERIDOS**

Antes de implementar, criar benchmarks para:

1. **Consumo de bateria por hora** (mAh/h)
2. **CPU usage** durante scanning
3. **Network requests por hora**
4. **Wake locks duration**
5. **Time to detect beacon** (latência)
6. **False positives/negatives** rate

### **Ambiente de Teste**

- Dispositivos: Low-end (< 2GB RAM), Mid-range, High-end
- Android versions: 8.0, 10.0, 12.0, 13.0, 14.0+
- Cenários: App em foreground, background, doze mode
- Duração: Testes de 24h, 48h, 1 semana

---

## 📚 **REFERÊNCIAS**

- [Android Battery Optimization Best Practices](https://developer.android.com/topic/performance/power)
- [Bluetooth Low Energy Overview](https://developer.android.com/guide/topics/connectivity/bluetooth/ble-overview)
- [AltBeacon Battery Consumption Guide](https://altbeacon.github.io/android-beacon-library/power-saving.html)
- [WorkManager Best Practices](https://developer.android.com/topic/libraries/architecture/workmanager/advanced)
- [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)

---

**Última atualização:** 2025-10-22
**Versão do SDK analisada:** 1.0.15
**Status:** Em análise
