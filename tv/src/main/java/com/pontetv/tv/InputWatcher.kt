package com.pontetv.tv

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice

/** Detecta (inclusive ao plugar/desplugar) o teclado e o mouse USB ligados na TV. */
object InputWatcher : InputManager.InputDeviceListener {
    private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        val m = ctx.applicationContext.getSystemService(Context.INPUT_SERVICE) as InputManager
        m.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        scan()
    }

    fun scan() {
        var kb: String? = null
        var ms: String? = null
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            if (d.isVirtual) continue
            val s = d.sources
            if (d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
                (s and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD) kb = kb ?: d.name
            if ((s and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) ms = ms ?: d.name
        }
        ReceiverCore.devicesChanged(kb, ms)
    }

    override fun onInputDeviceAdded(deviceId: Int) { scan() }
    override fun onInputDeviceRemoved(deviceId: Int) { scan() }
    override fun onInputDeviceChanged(deviceId: Int) { scan() }
}
