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

## v2.15: o teclado não morre com o bloqueio ligado

**Causa achada no código (tecla presa):** com o filtro ligado, se o sistema demora mais de 500 ms para ouvir a resposta do serviço, ele desiste e manda a mesma tecla
pelo caminho normal. Ela chegava duas vezes (serviço e tela), às vezes fora de ordem: o DOWN atrasado chegava **depois** do UP que o serviço já tinha tratado, e ninguém mais soltava a tecla
(o reenvio a cada 250 ms mantinha ela presa até apertar de novo). Agora:

* **Tecla repetida ou atrasada é descartada:** o `InputBridge` guarda o último evento de cada tecla física (aparelho + scancode) e ignora o que for igual ou mais velho. O F8 também não roda duas vezes.
  HUD: `descartadas (duplicada/atrasada): N`.
* **Bloqueio com 3 modos, valendo antes e depois de conectar:** botão **Bloqueio de teclas** no painel da TV (Inteligente → Forte → Desligado). Scroll Lock liga/desliga também antes de conectar (↑ ↓ do controle, só em jogo).
  O modo fica salvo e é o que entra quando o celular conecta.
  * **Inteligente (padrão):** se a TV atrasar a entrega (>= 700 ms, ou duas vezes >= 350 ms em 6 s), o filtro **pausa** e as teclas seguem o caminho normal (o teclado não morre).
    Ele **volta sozinho** quando nada está apertado e teclado/mouse pararam por 0,8 s, depois de 20 s de espera (dobra a cada pausa: 40 s, 80 s… até 5 min; volta a 20 s se ficou 10 min sem problema).
  * **Forte:** nunca pausa sozinho (só conta os atrasos no HUD). Use para ver se, no seu caso, o atraso passa.
* **Bug "liguei antes de conectar e apareceu desligado":** (1) o painel só dizia se o *serviço* estava ligado, não se o *bloqueio* estava ligado (a escolha salva podia estar desligada por um teste antigo): agora há um botão e um texto só;
  (2) se o sistema ainda não aceitava o filtro, o app desistia em silêncio: agora confere lendo de volta e tenta de novo (até ~4 s);
  (3) um serviço que ficou "ligado na lista" mas não está rodando nunca era religado: agora o app tira e põe de novo na lista (precisa do `pm grant` por ADB já feito) quando ele some, ao abrir o app e durante o jogo;
  (4) o serviço lê o modo salvo ao conectar, mesmo que ele suba antes da tela.
* **Menos chamadas ao sistema durante o jogo:** a RAM livre vem de `/proc/meminfo` (antes era `ActivityManager.getMemoryInfo`, uma chamada ao `system_server` a cada 2 s); o painel de diagnóstico não gera eventos de acessibilidade;
  gravar o serviço nas configurações seguras roda fora da thread principal.
* **HUD:** `Bloqueio de teclas: LIGADO|PAUSADO|DESLIGADO (modo)`, `chegaram >= 350 ms`, `pausas (religou)`, `filtro recusado`.
* **USB direto:** as sobras (vid:pid guardado, textos) saíram do código.

**Como testar:** deixe no modo Inteligente e jogue. Tire a foto do HUD logo depois de uma travada: `chegada ao serviço (pior)` alto + `chegaram >= 350 ms` > 0 = o atraso é do sistema da TV (dentro do filtro);
`descartadas` > 0 = o sistema reenviou teclas e o app evitou tecla presa. Depois teste o modo Forte para comparar.

## v2.14: bloqueio inteligente (e o USB direto sai)

**USB direto removido.** Na HUD, `USB visto pelo app` listou só um pendrive e o chip de Wi-Fi/Bluetooth da TV: o teclado (1a2c:6004) e o mouse não aparecem na API USB.
O Android esconde teclado/mouse HID dos apps de propósito, então o app nunca consegue pegá-los por USB. As teclas F1…F4 etc. agem na TV porque o sistema as intercepta antes
de o app recebê-las (o `HidKeys` já converte F1…F12); o único jeito de um app pegá-las antes é o filtro de teclas de acessibilidade.

**O filtro agora é "inteligente":**
* o serviço recebe as teclas numa **thread própria** (de prioridade alta, via `getMainLooper()`), e não na thread principal que também recebe o mouse e desenha a tela;
* se a TV demorar para chamar o serviço (**>= 1 s, ou duas vezes >= 300 ms em 5 s**), o filtro se **desliga sozinho** e as teclas seguem o caminho normal do Android: o teclado não morre
  (no pior caso engasga uma vez). A cada vez que o teclado é pego de novo (soltar com F8 e pegar), o bloqueio tenta outra vez;
* `InputBridge` ficou sincronizado (o serviço e a tela mexem no estado das teclas de threads diferentes);
* o bloqueio vem **ligado** por padrão de novo (preferência nova `filterOn2`);
* HUD: `chegada ao serviço (pior): X ms` (da tecla nascer até o sistema chamar o serviço) separa "o sistema demora" de "o app demora", e aparece `DESLIGADO SOZINHO: a TV atrasou X ms`.

## v2.11: teclado/mouse "morrendo" no meio do jogo

Leitura da HUD de teste (Bluetooth saudável: envio mais lento 2 ms, fila 13 ms, 0 quedas do link, 0 foco perdido, 0 USB removido;
**mas entrega das teclas/mouse na TV de até 1637 ms**): o atraso não está no Bluetooth nem no USB, está **dentro da TV, antes de o evento
chegar ao app**. Mudanças:

* **Thread principal livre enquanto controla:** `update()` (disparado por todo evento de Bluetooth/USB) chamava o Bluetooth da TV
  (`isEnabled`, `name`, `bondedDevices`, `device.name`: chamadas síncronas ao outro processo) e refazia a lista de celulares com o painel escondido.
  Agora, com o controle ativo, nada disso roda; o nome do celular é lido uma vez, fora da thread principal. A varredura de dispositivos por
  "mudança" (Caps/Num Lock) também não roda mais durante o controle.
* **Sair (F8/VOLTAR) e trocar o Hz não dependem mais do sistema responder:** ligar/desligar o filtro (`serviceInfo`) e ler o estado do
  Bluetooth da TV (ligado, nome, pareados) eram chamadas síncronas na thread principal; se o `system_server` ou o Bluetooth da TV
  estivessem parados, F8/VOLTAR/F9 ficavam presos junto (só o botão Home, que o sistema trata, saía). Agora essas chamadas rodam em threads
  próprias e o painel usa uma cópia. Os comandos do serviço também são adiados para depois de ele responder ao sistema.
* **USB soltando e voltando:** a HUD (teste com o bloqueio desligado) mostrou entrega e thread principal saudáveis, mas `USB removido/religado: 6x`.
  O contador contava qualquer dispositivo de entrada que sumia, até virtual, e uma tecla/mouse costuma ter mais de uma interface
  (6 podem ser 3 soluços). Agora os virtuais não contam, a HUD mostra `Entrada na TV agora:` (nomes) e `Últimos:` (`+`/`−` nome vid:pid, há quantos segundos),
  e quando um dispositivo real entra/sai o app pede a captura do mouse de novo.
* **Teclado morrendo no jogo de ritmo (com o bloqueio desligado):** a HUD ganhou `Teclado — eventos recebidos (última tecla, há Ns) · apertadas agora (app) ·
  sem mapeamento · passou de 6 teclas · recusadas pelo Bluetooth`. Assim dá para separar: o app da TV deixou de receber teclas, o app achou que havia teclas
  presas, ou o Bluetooth recusou o envio. Reforço: com 6 teclas já marcadas como apertadas (um "soltar" perdido), a tecla nova antes era ignorada e o teclado
  parecia morto; agora a mais antiga sai e a nova entra. Uma repetição de tecla que o app não tinha como apertada passa a contar como "apertar".
* **Tecla presa:** se a thread principal ficar parada mais de 1,5 s, o app solta teclas e botões no celular por outra thread.
* **O bloqueio de teclas vem DESLIGADO por padrão nesta versão de teste** (↑/↓ do controle ou Scroll Lock ligam).
* **Prioridade da thread principal** sobe (como a de envio) enquanto o teclado/mouse controlam o celular.
* **Chave do bloqueio de teclas (teste A/B):** com o filtro de acessibilidade ligado, *todo* evento de teclado e mouse (o mouse a até 1000 Hz)
  passa pela thread principal do `system_server` da TV antes de chegar ao app, e cada tecla espera a resposta do serviço (limite de 500 ms).
  Numa TV de ~900 MB de RAM isso pode travar teclado e mouse juntos. **↑ / ↓ do controle remoto ou Scroll Lock** ligam/desligam o filtro ao vivo
  (a escolha fica salva). O HUD mostra `Bloqueio de teclas: LIGADO/DESLIGADO`.
* **HUD novo para achar a causa na próxima foto:**
  * `Na TV — entrega (pior): X ms (tecla|mouse, há Ns) · demoras ≥200 ms: N (última há Ns)`: agora diz *quando* e *de quem* foi a demora.
  * `App na TV — thread principal parada (pior): X ms · paradas ≥250 ms: N` + `onde ficou presa:` (pilha da thread principal no momento da parada).
    Pilha com chamada ao Bluetooth/binder = o app estava preso numa chamada; pilha ociosa (`nativePollOnce`) com atraso = a **TV inteira** parou.
  * `TV — RAM livre · pouca memória · GC que bloqueou o app`.
  * `Bloqueio de teclas: … · resposta do serviço: até X ms em N teclas`.

**Como testar:** jogue até travar, tire a foto da HUD. Depois repita com o bloqueio **DESLIGADO** (↓ no controle). Se com ele desligado não travar mais,
o filtro é a causa e a gente troca o jeito de bloquear as teclas da TV.

## v2.10: auditoria do HID

* **Thread de envio à prova de erro:** qualquer exceção inesperada no laço de envio agora é registrada (`erro:` no HUD) em vez de matar a thread
  em silêncio (o que pararia todo o envio até reiniciar o app); se a thread morrer mesmo assim, é recriada (`thread de envio reiniciada Nx`).
* **Espera ociosa com limite (0,5 s):** qualquer "acordar" perdido se cura sozinho.
* **Protocolo BOOT:** se o celular pedir `SET_PROTOCOL` em modo BOOT, os relatórios passam a ir sem ID (mouse de 3 bytes) até voltar ao modo normal;
  antes eles seriam ignorados pelo celular. O HUD mostra `PROTOCOLO BOOT` quando isso acontece.
* **Reconexão sem colisão:** a TV espera 2,5 s antes da 1ª tentativa de reconectar (4 s, 6 s…), para não disputar a conexão com o próprio celular.
* **HUD:** `App saiu da frente (menu/aviso da TV): Nx (última há Ns, ficou X ms fora)`.

## v2.9: linha do tempo das travadas

* O HUD ganhou `Há quanto tempo — envio travado (duração) · queda do link · USB mexeu · foco`. Tirando foto do HUD logo depois de uma parada do
  teclado, dá para ver qual evento aconteceu junto com ela (um `sendReport` que ficou ~2 s preso dentro do Bluetooth da TV, o link caindo,
  o USB removido ou uma janela da TV tomando o foco).
* `GET_REPORT` do celular agora é respondido com o estado real do teclado.

## v2.8: USB que some e diagnóstico da TV

* **USB removido/religado:** se o teclado ou o mouse USB sumir da TV (cabo, hub, energia), todas as teclas e botões são soltos no celular na hora,
  e um "soltar" que chegar sem o dispositivo (cancelado pelo Android) ainda é aceito. Antes a tecla podia ficar presa até o jogo acabar.
* **HUD:** nova linha `Na TV — entrega das teclas/mouse (pior): X ms · USB removido/religado: N×`. Entrega alta = a própria TV está sobrecarregada
  (por exemplo, decodificando espelhamento de tela); remoções > 0 = o USB está caindo; as duas baixas = o problema é no Bluetooth ou no celular.

## v2.7: tecla presa, comandos sem F9 e descrição na Release

* **Tecla presa:** o estado do teclado é reenviado a cada ~250 ms com o controle ativo (se um "soltar" se perder, a tecla é solta sozinha),
  e teclas/botões que o Bluetooth recusar são reenviados até 5 vezes.
* **Comandos:** F9 em muitas TVs é a tecla "Apps" e abre o launcher. Agora, com o controle ativo: **← →** do controle remoto (ou **Insert**)
  trocam o ritmo do mouse, **OK** do controle (ou **Pause**) liga/desliga o keepalive, **F8** solta/volta. Com o serviço de bloqueio
  ligado, F8/F9/F10 também são consumidos pelo próprio serviço (a TV nunca vê).
* **Release:** a descrição de cada Release no GitHub passa a ser a mensagem do(s) commit(s) do push (o título leva a 1ª linha).

## v2.6: travadas, diagnóstico e versão na tela

* O título do painel da TV mostra a versão instalada (ex.: `PonteTV Hub v2.6`), para conferir se o APK novo foi mesmo instalado.
* **Sem repetir o que ficou parado:** se o Bluetooth trava e volta, o app manda só o estado mais recente (última tecla/estado do teclado e a soma do
  movimento do mouse) em vez de repetir segundos de teclas atrasadas.
* **Reconexão rápida:** se o link cai, a TV tenta reconectar sozinha (0,3 s, 0,8 s, 1,5 s…) sem esperar o celular.
* **Foco:** se uma janela da TV (menu, aviso, fonte de entrada) tomar o foco, o app solta todas as teclas no celular (nada fica preso) e retoma o mouse ao voltar.
* **HUD de diagnóstico:** `Travadas — fila: X ms · envio: Y ms · quedas do link: N · foco perdido: Nx (última Z ms)`.
* **Bloquear teclas:** o 1º toque abre as Configurações da TV; os toques seguintes tentam abrir a Acessibilidade direto e, por fim, mostram o passo a passo por ADB.

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
