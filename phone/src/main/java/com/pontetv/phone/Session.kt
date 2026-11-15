package com.pontetv.phone

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

object Session {
    enum class State { IDLE, CONNECTING, STREAMING, RECONNECTING, ERROR }

    @Volatile var state = State.IDLE
    @Volatile var message = ""
    @Volatile var tvName = ""
    @Volatile var rtt = -1
    @Volatile var fps = 0
    @Volatile var kbps = 0
    @Volatile var targetKbps = 0
    @Volatile var width = 0
    @Volatile var height = 0
    @Volatile var drops = 0
    @Volatile var game = false
    @Volatile var e2eMs = -1      // captura -> TV (medido com relógios sincronizados)
    @Volatile var audMs = -1
    @Volatile var tvFps = 0
    @Volatile var tvKb: String? = null
    @Volatile var tvMouse: String? = null

    private val main = Handler(Looper.getMainLooper())
    private val ls = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { ls.addIfAbsent(l) }
    fun removeListener(l: () -> Unit) { ls.remove(l) }
    fun changed() { main.post { for (l in ls) l() } }

    fun reset() {
        state = State.IDLE; message = ""; tvName = ""; rtt = -1; fps = 0; kbps = 0
        targetKbps = 0; width = 0; height = 0; drops = 0; game = false
        e2eMs = -1; audMs = -1; tvFps = 0; tvKb = null; tvMouse = null
        changed()
    }

    val active: Boolean get() = state != State.IDLE && state != State.ERROR
}
