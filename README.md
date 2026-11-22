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
* **Teclas do sistema da TV:** o teclado é lido direto pela porta USB (modo USB direto, v2.12/v2.13): a TV nunca vê a tecla,
  então Esc, F1…F12, Windows etc. vão só para o celular. Não há mais bloqueio por acessibilidade (removido na v2.13).
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

## v2.13: bloqueio de teclas removido, USB direto corrigido

* **Bloqueio de teclas (serviço de acessibilidade) removido** por completo: botão "Bloquear teclas na TV", serviço, ativação por ADB
  (`tools/ativar-bloqueio.sh`), permissões `WRITE_SECURE_SETTINGS`/`RECEIVE_BOOT_COMPLETED`/`QUERY_ALL_PACKAGES`, comando Scroll Lock e ↑/↓ do controle.
  Quem impede F1…F12/Esc de agirem na TV agora é só o USB direto.
* **Leitura do USB direto corrigida:** a v2.12 lia o teclado com `UsbDeviceConnection.requestWait(timeout)`, que só existe a partir do Android 13;
  na TV (Android 11) isso daria `NoSuchMethodError` assim que o teclado fosse pego. Agora a leitura usa `bulkTransfer` (existe em qualquer Android).
* **Detecção mais tolerante:** além do teclado "boot" (HID, subclasse 1, protocolo 1), o app aceita uma interface HID sem subclasse
  se o descritor de relatório for de um teclado padrão (8 bytes, sem Report ID). Interface que não é teclado padrão é ignorada, sem mexer nela de novo.
* **USB direto vem LIGADO por padrão**, inclusive para quem tinha desligado (F11) na v2.12: a preferência salva foi renovada. F11 ou o botão do painel ainda desligam.
* **HUD que explica a falha:** `Teclado USB direto: …` agora diz o motivo (sem host USB na TV, nenhum aparelho USB visível, nenhum teclado padrão…)
  e a linha nova `USB visto pelo app: host USB do sistema: sim/NÃO · aparelhos que o app vê: N · vid:pid [classe/subclasse/protocolo …]`
  mostra exatamente o que o Android da TV deixa o app enxergar. Se aparecer `NÃO … 0 aparelhos`, esta TV não entrega USB aos apps e o USB direto não tem como funcionar nela.

## v2.12: modo USB direto do teclado (sem o filtro de acessibilidade)

**Causa confirmada em teste:** com o bloqueio de teclas (filtro de acessibilidade) ligado, o teclado morria e a entrega chegava a ~2 s (1637 ms, 1971 ms), e F8/F9
(sair, trocar Hz) também paravam de responder. Com o filtro, o sistema da TV entrega as teclas uma por vez e espera até 500 ms pela resposta do serviço
(~4 esperas = ~2 s); numa TV de ~900 MB o `system_server` atrasa essa resposta. O app não tem como acelerar isso: a saída é não usar o filtro.

**O que o modo faz:** o app lê o teclado USB direto pela porta USB (`UsbManager`, interface HID "boot": relatório de 8 bytes), pega a interface com
`claimInterface(force)` (o Android solta o driver dele) e manda o relatório como está pelo Bluetooth. O sistema da TV nunca vê as teclas: F1…F12, Windows e Esc não agem na TV
e **não há nada para bloquear**, sem filtro e sem `system_server` no caminho. Só roda enquanto o teclado controla o celular; F8/VOLTAR devolvem a interface
(`releaseInterface`) e o Android religa o teclado. O mouse continua pelo Android (captura), para a roda e os botões extras continuarem funcionando.

* **Permissão:** o Android pede "permitir acesso USB" ao abrir o app (marque "usar por padrão" para não perguntar de novo; o app tem filtro para teclados USB).
* **Reserva automática:** sem permissão, sem teclado USB ou com qualquer erro, o teclado segue funcionando pelo caminho normal do Android (bloqueio desligado).
* **F11** (ou o botão "Teclado: USB direto" do painel) liga/desliga o modo; a escolha fica salva. F8, F9/Insert e F10/Pause continuam como comandos, agora lidos do USB.
* **HUD:** `Teclado USB direto: LIGADO — lendo … (N relatórios, último há Ns)` ou o motivo de não estar ativo. A entrada/saída do teclado causada por pegar/devolver a interface não conta como "USB removido".
* (O bloqueio por acessibilidade foi removido na v2.13.)

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

## Modo BLE (experimental)

* **BLE HID (HOGP):** o botão *Modo* do painel da TV troca o Bluetooth clássico por BLE (a TV anuncia o serviço HID e o
  celular pareia como central). O clássico continua sendo o padrão. Só funciona se o Bluetooth da TV conseguir anunciar BLE;
  se não conseguir, o app avisa e fica no clássico. Ao trocar, esqueça a TV antiga no celular e use *Procurar TV* de novo.
  O intervalo de conexão BLE quem decide é o celular; a TV só pede prioridade alta (o HUD mostra o resultado).

## Compilar

Push no GitHub → Actions → Release com `PonteTV-Celular.apk` e `PonteTV-AndroidTV.apk`.
