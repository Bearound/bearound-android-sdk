# Migration Guide - Version 1.0.16

## ⚠️ Breaking Changes

### Package Name Changed

O package do SDK foi alterado de `org.bearound.sdk` para `io.bearound.sdk` para refletir corretamente o domínio da empresa (bearound.io).

## 🔄 Como Migrar

### Passo 1: Atualizar Imports

**Antes (v1.0.15 e anteriores):**
```kotlin
import org.bearound.sdk.BeAround
import org.bearound.sdk.BeaconEventListener
import org.bearound.sdk.BeaconData
import org.bearound.sdk.BeaconEventType
import org.bearound.sdk.SyncResult
import org.bearound.sdk.LogListener
```

**Depois (v1.0.16+):**
```kotlin
import io.bearound.sdk.BeAround
import io.bearound.sdk.BeaconEventListener
import io.bearound.sdk.BeaconData
import io.bearound.sdk.BeaconEventType
import io.bearound.sdk.SyncResult
import io.bearound.sdk.LogListener
```

### Passo 2: Find & Replace

No Android Studio / IntelliJ:

1. **Ctrl/Cmd + Shift + R** (Replace in Path)
2. Find: `org.bearound.sdk`
3. Replace: `io.bearound.sdk`
4. Scope: **Project Files**
5. Click **Replace All**

### Passo 3: Sync Project

```bash
# No Android Studio
File → Sync Project with Gradle Files
```

Ou via linha de comando:
```bash
./gradlew clean build
```

## 📝 Código de Exemplo

### Inicialização (sem mudanças na API)

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // ✅ API permanece igual, apenas o import mudou
        val beAround = BeAround.getInstance(applicationContext)
        beAround.initialize(
            iconNotification = R.drawable.ic_notification,
            clientToken = "seu-token",
            debug = true
        )
    }
}
```

### Listeners (sem mudanças na API)

```kotlin
// ✅ API permanece igual
beAround.addBeaconEventListener(object : BeaconEventListener {
    override fun onBeaconsDetected(beacons: List<BeaconData>, eventType: BeaconEventType) {
        // Seu código
    }

    override fun onBeaconRegionEnter(beacons: List<BeaconData>) {
        // Seu código
    }

    override fun onBeaconRegionExit(beacons: List<BeaconData>) {
        // Seu código
    }

    override fun onSyncSuccess(result: SyncResult.Success) {
        // Seu código
    }

    override fun onSyncError(result: SyncResult.Error) {
        // Seu código
    }
})
```

## 🔍 Verificação

Após a migração, verifique:

1. ✅ Todos os imports foram atualizados
2. ✅ Projeto compila sem erros
3. ✅ ProGuard rules (se customizadas) foram atualizadas
4. ✅ Testes passam

### Buscar Referências Antigas

```bash
# Procurar por imports antigos no seu projeto
grep -r "org.bearound.sdk" app/src/
```

Se retornar algum resultado, atualize manualmente.

## ❓ Por Que a Mudança?

### Antes: `org.bearound.sdk`
- Não reflete o domínio real da empresa
- Convenção de domínio reverso incorreta

### Agora: `io.bearound.sdk`
- ✅ Alinhado com domínio real: **bearound.io**
- ✅ Segue convenção Java de package naming
- ✅ Mais profissional e consistente

## 📦 ProGuard / R8

Se você tem regras ProGuard customizadas, atualize:

**Antes:**
```proguard
-keep class org.bearound.sdk.** { *; }
```

**Depois:**
```proguard
-keep class io.bearound.sdk.** { *; }
```

**Nota:** O SDK já inclui `consumer-rules.pro` que são aplicadas automaticamente.

## 🚨 Troubleshooting

### Erro: "Unresolved reference: org"

**Causa:** Import antigo ainda presente.

**Solução:**
```kotlin
// ❌ Remova
import org.bearound.sdk.BeAround

// ✅ Adicione
import io.bearound.sdk.BeAround
```

### Erro: "Class not found: org.bearound.sdk.BeAround"

**Causa:** Versão antiga do SDK em cache.

**Solução:**
```bash
# Limpar cache
./gradlew clean
./gradlew --refresh-dependencies

# No Android Studio:
# File → Invalidate Caches / Restart
```

### ProGuard/R8 warnings

**Causa:** Regras customizadas com package antigo.

**Solução:** Atualize suas regras ProGuard ou remova-as (o SDK já inclui as regras necessárias via `consumer-rules.pro`).

## ✅ Checklist de Migração

- [ ] Atualizar versão do SDK para 1.0.16 no `build.gradle`
- [ ] Fazer Find & Replace: `org.bearound.sdk` → `io.bearound.sdk`
- [ ] Sync project com Gradle
- [ ] Compilar projeto
- [ ] Rodar testes
- [ ] Atualizar regras ProGuard customizadas (se houver)
- [ ] Testar app em runtime

## 📚 Mais Informações

- **Changelog:** [CHANGELOG.md](../CHANGELOG.md)
- **Documentation:** [docs/README.md](README.md)
- **Issues:** https://github.com/Bearound/bearound-android-sdk/issues

---

**Tempo estimado de migração:** 2-5 minutos

**Dificuldade:** ⭐ Baixa (apenas atualização de imports)
