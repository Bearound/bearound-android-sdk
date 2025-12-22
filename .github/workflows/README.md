# GitHub Actions CI/CD Documentation

Este documento descreve os workflows de CI/CD configurados para o BeAround Android SDK.

## 📋 Workflows Disponíveis

### 1. **CI** (`ci.yml`)
Executa verificações contínuas em todos os pushes e PRs.

**Triggers:**
- Push para `main`, `develop`, `feature/**`, `fix/**`
- Pull Requests para `main`, `develop`

**Jobs:**
- ✅ **Validate**: Valida qualidade do código (lint, formatting)
- 🧪 **Test**: Executa testes unitários
- 🔨 **Build**: Compila SDK em debug e release
- 📱 **Build Sample App**: Compila app de exemplo
- 🎯 **JitPack Simulation**: Simula o processo de build do JitPack
- 📦 **Dependency Check**: Verifica atualizações de dependências

**Artefatos Gerados:**
- Relatórios de lint
- Resultados de testes
- AAR do SDK (debug e release)
- APK do app de exemplo
- Relatório de dependências

---

### 2. **JitPack CI** (`jitpack-ci.yml`)
Testa especificamente a compatibilidade com JitPack.

**Triggers:**
- Pull Requests para `main`, `develop`
- Push para `main`, `develop`

**Testes Realizados:**
- ✅ Build completo como JitPack faria
- ✅ Publicação no Maven Local
- ✅ Verificação de artefatos Maven
- ✅ Validação do tamanho do AAR
- ✅ Inspeção do conteúdo do AAR
- ✅ Validação do arquivo POM
- ✅ Teste de resolução de dependências
- ✅ Verificação de compatibilidade do Android SDK

**Benefícios:**
- 🚫 Previne falhas no JitPack antes do release
- 📊 Gera relatórios detalhados de build
- 🔍 Identifica problemas de publicação antecipadamente

---

### 3. **PR Checks** (`pr-checks.yml`)
Análise automática de Pull Requests.

**Triggers:**
- Abertura de PR
- Sincronização de PR
- Reabertura de PR

**Funcionalidades:**
- 📊 Análise de mudanças (arquivos, commits)
- 🔢 Verificação de versão (para PRs em `main`)
- 📝 Validação de CHANGELOG
- 🔍 Detecção de problemas comuns
- 💬 Comentários automáticos no PR

**Verificações:**
- TODOs no código
- Debug logs não controlados
- URLs hardcoded
- Arquivos grandes
- Atualização de versão e CHANGELOG

---

### 4. **Release** (`release.yml`)
Workflow de publicação automática.

**Triggers:**
- Push de tag `v*` (ex: `v1.3.2`)
- Manual (workflow_dispatch)

**Processo:**
1. ✅ **Pre-Release Validation**
   - Valida versão em `gradle.properties`
   - Verifica CHANGELOG
   - Executa testes
   - Roda lint
   - Compila AAR de release

2. 🚀 **Publish to JitPack**
   - Trigger automático de build no JitPack
   - Usa token de autenticação

3. 📦 **Create GitHub Release**
   - Cria release no GitHub
   - Extrai notas do CHANGELOG
   - Anexa artefatos

4. 🎉 **Success Notification**
   - Confirma publicação bem-sucedida

---

## 🎯 Como Usar

### Para Desenvolvimento Diário

1. **Crie uma branch:**
   ```bash
   git checkout -b feature/minha-feature
   ```

2. **Faça suas alterações e commit:**
   ```bash
   git add .
   git commit -m "feat: adiciona nova funcionalidade"
   ```

3. **Push para GitHub:**
   ```bash
   git push origin feature/minha-feature
   ```

4. **CI automático será executado:**
   - ✅ Valida código
   - 🧪 Roda testes
   - 🔨 Compila SDK
   - 🎯 Simula build do JitPack

### Para Pull Requests

1. **Abra um PR no GitHub**

2. **Workflows automáticos:**
   - `ci.yml` - Verifica qualidade do código
   - `jitpack-ci.yml` - Testa compatibilidade com JitPack
   - `pr-checks.yml` - Analisa mudanças e adiciona comentários

3. **Revise os resultados:**
   - ✅ Todos os checks devem passar
   - 💬 Leia comentários automáticos do bot
   - 📊 Verifique artefatos gerados

4. **Corrija problemas se necessário**

### Para Releases

1. **Atualize a versão:**
   ```bash
   # Em gradle.properties
   SDK_VERSION=1.3.2
   ```

2. **Atualize o CHANGELOG:**
   ```markdown
   ## [1.3.2] - 2025-12-22
   ### Added
   - Nova funcionalidade X
   ### Fixed
   - Bug Y corrigido
   ```

3. **Commit e push:**
   ```bash
   git add gradle.properties CHANGELOG.md
   git commit -m "chore: bump version to 1.3.2"
   git push origin main
   ```

4. **Crie e push a tag:**
   ```bash
   git tag v1.3.2
   git push origin v1.3.2
   ```

5. **Workflow de Release será executado automaticamente:**
   - ✅ Valida tudo
   - 🚀 Publica no JitPack
   - 📦 Cria GitHub Release

---

## 🔐 Secrets Necessários

Configure estes secrets no GitHub (Settings → Secrets and variables → Actions):

| Secret | Descrição | Obrigatório |
|--------|-----------|-------------|
| `JITPACK_TOKEN` | Token de autenticação do JitPack | Sim (para release) |
| `GH_PUSH_TOKEN` | Token GitHub com permissão de push | Sim (para release) |

### Como Obter Tokens:

#### JitPack Token:
1. Acesse https://jitpack.io/
2. Faça login com GitHub
3. Vá em Settings → API Token
4. Copie o token

#### GitHub Token:
1. GitHub → Settings → Developer settings → Personal access tokens
2. Generate new token (classic)
3. Permissões necessárias: `repo`, `workflow`
4. Copie o token

---

## 📊 Status e Badges

Adicione estes badges no README principal:

```markdown
[![CI](https://github.com/Bearound/bearound-android-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/Bearound/bearound-android-sdk/actions/workflows/ci.yml)
[![JitPack CI](https://github.com/Bearound/bearound-android-sdk/actions/workflows/jitpack-ci.yml/badge.svg)](https://github.com/Bearound/bearound-android-sdk/actions/workflows/jitpack-ci.yml)
[![Release](https://github.com/Bearound/bearound-android-sdk/actions/workflows/release.yml/badge.svg)](https://github.com/Bearound/bearound-android-sdk/actions/workflows/release.yml)
```

---

## 🐛 Troubleshooting

### Build Falha no JitPack Simulation

**Problema:** Job `jitpack-simulation` falha

**Solução:**
1. Verifique o arquivo `jitpack.yml` na raiz do projeto
2. Confirme que `gradle.properties` tem `SDK_VERSION` definido
3. Execute localmente:
   ```bash
   ./gradlew clean
   ./gradlew :sdk:assemble
   ./gradlew :sdk:publishToMavenLocal
   ```

### Version Check Falha

**Problema:** PR checks indicam que versão não foi atualizada

**Solução:**
- PRs para `main` devem incluir bump de versão
- Atualize `SDK_VERSION` em `gradle.properties`
- Adicione entrada correspondente no `CHANGELOG.md`

### Release Workflow Não Dispara

**Problema:** Tag foi criada mas release não acontece

**Solução:**
1. Verifique se a tag começa com `v` (ex: `v1.3.2`)
2. Confirme que workflow está habilitado em Actions
3. Verifique se há erros no workflow

### JitPack Token Inválido

**Problema:** Erro de autenticação ao publicar no JitPack

**Solução:**
1. Gere novo token no JitPack
2. Atualize secret `JITPACK_TOKEN` no GitHub
3. Re-execute o workflow

---

## 📈 Melhorias Futuras

- [ ] Adicionar coverage reports
- [ ] Integrar análise de código (SonarQube)
- [ ] Adicionar testes de integração
- [ ] Automatizar atualização de dependencies
- [ ] Adicionar notificações (Slack, Discord)
- [ ] Performance benchmarks

---

## 📚 Referências

- [GitHub Actions Documentation](https://docs.github.com/en/actions)
- [JitPack Documentation](https://jitpack.io/docs/)
- [Android Gradle Plugin](https://developer.android.com/build/releases/gradle-plugin)

---

**Última atualização:** 2025-12-22

