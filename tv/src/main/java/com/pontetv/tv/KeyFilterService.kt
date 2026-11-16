package com.pontetv.tv

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Filtro de teclas do sistema da TV. Enquanto o teclado está controlando o celular, as teclas do teclado USB
 * são consumidas aqui, antes de chegarem ao launcher ou às funções do fabricante (Esc, F1..F12, Windows…),
 * e seguem só para o celular. Sem este serviço ligado, o app ainda envia as teclas, mas a TV pode reagir a algumas.
 */
class KeyFilterService : AccessibilityService() {
    companion object {
        @Volatile var instance: KeyFilterService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!InputBridge.capturing || !HidLink.connected) return false
        if (!InputBridge.isKeyboard(event)) return false
        // F8 (soltar/controlar), F9 (ritmo do mouse) e F10 (keepalive) são tratados pelo app
        if (event.keyCode == KeyEvent.KEYCODE_F8 || event.keyCode == KeyEvent.KEYCODE_F9 ||
            event.keyCode == KeyEvent.KEYCODE_F10) return false
        InputBridge.key(event)
        return true
    }
}
