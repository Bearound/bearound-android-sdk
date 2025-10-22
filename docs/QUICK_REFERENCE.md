# BeAround SDK - Quick Reference

Referência rápida para desenvolvimento e publicação do SDK.

## 🚀 Comandos Rápidos

### Build & Test
```bash
# Clean build
./gradlew clean

# Build completo
./gradlew :sdk:build

# Rodar testes
./gradlew :sdk:test

# Build release (gera .aar automaticamente)
./gradlew :sdk:assembleRelease
# Output: release/bearound-android-sdk-1.0.16.aar
```

### Publicação

```bash
# Publicar no Maven Local (teste)
./gradlew :sdk:publishToMavenLocal

# Publicar no Maven Central
./gradlew :sdk:publish
```

## 📁 Estrutura do Projeto

```
bearound-android-sdk/
├── .github/
│   └── workflows/
│       └── publish-maven-central.yml    # CI/CD automation
├── sdk/
│   ├── src/main/java/org/bearound/sdk/
│   │   └── BeAround.kt                  # SDK principal
│   ├── build.gradle                     # Build config
│   ├── proguard-rules.pro              # Ofuscação do SDK
│   └── consumer-rules.pro              # Regras para consumidores
├── release/                             # .aar binários gerados
├── gradle.properties                    # Versão e metadata
├── CHANGELOG.md                         # Histórico de versões
├── BUILD_AAR_GUIDE.md                   # Como gerar .aar
├── MAVEN_CENTRAL_PUBLISH.md            # Como publicar
├── PROGUARD_CONFIG_EXPLAINED.md        # Detalhes ProGuard
└── QUICK_REFERENCE.md                   # Este arquivo
```

## 📝 Arquivos de Configuração

### gradle.properties
```properties
SDK_GROUP_ID=io.bearound
SDK_ARTIFACT_ID=android-beacon-sdk
SDK_VERSION=1.0.16
```

### local.properties (não commitar)
```properties
ossrhUsername=seu-username
ossrhPassword=sua-senha
signing.keyId=12345678
signing.password=senha-gpg
signing.secretKeyRingFile=/path/to/secring.gpg
```

## 🔄 Fluxo de Release

### 1. Atualizar Versão
```bash
# Editar gradle.properties
SDK_VERSION=1.0.17
```

### 2. Atualizar CHANGELOG
Documentar mudanças em `CHANGELOG.md`

### 3. Commit e Tag
```bash
git add gradle.properties CHANGELOG.md
git commit -m "Release version 1.0.17"
git tag v1.0.17
git push origin main --tags
```

### 4. Criar Release no GitHub
- Acesse: https://github.com/Bearound/bearound-android-sdk/releases/new
- Tag: `v1.0.17`
- Title: `Release v1.0.17`
- Descrição: Cole do CHANGELOG
- Publish release

### 5. GitHub Actions (Automático)
O workflow `.github/workflows/publish-maven-central.yml` executará automaticamente:
- ✅ Build
- ✅ Testes
- ✅ Publicação no Maven Central
- ✅ Upload do .aar

### 6. Promover no Sonatype Nexus
1. Acesse: https://s01.oss.sonatype.org/
2. Login
3. **Staging Repositories** → Selecione `iobearound-XXXX`
4. **Close** → Aguarde validação
5. **Release** → Promove para Maven Central
6. Aguarde 2-4 horas para sincronização

## 🎯 APIs Públicas

### Inicialização
```kotlin
val beAround = BeAround.getInstance(context)
beAround.initialize(
    iconNotification = R.drawable.ic_notification,
    clientToken = "seu-token",
    debug = true
)
```

### Configuração Opcional
```kotlin
beAround.changeScamTimeBeacons(BeAround.TimeScanBeacons.TIME_10)
beAround.changeListSizeBackupLostBeacons(BeAround.SizeBackupLostBeacons.SIZE_30)
```

### Listeners
```kotlin
beAround.addBeaconEventListener(object : BeaconEventListener {
    override fun onBeaconsDetected(beacons: List<BeaconData>, eventType: BeaconEventType) {
        // Beacons detectados
    }

    override fun onBeaconRegionEnter(beacons: List<BeaconData>) {
        // Entrou em região
    }

    override fun onBeaconRegionExit(beacons: List<BeaconData>) {
        // Saiu de região
    }

    override fun onSyncSuccess(result: SyncResult.Success) {
        // Sync com API OK
    }

    override fun onSyncError(result: SyncResult.Error) {
        // Erro no sync
    }
})
```

### Logs
```kotlin
beAround.addLogListener(object : LogListener {
    override fun onLogAdded(log: String) {
        Log.d("MyApp", log)
    }
})
```

### Parar SDK
```kotlin
beAround.stop()
```

## 📦 Instalação (Consumidores)

### Maven Central
```gradle
dependencies {
    implementation 'io.bearound:android-beacon-sdk:1.0.16'
}
```

### .aar Local
```gradle
dependencies {
    implementation files('libs/bearound-android-sdk-1.0.16.aar')

    // Dependências necessárias
    implementation 'org.altbeacon:android-beacon-library:2.21.1'
    implementation 'com.google.android.gms:play-services-ads-identifier:18.2.0'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
    implementation 'androidx.lifecycle:lifecycle-process:2.9.1'
}
```

## 🔐 ProGuard

### APIs Mantidas (Não Ofuscadas)
- `BeAround` - Todos métodos públicos
- `BeaconEventListener`
- `LogListener`
- `BeaconData`, `BeaconEventType`, `SyncResult`
- Enums: `TimeScanBeacons`, `SizeBackupLostBeacons`

### Métodos Ofuscados
- Todos métodos `private`
- Implementações internas
- Constantes privadas

## 🧪 Testes

### Verificar Ofuscação
```bash
# Ver mapping
cat sdk/build/outputs/mapping/release/mapping.txt

# Extrair .aar
unzip release/bearound-android-sdk-1.0.16.aar -d aar-content

# Inspecionar classes
cd aar-content && unzip classes.jar -d classes
```

### Testar Maven Local
```bash
# Publicar
./gradlew :sdk:publishToMavenLocal

# Verificar
ls -la ~/.m2/repository/io/bearound/android-beacon-sdk/1.0.16/
```

## 📚 Documentação Completa

- **BUILD_AAR_GUIDE.md** - Guia completo de geração do .aar
- **MAVEN_CENTRAL_PUBLISH.md** - Processo completo de publicação
- **PROGUARD_CONFIG_EXPLAINED.md** - Detalhes da ofuscação
- **CHANGELOG.md** - Histórico de versões
- **BUILDCONFIG_LINT_ISSUE.md** - Solução para warning do lint

## 🐛 Troubleshooting Rápido

### Build falha
```bash
./gradlew clean
./gradlew :sdk:build --stacktrace
```

### Publicação falha (401)
```bash
# Verificar credenciais
echo $OSSRH_USERNAME
cat local.properties | grep ossrh
```

### GPG signing falha
```bash
# Listar chaves
gpg --list-secret-keys

# Reexportar
gpg --export-secret-keys -o ~/.gnupg/secring.gpg
```

## 📞 Links Úteis

- **Repositório:** https://github.com/Bearound/bearound-android-sdk
- **Maven Central:** https://search.maven.org/artifact/io.bearound/android-beacon-sdk
- **Sonatype Nexus:** https://s01.oss.sonatype.org/
- **Issues:** https://github.com/Bearound/bearound-android-sdk/issues

## 📊 Checklist de Release

- [ ] Versão atualizada em `gradle.properties`
- [ ] `CHANGELOG.md` atualizado
- [ ] Build local OK: `./gradlew :sdk:build`
- [ ] Teste local: `./gradlew :sdk:publishToMavenLocal`
- [ ] Commit e tag criados
- [ ] Release criado no GitHub
- [ ] GitHub Actions executou com sucesso
- [ ] Staging repository promovido no Nexus
- [ ] Aguardar 2-4h para sync Maven Central
- [ ] Verificar no Maven Central Search
- [ ] Atualizar badge no README (se necessário)
