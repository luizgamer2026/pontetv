package com.pontetv.tv

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
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
        /** true = o filtro de teclas do sistema está de fato ligado agora (diagnóstico do HUD). */
        @Volatile var filterOn = false
        /** Quanto o pior onKeyEvent demorou para responder ao sistema (o sistema só espera 500 ms por tecla) e quantas teclas passaram por aqui. */
        @Volatile var maxMs = 0
        @Volatile var calls = 0
        /** Pior tempo entre a tecla nascer no USB e o sistema chamar o serviço (inclui a espera na fila do sistema). */
        @Volatile var entryMaxMs = 0
        @Volatile var entryMaxAt = 0L
        fun resetDiag() { maxMs = 0; calls = 0; entryMaxMs = 0; entryMaxAt = 0L }

        // O sistema chama onKeyEvent numa thread do app e espera a resposta (no máximo 500 ms por tecla). Por padrão é a thread principal,
        // que também recebe todo o mouse e desenha a tela. Aqui o serviço ganha uma thread só dele, de prioridade alta.
        private val cbThread = HandlerThread("PonteTV-Key", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        private var slowAt = 0L

        // serviceInfo (ler/gravar) é chamada síncrona ao system_server: se ele estiver parado, quem chamou fica preso.
        // Por isso o filtro é ligado/desligado numa thread própria, nunca na principal (que precisa atender F8/VOLTAR/F9).
        private val ft = HandlerThread("PonteTV-Filter").apply { start() }
        private val fh = Handler(ft.looper)
        private val mainH = Handler(Looper.getMainLooper())
        @Volatile private var wanted = false
    }

    private val applyRun = Runnable { applyFilter(wanted) }

    /** AccessibilityService usa este Looper para entregar onKeyEvent: devolve o da thread própria em vez da principal. */
    override fun getMainLooper(): Looper = cbThread.looper

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        setFilter(InputBridge.capturing)
    }

    /** Liga/desliga o filtro do sistema. Só liga se o usuário não o desligou (Diag.filterEnabled: ↑/↓ do controle ou Scroll Lock). */
    fun setFilter(on: Boolean) {
        wanted = on && Diag.filterEnabled && !Diag.autoOff
        fh.removeCallbacks(applyRun)
        fh.post(applyRun)
    }

    private fun applyFilter(want: Boolean) {
        try {
            val info = serviceInfo ?: return
            val flag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            if (((info.flags and flag) != 0) == want) { filterOn = want; return }
            info.flags = if (want) info.flags or flag else info.flags and flag.inv()
            serviceInfo = info
            filterOn = want
        } catch (_: Throwable) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        filterOn = false
        return super.onUnbind(intent)
    }

    /**
     * "Bloqueio inteligente": se a TV demorar para entregar as teclas ao serviço (>= 1 s, ou duas vezes >= 300 ms em 5 s), o filtro
     * é desligado na hora e as teclas passam a seguir o caminho normal do Android, em vez de o teclado "morrer".
     */
    private fun watch(lat: Int, now: Long) {
        if (lat < 300 || Diag.autoOff) return
        if (lat >= 1000 || now - slowAt < 5000L) {
            Diag.autoOffMs = lat; Diag.autoOffAt = now; Diag.autoOff = true
            setFilter(false)
        } else slowAt = now
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val t0 = System.nanoTime()
        val now = SystemClock.uptimeMillis()
        val lat = (now - event.eventTime).toInt()
        if (filterOn) {
            if (lat > entryMaxMs) { entryMaxMs = lat; entryMaxAt = now }
            watch(lat, now)
        }
        val r = handle(event)
        val ms = ((System.nanoTime() - t0) / 1_000_000L).toInt()
        calls++
        if (ms > maxMs) maxMs = ms
        return r
    }

    private fun handle(event: KeyEvent): Boolean {
        return try {
            when {
                !Diag.filterEnabled || Diag.autoOff || !InputBridge.capturing || !HidLink.connected -> false
                !InputBridge.isKeyboard(event) ->
                    // "soltar" sem dispositivo (USB que sumiu) de uma tecla que está apertada: aceita, senão ela fica presa
                    if (event.action == KeyEvent.ACTION_UP && InputBridge.isHeld(event)) { InputBridge.key(event); true } else false
                else -> {
                    val cmd = HubCommands.forKey(event.keyCode)
                    if (cmd != 0) {
                        // teclas de comando (F8, F9/Insert, F10/Pause): o próprio serviço trata e consome, a TV nunca as vê
                        // adia para depois de responder ao sistema (ele só espera 500 ms pela resposta de cada tecla)
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            val h = HubCommands.handler
                            if (h != null) mainH.post { h(cmd) }
                        }
                        true
                    } else { InputBridge.key(event); true }
                }
            }
        } catch (t: Throwable) { false } // nunca derrubar o serviço: o Android desliga serviço que quebra
    }
}
