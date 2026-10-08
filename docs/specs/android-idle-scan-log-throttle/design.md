# Design: throttle do log de scan em idle

## Component design

Recomendação para REQ-001 a REQ-006: corrigir o gate diagnóstico Scan. Por quê: a assinatura com RSSI viola o contrato de composição e `DetectionLogStore.append` apareceu no perfil físico. Alternativa considerada: reduzir callbacks ou duty cycle, descartada porque altera detecção, latência ou estatísticas e não é necessária para este defeito confirmado. REQ-007 acrescenta a parada segura e o estado correto do restart, descritos abaixo.

O caminho de `BeAroundSDK.setupCallbacks()` continua enriquecendo metadata, preservando `syncedAt`, atualizando `collectedBeacons` e emitindo `onBeaconsUpdated` antes da decisão diagnóstica. Os blocos Background e região permanecem intactos. Isso atende REQ-005.

Adaptar a separação entre estado, relógio injetável e decisão de `NotificationUpdateThrottle`, sem reutilizar suas regras de trailing ou coalescência. Introduzir apenas `utilities/ScanLogThrottle.kt`, um helper interno para testar o gate realmente chamado pelo SDK. Não existe API pública nova.

## Identity and timing rules

REQ-001 e REQ-002: composição é um `Set<String>` de identidades completas, construído de `"${beacon.uuid}:${beacon.identifier}"`. O modelo existente define identifier como `major.minor`; incluir UUID somente aqui evita colisão entre namespaces sem modificar o modelo ou os caches do SDK. Igualdade de conjunto ignora ordem e duplicatas. RSSI, metadata, txPower, timestamps, proximidade e sync não participam da comparação. Nenhum objeto Beacon ou lista recebida é modificado.

REQ-003: primeira composição não vazia é admitida, inclusive com relógio em zero. Uma composição diferente é admitida imediatamente. Composição igual é admitida somente quando `now - lastLogAt > 10_000L`. A entrada admitida atualiza composição e timestamp juntos; uma entrada por mudança real reinicia essa janela. Lista vazia retorna null sem alterar estado. Usar `System.currentTimeMillis()` por padrão, preservando inclusive supressão de composição igual durante recuo do relógio. Não introduzir relógio monotônico, timer, trailing, lock ou reset de ciclo de scan nesta mudança.

O estado continua pertencendo à instância do SDK. Substituir os dois campos atuais pelo helper com a mesma vida útil. Não criar reset em start, stop, configure ou clearDetectionLog: o código atual não reinicia o gate nesses pontos.

## Data models and interfaces

Contrato interno `ScanLogThrottle`:

```kotlin
internal class ScanLogThrottle(
    private val minIntervalMs: Long = 10_000L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private var lastComposition: Set<String>? = null
    private var lastLogAt: Long = 0L

    fun detailIfDue(beacons: List<Beacon>, buildDetail: () -> String): String?
}
```

| Entrada/saída | Contrato |
|---|---|
| `beacons` | Lista existente já enriquecida; vazia produz null. |
| `clock` | Injetável em teste; mantém relógio de parede em produção. |
| `buildDetail` | Invocado uma vez somente quando a composição ou tempo admitem a entrada. |
| Retorno null | Nenhum append, nenhuma construção de detalhe, estado preservado. |
| Retorno String | Detalhe completo daquela lista admitida; estado atualizado para a tentativa. |

REQ-004: o SDK chama o mesmo helper testado e fornece a construção atual dentro de lambda. Não manter uma segunda cópia do predicado no callback.

```kotlin
scanLogThrottle.detailIfDue(enrichedBeacons) {
    enrichedBeacons.joinToString(", ") {
        "${it.major}.${it.minor} rssi=${it.rssi}"
    }
}?.let { detail ->
    DetectionLogStore.append(context, type = "Scan", detail = detail)
}
```

A string exibida conserva ordem e formato da lista atual; a chave de deduplicação não é exibida. Construir o detalhe admitido antes de atualizar o estado permite que uma falha de formatação não avance o gate. Atualizar estado antes do append conserva o comportamento existente diante de falha de persistência: não introduzir retries de diagnóstico.

## API contracts

Não há rota HTTP, método, status code, request ou validação de endpoint neste ajuste. A API nativa pública e os callbacks existentes permanecem iguais (REQ-004, REQ-005).

| Contrato existente | Resposta e erros preservados |
|---|---|
| `getDetectionLogJson()` | JSON array newest-first, `[]` vazio ou quando a leitura falha. |
| `clearDetectionLog()` | Limpa registros; não introduz reset do gate. |
| Entrada persistida | Campos `id`, `timestamp`, `state`, `type`, `detail`; tipo Scan e detalhe existente. |
| Escrita | Cap 500; estado capturado no momento do append; terminated usa `commit()`, demais usam `apply()`. |
| Callbacks/detecção | Nenhuma filtragem adicional, mudança de payload ou mudança de ordem. |

## Error handling

O helper não realiza IO nem inclui dependência Android. Entrada vazia é estado válido, não erro. Não acrescentar exceptions públicas ou guards no fluxo de detecção. `DetectionLogStore` continua capturando falhas de append e read como hoje, para o diagnóstico não quebrar a detecção. REQ-004 conserva a política de flush e persistência sem alterar o store.

No runner físico, argumento ausente, APK ausente, telefone não disponível, captura falha ou evidência funcional ausente produzem saída diferente de zero e relatório explícito. Manter a restauração do estado inicial ao encerrar, inclusive em erro, respeitando controle exclusivo do executor principal sobre o aparelho. Uma comparação de CPU válida que resulte em ausência de melhora ou incerteza é um resultado de medição, não uma falha artificial de threshold (REQ-006).

## Test strategy

### Gate unit tests

`ScanLogThrottleTest` usa o relógio falso do precedente JUnit e instâncias Beacon reais. Conta chamadas do fornecedor de detalhe e valores retornados. Exercita o helper de produção, sem reproduzir o predicado em um helper de teste.

| REQ | Cenário/assertiva |
|---|---|
| REQ-001 | RSSI alternado, reordenação, metadata/txPower alterados e duplicatas não liberam nova entrada dentro da janela. Doze callbacks 0–11.000 ms liberam apenas 0 e 11.000 ms. |
| REQ-002 | Primeiro conjunto em zero, adição, remoção e troca somente de UUID liberam imediatamente; vazio não grava nem reseta. Entradas e Beacon.identifier não são modificados. |
| REQ-003 | 10.000 ms suprime, 10.001 ms libera; alteração real reinicia janela; recuo do relógio mantém supressão; detalhe de entrada liberada usa RSSI recente. |
| REQ-004 | Fornecedor chamado exatamente uma vez por entrada admitida, nunca em vazio/supressão; formato e ordem do detalhe preservados. |
| REQ-005 | Comparar input antes/depois e confirmar que o helper só entrega um detalhe opcional. Revisão do diff verifica que callbacks, scanners e sync não mudam. |

### Store and Kotlin gates

Executar os seis testes existentes de `DetectionLogStoreTest`, cobrindo REQ-004: array vazio, campos, terminated, newest-first, cap 500 e limpeza. O executor informou baseline 6/6 verde. Como o store permanece intacto, preservar seu código de `commit()`/`apply()` por revisão do diff; testes de ordem/cap não demonstram sozinhos flush síncrono. Não acrescentar testes que espelhem essas seis assertivas.

Executar `:sdk:compileDebugKotlin`, os dois testes por nomes completos e `:sdk:lintDebug`. O gate de projeto é compilação Kotlin, não um typecheck JavaScript. A suíte nativa completa e matriz de aparelhos não são gates do perfil fast; informar os testes realmente executados.

### Native Android/ADB E2E

Uma única tarefa E2E final, sob responsabilidade do executor principal, atende REQ-001 a REQ-007. Playwright não se aplica ao fluxo nativo Android e ao host físico. O runner `docs/specs/android-idle-scan-log-throttle/e2e/validate-idle.sh` reaproveita `/Users/jotta/Documents/bearound/qa/sdk-performance-20261006/samsung-sdk314/idle-optimization/record_case.py`. O executor confirma sua CLI existente e a invoca sem inventar outro coletor. Nenhum agente paralelo opera o telefone.

Contrato da CLI:

```text
validate-idle.sh --serial SERIAL --baseline-apk APK_A --optimized-apk APK_B --qa-root DIR
```

SERIAL e APKs são argumentos explícitos dos artefatos reais. Não incluir serial inventado ou caminho fictício no resultado. Registrar SHA-256 dos APKs, versão nativa, commit/diff, versão Android, configuração e fonte do collector. Evidências volumosas ficam no diretório QA fora do git; o relatório versionável aponta os arquivos exatos.

Execução ABBA: A é artefato 3.14 congelado, B é artefato com este gate e a recuperação de rádio de REQ-007. Repetir Home parado, SDK ON, mesmas permissões, flags, configuração, posição dos beacons e assinatura QA. Aplicar aquecimento e duração iguais. Preservar a correção local de permissões e o upgrade do host em ambos os artefatos. Não atribuir ao gate efeitos de outra diferença de build.

CPU por delta de tempo do processo é a medição primária; Perfetto é uma medida independente para concordância. Registrar duração, CPU percentual de um core, estado térmico disponível e atividades de sync. Perfil amostrado serve para localizar `append`, sem tratar seu overhead como consumo final ou somar ancestrais inclusivos. Apresentar cada janela A1/B1/B2/A2, comparação agregada, dispersão e limites de atribuição; reconciliar baseline fresco com o histórico que motivou a investigação.

Exportar Scan e comparar intervalo e composição: composição estável não deve criar entradas rápidas por RSSI; mudanças reais continuam imediatas. Exportação disponível classifica `major.minor`; registrar a limitação de UUID no dado físico e apoiar essa distinção no teste unitário. Conferir independentemente callbacks, identidades, metadata disponível, `sampleCount/min/max/avg`, regiões e sync, sem exigir contagens idênticas de rádio em janelas diferentes. Provocar uma alteração controlada de presença do beacon fora da janela de medição de CPU e verificar a entrega e diagnóstico imediato. Verificar sync de metadata e ausência de crash/ANR nos dois artefatos.

REQ-006 não promete porcentagem mínima. Mecanismo e funcionalidade podem passar com CPU inconclusiva; registrar o resultado sem inventar melhora. A cobertura física desta entrega é Samsung A16/API 36/foreground; tags terminated são protegidas pelo teste do store e código intacto.

## File-structure plan

| Arquivo | Ação | Responsabilidade |
|---|---|---|
| `sdk/src/main/java/io/bearound/sdk/BeAroundSDK.kt` | Modificar somente estado e gate Scan; lambda lazy no callback. | F1-01 |
| `sdk/src/main/java/io/bearound/sdk/utilities/ScanLogThrottle.kt` | Criar helper interno, identidade completa e relógio injetável. | F1-01 |
| `sdk/src/test/java/io/bearound/sdk/utilities/ScanLogThrottleTest.kt` | Criar testes do gate de produção. | F1-01 |
| `sdk/src/main/java/io/bearound/sdk/BeaconManager.kt` | Proteger paradas regulares e limpar estado do restart. | F1-02 |
| `sdk/src/test/java/io/bearound/sdk/BeaconManagerRadioRecoveryTest.kt` | Exercitar manager real, exceção, timers, quota e cancelamento. | F1-02 |
| `sdk/src/main/java/io/bearound/sdk/utilities/DetectionLogStore.kt` | Reutilizar intacto. | Sem escrita |
| `sdk/src/test/java/io/bearound/sdk/utilities/DetectionLogStoreTest.kt` | Executar intacto. | Sem escrita |
| `sdk/src/main/java/io/bearound/sdk/models/Beacon.kt` | Reutilizar identity/UUID sem mudar modelo. | Sem escrita |
| `docs/specs/android-idle-scan-log-throttle/e2e/validate-idle.sh` | Criar wrapper reproduzível dos collectors existentes e comparação ABBA. | F2-01, executor principal |
| `docs/specs/android-idle-scan-log-throttle/device-validation.md` | Criar relatório de execução, artefatos, resultados, regressões e limites. | F2-01, executor principal |

Write-set externo de F2-01, fora dos fingerprints do engine: `/Users/jotta/Documents/bearound/qa/sdk-performance-20261006/samsung-sdk314/idle-optimization/`, contendo capturas por janela, exports, comparação e relatório bruto. O runner nativo e `device-validation.md` são os arquivos rastreados. Não escrever na ponte, no host ou no clone principal como parte de F1-01.

## Coverage and operational constraints

F1-01 implementa e testa REQ-001 a REQ-005; F1-02 cobre REQ-007. F2-01 confirma essas propriedades no aparelho e prova REQ-006 por relatório reproduzível. Não há alteração de dependências, esquema, configuração operacional ou versão do SDK. Deploy, publicação de pacote, push para main, AWS e troca de precisão ficam fora desta entrega. Push da branch e PR para revisão foram autorizados pelo usuário.

O único Open question é a magnitude de CPU atribuível ao gate; a tarefa física responde ou documenta a incerteza. A execução é sequencial: F2-01 consome o artefato compilado e validado por F1-01 e F1-02.

## Safe regular scan shutdown

Recomendação: proteger somente as três paradas regulares e corrigir a flag do restart. Por quê: são os caminhos concretos da falha e da recuperação bloqueada. Alternativa considerada: capturar tudo no watchdog, descartada porque deixaria limpeza incompleta e estado falso de registro.

| Fronteira em `BeaconManager.kt` | Alteração REQ-007 |
|---|---|
| `stopScanning():465-502`, chamada em 477 | Capturar `Exception` da parada regular, definir `isRanging=false` e continuar batch, snapshot da região, limpeza, `isScanning=false` e callback false. |
| `stopRanging():576-587`, chamada em 580 | Capturar falha e concluir `isRanging=false`, cancelamento do watchdog e política existente do refresh. |
| `restartRanging():1052-1087`, chamada em 1079 | Preservar aquisição de quota antes da parada. Após tentativa, limpar `isRanging` antes de agendar `startRanging(budgetAlreadyAcquired=true)`. Manter watchdog vivo, backoff e limite de restarts. |

Seguir o precedente de `restartRangingForModeChange():362-374` e `stopSlowBeaconBatchScan():675-684`, que já capturam exceções. Uma função privada pequena para a parada regular é admissível pelos três consumidores; não criar API pública ou módulo novo. Usar log em inglês, sem relançar falha esperada de desligamento.

Quota negada deve retornar antes de alterar registro ou flags. Depois da parada, o guard de `startRanging():513-514` não pode enxergar `isRanging=true` residual. Falha de partida conserva false; partida bem-sucedida usa o caminho existente. Preservar guards de scanning/região para impedir partida atrasada após stop. Não chamar `stopRanging()` dentro do restart: ele cancela o watchdog que deve continuar recuperando.

Consumidores afetados: stop/reconfigure do SDK, saída de região, watchdog e refresh de ranging. Nenhuma mudança de payload, parser, RSSI, quota, cadência, graça de região, modelos ou log throttle. Contratos públicos, erros de start e políticas batch já protegidas permanecem iguais.

### Test additions

Criar `BeaconManagerRadioRecoveryTest.kt` com Robolectric e fixture de scanner que lança `IllegalStateException` em stop e conta partidas. Exercitar o `BeaconManager` real com main looper pausado; reflexão/Shadow no teste pode instalar fixture e estado, sem nova API pública para testes.

Casos mínimos: stopScanning completa limpeza/callback; stopRanging limpa flag/timers; restart com falha de stop e restart normal limpam flag e permitem partida após backoff; ausência de quota mantém sessão; stop entre agendamento e execução impede partida atrasada. Não testar somente um wrapper que captura exceções. Usar o isolamento de quota disponível no projeto, sem inventar novo contrato.

REQ-007 integra a E2E existente F2-01: confirmar controlador realmente OFF usando airplane mode e `ble_scan_always_enabled=0`; `bluetooth_on=0` isolado deixa BLE_ON e não prova ausência de rádio. Capturar estado do controlador, crash/ANR, PID, pausa de callbacks, graça de região e recuperação ON com beacons, metadata/stats e sync. O executor principal salva e restaura todas as configurações, inclusive em falha. Não executar essa fixture durante as janelas ABBA de CPU.
