# Samsung A16: diagnóstico Scan e recuperação de rádio

CPU ociosa média: **10,0182% → 6,4879% de um núcleo**. Atribuição causal do ganho inconclusiva devido à carga desigual. Redução descritiva de **35,2387%** na ABBA final. Logs Scan: **135 → 10**. Correções direcionadas validadas; homologação geral de performance pendente.

## Reconciliação e atribuição

Os 11,27%–16,21% iniciais pertencem a outra rodada; uma sessão aquecida já tinha caído a 3,53% antes da mudança. Não são o baseline da correção. Nesta rodada cada APK foi reiniciado e aquecido por 60 s. Razão corrigido/publicado: 0,6476. Diferença publicado menos corrigido: 3,5303 pontos percentuais.

Perfetto, o segundo método, encontrou médias de 9,9197% e 6,3956%. As medidas por execução concordam. As duas janelas por versão são curtas, com rádio e catálogo reais. O AAR candidato foi compilado localmente, enquanto o baseline usa a dependência publicada. Ambiente, toolchain nativo e instrumentação QA limitam a atribuição exata do percentual ao SDK. Não há promessa de ganho em produção, bateria ou outro aparelho.

O volume de eventos foi desigual: 2020 observações de metadata no publicado e 668 no corrigido, razão 3,0240. A2 recebeu muito mais eventos. A média é descritiva, e a atribuição causal do percentual permanece inconclusiva sob essa diferença de carga. O mecanismo de log é verificado separadamente pelo ritmo/composição e pelos testes.

O defeito de diagnóstico independe do ganho de CPU: RSSI fazia parte da assinatura e liberava persistência repetidamente. O perfil físico localizou serialização síncrona de até 500 registros em `DetectionLogStore.append`. Tempos inclusivos de ancestrais não foram somados. A captura com sampler não foi usada na ABBA.

A ABBA intermediária que corrigia somente o log está em `log-only-abba/idle-analysis.json`. Ela observou 26,89% de redução média; não foi misturada com o APK final, que também corrige a recuperação de rádio.

## Aparelho e artefatos

| Item | Valor |
|---|---|
| Aparelho | Samsung Galaxy A16 5G, SM-A166M |
| Serial | RQCXB06SBVY |
| Sistema | Android 16, API 36 |
| Aplicativo | Car Media, media.car.app, 1.0.20+42, arm64 profile |
| Base nativa | v3.14.0, commit 9cab0107acc45074824456f163830ec7060b2ba1 |
| Correção de log | 1f625a0541aeae04e2c107c952531255e325a087 |
| Correção de rádio | 80bffc5b7a0e9497c1f01b1508ecea4152d9be28 |
| SHA-256 APK publicado | 79e82ae80ce6ba632b2b8fbae35eda8ab68eaa6a1ce2282638c1ed4108d26f6b |
| SHA-256 APK candidato final | 7f659b2a37045f3fb8385fbbeb3d6b64f7f2c5aae33a9fa4f85958ec154e2b16 |
| SHA-256 AAR final | 7f09b227e939982d4fb4426bbb8f18a4fce702ab7e0777f5d100c8c2b1d5173b |
| SHA-256 entrypoint QA comum | 3551ce0a6612c06f1400cf3ef995d03b35fb1e4b8025d17864ee105df9d89b24 |

`libapp.so` e `libflutter.so` são idênticos entre os APKs. Os 24 DEX do baseline são iguais aos do APK corrigido de permissões da rodada anterior. Ambos usam a correção local de permissões do host e a mesma instrumentação. O candidato é local, sem publicação, e conserva a versão base 3.14.0 no runtime. A origem é identificada por hash e commit.

ABBA: localização durante o uso previamente concedida, Nearby e notificações concedidas, background location negado. Home sem gestos e USB carregando. Não houve coleta concorrente no telefone. Os beacons são reais; quantidade e leituras por sessão constam nos JSONs.

## Execuções finais e medidas

| Ordem/artefato | Janela efetiva (s) | CPU /proc (%) | CPU Perfetto (%) | Scan | Callbacks | Metadata | Sync OK |
|---|---:|---:|---:|---:|---:|---:|---:|
| on-published-idle-a1 | 44,0200 | 7,0195 | 6,9652 | 21 | 55 | 286 | 3 |
| on-candidate-idle-b1 | 44,0100 | 6,4076 | 6,3283 | 6 | 59 | 341 | 3 |
| on-candidate-idle-b2 | 44,0000 | 6,5682 | 6,4628 | 4 | 56 | 327 | 3 |
| on-published-idle-a2 | 44,0200 | 13,0168 | 12,8742 | 114 | 290 | 1734 | 3 |

As médias são ponderadas pela janela efetiva: 88,0400 s publicados e 88,0100 s candidatos. Contadores funcionais usam janelas de cinco segundos sobrepostas às capturas. Metadata conta observações repetidas, não identidades distintas.

A medição que poderia contrariar a melhora verificou o fluxo funcional: o candidato entregou 115 callbacks, 668 observações de metadata, 668/668 estatísticas RSSI coerentes, 6 syncs e 0 falhas de envio. O publicado teve 2020 observações e 6 syncs. Contagens de rádio não precisam ser artificialmente iguais.

No publicado, 126 de 133 pares Scan consecutivos tinham composição igual e intervalo menor ou igual a dez segundos. No candidato: 0 de 8. Mudanças reais continuam registradas imediatamente. O detalhe físico só distingue major/minor; UUID está coberto por teste unitário.

As quatro capturas finais preservaram cada PID e não apresentaram erros de trace nem saída por ANR/crash do PID observado. Isso não equivale a passar Android Vitals ou a um teste prolongado.

## Crash encontrado e regressão de rádio

O candidato intermediário caiu com `IllegalStateException` na main thread quando o watchdog chamou `BluetoothLeScanner.stopScan` com o controlador realmente OFF. Esse trecho do manager era inalterado pela correção de log. O controle publicado permaneceu vivo por 104 s de OFF; a reprodução física na versão publicada não foi confirmada.

Sete testes do `BeaconManager` real foram escritos antes da correção. Cinco falharam no código anterior: stopScanning, stopRanging foreground/background, restart com exceção e restart normal. Quota negada e cancelamento da partida atrasada já passavam. Após a correção, sete passaram. A parada conclui a limpeza mesmo com exceção, e o restart limpa `isRanging` antes do backoff. Quota é reservada antes da parada; política de timers/background, região, filtros e sync foram preservados.

No APK final o controlador permaneceu OFF por 308,5194 s. Houve 1 saída(s) de região após a tolerância de cinco minutos. Ao religar houve 1 entrada(s), +318 metadata e +1 syncs, no mesmo PID 10491, sem nova saída por crash ou ANR. Bluetooth, Wi-Fi, modo avião e scan sempre disponível foram restaurados.

Dois estímulos anteriores foram excluídos: `bluetooth_on=0` ainda permitia anúncios BLE, e outro estímulo voltou para BLE_ON. Somente OFF confirmado no controlador foi aceito como ausência física. Evidências estão em `excluded-region-setting-only`, `excluded-region-ble-on`, `radio-crash-log-only`, `published-radio-crash.json` e `region-validation.json`.

## Permissões do host

Sete cenários físicos passaram no APK final, sem abrir Settings automaticamente. Nesta repetição o diálogo de localização não apareceu; ela cobre continuidade e recusas, mas não repete a escolha durante o uso no diálogo. Essa escolha foi observada na suíte inicial do host. A ABBA usou foreground location previamente concedida. Permissões do Car Media continuam como alteração local separada deste PR nativo.

## Cobertura e gates

| REQ | Evidência |
|---|---|
| REQ-001 | RSSI/ordem/duplicatas/metadata no teste; composição estável antecipada eliminada na ABBA. |
| REQ-002 | UUID/adição/remoção/vazio no teste; mudanças físicas continuam registradas. |
| REQ-003 | Limites 10.000/10.001 ms e rollback; detalhe RSSI atual e comparador preservados. |
| REQ-004 | Construção lazy e seis testes do store; cap, ordem e commit/apply intactos. |
| REQ-005 | Kotlin/lint; callbacks, metadata/stats e sync nas quatro capturas e recuperação. |
| REQ-006 | CLI com hashes, ABBA sem sobreposição, dois métodos e resultado sem limiar de ganho inventado. |
| REQ-007 | Cinco falhas RED e sete GREEN no manager real; OFF/ON sustentado preserva processo e recupera região/dados/sync. |

24 testes nativos distintos passaram: 11 do throttle, seis do store e sete de recuperação de rádio. Os 17 anteriores não foram repetidos, pois seu código não mudou. `compileDebugKotlin`, `lintDebug` e `assembleRelease` concluíram com exit 0. O build inicial atingiu watchdog após testes verdes; o retry aquecido com dois workers passou. Avisos preexistentes de compilador/Gradle continuam registrados nos logs.

Profile fast: compilação Kotlin, testes direcionados, lintDebug, release AAR e Android/ADB E2E direcionado. Fora do gate: suíte nativa completa, matriz completa e revisão formal. Playwright não se aplica ao fluxo nativo. A suíte global do Car Media tinha uma falha preexistente em widget_test.dart; não é declarada verde.

coverage: 7 REQ declared | 7 with acceptance criteria | 7 covered by design | 7 implemented by a task | 3 tasks (3 with validate, 1 e2e)

## Modelo de dados e próximos limites

Recommendation: medir gravação incremental do diagnóstico local como próxima otimização. Why: a frequência diminuiu, mas cada entrada admitida ainda reserializa até 500 registros. Alternative considered: compactar primeiro todo o protocolo, descartada como primeiro passo porque o envelope já compartilha SDK/aparelho uma vez por lote, um beacon é consolidado por janela e RSSI já chega como estatísticas; o ganho ainda não foi medido e exige compatibilidade entre SDKs e ingestão.

Beacon → observações ajuda quando um lote realmente tem várias observações temporais do mesmo beacon. Bateria, temperatura e movimentos continuam associados ao instante; o lote precisa ser autocontido para replay offline. Não existe representação sem nenhuma operação, mas podemos evitar reprocessar todo o histórico em cada inserção. Protocolo e fila não foram alterados neste PR.

Faltam bateria sem USB, Doze, sessões longas, fila após morte do processo, GPS/geofencing, vídeo após a mudança e aparelhos antigos. A ponte Flutter permanece candidata separada de investigação. Nenhuma métrica do A16 é extrapolada para iOS.

## Método e reprodução

CPU: delta de utime+stime em `/proc/PID/stat`, CLK_TCK=100, dividido pelo delta de uptime. Cross-check: tempo escalonado no Perfetto nos mesmos limites. Simpleperf localiza stacks; não mede ganho. A coleta exporta contadores agregados, sem tokens, coordenadas, identidades de beacons ou payloads brutos. PSS foi capturado, mas não comprova economia de memória. CPU cobre o processo completo com streams QA; o overhead não foi subtraído por suposição.

Runner: `e2e/validate-idle.sh`, com quatro argumentos declarados no plano. `--verify-only` verifica as evidências já coletadas. Os collectors externos são reaproveitados e suportam este Samsung explicitamente; não são uma suíte CI genérica.

QA local: `/Users/jotta/Documents/bearound/qa/sdk-performance-20261006/samsung-sdk314/idle-optimization`. Dados principais: idle-analysis.json, e2e-validation.json, candidate-build.json, baseline-build.json, region-validation.json, permission-e2e-candidate/results.json, native-radio-red/green.log e XML, traces e captures por execução. O relatório consolidado fica em `/Users/jotta/Documents/bearound/qa/sdk-performance-20261006/samsung-sdk314/report.html`. O PR não inclui APKs, traces ou estado operacional do executor. Nenhum pacote ou deploy foi publicado.
