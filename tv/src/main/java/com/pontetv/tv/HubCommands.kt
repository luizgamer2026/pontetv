package com.pontetv.tv

import android.view.KeyEvent

/** Comandos do hub dados pelo teclado (e pelo controle remoto) enquanto o teclado controla o celular. */
object HubCommands {
    const val RELEASE_TOGGLE = 1 // soltar / voltar a controlar o celular
    const val RATE = 2           // troca o ritmo máximo do mouse
    const val KEEPALIVE = 3      // liga/desliga o keepalive
    const val FILTER = 4         // liga/desliga o bloqueio de teclas do sistema (filtro de acessibilidade)
    const val USB = 5            // liga/desliga o modo USB direto do teclado

    /** Quem executa os comandos (a tela da TV registra aqui). Também é chamado pelo serviço de bloqueio. */
    @Volatile var handler: ((Int) -> Unit)? = null

    /**
     * F8 solta/volta. F9 e Insert trocam o ritmo; F10 e Pause ligam/desligam o keepalive.
     * Em muitas TVs o F9 é a tecla "Apps" e abre o launcher: por isso há Insert/Pause e o controle remoto como alternativa.
     */
    fun forKey(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_F8 -> RELEASE_TOGGLE
        KeyEvent.KEYCODE_F9, KeyEvent.KEYCODE_INSERT -> RATE
        KeyEvent.KEYCODE_F10, KeyEvent.KEYCODE_BREAK -> KEEPALIVE
        KeyEvent.KEYCODE_SCROLL_LOCK -> FILTER
        KeyEvent.KEYCODE_F11 -> USB
        else -> 0
    }
}
