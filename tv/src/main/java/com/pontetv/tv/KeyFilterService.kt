package com.pontetv.tv

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
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
 * O filtro só fica LIGADO enquanto o teclado controla o celular. Fora disso o serviço não entra no
 * caminho das teclas da TV: nada de atraso no controle remoto e nada de travar ao ligar o serviço nas configurações.
 *
 * v2.15: o estado do filtro é CONFERIDO (lê de volta e tenta de novo se o sistema ainda não aceitou), o serviço é religado se
 * ele ficou "ligado na lista" mas não está rodando, e o bloqueio inteligente volta sozinho depois de pausar.
 */
class KeyFilterService : AccessibilityService() {
    companion object {
        @Volatile var instance: KeyFilterService? = null
        /** true = o filtro de teclas do sistema está de fato ligado agora (confirmado lendo de volta do sistema). */
        @Volatile var filterOn = false
        /** Quanto o pior onKeyEvent demorou para responder ao sistema (o sistema só espera 500 ms por tecla) e quantas teclas passaram por aqui. */
        @Volatile var maxMs = 0
        @Volatile var calls = 0
        /** Pior tempo entre a tecla nascer no USB e o sistema chamar o serviço (inclui a espera na fila do sistema). */
        @Volatile var entryMaxMs = 0
        @Volatile var entryMaxAt = 0L
        /** Teclas que o sistema só entregou ao serviço com >= 350 ms de atraso, e vezes que o sistema não aceitou o filtro de primeira. */
        @Volatile var lateKeys = 0
        @Volatile var applyFails = 0
        fun resetDiag() { maxMs = 0; calls = 0; entryMaxMs = 0; entryMaxAt = 0L; lateKeys = 0; applyFails = 0 }

        // O sistema chama onKeyEvent numa thread do app e espera a resposta (no máximo 500 ms por tecla). Por padrão é a thread principal,
        // que também recebe todo o mouse e desenha a tela. Aqui o serviço ganha uma thread só dele, de prioridade alta.
        private val cbThread = HandlerThread("PonteTV-Key", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        private var spikeAt = 0L

        // serviceInfo (ler/gravar) é chamada síncrona ao system_server: se ele estiver parado, quem chamou fica preso.
        // Por isso o filtro é ligado/desligado numa thread própria, nunca na principal (que precisa atender F8/VOLTAR/F9).
        private val ft = HandlerThread("PonteTV-Filter").apply { start() }
        private val fh = Handler(ft.looper)
        private val mainH = Handler(Looper.getMainLooper())
        @Volatile private var wanted = false
        @Volatile private var tries = 0
        @Volatile private var lastTryAt = 0L
        private var noSvcSince = 0L

        /** O que o filtro deve estar fazendo agora: ligado só enquanto controla o celular, no modo ligado e sem pausa do inteligente. */
        fun wantedNow(): Boolean = InputBridge.capturing && Diag.filterEnabled && !Diag.autoOff

        /** Reaplica o estado desejado (pegou/soltou o teclado, mudou o modo). */
        fun refresh() { instance?.setFilter(InputBridge.capturing) }

        /**
         * Chamado a cada ~1 s pela thread de diagnóstico enquanto controla o celular. Só lê variáveis, a não ser que algo esteja errado:
         *  - serviço não está rodando há >= 3 s -> religa (desliga e liga de novo na lista de acessibilidade);
         *  - o filtro está diferente do que deveria -> pede de novo.
         */
        fun sync(ctx: Context, now: Long) {
            if (!InputBridge.capturing || !Diag.filterEnabled) { noSvcSince = 0L; return }
            val s = instance
            if (s == null) {
                if (noSvcSince == 0L) noSvcSince = now
                else if (now - noSvcSince >= 3000L) A11yEnabler.kick(ctx, false)
                return
            }
            noSvcSince = 0L
            if (filterOn != wantedNow() && now - lastTryAt >= 2000L) s.setFilter(InputBridge.capturing)
        }

        /** Bloqueio inteligente: depois da pausa, religa o filtro quando nada está apertado e o teclado/mouse pararam por um instante. */
        fun maybeRearm(now: Long) {
            if (!Diag.autoOff || !Diag.smart || !InputBridge.capturing) return
            if (now - Diag.autoOffAt < Diag.cooldownMs) return
            if (now - InputBridge.lastInputAt < 800L || !InputBridge.idle()) return
            Diag.autoOff = false
            Diag.rearms++
            Diag.armedAt = now
            instance?.setFilter(true)
        }
    }

    private val applyRun = Runnable { applyFilter(wanted) }

    /** AccessibilityService usa este Looper para entregar onKeyEvent: devolve o da thread própria em vez da principal. */
    override fun getMainLooper(): Looper = cbThread.looper

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try { Diag.loadPrefs(applicationContext) } catch (_: Throwable) {}
        setFilter(InputBridge.capturing)
    }

    /** Liga/desliga o filtro do sistema. Só liga se o usuário não o desligou (modo != 0) e o inteligente não está em pausa. */
    fun setFilter(on: Boolean) {
        wanted = on && Diag.filterEnabled && !Diag.autoOff
        tries = 0
        fh.removeCallbacks(applyRun)
        fh.post(applyRun)
    }

    private fun applyFilter(want: Boolean) {
        lastTryAt = SystemClock.uptimeMillis()
        var ok = false
        try {
            val info = serviceInfo
            if (info != null) {
                val flag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                if (((info.flags and flag) != 0) != want) {
                    info.flags = if (want) info.flags or flag else info.flags and flag.inv()
                    serviceInfo = info
                    // confere: o sistema aceitou mesmo?
                    val chk = serviceInfo
                    ok = chk != null && ((chk.flags and flag) != 0) == want
                } else ok = true
                if (ok) filterOn = want
            }
        } catch (_: Throwable) {}
        if (!ok) {
            applyFails++
            // o serviço pode ainda estar conectando ou o sistema ocupado: tenta de novo (até ~4 s), em vez de ficar desligado em silêncio
            if (++tries <= 16 && wanted == want) fh.postDelayed(applyRun, 250L)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        filterOn = false
        return super.onUnbind(intent)
    }

    /**
     * Bloqueio inteligente: se a TV demorar para entregar as teclas ao serviço (>= 700 ms, ou duas vezes >= 350 ms em 6 s), o filtro
     * é pausado na hora e as teclas passam a seguir o caminho normal do Android, em vez de o teclado "morrer".
     * Ele volta sozinho (maybeRearm) quando tudo está parado; a espera dobra a cada pausa (20 s, 40 s… até 5 min).
     * No modo FORTE nada é pausado: só conta (HUD).
     */
    private fun watch(lat: Int, now: Long) {
        if (lat < 350) return
        lateKeys++
        if (!Diag.smart || Diag.autoOff) return
        if (lat >= 700 || now - spikeAt < 6000L) {
            Diag.autoOffMs = lat; Diag.autoOffAt = now; Diag.autoOff = true; Diag.autoOffCount++
            val stable = now - Diag.armedAt > 600_000L
            Diag.cooldownMs = if (stable || Diag.cooldownMs == 0L) 20_000L else minOf(Diag.cooldownMs * 2, 300_000L)
            setFilter(false)
        } else spikeAt = now
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
                        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && HubCommands.fresh(event)) {
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
