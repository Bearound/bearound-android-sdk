# Projeto Beacon BLE com Adafruit nRF52840 e App Android

Este projeto consiste em um beacon BLE configurado em um Adafruit nRF52840 Feather Express e um aplicativo Android para detectar este beacon, exibir suas informações, obter o Advertising ID do dispositivo e sincronizar os dados com uma API.

## Funcionalidades do App Android

1.  **Detecção de Beacons**: O aplicativo utiliza a biblioteca AltBeacon para detectar beacons iBeacon com um UUID específico.
2.  **Exibição de Informações**: A interface do usuário mostra o status da detecção, UUID, Major, Minor do beacon detectado, o Advertising ID (AAID) do dispositivo e o status da sincronização com a API.
3.  **Notificações**: O usuário é notificado quando entra ou sai da região do beacon, mesmo com o aplicativo em segundo plano.
4.  **Obtenção do Advertising ID**: O aplicativo obtém o AAID do dispositivo para ser enviado à API.
5.  **Sincronização com API**: Ao detectar um beacon, o aplicativo envia o UUID, Major, Minor do beacon e o AAID do dispositivo para um endpoint de API configurável.
6.  **Execução em Segundo Plano**: Um serviço de primeiro plano garante que o monitoramento de beacons e as notificações continuem funcionando mesmo quando o aplicativo está em segundo plano.
7.  **Solicitação de Permissões**: O aplicativo lida com a solicitação das permissões necessárias em tempo de execução (Localização, Bluetooth Scan).

## Estrutura do Projeto Android (`android_app`)

-   `app/build.gradle`: Arquivo de configuração do Gradle para o módulo do aplicativo, incluindo dependências como AltBeacon e Play Services Advertising ID.
-   `app/src/main/AndroidManifest.xml`: Define as permissões, componentes do aplicativo (Activity, Application, Services) e outras configurações essenciais.
-   `app/src/main/java/com/example/beaconpoc/`:
    -   `BeaconPocApplication.kt`: Classe principal da aplicação. Inicializa o BeaconManager, configura o parser para iBeacon, define a região de monitoramento, gerencia o RegionBootstrap para detecção em background, lida com notificações de entrada/saída da região, obtém o Advertising ID e realiza a sincronização com a API.
    -   `MainActivity.kt`: Activity principal que exibe as informações do beacon e o status. Lida com a solicitação de permissões e atualiza a UI com base nos dados recebidos do `BeaconPocApplication`.
-   `app/src/main/res/`:
    -   `layout/activity_main.xml`: Define a interface do usuário da MainActivity.
    -   `values/strings.xml`: Contém todas as strings de texto usadas no aplicativo, facilitando a localização.
    -   `drawable/`: (Você precisará adicionar um ícone de notificação aqui, por exemplo, `ic_launcher_foreground.xml` ou um PNG).
-   `build.gradle` (nível do projeto): Arquivo de configuração do Gradle para o projeto inteiro.
-   `todo_android.md`: Lista de tarefas para o desenvolvimento do app Android (concluída).

## Configuração e Uso

1.  **Pré-requisitos**:
    *   Android Studio instalado.
    *   Um dispositivo Android com Bluetooth e Serviços de Localização habilitados.
    *   O beacon Arduino (Adafruit nRF52840) programado e transmitindo com o UUID: `E25B8D3C-947A-452F-A13F-589CB706D2E5` (o app Android espera este UUID em minúsculas: `e25b8d3c-947a-452f-a13f-589cb706d2e5`).

2.  **Importar o Projeto**:
    *   Descompacte o arquivo `beacon_project_android.zip`.
    *   Abra o Android Studio.
    *   Selecione "Open an existing Android Studio project" e navegue até a pasta `android_app` extraída.

3.  **Configurar o Endpoint da API**:
    *   Abra o arquivo `app/src/main/java/com/example/beaconpoc/BeaconPocApplication.kt`.
    *   Localize a constante `API_ENDPOINT_URL`.
    *   Substitua o valor placeholder (`"https://your.api.endpoint/beacon_data"`) pelo URL real do seu endpoint da API que receberá os dados (UUID, Major, Minor, IDFA/AAID).

4.  **Ícone de Notificação**:
    *   O aplicativo usa `R.drawable.ic_launcher_foreground` como ícone de notificação. Certifique-se de que este drawable existe ou substitua-o por um ícone de notificação adequado no método `sendNotification` em `BeaconPocApplication.kt` e no `AndroidManifest.xml` se necessário.

5.  **Compilar e Executar**:
    *   Conecte seu dispositivo Android ou inicie um emulador.
    *   Compile e execute o aplicativo a partir do Android Studio.

6.  **Conceder Permissões**:
    *   Ao iniciar o aplicativo pela primeira vez, ele solicitará as permissões necessárias (Localização Precisa, Acesso à Localização em Segundo Plano e Bluetooth Scan em dispositivos Android 12+). Conceda essas permissões para que o aplicativo funcione corretamente.

7.  **Testar**:
    *   Com o beacon Arduino transmitindo, aproxime o dispositivo Android.
    *   O aplicativo deve detectar o beacon, exibir suas informações (UUID, Major, Minor), o Advertising ID do dispositivo e o status da sincronização com a API.
    *   Você deve receber notificações ao entrar e sair da região do beacon.

## Observações

-   **UUID do Beacon**: O UUID `e25b8d3c-947a-452f-a13f-589cb706d2e5` está configurado no código. Se o seu beacon Arduino usar um UUID diferente, atualize-o na constante `beaconUUID` em `BeaconPocApplication.kt`.
-   **Consumo de Bateria**: A biblioteca AltBeacon e o monitoramento em segundo plano são otimizados, mas o uso contínuo de Bluetooth e localização pode impactar a bateria. O `BackgroundPowerSaver` está habilitado para ajudar a mitigar isso.
-   **Consumo de Bateria**: A biblioteca AltBeacon e o monitoramento em segundo plano são otimizados, mas o uso contínuo de Bluetooth e localização pode impactar a bateria. O `BackgroundPowerSaver` está habilitado para ajudar a mitigar isso e o aplicativo utiliza um serviço de primeiro plano para manter o monitoramento ativo.
-   **Background Fetch**: A sincronização com a API ocorre quando o beacon é detectado (ao entrar na região e durante o ranging). Não foi implementado um "background fetch" no sentido de uma tarefa periódica agendada pelo sistema operacional para buscar dados da API, mas sim uma sincronização acionada por eventos de beacon. Se uma sincronização periódica independente de eventos de beacon for necessária, o WorkManager do Android seria uma boa abordagem.

Este aplicativo Android fornece uma base sólida para interagir com seu beacon BLE e sincronizar dados com uma API. Sinta-se à vontade para expandir e personalizar conforme necessário.
