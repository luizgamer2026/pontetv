# PonteTV

Hub USB sem fio: o **teclado e o mouse ligados na TV funcionam no celular como se estivessem ligados nele**.
Não há transmissão de tela. A TV se apresenta ao celular como um teclado + mouse **Bluetooth de verdade** (perfil HID Device),
então o celular usa o **cursor do próprio Android** e as teclas chegam como teclas físicas. Nada de mouse desenhado pelo app,
nada de calibração.

Módulos: `phone` (Android 10+, pareia e mostra "Teclado e mouse conectados") · `tv` (Android 9+, o hub).

## Como funciona

* **TV** (`PonteTV Hub`): registra um dispositivo HID composto (teclado + mouse com 5 botões e rolagem vertical/horizontal),
  lê o teclado/mouse USB e envia relatórios HID ao celular.
* **Mouse some da TV:** enquanto controla o celular, a TV captura o ponteiro (`requestPointerCapture`) e esconde o cursor.
  Se a TV não liberar a captura, entra um modo alternativo por eixos relativos.
* **Teclado:** a tecla física é enviada pelo *scancode* (tabela evdev → uso HID). Quem decide ç, acentos e AltGr é o layout do
  celular (Ajustes → Teclado físico → Português do Brasil ABNT2). Isso evita teclas trocadas/erradas.
  A tecla `/ ?` do ABNT2 e o `.` do teclado numérico são enviadas.
* **Teclas do sistema da TV:** ligando o serviço de acessibilidade "PonteTV Hub" na TV (botão **Bloquear teclas na TV**),
  Esc, F1…F12, Windows etc. são consumidos antes do launcher e vão só para o celular.
* **Soltar para a TV:** **F8** (teclado) ou **VOLTAR** do controle remoto devolvem teclado e mouse à TV. F8 de novo volta a controlar o celular.
  Se o app sai da frente na TV, tudo é solto no celular.
* **Celular** (`PonteTV`): procura a TV por Bluetooth, faz o pareamento e mostra o estado lido do próprio Android
  (✔ Teclado e mouse conectados). Depois de pareado, **a TV conecta sozinha** ao abrir o app.

## Primeira conexão

1. TV: abrir **PonteTV Hub** → **Tornar visível**.
2. Celular: abrir **PonteTV** → **Procurar TV** → tocar na TV → aceitar o pareamento nos dois aparelhos.
3. A TV conecta e passa a controlar o celular. Nas próximas vezes basta abrir o app na TV.

## Requisitos e limites

* A TV precisa de Bluetooth e do perfil **HID Device** liberado pelo fabricante. Se não tiver, o app mostra o motivo na TV.
* O app da TV precisa estar **na frente** para receber o teclado/mouse USB (limite do Android).
* A imagem do celular na TV não faz parte do app (use o espelhamento nativo/cabo, se quiser).
* Velocidade do mouse: configuração de ponteiro do próprio celular.

## Latência (v2.5)

* **Envio em thread própria** (prioridade alta) com fila curta: o evento do mouse/teclado sai na hora, sem timer de 8 ms.
* **Movimento nunca acumula atraso:** o mouse é enviado a no máximo ~125 Hz; se o Bluetooth não der conta, o movimento
  que chega no intervalo é *somado* ao relatório que ainda não saiu (o cursor chega no mesmo lugar, sem fila crescendo).
  Teclas e botões nunca esperam esse intervalo.
* **Keepalive:** com o controle ativo, a cada 40 ms vai um relatório vazio de mouse (o Android ignora) para o link
  Bluetooth não entrar no modo de economia, que atrasa o primeiro movimento/tecla depois de uma pausa.
* **QoS Bluetooth** de baixa latência no registro HID (se a TV recusar, registra de novo sem ele).
* **Mouse sem lote por quadro** (Android 12+: `requestUnbufferedDispatch`) e sem consulta de dispositivo a cada evento.
* O HUD mostra `Bluetooth: N envios/s · envio mais lento X ms` para diagnosticar (atualiza a cada 2 s, em um TextView só).
* **Menos trabalho por evento (v2.5):** o movimento do mouse é somado sem alocar memória e a thread de envio só acorda quando
  há relatório novo; no Android 12+ ela também recebe uma dica de desempenho (ADPF) para a CPU não "dormir" nela.

**Teste ao vivo (com o controle ativo, no teclado da TV):** **F9** troca o ritmo máximo do mouse
(125 → 250 → 90 → 62 Hz) e **F10** liga/desliga o keepalive. A escolha fica salva e aparece no HUD. F9/F10 não vão para o celular.
Dica: em jogo, evite fone Bluetooth tocando no mesmo celular e deixe a TV no Wi-Fi 5 GHz ou cabo; os dois disputam o rádio com o mouse.

## Modo BLE (experimental) e bloqueio de teclas

* **BLE HID (HOGP):** o botão *Modo* do painel da TV troca o Bluetooth clássico por BLE (a TV anuncia o serviço HID e o
  celular pareia como central). O clássico continua sendo o padrão. Só funciona se o Bluetooth da TV conseguir anunciar BLE;
  se não conseguir, o app avisa e fica no clássico. Ao trocar, esqueça a TV antiga no celular e use *Procurar TV* de novo.
  O intervalo de conexão BLE quem decide é o celular; a TV só pede prioridade alta (o HUD mostra o resultado).
* **Bloqueio de teclas (v2.5):** o filtro de teclas do serviço de Acessibilidade agora só fica ligado *enquanto o teclado controla
  o celular* (antes ficava sempre no caminho de todas as teclas da TV, e por isso a TV podia congelar ao ligar o serviço).
  O botão varre as telas de Acessibilidade que a TV realmente tem (ignorando os "Stubs", que só mostram o erro "nenhuma ação pode ser executada") e tenta uma por toque (o aviso mostra qual); se nenhuma abrir, abre as Configurações da TV e mostra o caminho. Se a TV desligar o
  serviço ao ser desligada, a permissão dada uma vez por ADB (`pm grant ... WRITE_SECURE_SETTINGS`) faz o app religá-lo sozinho
  ao abrir e a cada boot. Sem a permissão, o passo a passo por ADB aparece na tela (com o IP da TV). Em `tools/ativar-bloqueio.sh` há um script para o Termux que liga tudo de uma vez:
  `bash tools/ativar-bloqueio.sh IP_DA_TV`. Depois de uma vez, o serviço fica ligado (e o app o religa sozinho).

## Compilar

Push no GitHub → Actions → Release com `PonteTV-Celular.apk` e `PonteTV-AndroidTV.apk`.
