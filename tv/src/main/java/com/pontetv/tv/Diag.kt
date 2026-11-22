package com.pontetv.tv

import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Diagnóstico do que acontece DENTRO da TV (o Bluetooth já é medido no HidLink) + estado do bloqueio de teclas.
 *
 * Uma thread própria (fora da thread principal) faz, enquanto o teclado/mouse controlam o celular:
 *  1. manda um "ping" para a thread principal a cada ~40 ms e mede quanto ele demorou para ser atendido;
 *     se a thread principal ficar parada >= 250 ms, tira uma "foto" da pilha dela;
 *  2. lê a RAM livre da TV (direto de /proc/meminfo: sem chamar o system_server, que é justamente o que pode estar lento);
 *  3. lê o tempo total de GC que bloqueou o app;
 *  4. mantém o bloqueio de teclas saudável: religa o serviço se ele sumiu, confere o filtro e religa o bloqueio inteligente quando a TV volta ao normal.
 */
object Diag {
    /**
     * Modo do bloqueio de teclas do sistema (filtro de acessibilidade):
     *  0 = desligado · 1 = inteligente (pausa sozinho se a TV atrasar e religa quando tudo está parado) · 2 = forte (nunca pausa sozinho).
     */
    @Volatile var mode = 1
    val filterEnabled: Boolean get() = mode != 0
    val smart: Boolean get() = mode == 1

    /** O bloqueio inteligente pausou o filtro porque a TV atrasou a entrega das teclas (volta sozinho depois de [cooldownMs], com tudo parado). */
    @Volatile var autoOff = false
    @Volatile var autoOffMs = 0
    @Volatile var autoOffAt = 0L
    @Volatile var autoOffCount = 0
    @Volatile var rearms = 0
    @Volatile var cooldownMs = 0L
    /** Desde quando o filtro está armado (liga ao pegar o teclado ou ao religar sozinho). */
    @Volatile var armedAt = 0L

    fun loadPrefs(ctx: Context) { mode = TvPrefs(ctx).filterMode.coerceIn(0, 2) }

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
    private var appCtx: Context? = null

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
        appCtx = ctx.applicationContext
        Thread({ loop(g) }, "PonteTV-Diag").apply { isDaemon = true; start() }
    }

    @Synchronized fun stop() { running = false }

    fun reset() { mainLagMax = 0; mainLagMaxAt = 0L; mainStalls = 0; worstStack = "" }

    private fun loop(g: Int) {
        var nextSample = 0L
        var nextSync = 0L
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
            // bloqueio de teclas: religa o inteligente quando tudo está parado (barato: só lê variáveis)
            KeyFilterService.maybeRearm(now)
            if (now >= nextSync) {
                nextSync = now + 1000L
                appCtx?.let { KeyFilterService.sync(it, now) }
            }
            if (now >= nextSample) { nextSample = now + 2000L; sample() }
            try { Thread.sleep(40) } catch (_: InterruptedException) {}
        }
    }

    private fun shortStack(): String = try {
        mainThread.stackTrace.take(7).joinToString(" ← ") { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber }
    } catch (_: Throwable) { "" }

    private fun sample() {
        // /proc/meminfo em vez de ActivityManager.getMemoryInfo: este é uma chamada ao system_server (com a trava global dele)
        // a cada 2 s, justamente o processo que pode estar atrasando as teclas.
        try {
            val mb = readAvailMb()
            if (mb >= 0) { availMb = mb; lowMemory = mb < 100 }
        } catch (_: Throwable) {}
        try {
            Debug.getRuntimeStat("art.gc.blocking-gc-time")?.toLongOrNull()?.let { gcBlockMs = it }
            Debug.getRuntimeStat("art.gc.blocking-gc-count")?.toLongOrNull()?.let { gcBlockCount = it }
        } catch (_: Throwable) {}
    }

    private fun num(l: String): Long = l.filter { it.isDigit() }.toLongOrNull() ?: 0L

    private fun readAvailMb(): Int =
        java.io.BufferedReader(java.io.FileReader("/proc/meminfo")).use { r ->
            var avail = -1L; var free = 0L; var cached = 0L
            var line = r.readLine()
            var n = 0
            while (line != null && n++ < 16) {
                when {
                    line.startsWith("MemAvailable:") -> avail = num(line)
                    line.startsWith("MemFree:") -> free = num(line)
                    line.startsWith("Cached:") -> cached = num(line)
                }
                line = r.readLine()
            }
            val kb = if (avail >= 0) avail else free + cached
            (kb / 1024L).toInt()
        }
}
