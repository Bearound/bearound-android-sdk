# 🚀 Como Publicar uma Nova Versão

Guia rápido para publicar uma nova versão do BeAround SDK.

## ✅ Pré-requisitos

- Acesso ao repositório GitHub
- Secrets configurados no GitHub:
  - `GH_PUSH_TOKEN` - Token para push de tags
  - `JITPACK_TOKEN` - Token da API do JitPack
  - `GPR_USER` e `GPR_KEY` - Credenciais GitHub Packages (opcional)

## 📦 Processo de Publicação (Automatizado)

### Passo 1: Atualizar Versão

Edite `gradle.properties`:
```properties
SDK_VERSION=1.0.17
```

### Passo 2: Atualizar CHANGELOG.md

Documente as mudanças:
```markdown
## [1.0.17] - 2025-10-XX

### Added
- Nova funcionalidade X
- Correção do bug Y
```

### Passo 3: Commit e Push

```bash
git add gradle.properties CHANGELOG.md
git commit -m "chore: bump version to 1.0.17"
git push origin main
```

### Passo 4: Executar Workflow

1. Acesse: https://github.com/Bearound/bearound-android-sdk/actions
2. Selecione **"Publish SDK and Create TAG and Release"**
3. Clique em **"Run workflow"**
4. Selecione branch **main**
5. Clique em **"Run workflow"** (verde)

## 🎯 O Que o Workflow Faz (Automaticamente)

1. ✅ Lê a versão do `gradle.properties`
2. ✅ Atualiza badge de versão no README
3. ✅ Verifica se a tag já existe
4. ✅ Cria e pusha a tag (ex: `v1.0.17`)
5. ✅ Triggera build no JitPack via API
6. ✅ Cria GitHub Release

## ⏱️ Tempo Estimado

- **Workflow**: ~2-3 minutos
- **JitPack Build**: ~2-5 minutos
- **Total**: ~5-8 minutos

## 🔍 Verificação

### 1. GitHub Release
Acesse: https://github.com/Bearound/bearound-android-sdk/releases

Verifique que a release foi criada com a tag correta.

### 2. JitPack Status
Acesse: https://jitpack.io/#Bearound/bearound-android-sdk

Verifique que a versão aparece com status ✅ (verde).

### 3. Testar Instalação

Em um projeto de teste:

```gradle
// settings.gradle
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}

// app/build.gradle
dependencies {
    implementation 'com.github.Bearound:bearound-android-sdk:1.0.17'
}
```

Sync e verifique que baixa sem erros.

## 📊 Checklist Completo

- [ ] Versão atualizada em `gradle.properties`
- [ ] `CHANGELOG.md` atualizado com as mudanças
- [ ] Commit e push para `main`
- [ ] Workflow executado via Actions
- [ ] Tag criada no GitHub
- [ ] Release criada no GitHub
- [ ] JitPack build com status ✅
- [ ] Testado instalação em projeto de teste
- [ ] Documentação atualizada (se necessário)

## 🐛 Troubleshooting

### Workflow falha: "Tag already exists"

**Causa:** A tag já foi criada anteriormente.

**Solução:**
```bash
# Deletar tag local e remota
git tag -d v1.0.17
git push origin :refs/tags/v1.0.17

# Executar workflow novamente
```

### JitPack Build falha

**Causa:** Erro de compilação.

**Solução:**
1. Acesse: https://jitpack.io/#Bearound/bearound-android-sdk
2. Clique na versão com erro
3. Veja o log completo
4. Teste build local: `./gradlew :sdk:build`
5. Corrija e faça nova release

### Badge não atualiza no README

**Causa:** Workflow atualizou mas precisa de nova tag.

**Solução:** O workflow já faz isso automaticamente. Verifique o commit após a execução.

## 📝 Notas Importantes

### Versionamento Semântico

Siga [SemVer](https://semver.org/):
- **MAJOR** (1.x.x): Breaking changes
- **MINOR** (x.1.x): Novas funcionalidades (compatível)
- **PATCH** (x.x.1): Bug fixes

### Package Name

A partir da v1.0.16, o package é:
```kotlin
io.bearound.sdk  // ✅ Correto
org.bearound.sdk // ❌ Antigo
```

### Instalação JitPack

```gradle
implementation 'com.github.Bearound:bearound-android-sdk:VERSION'
```

Onde `VERSION` pode ser:
- `1.0.17` - Release específica
- `main-SNAPSHOT` - Última versão da main
- `abc1234` - Commit específico

## 🔗 Links Úteis

- **Actions**: https://github.com/Bearound/bearound-android-sdk/actions
- **Releases**: https://github.com/Bearound/bearound-android-sdk/releases
- **JitPack**: https://jitpack.io/#Bearound/bearound-android-sdk
- **Changelog**: [CHANGELOG.md](CHANGELOG.md)

## 🎉 Exemplo Completo

```bash
# 1. Atualizar versão
echo "SDK_VERSION=1.0.17" >> gradle.properties

# 2. Atualizar changelog
vim CHANGELOG.md

# 3. Commit
git add .
git commit -m "chore: release v1.0.17"
git push origin main

# 4. Executar workflow via GitHub UI
# https://github.com/Bearound/bearound-android-sdk/actions

# 5. Aguardar 5-8 minutos

# 6. Verificar
# https://jitpack.io/#Bearound/bearound-android-sdk
```

**Pronto! Nova versão publicada! 🚀**
