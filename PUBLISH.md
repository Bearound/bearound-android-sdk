# Publicando uma nova versao do SDK

Checklist completo para publicar uma atualizacao do BeAround Android SDK.

## Pre-requisitos

- **PR mergeada na `main` antes de taggear.** O `release.yml` valida a tag contra o commit
  apontado por ela — taggear uma branch "funciona", mas publica um commit fora da `main`
  (e com squash merge a tag fica em linhagem orfa). Sequencia: PR ready → CI verde →
  merge → `git checkout main && git pull` → conferir `SDK_VERSION` → taggear na `main`.
- CI verde na `main` apos o merge (aguardar o `ci.yml` do push do bump).
- Build passando localmente: `./gradlew :sdk:test :sdk:lint` (o `release.yml` roda os
  testes e **bloqueia** a release se falharem; o gate de PR do `ci.yml` tambem trava
  com teste vermelho).
- Secrets do repositorio validos (Settings → Secrets): `JITPACK_TOKEN` (trigger do build
  JitPack) e `GH_PUSH_TOKEN` (criacao da GitHub Release). Se o `GH_PUSH_TOKEN` estiver
  expirado, a release falha DEPOIS de o JitPack ja ter sido disparado (estado
  meio-publicado — ver Rollback).

> ⚠️ **Nao usar o workflow `gradle-publish.yml`** (workflow_dispatch "Publish SDK and Create TAG and Release").
> Ele e um fluxo paralelo que reescreve o pin do README via sed, cria/pusha a tag por
> conta propria e publica no GitHub Packages — a tag pushada dispara o `release.yml`
> tambem, resultando em execucao dupla e duas escritas concorrentes na mesma Release.
> O fluxo oficial e o deste documento (tag manual → `release.yml`).

---

## 1. Atualizar a versao

A versao vive em **um unico lugar**: `gradle.properties` (`SDK_VERSION=X.Y.Z`). Editar esse valor e incrementar:

```properties
SDK_VERSION=X.Y.Z
```

Dessa unica fonte, o valor flui automaticamente para:
- `BuildConfig.SDK_VERSION` (via `buildConfigField` em `sdk/build.gradle`) — usado em runtime por `SDKInfo.version`
- a versao da publicacao Maven/JitPack (via `version = findProperty("SDK_VERSION")` em `sdk/build.gradle`)

Nao ha outro lugar para editar a versao **em codigo**. Nao hardcode a versao em codigo.

> **Excecao documental:** o snippet de instalacao do `README.md` (secao Installation) tem o
> pin manual `com.github.Bearound:bearound-android-sdk:vX.Y.Z` — atualizar junto no mesmo
> commit do bump (entra no passo 4).

> **Nota:** `technology` em `SDKInfo` e uma constante hardcoded (`"android-native"`), nao versionada nem configuravel — nao mexer ao publicar.

Regras de versionamento ([SemVer](https://semver.org)):
- **MAJOR** (X) — breaking changes na API publica
- **MINOR** (Y) — nova funcionalidade retrocompativel
- **PATCH** (Z) — bug fix retrocompativel

## 2. Atualizar o CHANGELOG.md

Mover o conteudo de `[Unreleased]` para uma nova secao com a versao e data:

```markdown
## [X.Y.Z] - YYYY-MM-DD

### Added
- ...

### Changed
- ...

### Fixed
- ...
```

## 3. Verificar o build

```bash
./gradlew clean
./gradlew :sdk:test
./gradlew :sdk:lint
./gradlew :sdk:assembleRelease
```

Confirmar que nao ha erros (warnings pre-existentes sao OK). Os testes sao obrigatorios:
o `release.yml` os executa na validacao da tag e **bloqueia** a release se falharem.

## 4. Commit de versao

```bash
git add gradle.properties CHANGELOG.md README.md
git commit -m "bump: version X.Y.Z"
```

(`README.md` entra por causa do pin manual do snippet de instalacao — ver passo 1.)

## 5. Criar e push da tag

**A tag deve apontar para o commit da `main` ja mergeado** (ver Pre-requisitos):

```bash
git checkout main && git pull
grep SDK_VERSION gradle.properties   # conferir que e X.Y.Z
git tag vX.Y.Z
git push origin vX.Y.Z
```

> **Importante:** A tag DEVE ter o prefixo `v` (ex: `v2.4.0`).
> O push da tag dispara automaticamente o workflow `release.yml` que:
> 1. Valida que `gradle.properties` e `CHANGELOG.md` estao de acordo com a tag
> 2. Roda **testes**, lint e build do AAR
> 3. Tenta disparar o build no JitPack. **Hoje isso e um no-op**: o secret `JITPACK_TOKEN`
>    nao esta configurado no repo, entao o POST volta `Missing access token` / HTTP 401 e o
>    passo so registra um warning — o job fica verde sem o JitPack ter recebido nada.
>    Quem publica de fato e o GET do passo 6.
> 4. Cria a GitHub Release com as notas do CHANGELOG (precisa do secret `GH_PUSH_TOKEN`)

## 6. Verificar publicacao

### JitPack

Acessar https://jitpack.io/#Bearound/bearound-android-sdk e confirmar que a versao `vX.Y.Z` foi buildada com sucesso (icone verde).

O primeiro GET nessa URL e o que faz o JitPack clonar a tag e buildar; ele responde 404
enquanto compila. Repetir o GET ate virar 200 (ou acompanhar em https://jitpack.io/#Bearound/bearound-android-sdk).

Se falhou, clicar no log para investigar. O build do JitPack usa `jitpack.yml` na raiz do projeto.

**Se a falha foi de infra do JitPack** (ex.: `Temporary failure in name resolution` ao
alcancar o Maven Central), o resultado fica **cacheado por versao+commit**: repetir o GET
so devolve o mesmo `Error`, e apagar/recriar a tag no MESMO commit tambem nao adianta.
Para forcar um build novo sem queimar o numero da versao, aponte a tag para um commit
novo (qualquer commit posterior em `main` serve):

```bash
git tag -d vX.Y.Z && git push origin :refs/tags/vX.Y.Z
git tag vX.Y.Z <novo-commit> && git push origin vX.Y.Z
```

### GitHub Release

Acessar https://github.com/Bearound/bearound-android-sdk/releases e confirmar que a release `vX.Y.Z` foi criada com as notas corretas.

### GitHub Actions

Acessar https://github.com/Bearound/bearound-android-sdk/actions e confirmar que o workflow "Release" passou.

## 7. Testar integracao

Num projeto consumidor, atualizar a dependencia (**com** o prefixo `v`, igual ao README —
o JitPack resolve as duas formas, mas sao coordenadas/caches distintos; padronizamos a
`vX.Y.Z`, que e a que o `release.yml` pre-aquece no trigger):

```gradle
implementation 'com.github.Bearound:bearound-android-sdk:vX.Y.Z'
```

Fazer sync do Gradle e confirmar que compila e funciona.

---

## Rollback / tag errada

- **GitHub Release + tag:** deletar a Release (UI ou `gh release delete vX.Y.Z`) e a tag
  (`git push origin :refs/tags/vX.Y.Z`).
- **JitPack cacheia o build por coordenada** — deletar e re-pushar a MESMA tag **nao**
  rebuilda. Para invalidar: deletar o build na UI do JitPack (logado com acesso ao repo,
  botao de lixeira na versao) ou via API, e so entao re-pushar a tag. Na duvida, o caminho
  mais seguro e soltar um patch novo (X.Y.Z+1) em vez de reutilizar a tag.
- **Release meio-publicada** (JitPack ok, GitHub Release falhou por secret expirado):
  renovar o secret e re-rodar so o job da Release pelo Actions (re-run), ou criar a
  Release manualmente com as notas do CHANGELOG — nao re-pushar a tag.

---

## Referencia rapida

```bash
# Exemplo completo para publicar a versao 2.4.0:

# 0. Garantir que a PR do release foi MERGEADA na main e o CI esta verde

# 1. Editar gradle.properties: SDK_VERSION=2.4.0
# 2. Editar CHANGELOG.md ([2.4.0]) e README.md (pin do snippet: v2.4.0)
# 3. Verificar build
./gradlew clean :sdk:test :sdk:lint :sdk:assembleRelease

# 4. Commit (via PR; nao commitar direto na main)
git add gradle.properties CHANGELOG.md README.md
git commit -m "bump: version 2.4.0"

# 5. Depois do merge: tag no commit da main
git checkout main && git pull
git tag v2.4.0
git push origin v2.4.0

# 6. Aguardar e verificar:
#    - GitHub Actions: https://github.com/Bearound/bearound-android-sdk/actions
#    - JitPack: https://jitpack.io/#Bearound/bearound-android-sdk
#    - Release: https://github.com/Bearound/bearound-android-sdk/releases
```
