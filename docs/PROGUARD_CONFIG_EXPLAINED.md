# Configuração ProGuard do BeAround SDK

Este documento explica em detalhes como o ProGuard está configurado no BeAround SDK para proteger o código enquanto mantém as APIs públicas acessíveis.

## 📋 Visão Geral

O SDK usa **duas camadas** de configuração ProGuard:

1. **`proguard-rules.pro`**: Regras aplicadas ao SDK durante o build
2. **`consumer-rules.pro`**: Regras aplicadas aos apps que consomem o SDK

## 🔐 Estratégia de Ofuscação

### Objetivo
- ✅ **Proteger** implementações internas e métodos privados
- ✅ **Expor** APIs públicas documentadas
- ✅ **Otimizar** tamanho do binário
- ✅ **Remover** logs de debug em release

### Níveis de Proteção

```
┌─────────────────────────────────────────────┐
│           Consumidor do SDK                 │
│  (Pode acessar apenas APIs públicas)        │
└─────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────┐
│         APIs Públicas (Expostas)            │
│  - BeAround.getInstance()                   │
│  - BeAround.initialize()                    │
│  - BeAround.addBeaconEventListener()        │
│  - BeaconEventListener interface            │
│  - BeaconData, SyncResult, etc.             │
└─────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────┐
│    Implementação Interna (Ofuscada)         │
│  - syncWithApi() → a.b.c()                  │
│  - beaconsToBeaconData() → a.b.d()          │
│  - rangeNotifierForSync → campo ofuscado    │
│  - Constantes privadas → ofuscadas          │
└─────────────────────────────────────────────┘
```

## 📄 proguard-rules.pro

### 1. APIs Públicas Mantidas

```proguard
# Classe principal - todos os métodos públicos são mantidos
-keep public class io.bearound.sdk.BeAround {
    public *;
    public static *;
}

# Interfaces públicas - mantidas integralmente
-keep public interface io.bearound.sdk.BeaconEventListener {
    public *;
}

-keep public interface io.bearound.sdk.LogListener {
    public *;
}
```

**Por que?** Consumidores do SDK precisam chamar esses métodos pelos nomes originais.

### 2. Classes de Dados

```proguard
# Data classes e sealed classes
-keep public class io.bearound.sdk.BeaconData {
    public *;
}

-keep public class io.bearound.sdk.SyncResult {
    public *;
}

-keep public class io.bearound.sdk.SyncResult$Success {
    public *;
}

-keep public class io.bearound.sdk.SyncResult$Error {
    public *;
}
```

**Por que?**
- Reflection pode ser usado em data classes
- Garantir serialização/deserialização funcione corretamente
- Properties devem ser acessíveis por nome

### 3. Enums

```proguard
-keep public enum io.bearound.sdk.BeaconEventType {
    public *;
}

-keep public enum io.bearound.sdk.BeAround$TimeScanBeacons {
    public *;
}

-keep public enum io.bearound.sdk.BeAround$SizeBackupLostBeacons {
    public *;
}
```

**Por que?** Enums precisam manter seus valores para funcionar corretamente.

### 4. Métodos Privados (Ofuscados)

```proguard
# Estes serão OFUSCADOS (nomes alterados)
-keepclassmembers class io.bearound.sdk.BeAround {
    private *;
    private static *;
}
```

**Resultado:**
```kotlin
// Antes (código fonte)
private fun syncWithApi(beacons: Collection<Beacon>, eventType: String)
private fun beaconsToBeaconData(beacons: Collection<Beacon>): List<BeaconData>

// Depois (no .aar)
private fun a(b: Collection<Beacon>, c: String)
private fun d(b: Collection<Beacon>): List<BeaconData>
```

### 5. Otimizações

```proguard
# Remove logs em release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
```

**Resultado:** Todo código de logging é completamente removido do bytecode.

### 6. Dependências

```proguard
# AltBeacon - biblioteca externa precisa ser mantida
-keep class org.altbeacon.beacon.** { *; }
-keep interface org.altbeacon.beacon.** { *; }

# Google Play Services
-keep class com.google.android.gms.ads.identifier.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
```

**Por que?** Evitar quebra de funcionalidades das bibliotecas externas.

## 📄 consumer-rules.pro

Este arquivo é **incluído automaticamente** nos apps que usam o SDK.

### Propósito

Garante que quando o app consumidor executar ProGuard, ele não vai:
- ❌ Ofuscar as APIs públicas do SDK
- ❌ Remover classes necessárias
- ❌ Quebrar reflection usado internamente

### Exemplo Prático

**Sem consumer-rules.pro:**
```kotlin
// App consumidor após ProGuard
val sdk = a.b.c.d()  // BeAround.getInstance() foi ofuscado!
sdk.e()              // initialize() foi ofuscado!
```

**Com consumer-rules.pro:**
```kotlin
// App consumidor após ProGuard
val sdk = BeAround.getInstance()  // ✅ Nome mantido
sdk.initialize()                  // ✅ Nome mantido
```

## 🧪 Testando a Ofuscação

### 1. Build do SDK

```bash
./gradlew :sdk:assembleRelease
```

### 2. Verificar Mapping

```bash
cat sdk/build/outputs/mapping/release/mapping.txt
```

Exemplo de saída:
```
io.bearound.sdk.BeAround -> io.bearound.sdk.BeAround:
    void initialize(int,java.lang.String,boolean) -> initialize
    void stop() -> stop
    void syncWithApi(java.util.Collection,java.lang.String) -> a
    java.util.List beaconsToBeaconData(java.util.Collection) -> b
```

**Interpretação:**
- `initialize` → mantido (público)
- `stop` → mantido (público)
- `syncWithApi` → ofuscado para `a` (privado)
- `beaconsToBeaconData` → ofuscado para `b` (privado)

### 3. Extrair e Inspecionar .aar

```bash
# Extrair conteúdo
unzip release/bearound-android-sdk-1.0.16.aar -d aar-content

# Ver classes
cd aar-content
unzip classes.jar -d classes
cd classes

# Decompile (use jadx ou similar)
jadx org/bearound/sdk/BeAround.class
```

## 📊 Comparação: Antes vs Depois

| Item | Sem Ofuscação | Com Ofuscação |
|------|---------------|---------------|
| Tamanho .aar | ~180 KB | ~120 KB |
| Métodos públicos | Nomes originais | Nomes originais |
| Métodos privados | Nomes originais | **a, b, c, d...** |
| Logs debug | Presentes | **Removidos** |
| Constantes privadas | Visíveis | **Ofuscadas** |

## 🔍 O Que É Protegido?

### ✅ Totalmente Protegido
- Implementação de `syncWithApi`
- Implementação de `syncFailedBeaconsArrayWithApi`
- Campo `lastSeenBeacon`
- Campo `syncFailedBeaconsArray`
- Campo `advertisingId`
- Constante `API_ENDPOINT_URL`
- Lógica interna de conversão de beacons

### ⚠️ Parcialmente Visível
- Estrutura de classes públicas (por necessidade)
- Assinaturas de métodos públicos
- Tipos de parâmetros e retorno

### ❌ Não Protegido
- Métodos públicos (intencionalmente)
- Interfaces públicas (necessário para uso)
- Data classes públicas (necessário para acesso)

## 🛡️ Benefícios da Configuração Atual

1. **Segurança**: Implementação interna é difícil de engenharia reversa
2. **Compatibilidade**: APIs públicas permanecem estáveis
3. **Performance**: Otimizações aplicadas reduzem tamanho
4. **Manutenibilidade**: Debug info mantida para stack traces
5. **Usabilidade**: Consumer rules protegem o SDK quando usado em apps

## 🚨 Considerações Importantes

### Debug de Crashes

Quando ocorrer um crash em produção, o stack trace virá ofuscado:

```
at io.bearound.sdk.BeAround.a(Unknown Source)
at io.bearound.sdk.BeAround.b(Unknown Source)
```

**Solução:** Use o arquivo `mapping.txt` para deofuscar:

```bash
retrace.sh -verbose mapping.txt stacktrace.txt
```

### Reflection

Se o SDK usar reflection em métodos privados, adicione regras específicas:

```proguard
-keepclassmembers class io.bearound.sdk.BeAround {
    private void metodoUsadoComReflection(...);
}
```

### Serialização

Se classes privadas forem serializadas (ex: com Gson), mantenha-as:

```proguard
-keep class io.bearound.sdk.internal.** { *; }
```

## 📚 Referências

- [ProGuard Manual](https://www.guardsquare.com/manual/configuration)
- [Android ProGuard Guide](https://developer.android.com/studio/build/shrink-code)
- [R8 and ProGuard](https://developer.android.com/studio/build/shrink-code#optimization)
