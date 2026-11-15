# PonteTV

Alternativa ao Motorola Smart Connect com suporte a Android TV: espelha a tela do celular na TV
(H.264 por hardware, rede local, sem nuvem) e usa o **teclado e o mouse USB ligados na TV** para controlar o celular.

Módulos: `common` (protocolo) · `phone` (celular, Android 10+) · `tv` (receptor, Android 8+).
TV de referência (CPU-Z): TCL "Smart TV (C06_2K_GB)", MediaTek MT5867, 4×Cortex-A55 1,5 GHz, Mali-G31, **Android 11**, 1280×720, **917 MB de RAM**, ~1 GB livre.

## Arquitetura

| Tema | Decisão |
|---|---|
| Captura | `MediaProjection` → `VirtualDisplay` → `MediaCodec` (H.264 Baseline, CBR, sem B-frames). Android 14: serviço `mediaProjection` + callback registrado antes do VirtualDisplay. |
| Decodificação | `MediaCodec` H.264 → `SurfaceView`; flag de baixa latência (Android 11 na TV). |
| Transporte | TCP próprio, `TCP_NODELAY`, buffer de envio pequeno, fila curta: se a rede atrasa, descarta quadros P e pede novo quadro-chave. (UDP/RTP com FEC seria melhor em Wi-Fi ruim; não implementado.) |
| Rede | Rede local (Wi-Fi/cabo), hotspot do celular, ou **Wi-Fi Direct** (TV cria o grupo; o celular entra pelo QR). |
| Descoberta/pareamento | mDNS + QR + PIN + IP manual. Após parear, a TV devolve uma chave persistente (reconecta sem código). |
| Resolução | A TV informa o tamanho do painel (1280×720); o celular **nunca transmite acima disso**, mesmo que você escolha 1080p (poupa decoder e RAM da TV). |
| Adaptação | A cada 1 s: descartes/fila/RTT/latência alta → bitrate ×0,7 até 1,5 Mbps; depois resolução 100→75→50 %. Recupera quando estável. Rotação de tela recria o encoder. |

## Latência e sincronismo A/V (medidos, não supostos)

* Celular e TV sincronizam relógios (ping/pong estilo NTP, escolhendo a amostra de menor RTT) e os quadros/áudio levam o horário de captura.
* A TV mede **captura → saída do decoder** (vídeo) e **captura → AudioTrack** (áudio, incluindo o que já está na fila do AudioTrack) e envia ao celular a cada 1 s. O celular mostra "Captura → TV" e "áudio".
* **Sincronismo:** se o áudio está mais atrasado que o vídeo, o quadro de vídeo é agendado (até 60 ms, limite das filas do decoder); se o vídeo está atrasado, o áudio é segurado (até 150 ms). F9 na TV mostra o desvio A/V residual.
* **O que a medição NÃO inclui:** o tempo do painel/processamento de imagem da TV. Ligue o *Modo Jogo* da TV (reduz esse processamento; não afeta teclado/mouse). Para o total real, filme celular e TV com um cronômetro.

## Teclado e mouse da TV

* **Detecção:** o receptor lista o teclado e o mouse USB ligados na TV (inclusive ao plugar/desplugar) e mostra na TV e no celular (✔/✖ com o nome do dispositivo).
* A TV captura só enquanto o app está em primeiro plano. **F8** liga/desliga o modo jogo; **F9** estatísticas; **Ctrl+Shift+Q** encerra.
* **Mouse relativo (jogo):** captura de ponteiro; se a TV não liberar, entra um modo alternativo por eixos relativos do Android (`AXIS_RELATIVE_X/Y`), que continuam mesmo com o cursor na borda. Não testado nesta TV.
* No celular, **sem root/Shizuku o Android não permite criar um mouse/teclado de verdade**. Via AccessibilityService:
  * Normal: mouse = dedo (toque/arrasto/rolagem/toque longo), digitação em campos de texto, Ctrl+C/V/X/A, Esc = Voltar, Windows = Início, Alt+Tab = Recentes.
  * Jogo: WASD/setas = joystick virtual, mouse = arrasto de câmera, botões/teclas = toques calibrados (tela **Calibrar**, também na notificação).
  * Limite: o jogo recebe **toques**, não um mouse. Não precisa de teclado/mouse conectado ao celular.

"Computador" (receptor de PC) não está incluído. "Tablet"/"Outros" funcionam com qualquer Android que rode o receptor.

## Wi-Fi Direct

Na TV: botão **Wi-Fi Direct** (pede localização, exigência do Android) → o QR passa a levar SSID/senha → no celular, escanear o QR; o Android pede confirmação para entrar na rede local (sem internet). Se a TV usa Wi-Fi com um só rádio, ele pode cair enquanto o grupo existe; no cabo não. Não testado nesta TV; se falhar, use o hotspot do celular.

## Compilar e testar

Push no GitHub → Actions → artefatos `PonteTV-Celular-APK` e `PonteTV-AndroidTV-APK`.
1. Receptor na TV (QR + código; ✔ teclado/mouse). 2. Celular → TV → parear → permitir captura. 3. Ativar acessibilidade (Android 13+/14: Informações do app → ⋮ → *Permitir configurações restritas*). 4. Conferir RTT, "Captura → TV" e A/V; F9 na TV; F8 modo jogo.
