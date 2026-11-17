package com.pontetv.tv

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice

/** Detecta (inclusive ao plugar/desplugar) o teclado e o mouse USB ligados na TV. */
object InputWatcher : InputManager.InputDeviceListener {
    interface Listener { fun onDevices(kb: String?, mouse: String?) }

    @Volatile var listener: Listener? = null
    @Volatile var kb: String? = null
    @Volatile var mouse: String? = null
    @Volatile private var seenMouse: String? = null
    private var started = false

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
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            if (d.isVirtual) continue
            val s = d.sources
            if (d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
                (s and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD) k = k ?: d.name
            if (isMouse(s)) ms = ms ?: d.name
        }
        kb = k; mouse = ms ?: seenMouse
        listener?.onDevices(k, ms)
    }

    override fun onInputDeviceAdded(deviceId: Int) { scan() }
    override fun onInputDeviceRemoved(deviceId: Int) { seenMouse = null; scan() }
    override fun onInputDeviceChanged(deviceId: Int) { scan() }
}
