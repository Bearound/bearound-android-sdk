# Guia de Publicação com JitPack

O BeAround SDK usa JitPack para publicação rápida e automática. Não precisa de credenciais, aprovações ou configurações complexas!

## 🚀 Por Que JitPack?

### ✅ Vantagens
- **Zero configuração** - Sem credenciais, sem GPG, sem Sonatype
- **Publicação instantânea** - Basta criar uma tag/release no GitHub
- **Automático** - JitPack compila direto do repositório
- **Versionamento simples** - Usa tags do Git
- **Sem custo** - 100% gratuito para projetos open source

### ❌ Maven Central (removido)
- Requer conta Sonatype e aprovação (1-2 dias)
- Requer chave GPG e assinatura
- Processo manual de staging/release
- Sincronização lenta (2-4 horas)

## 📦 Como Funciona

```
1. Você cria tag/release → 2. JitPack detecta → 3. Build automático → 4. Disponível!
   (GitHub)                   (webhook)            (JitPack.io)         (imediato)
```

## 🎯 Publicar Nova Versão

### Passo 1: Atualizar Versão

Edite `gradle.properties`:
```properties
SDK_VERSION=1.0.17
```

### Passo 2: Commit e Tag

```bash
git add gradle.properties CHANGELOG.md
git commit -m "Release v1.0.17"
git tag v1.0.17
git push origin main --tags
```

### Passo 3: Criar Release no GitHub (Opcional mas Recomendado)

1. Acesse: https://github.com/Bearound/bearound-android-sdk/releases/new
2. Tag: `v1.0.17`
3. Title: `Release v1.0.17`
4. Descrição: Cole do CHANGELOG
5. **Publish release**

### Passo 4: JitPack Build Automático

JitPack detecta automaticamente e faz o build:
- Acesse: https://jitpack.io/#Bearound/bearound-android-sdk
- Versão `v1.0.17` aparecerá na lista
- Status: 🔨 Building → ✅ Ready

**Tempo total: ~2-5 minutos!**

## 📥 Como Instalar (Para Usuários)

### Adicionar Repositório

No `settings.gradle` (raiz do projeto):
```gradle
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }  // ← Adicione esta linha
    }
}
```

Ou no `build.gradle` (nível raiz):
```gradle
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }  // ← Adicione esta linha
    }
}
```

### Adicionar Dependência

No `build.gradle` do app:
```gradle
dependencies {
    implementation 'com.github.Bearound:bearound-android-sdk:1.0.16'
}
```

## 🔖 Versionamento

### Usando Tags

```bash
# Release estável
git tag v1.0.17
git push origin v1.0.17

# Instalação:
implementation 'com.github.Bearound:bearound-android-sdk:v1.0.17'
```

### Usando Branch (desenvolvimento)

```gradle
# Branch main (sempre a versão mais recente)
implementation 'com.github.Bearound:bearound-android-sdk:main-SNAPSHOT'

# Branch develop
implementation 'com.github.Bearound:bearound-android-sdk:develop-SNAPSHOT'
```

### Usando Commit Específico

```gradle
implementation 'com.github.Bearound:bearound-android-sdk:abc1234'
```

## 🧪 Testar Build Localmente

Antes de criar a tag, teste se o JitPack conseguirá fazer o build:

```bash
# Limpar
./gradlew clean

# Build completo
./gradlew :sdk:build

# Publicar no Maven Local
./gradlew :sdk:publishToMavenLocal

# Verificar
ls -la ~/.m2/repository/com/github/Bearound/bearound-android-sdk/
```

## 🔍 Verificar Build no JitPack

### Status da Versão

Acesse: https://jitpack.io/#Bearound/bearound-android-sdk/v1.0.16

**Status possíveis:**
- 🔨 **Building** - JitPack está compilando
- ✅ **Ready** - Pronto para uso
- ❌ **Error** - Falha no build

### Ver Logs de Build

Se o build falhar:
1. Clique na versão com erro
2. Clique em **"Look Up"** ou **"Build Log"**
3. Veja o log completo do Gradle

### Forçar Rebuild

Se precisar recompilar:
1. Acesse: https://jitpack.io/#Bearound/bearound-android-sdk
2. Clique em **"Look Up"**
3. Digite a versão: `v1.0.16`
4. Clique em **"Get it"**

## 📊 Badge para README

Adicione ao README.md:

```markdown
[![](https://jitpack.io/v/Bearound/bearound-android-sdk.svg)](https://jitpack.io/#Bearound/bearound-android-sdk)
```

Resultado:
[![](https://jitpack.io/v/Bearound/bearound-android-sdk.svg)](https://jitpack.io/#Bearound/bearound-android-sdk)

## 🔄 Fluxo Completo de Release

```bash
# 1. Atualizar versão
vim gradle.properties  # SDK_VERSION=1.0.17

# 2. Atualizar changelog
vim CHANGELOG.md

# 3. Commit e tag
git add .
git commit -m "Release v1.0.17"
git tag v1.0.17
git push origin main --tags

# 4. Criar release no GitHub (opcional)
# Via web UI ou gh CLI:
gh release create v1.0.17 --title "Release v1.0.17" --notes "Ver CHANGELOG.md"

# 5. Aguardar JitPack (2-5 minutos)
# Verificar: https://jitpack.io/#Bearound/bearound-android-sdk

# 6. Testar instalação
# Em um projeto de teste, adicione a dependência e sincronize
```

## 🐛 Troubleshooting

### Erro: "Could not find com.github.Bearound:bearound-android-sdk:X.X.X"

**Solução:**
1. Verifique se adicionou o repositório JitPack
2. Verifique se a tag existe no GitHub
3. Acesse https://jitpack.io/#Bearound/bearound-android-sdk
4. Clique em "Look Up" e force o build

### Erro: Build falha no JitPack

**Solução:**
1. Veja o log: https://jitpack.io/#Bearound/bearound-android-sdk/v1.0.16
2. Teste localmente: `./gradlew :sdk:build`
3. Verifique `jitpack.yml` está correto
4. Certifique-se que o JDK é 11

### Versão antiga sendo usada

**Solução:**
```bash
# Limpar cache local
./gradlew clean --refresh-dependencies

# Em Android Studio:
# File → Invalidate Caches / Restart
```

## 📚 Recursos

- **JitPack:** https://jitpack.io/
- **Documentação:** https://jitpack.io/docs/
- **Status do SDK:** https://jitpack.io/#Bearound/bearound-android-sdk
- **GitHub Releases:** https://github.com/Bearound/bearound-android-sdk/releases

## ⏱️ Comparação de Tempo

| Tarefa | Maven Central | JitPack |
|--------|---------------|---------|
| **Configuração inicial** | 2-3 dias | ✅ 5 minutos |
| **Criar release** | 30 min | ✅ 2 min |
| **Build & publicação** | 2-4 horas | ✅ 2-5 min |
| **Disponível para uso** | 3-6 horas | ✅ 5 min |
| **Total** | ~1 dia | ✅ ~10 min |

## ✅ Checklist de Release

- [ ] Versão atualizada em `gradle.properties`
- [ ] `CHANGELOG.md` atualizado
- [ ] Build local OK: `./gradlew :sdk:build`
- [ ] Commit e tag criados
- [ ] Tag pushed para GitHub
- [ ] (Opcional) Release criado no GitHub
- [ ] Aguardar build JitPack (2-5 min)
- [ ] Verificar status: https://jitpack.io/#Bearound/bearound-android-sdk
- [ ] Testar instalação em projeto de teste

**Pronto! Nova versão publicada em minutos!** 🚀
