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

## Compilar

Push no GitHub → Actions → Release com `PonteTV-Celular.apk` e `PonteTV-AndroidTV.apk`.
