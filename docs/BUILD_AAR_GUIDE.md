# Guia de Build do BeAround SDK (.aar)

Este documento explica como gerar o arquivo binário `.aar` do BeAround SDK para distribuição.

## 📦 Gerando o arquivo .aar

### Método 1: Usando Gradle (Recomendado)

```bash
# Navegar até a raiz do projeto
cd bearound-android-sdk

# Gerar o .aar release
./gradlew :sdk:assembleRelease

# O arquivo será automaticamente copiado para:
# ./release/bearound-android-sdk-1.0.16.aar
```

### Método 2: Via Android Studio

1. Abra o projeto no Android Studio
2. No painel **Build Variants**, selecione **release**
3. Menu: **Build → Build Bundle(s) / APK(s) → Build APK(s)**
4. O arquivo `.aar` será gerado em: `sdk/build/outputs/aar/sdk-release.aar`
5. Uma cópia será automaticamente criada em: `release/bearound-android-sdk-1.0.16.aar`

## 🔐 Ofuscação e Proteção

O SDK está configurado com ProGuard para:

### ✅ Mantido (Público - APIs Expostas)
- `BeAround` - Classe principal com métodos públicos
- `BeaconEventListener` - Interface para eventos de beacons
- `LogListener` - Interface para logs
- `BeaconData` - Classe de dados de beacons
- `BeaconEventType` - Enum de tipos de eventos
- `SyncResult`, `SyncResult.Success`, `SyncResult.Error` - Classes de resultado
- `TimeScanBeacons` - Enum de tempos de scan
- `SizeBackupLostBeacons` - Enum de tamanhos de backup

### 🔒 Ofuscado (Privado - Protegido)
- Todos os métodos `private` da classe `BeAround`
- Implementações internas
- Métodos auxiliares
- Constantes privadas

### 🗑️ Removido em Release
- Logs de debug (`Log.d`, `Log.v`, `Log.i`)
- Metadata desnecessária

## 📋 Arquivos de Configuração

### `sdk/build.gradle`
- **`minifyEnabled true`**: Ativa ofuscação no build release
- **Task `copyReleaseAAR`**: Copia automaticamente o .aar para pasta `release/`
- **`proguardFiles`**: Aplica regras do `proguard-rules.pro`
- **`consumerProguardFiles`**: Aplica `consumer-rules.pro` aos consumidores do SDK

### `sdk/proguard-rules.pro`
Regras aplicadas ao SDK durante o build:
- Mantém APIs públicas
- Ofusca métodos privados
- Otimizações de código
- Remoção de logs

### `sdk/consumer-rules.pro`
Regras aplicadas aos apps que consomem o SDK:
- Garante que as APIs públicas não sejam ofuscadas no app consumidor
- Preserva classes de dados e interfaces
- Protege dependências (AltBeacon, Coroutines, Play Services)

## 📂 Estrutura de Saída

```
bearound-android-sdk/
├── release/
│   └── bearound-android-sdk-1.0.16.aar  ← Arquivo final
├── sdk/
│   └── build/
│       └── outputs/
│           └── aar/
│               └── sdk-release.aar       ← Gerado pelo Gradle
```

## 🚀 Distribuindo o .aar

### Opção 1: Importação Local (Manual)

1. Copie o arquivo `bearound-android-sdk-1.0.16.aar` para a pasta `libs/` do projeto consumidor
2. No `build.gradle` do app, adicione:

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

### Opção 2: Maven Local

```bash
# Publicar no Maven Local
./gradlew :sdk:publishToMavenLocal

# No projeto consumidor, adicione ao build.gradle:
repositories {
    mavenLocal()
}

dependencies {
    implementation 'io.bearound:android-beacon-sdk:1.0.16'
}
```

### Opção 3: Maven Central

```bash
# Publicar no Maven Central (requer configuração adicional)
./gradlew :sdk:publish
```

Veja `MAVEN_CENTRAL_PUBLISH.md` para instruções completas.

## 🧪 Verificando a Ofuscação

Para verificar se a ofuscação está funcionando:

```bash
# Gerar o .aar
./gradlew :sdk:assembleRelease

# Extrair o conteúdo
unzip release/bearound-android-sdk-1.0.16.aar -d aar-content

# Ver o mapping de ofuscação
cat sdk/build/outputs/mapping/release/mapping.txt
```

O arquivo `mapping.txt` mostra quais classes/métodos foram ofuscados.

## 📊 Tamanho do .aar

Estimativa de tamanho após compressão e ofuscação:
- **Sem ofuscação**: ~150-200 KB
- **Com ofuscação**: ~100-150 KB

## 🐛 Troubleshooting

### Erro: "BuildConfig.SDK_VERSION not found"
```bash
./gradlew clean
./gradlew :sdk:assembleRelease
```

### Task copyReleaseAAR não executa
```bash
./gradlew :sdk:assembleRelease --info
```

### ProGuard warnings/errors
Verifique o arquivo: `sdk/build/outputs/mapping/release/usage.txt`

## 📝 Notas Importantes

1. **Sempre use o build release** para distribuição
2. **Guarde o arquivo `mapping.txt`** para debug de stack traces ofuscados
3. **Teste o .aar** em um projeto separado antes de distribuir
4. **Versão no arquivo**: O nome do .aar inclui automaticamente a versão do `gradle.properties`

## 🔄 Atualizando a Versão

Para criar uma nova versão:

1. Atualize `gradle.properties`:
   ```properties
   SDK_VERSION=1.0.17
   ```

2. Build novamente:
   ```bash
   ./gradlew :sdk:assembleRelease
   ```

3. Novo arquivo gerado: `release/bearound-android-sdk-1.0.17.aar`
