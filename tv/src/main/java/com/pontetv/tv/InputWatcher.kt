package com.pontetv.tv

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice

/** Detecta (inclusive ao plugar/desplugar) o teclado e o mouse USB ligados na TV. */
object InputWatcher : InputManager.InputDeviceListener {
    interface Listener { fun onDevices(kb: String?, mouse: String?) }

    @Volatile var listener: Listener? = null
    @Volatile var kb: String? = null
    @Volatile var mouse: String? = null
    @Volatile private var seenMouse: String? = null
    private var started = false
    /** Quantas vezes algum dispositivo de entrada foi removido (USB soltando/reiniciando) desde o último zerar do diagnóstico. */
    @Volatile var removals = 0
    @Volatile var lastChangeAt = 0L
    /** Nomes dos dispositivos de entrada reais (não virtuais) que existem agora. */
    @Volatile var present = ""
    /** Avisa a tela quando um dispositivo real entra/sai (para o mouse ser recapturado depois de um soluço do USB). */
    @Volatile var onTopology: (() -> Unit)? = null

    private val known = HashMap<Int, String>()   // id -> "nome vid:pid" dos reais, para nomear quem sumiu (depois de sumir não dá mais para perguntar)
    private val virt = HashSet<Int>()
    private val events = ArrayList<Pair<Long, String>>()
    /** Últimos eventos de entrada (mais novo primeiro): "+nome" entrou, "−nome" saiu. */
    fun recent(): List<Pair<Long, String>> = events.toList()

    private fun tag(d: InputDevice) = d.name + " " + "%04x".format(d.vendorId) + ":" + "%04x".format(d.productId)
    private fun note(sign: String, text: String) {
        events.add(0, SystemClock.uptimeMillis() to (sign + text))
        while (events.size > 4) events.removeAt(events.size - 1)
    }

    private fun isMouse(s: Int): Boolean =
        (s and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
            (s and InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE ||
            (s and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD

    /** Alguns mouses USB não se anunciam direito: se chegou movimento de mouse, ele existe. */
    fun noteMouse(name: String?) {
        if (mouse != null) return
        seenMouse = name ?: "mouse USB"
        mouse = seenMouse
        listener?.onDevices(kb, mouse)
    }

    fun start(ctx: Context) {
        if (started) return
        started = true
        val m = ctx.applicationContext.getSystemService(Context.INPUT_SERVICE) as InputManager
        m.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        scan()
    }

    fun scan() {
        var k: String? = null
        var ms: String? = null
        val names = ArrayList<String>()
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            if (d.isVirtual) { virt.add(id); continue }
            known[id] = tag(d)
            names.add(d.name)
            val s = d.sources
            if (d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
                (s and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD) k = k ?: d.name
            if (isMouse(s)) ms = ms ?: d.name
        }
        present = names.joinToString(", ")
        kb = k; mouse = ms ?: seenMouse
        listener?.onDevices(k, ms)
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        lastChangeAt = SystemClock.uptimeMillis()
        val d = InputDevice.getDevice(deviceId)
        if (d != null && d.isVirtual) virt.add(deviceId)
        else if (d != null) { note("+", tag(d)); known[deviceId] = tag(d); onTopology?.invoke() }
        scan()
    }
    override fun onInputDeviceRemoved(deviceId: Int) {
        if (virt.remove(deviceId)) { scan(); return }   // dispositivo virtual do sistema: não é USB soltando
        removals++
        lastChangeAt = SystemClock.uptimeMillis()
        note("−", known.remove(deviceId) ?: ("dispositivo #" + deviceId))
        onTopology?.invoke()
        seenMouse = null
        // o teclado USB sumiu (cabo, hub, energia): nenhuma tecla pode ficar presa no celular
        InputBridge.releaseAll()
        scan()
    }
    // trocar LED (Caps/Num Lock) dispara isto: durante o controle não há nada novo para mostrar, e a varredura pesa na thread principal
    override fun onInputDeviceChanged(deviceId: Int) { if (!InputBridge.capturing) scan() }
}
