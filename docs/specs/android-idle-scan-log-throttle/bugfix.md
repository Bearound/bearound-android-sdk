# Bugfix: throttle do log de scan em idle

## Contexto

O gate diagnóstico confunde mudança de RSSI com mudança de composição e persiste Scan repetidamente. A correção preserva a detecção e reduz apenas o trabalho diagnóstico redundante. Perfil fast; implementação autorizada pelo executor principal.

### REQ-001: Suprimir composição estável

Atual: WHEN RSSI ou ordem mudam com composição estável THEN o SDK produz novos logs antes da janela.

Esperado: WHEN uma composição não vazia permanece igual THEN o SDK SHALL suprimir Scan enquanto o intervalo desde a última entrada for menor ou igual a 10.000 ms.

Preservado: WHEN recebe beacons THEN o SDK SHALL CONTINUE TO processar cada callback.

- [x] RSSI, metadata, txPower, ordem e duplicatas da mesma identidade não provocam entrada antecipada.
- [x] Doze callbacks entre 0 e 11.000 ms, com RSSI alternado, produzem duas entradas.

### REQ-002: Identidade completa e mudanças imediatas

Atual: WHEN somente UUID muda com `major.minor` e RSSI iguais THEN a assinatura atual não distingue a identidade.

Esperado: WHEN o conjunto de UUID/major/minor muda THEN o SDK SHALL admitir imediatamente Scan, mesmo dentro da janela.

Preservado: WHEN a lista está vazia THEN o SDK SHALL CONTINUE TO omitir Scan sem apagar o estado anterior.

- [x] Primeiro conjunto, adição, remoção e substituição por UUID diferente produzem entradas imediatas.
- [x] Repetir composição após lista vazia conserva o intervalo original.
- [x] `Beacon.identifier` e os dados recebidos permanecem intactos.

### REQ-003: Preservar tempo e detalhe atual

Atual: WHEN RSSI varia THEN o predicado de composição contorna o intervalo temporal.

Esperado: WHEN a composição permanece igual e passam mais de 10.000 ms THEN o SDK SHALL admitir Scan com RSSI atual.

Preservado: WHEN avalia tempo THEN o SDK SHALL CONTINUE TO usar o relógio atual e o comparador estrito `>`.

- [x] Após entrada em 0 ms, 10.000 ms é suprimido e 10.001 ms é admitido.
- [x] Mudança de composição reinicia o intervalo; recuo do relógio não libera composição igual.
- [x] Não existe trailing, timer ou reset novo.

### REQ-004: Construção lazy e persistência compatível

Atual: WHEN recebe callback não vazio THEN o SDK monta o detalhe com RSSI antes de decidir gravar.

Esperado: WHEN o gate admite Scan THEN o SDK SHALL construir seu detalhe uma vez e enviar ao store existente.

Preservado: WHEN persiste THEN o SDK SHALL CONTINUE TO conservar formato, estado, timestamp, id, cap 500, ordem e política de escrita.

- [x] Fornecedor de detalhe não executa em callbacks suprimidos ou vazios.
- [x] Detalhe conserva `major.minor rssi=value` e a ordem da lista admitida.
- [x] Os seis testes existentes do store passam; `commit()`/`apply()` ficam intactos.

### REQ-005: Conservar o fluxo funcional

Atual: WHEN chegam beacons com RSSI variável THEN trabalho diagnóstico redundante acompanha o fluxo funcional.

Esperado: WHEN o gate suprime Scan THEN o SDK SHALL limitar a supressão ao diagnóstico.

Preservado: WHEN recebe detecções THEN o SDK SHALL CONTINUE TO conservar callbacks, metadata, RSSI/stats, regiões, sync, parsers, filtros e cadências.

- [x] Kotlin do projeto compila; o diff fica restrito ao diagnóstico e à parada/restart regular de REQ-007.
- [x] No aparelho, ambos os artefatos entregam beacons, callbacks, metadata disponível, stats coerentes e sync.
- [x] Não surgem crash ou ANR durante a regressão.

### REQ-006: Medir resultado físico com controle pareado

Atual: WHEN compara idle histórico com sessão aquecida THEN a diferença prévia impede atribuir ganho à correção.

Esperado: WHEN valida a correção THEN o executor SHALL comparar baseline congelado e otimizado em ABBA e relatar o resultado medido.

Preservado: WHEN compara artefatos THEN o executor SHALL CONTINUE TO conservar Home, permissões, configurações e ambiente de scan.

- [x] CLI reproduzível recebe serial, dois APKs e diretório QA.
- [x] CPU por processo e Perfetto, ritmo Scan, callbacks, metadata/stats, sync e crash/ANR têm evidência por janela.
- [x] Reportar melhora, ausência de melhora ou inconclusão, sem limiar inventado.

### REQ-007: Parada segura e recuperação do rádio

Atual: WHEN o rádio desliga THEN `stopScan` sem proteção pode encerrar o processo; restart conserva `isRanging=true` e bloqueia partida.

Esperado: WHEN `stopScan` falha na parada ou restart THEN o SDK SHALL concluir a transição e recuperar sem crash.

Preservado: WHEN recupera scan THEN o SDK SHALL CONTINUE TO respeitar quota, backoff, watchdog, região e detecção.

- [x] Os três caminhos não propagam a falha e limpam flags necessárias.
- [x] Restart tenta registrar após backoff; sem quota preserva sessão.
- [x] OFF sustentado e retorno ON preservam processo, detecção e sync.

## Assumptions

- Esta entrega se limita ao diagnóstico Scan e à recuperação segura do scan regular no worktree 3.14; o executor principal controla integração e telefone.
- A comparação usa UUID mais `Beacon.identifier`, preservando o identifier público e caches existentes.
- A evidência física cobre Samsung A16, API 36, foreground Home. Não permite generalizar ganho para outros aparelhos ou bateria.
- Código e testes ficam em inglês. Documentação permanece em pt-BR.

## Open questions

- Qual é a redução de CPU total atribuível ao gate corrigido? Assumir nenhum ganho mínimo e resolver com comparação ABBA; resultado inconclusivo não autoriza afirmar melhora.

## Unchanged behavior

- Entrega e ordem dos callbacks; beacons e metadata atualizados por pacote.
- Estatísticas RSSI e payload/cadência de sync.
- Cadências e políticas de scan principal, batch, PendingIntent, precisão, filtros, TTL, refresh e watchdog; REQ-007 corrige a exceção de parada e o estado do restart.
- Transições de região, notificações e logs de outros tipos.
- API pública, formato de log, cap 500, newest-first, timestamp, estado, id e flush terminated.
- Dependências, configuração operacional e versão do SDK.
- Mudanças locais de SDK 3.14 e permissões do host Car Media.
