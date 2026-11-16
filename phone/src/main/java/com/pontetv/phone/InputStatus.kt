package com.pontetv.phone

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Observa os teclados e mouses que o Android enxerga neste celular. Quando a TV conecta como
 * teclado/mouse Bluetooth, ela aparece aqui como um teclado e um mouse normais.
 */
object InputStatus : InputManager.InputDeviceListener {
    @Volatile var kb: String? = null
    @Volatile var mouse: String? = null
    private var started = false
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun start(ctx: Context) {
        if (started) return
        started = true
        val m = ctx.applicationContext.getSystemService(Context.INPUT_SERVICE) as InputManager
        m.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        scan()
    }

    fun addListener(l: () -> Unit) { listeners.addIfAbsent(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun scan() {
        var k: String? = null
        var ms: String? = null
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            if (d.isVirtual) continue
            val s = d.sources
            if (d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
                (s and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD) k = k ?: d.name
            if ((s and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
                (s and InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE) ms = ms ?: d.name
        }
        kb = k; mouse = ms
        for (l in listeners) l()
    }

    override fun onInputDeviceAdded(deviceId: Int) { scan() }
    override fun onInputDeviceRemoved(deviceId: Int) { scan() }
    override fun onInputDeviceChanged(deviceId: Int) { scan() }
}
