package com.pontetv.tv

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Filtro de teclas do sistema da TV. Enquanto o teclado está controlando o celular, as teclas do teclado USB
 * são consumidas aqui, antes de chegarem ao launcher ou às funções do fabricante (Esc, F1..F12, Windows…),
 * e seguem só para o celular.
 *
 * O filtro só fica LIGADO enquanto o teclado controla o celular (setFilter). Fora disso o serviço não entra no
 * caminho das teclas da TV: nada de atraso no controle remoto e nada de travar ao ligar o serviço nas configurações.
 */
class KeyFilterService : AccessibilityService() {
    companion object {
        @Volatile var instance: KeyFilterService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        setFilter(InputBridge.capturing)
    }

    fun setFilter(on: Boolean) {
        try {
            val info = serviceInfo ?: return
            val flag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            if (((info.flags and flag) != 0) == on) return
            info.flags = if (on) info.flags or flag else info.flags and flag.inv()
            serviceInfo = info
        } catch (_: Throwable) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        return try {
            when {
                !InputBridge.capturing || !HidLink.connected -> false
                !InputBridge.isKeyboard(event) -> false
                // F8 (soltar/controlar), F9 (ritmo do mouse) e F10 (keepalive) são tratados pelo app
                event.keyCode == KeyEvent.KEYCODE_F8 || event.keyCode == KeyEvent.KEYCODE_F9 ||
                    event.keyCode == KeyEvent.KEYCODE_F10 -> false
                else -> { InputBridge.key(event); true }
            }
        } catch (t: Throwable) { false } // nunca derrubar o serviço: o Android desliga serviço que quebra
    }
}
