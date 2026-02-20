# Publicando uma nova versao do SDK

Checklist completo para publicar uma atualizacao do BeAround Android SDK.

## Pre-requisitos

- Branch `main` atualizada e estavel
- Todas as alteracoes commitadas
- Build passando: `./gradlew :sdk:lint`

---

## 1. Atualizar a versao

Editar `gradle.properties` e incrementar `SDK_VERSION`:

```properties
SDK_VERSION=X.Y.Z
```

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
./gradlew :sdk:lint
./gradlew :sdk:assembleRelease
```

Confirmar que nao ha erros (warnings pre-existentes sao OK).

## 4. Commit de versao

```bash
git add gradle.properties CHANGELOG.md
git commit -m "bump: version X.Y.Z"
```

## 5. Criar e push da tag

```bash
git tag vX.Y.Z
git push origin main
git push origin vX.Y.Z
```

> **Importante:** A tag DEVE ter o prefixo `v` (ex: `v2.4.0`).
> O push da tag dispara automaticamente o workflow `release.yml` que:
> 1. Valida que `gradle.properties` e `CHANGELOG.md` estao de acordo com a tag
> 2. Roda lint e build do AAR
> 3. Dispara o build no JitPack
> 4. Cria a GitHub Release com as notas do CHANGELOG

## 6. Verificar publicacao

### JitPack

Acessar https://jitpack.io/#Bearound/bearound-android-sdk e confirmar que a versao `vX.Y.Z` foi buildada com sucesso (icone verde).

Se falhou, clicar no log para investigar. O build do JitPack usa `jitpack.yml` na raiz do projeto.

### GitHub Release

Acessar https://github.com/Bearound/bearound-android-sdk/releases e confirmar que a release `vX.Y.Z` foi criada com as notas corretas.

### GitHub Actions

Acessar https://github.com/Bearound/bearound-android-sdk/actions e confirmar que o workflow "Release" passou.

## 7. Testar integracao

Num projeto consumidor, atualizar a dependencia:

```gradle
implementation 'com.github.Bearound:bearound-android-sdk:X.Y.Z'
```

Fazer sync do Gradle e confirmar que compila e funciona.

---

## Referencia rapida

```bash
# Exemplo completo para publicar a versao 2.4.0:

# 1. Editar gradle.properties: SDK_VERSION=2.4.0
# 2. Editar CHANGELOG.md: adicionar secao [2.4.0]
# 3. Verificar build
./gradlew clean :sdk:lint :sdk:assembleRelease

# 4. Commit
git add gradle.properties CHANGELOG.md
git commit -m "bump: version 2.4.0"

# 5. Tag + push
git tag v2.4.0
git push origin main
git push origin v2.4.0

# 6. Aguardar e verificar:
#    - GitHub Actions: https://github.com/Bearound/bearound-android-sdk/actions
#    - JitPack: https://jitpack.io/#Bearound/bearound-android-sdk
#    - Release: https://github.com/Bearound/bearound-android-sdk/releases
```
