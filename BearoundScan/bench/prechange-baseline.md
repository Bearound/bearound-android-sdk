# Baseline Android anterior à mudança

Este patch aplica-se somente a `5b5ebfe81bbb140fe58996f677caea7e53f48e3a`. Ele acrescenta transporte local Debug e isolamento do host, identidade sintética da fixture, desativação do companion e dos relatos de erro, confiança na CA do usuário e fingerprint do build. A alteração no fonte do SDK substitui somente a URL de destino. Registro BLE, filtros, rádio, timers, cache, agregação, persistência e critérios de visita permanecem iguais. Este baseline não contém a implementação adaptativa.

O baseline usa `io.bearound.bearoundscan.adaptivebaseline`. O candidato usa `.adaptivebench`, e o exemplo original conserva seu pacote. Isso separa a fila anterior dos envelopes v2 e preserva os dados do aplicativo original. As duas execuções continuam no mesmo aparelho físico e nas condições pareadas.

Criar checkout isolado no commit exato, copiar `local.properties` sem imprimir seu conteúdo e aplicar com `git apply --check --unidiff-zero`, seguido de `git apply --unidiff-zero`. Reverter com `git apply -R --unidiff-zero`. Não aplicar no checkout original nem no SDK candidato.

Build sem instalação:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --offline :BearoundScan:assembleDebug -PadaptiveBench=true -PbenchEndpoint=https://127.0.0.1:18080 -PbenchToken=fixture-token-adaptive-sampling -PbenchUser=fixture-person -PbenchAdaptive=false
```

Usar o endereço real da fixture na LAN e seu certificado na execução física. As flags não ignoram TLS nem instalam confiança na CA. O asset `assets/adaptive_sampling_bench_build.json` identifica o arm como `baseline`, `control=pre_change` e `transportOnlyInstrumentation=true`. Guardar hash do patch, SHA original, fingerprint do fonte, hash do APK e evidência de instalação. O candidato com `benchAdaptive=false` é identificado como `compatibility_control` e não substitui este baseline.

Nenhum aparelho recebeu instalação ou comando durante esta preparação. A execução nativa continua dependendo dos dois SDKs e do protocolo completo da bancada.
