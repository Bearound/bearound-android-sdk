# Descoberta: throttle do log de scan em idle

Perfil fast; descoberta existente reaproveitada, sem nova varredura. Fonte: `/Users/jotta/Documents/bearound/qa/sdk-performance-20261006/samsung-sdk314/idle-optimization/source-diagnosis.md`. Esta especificação registra uma correção local; não registra implementação ou ganho já obtido.

## Versão e orientação

Worktree nativo: `/Users/jotta/Documents/bearound/wt-android-sdk-idle-performance-20261006`, criado do tag `v3.14.0`, commit `9cab0107acc45074824456f163830ec7060b2ba1`. O clone principal em 3.12 fica intacto. `CLAUDE.md` define código em inglês, documentação em pt-BR, testes Gradle e aplicação de referência `:BearoundScan`. O executor principal conserva as mudanças locais de SDK 3.14 e permissões no Car Media; este ajuste não modifica o host ou a ponte Flutter.

## Evidência confirmada

- Perfil físico informado pelo executor: 330 amostras em 30 s; `DetectionLogStore.append` consumiu 12,73% inclusivo na thread principal e 3,33% em worker. Percentuais inclusivos de ancestrais não podem ser somados.
- Exportação: 500 registros, 434 Scan e 32 Scan nos últimos 60 s. Dos 425 pares rápidos classificáveis, 386, ou 90,8%, mantiveram a mesma composição `major.minor`. A classificação não comprova identidade UUID; o teste novo cobre essa distinção.
- O valor aquecido de 3,49% antecedeu qualquer otimização. O executor principal posteriormente informou baseline fresco de 3,532% em 43,88 s. Os 11,27–16,21% históricos não são controle pareado para atribuir ganho.
- A assinatura atual inclui RSSI e vence o throttle por diferença de string. Cada append reconstrói até 500 entradas e serializa todo o array antes do `apply()`.

## Mapa e precedentes

| Local | Papel e decisão |
|---|---|
| `sdk/src/main/java/io/bearound/sdk/BeAroundSDK.kt:84-85,241-243` | Janela `10_000L`, estado de assinatura e timestamp de vida da instância; manter comparador estrito e sem novos resets. |
| `sdk/src/main/java/io/bearound/sdk/BeAroundSDK.kt:364-409` | Enriquecimento, cache e callback precedem o diagnóstico. Corrigir somente o gate Scan e construir detalhe depois da aprovação. |
| `sdk/src/main/java/io/bearound/sdk/BeAroundSDK.kt:411-469` | Logs Background e transições de região permanecem intactos. |
| `sdk/src/main/java/io/bearound/sdk/models/Beacon.kt` | `identifier` contém apenas `major.minor`. Usar UUID mais identifier exclusivamente na comparação diagnóstica; não alterar o modelo. |
| `sdk/src/main/java/io/bearound/sdk/utilities/DetectionLogStore.kt:45-71` | Array newest-first, cap 500, estado no momento da escrita; `commit()` em terminated, `apply()` nos demais. Reutilizar sem modificar. |
| `sdk/src/test/java/io/bearound/sdk/utilities/DetectionLogStoreTest.kt` | Seis testes existentes de formato, estado, ordem, cap e limpeza; executor informou baseline 6/6 verde. |
| `sdk/src/main/java/io/bearound/sdk/background/NotificationUpdateThrottle.kt` | Precedente interno de gate com relógio injetável. Adaptar a forma; suas regras de coalescência e trailing não se aplicam ao Scan. |
| `sdk/src/test/java/io/bearound/sdk/background/NotificationUpdateThrottleTest.kt` | Harness JUnit com relógio falso; adaptar para testar o gate usado em produção. |

## Limites e validação

Identidades devem formar conjunto, independente de RSSI, metadata e ordem. Alteração real de composição deve produzir entrada imediata. Conservar `System.currentTimeMillis()`, comparação `> 10_000`, detalhe `major.minor rssi=value`, callbacks, scan, estatísticas e sync.

Não foi encontrado teste do gate atual. Um helper interno pequeno se justifica para exercitar o mesmo gate chamado pelo SDK, com relógio falso e fornecedor de detalhe contado; nenhuma API pública é necessária.

O executor principal controla o telefone Samsung A16, API 36, e o QA externo. Reutilizar seu `record_case.py`, baseline congelado e configuração Home/SDK ON em ABBA. A regressão é Android/ADB, sem navegador. Provar o mecanismo e medir CPU separadamente; ganho quantitativo continua aberto.

## Variação de formato

O executor confirmou que o parser atual aceita `- [ ] F1-01: Title`, inclusive em especificação anterior executada. Usar esse separador compatível para cumprir a proibição de U+2014 do workspace, apesar do exemplo antigo do skill.
