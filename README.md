# PonteTV — Bluetooth HID

Este projeto foi alterado para deixar de transmitir tela/áudio. A função agora é transformar a Android TV em um periférico Bluetooth HID combinado (teclado + mouse), usando o teclado e mouse USB conectados à TV como origem dos eventos.

## Como funciona

- **TV:** registra um dispositivo Bluetooth HID com `BluetoothHidDevice` (Android API 28+), publica um descritor HID composto e envia relatórios de teclado e mouse.
- **Celular:** não precisa de serviço de acessibilidade. O pareamento é feito nas configurações Bluetooth do Android. Depois de pareado, o próprio Android recebe os relatórios como teclado/mouse Bluetooth padrão.
- **Não existe transmissão de vídeo, áudio ou espelhamento.** O transporte dos eventos é Bluetooth HID, não Wi-Fi.

## Instalação e uso

1. Compile e instale o APK `tv` na Android TV.
2. Conecte teclado e mouse USB à TV.
3. Abra PonteTV HID e conceda as permissões Bluetooth solicitadas.
4. No celular, abra Configurações > Bluetooth e procure `PonteTV HID`.
5. Pareie e teste em um campo de texto e na tela inicial.

O app do celular é apenas um guia para abrir as configurações de Bluetooth. O HID é apresentado pela TV ao sistema Android do celular.

## Compatibilidade importante

A API pública `BluetoothHidDevice` existe desde Android 9 (API 28), mas o fabricante precisa incluir e habilitar o perfil HID Device na pilha Bluetooth da TV. Nem todo Android TV implementa esse papel. Se `getProfileProxy(..., HID_DEVICE)` não retornar o perfil ou `registerApp` não registrar, o aplicativo não consegue habilitar isso por conta própria sem suporte do firmware/sistema.

O app da TV precisa permanecer aberto em primeiro plano, pois o Android pode cancelar o registro HID quando o aplicativo deixa de estar em primeiro plano. O suporte de mouse depende de a Activity receber eventos de movimento enquanto está ativa. A primeira versão encaminha teclado padrão, mouse relativo, três botões e roda.

## Limitações desta primeira implementação

- O layout de teclado HID usa o mapeamento físico US (USB HID usage). Teclas alfanuméricas, navegação, F1–F12 e teclado numérico estão mapeados; layouts ABNT2, teclas multimídia e combinações específicas do fabricante ainda precisam de calibração.
- O Android TV precisa entregar eventos dos dispositivos USB à Activity enquanto o app está aberto. Alguns firmwares filtram eventos de mouse/teclado em segundo plano.
- A descoberta Bluetooth pode depender do firmware da TV e das permissões concedidas.
- Não foi possível validar a conexão física sem a TV e o celular reais. O código precisa ser testado nesses aparelhos antes de considerar a calibração concluída.

## Build

Projeto Gradle multi-módulo: `:tv` e `:phone`. Requer Android SDK 34 e JDK 17.
