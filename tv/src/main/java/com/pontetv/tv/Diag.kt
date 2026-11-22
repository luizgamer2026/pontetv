package com.pontetv.tv

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Diagnóstico do que acontece DENTRO da TV (o Bluetooth já é medido no HidLink).
 *
 * Uma thread própria (fora da thread principal) faz três coisas enquanto o teclado/mouse controlam o celular:
 *  1. manda um "ping" para a thread principal a cada ~40 ms e mede quanto ele demorou para ser atendido;
 *     se a thread principal ficar parada >= 250 ms, tira uma "foto" da pilha dela, que mostra ONDE ela estava presa
 *     (uma chamada ao Bluetooth, GC, desenho de tela...). Se a pilha mostrar a thread ociosa e mesmo assim houve atraso,
 *     é a TV inteira que parou (pouca memória/CPU), não o app;
 *  2. lê a RAM livre da TV;
 *  3. lê o tempo total de GC que bloqueou o app.
 */
object Diag {
    /** Bloqueio de teclas do sistema (filtro de acessibilidade) ligado pelo usuário. ↑/↓ do controle ou Scroll Lock alternam. */
    @Volatile var filterEnabled = true
    /** O app desligou o filtro sozinho porque a TV atrasou a entrega das teclas (volta a tentar quando o teclado é pego de novo). */
    @Volatile var autoOff = false
    @Volatile var autoOffMs = 0
    @Volatile var autoOffAt = 0L

    @Volatile var mainLagMax = 0
    @Volatile var mainLagMaxAt = 0L
    @Volatile var mainStalls = 0
    @Volatile var worstStack = ""

    @Volatile var availMb = -1
    @Volatile var lowMemory = false
    @Volatile var trimCount = 0
    @Volatile var trimLevel = 0
    @Volatile var gcBlockMs = 0L
    @Volatile var gcBlockCount = 0L

    private const val STALL_MS = 250L
    private val main = Handler(Looper.getMainLooper())
    private val mainThread: Thread = Looper.getMainLooper().thread
    @Volatile private var running = false
    @Volatile private var gen = 0   // evita duas threads se o controle for solto e retomado rápido
    @Volatile private var postedAt = 0L
    @Volatile private var curStack = ""
    private var am: ActivityManager? = null
    private val mi = ActivityManager.MemoryInfo()

    private val pong = Runnable {
        val p = postedAt
        if (p != 0L) {
            val now = SystemClock.uptimeMillis()
            val lag = (now - p).toInt()
            if (lag >= STALL_MS) mainStalls++
            if (lag > mainLagMax) {
                mainLagMax = lag
                mainLagMaxAt = now
                if (lag >= STALL_MS) worstStack = curStack
            }
            curStack = ""
            postedAt = 0L
        }
    }

    @Synchronized fun start(ctx: Context) {
        if (running) return
        running = true
        postedAt = 0L
        val g = ++gen
        am = ctx.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        Thread({ loop(g) }, "PonteTV-Diag").apply { isDaemon = true; start() }
    }

    @Synchronized fun stop() { running = false }

    fun reset() { mainLagMax = 0; mainLagMaxAt = 0L; mainStalls = 0; worstStack = "" }

    private fun loop(g: Int) {
        var nextSample = 0L
        var stackAt = 0L
        var released = false
        while (running && gen == g) {
            val now = SystemClock.uptimeMillis()
            val p = postedAt
            if (p == 0L) {
                postedAt = now
                stackAt = 0L
                main.post(pong)
            } else if (now - p >= STALL_MS && now - stackAt >= 400L) {
                // a thread principal não atendeu o ping: onde ela está agora?
                stackAt = now
                curStack = shortStack()
            }
            if (p != 0L && now - p >= 1500L && !released) {
                // a thread principal está parada há mais de 1,5 s: solta teclas e botões no celular para nada ficar preso (o jogo
                // correndo sozinho). O envio é feito por esta thread, que não depende da principal.
                released = true
                HidLink.send(HidDescriptor.ID_KEYBOARD, ByteArray(HidDescriptor.KEYBOARD_SIZE))
                HidLink.sendMouse(0, 0, 0, 0, 0)
            } else if (p == 0L || now - p < 1500L) released = false
            if (now >= nextSample) { nextSample = now + 2000L; sample() }
            try { Thread.sleep(40) } catch (_: InterruptedException) {}
        }
    }

    private fun shortStack(): String = try {
        mainThread.stackTrace.take(7).joinToString(" ← ") { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber }
    } catch (_: Throwable) { "" }

    private fun sample() {
        try {
            val a = am
            if (a != null) {
                a.getMemoryInfo(mi)
                availMb = (mi.availMem / 1048576L).toInt()
                lowMemory = mi.lowMemory
            }
        } catch (_: Throwable) {}
        try {
            Debug.getRuntimeStat("art.gc.blocking-gc-time")?.toLongOrNull()?.let { gcBlockMs = it }
            Debug.getRuntimeStat("art.gc.blocking-gc-count")?.toLongOrNull()?.let { gcBlockCount = it }
        } catch (_: Throwable) {}
    }
}
