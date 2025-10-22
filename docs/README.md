# BeAround SDK - Documentation

Documentação técnica completa do BeAround Android SDK.

## 📚 Índice de Documentos

### 🚀 Para Começar

- **[../README.md](../README.md)** - Documentação principal e guia de início rápido
- **[MIGRATION_1.0.16.md](MIGRATION_1.0.16.md)** - ⚠️ Guia de migração v1.0.16 (BREAKING CHANGE)
- **[QUICK_REFERENCE.md](QUICK_REFERENCE.md)** - Referência rápida com comandos e fluxos comuns

### 🔨 Build & Desenvolvimento

- **[BUILD_AAR_GUIDE.md](BUILD_AAR_GUIDE.md)** - Guia completo para gerar binário `.aar`
  - Como gerar o arquivo .aar
  - Métodos de distribuição
  - Verificação de ofuscação
  - Troubleshooting

### 📦 Publicação

- **[JITPACK_GUIDE.md](JITPACK_GUIDE.md)** - Guia de publicação com JitPack
  - Publicação instantânea via tags do GitHub
  - Como criar releases
  - Versionamento
  - Troubleshooting

### 🔐 Segurança & Ofuscação

- **[PROGUARD_CONFIG_EXPLAINED.md](PROGUARD_CONFIG_EXPLAINED.md)** - Explicação detalhada da configuração ProGuard
  - Estratégia de ofuscação
  - APIs públicas vs privadas
  - Regras ProGuard explicadas
  - Como verificar ofuscação
  - Debug de código ofuscado

### 📝 Changelog

- **[../CHANGELOG.md](../CHANGELOG.md)** - Histórico completo de versões e mudanças

## 🎯 Guias por Tarefa

### Quero desenvolver o SDK
1. Leia: [../README.md](../README.md)
2. Consulte: [QUICK_REFERENCE.md](QUICK_REFERENCE.md)

### Quero gerar um .aar binário
1. Leia: [BUILD_AAR_GUIDE.md](BUILD_AAR_GUIDE.md)
2. Execute: `./gradlew :sdk:assembleRelease`

### Quero publicar uma nova versão
1. Leia: [JITPACK_GUIDE.md](JITPACK_GUIDE.md)
2. Crie tag: `git tag v1.0.X && git push --tags`
3. Aguarde 2-5 minutos - Pronto!

### Quero entender a ofuscação
1. Leia: [PROGUARD_CONFIG_EXPLAINED.md](PROGUARD_CONFIG_EXPLAINED.md)
2. Veja: `sdk/proguard-rules.pro` e `sdk/consumer-rules.pro`

### Quero encontrar um comando específico
1. Consulte: [QUICK_REFERENCE.md](QUICK_REFERENCE.md)

## 📊 Estrutura da Documentação

```
docs/
├── README.md                          # Este arquivo (índice)
├── MIGRATION_1.0.16.md                # ⚠️ Guia de migração v1.0.16
├── JITPACK_GUIDE.md                   # 📦 Guia de publicação JitPack
├── QUICK_REFERENCE.md                 # ⚡ Comandos e referências rápidas
├── BUILD_AAR_GUIDE.md                 # 🔨 Guia de build do .aar
└── PROGUARD_CONFIG_EXPLAINED.md       # 🔐 Explicação do ProGuard
```

## 🔗 Links Úteis

- **Repositório:** https://github.com/Bearound/bearound-android-sdk
- **JitPack:** https://jitpack.io/#Bearound/bearound-android-sdk
- **Issues:** https://github.com/Bearound/bearound-android-sdk/issues
- **Releases:** https://github.com/Bearound/bearound-android-sdk/releases

## 🆘 Precisa de Ajuda?

1. Verifique [QUICK_REFERENCE.md](QUICK_REFERENCE.md) - seção "Troubleshooting Rápido"
2. Consulte o guia específico para sua tarefa
3. Abra uma issue no GitHub se o problema persistir

## 📖 Convenções

- Caminhos relativos: `../arquivo.md` (sobe um nível)
- Comandos: Use sempre da raiz do projeto
- Exemplos: Todos os exemplos são executáveis

---

**Última atualização:** 22 de outubro de 2025
