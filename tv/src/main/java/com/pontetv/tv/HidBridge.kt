package com.pontetv.tv

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.view.KeyEvent
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * TV-side Bluetooth HID peripheral. The phone is the HID host and receives native
 * keyboard/mouse reports from Android's Bluetooth stack.
 */
object HidBridge {
    private const val REPORT_KEYBOARD = 1
    private const val REPORT_MOUSE = 2
    private val executor: Executor = Executors.newSingleThreadExecutor()
    @Volatile private var hid: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    @Volatile private var ready = false
    @Volatile private var listener: ((String, Boolean) -> Unit)? = null
    private var modifiers = 0
    private val pressed = LinkedHashSet<Int>()
    private var mouseButtons = 0

    private val descriptor = byteArrayOf(
        0x05,0x01,0x09,0x06,0xA1.toByte(),0x01,0x85.toByte(),0x01,0x05,0x07,
        0x19,0xE0.toByte(),0x29,0xE7.toByte(),0x15,0x00,0x25,0x01,0x75,0x01,0x95.toByte(),0x08,0x81.toByte(),0x02,
        0x95.toByte(),0x01,0x75,0x08,0x81.toByte(),0x01,0x95.toByte(),0x05,0x75,0x01,0x05,0x08,0x19,0x01,0x29,0x05,
        0x91.toByte(),0x02,0x95.toByte(),0x01,0x75,0x03,0x91.toByte(),0x01,0x95.toByte(),0x06,0x75,0x08,0x15,0x00,0x25,0x65,
        0x05,0x07,0x19,0x00,0x29,0x65,0x81.toByte(),0x00,0xC0.toByte(),
        0x05,0x01,0x09,0x02,0xA1.toByte(),0x01,0x85.toByte(),0x02,0x09,0x01,0xA1.toByte(),0x00,0x05,0x09,
        0x19,0x01,0x29,0x03,0x15,0x00,0x25,0x01,0x95.toByte(),0x03,0x75,0x01,0x81.toByte(),0x02,
        0x95.toByte(),0x01,0x75,0x05,0x81.toByte(),0x01,0x05,0x01,0x09,0x30,0x09,0x31,0x09,0x38,
        0x15,0x81.toByte(),0x25,0x7F,0x75,0x08,0x95.toByte(),0x03,0x81.toByte(),0x06,0xC0.toByte(),0xC0.toByte()
    )

    fun start(context: Context, status: (String, Boolean) -> Unit) {
        listener = status
        if (Build.VERSION.SDK_INT < 28) {
            status("Bluetooth HID Device requer Android 9 ou superior.", false); return
        }
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) { status("Esta TV não possui Bluetooth.", false); return }
            adapter.getProfileProxy(context.applicationContext, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile != BluetoothProfile.HID_DEVICE) return
                    val device = proxy as BluetoothHidDevice
                    hid = device
                    val sdp = BluetoothHidDeviceAppSdpSettings(
                        "PonteTV HID", "Wireless keyboard and mouse", "PonteTV",
                        BluetoothHidDevice.SUBCLASS1_COMBO, descriptor
                    )
                    val accepted = device.registerApp(sdp, null, null, executor, object : BluetoothHidDevice.Callback() {
                        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                            ready = registered
                            if (registered) {
                                status("HID ativo. No celular, procure e pareie “PonteTV HID” no Bluetooth.", false)
                                try { adapter.setName("PonteTV HID") } catch (_: Exception) {}
                            } else status("O sistema recusou o registro HID. Esta TV pode não oferecer o perfil HID Device.", false)
                        }
                        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                            if (state == BluetoothProfile.STATE_CONNECTED) {
                                host = device
                                status("Conectado ao celular: ${safeName(device)}", true)
                                sendKeyboard()
                                sendMouse()
                            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                                if (host == device) host = null
                                status("HID ativo. Pareie PonteTV HID nas configurações Bluetooth do celular.", false)
                            }
                        }
                    })
                    if (!accepted) status("Não foi possível solicitar o registro do perfil HID.", false)
                }
                override fun onServiceDisconnected(profile: Int) {
                    hid = null; ready = false; host = null
                    status("Serviço Bluetooth HID desconectado.", false)
                }
            }, BluetoothProfile.HID_DEVICE)
        } catch (e: Exception) {
            status("Falha ao iniciar HID: ${e.message ?: e.javaClass.simpleName}", false)
        }
    }

    private fun safeName(d: BluetoothDevice): String = try { d.name ?: "celular" } catch (_: Exception) { "celular" }

    fun sendKey(e: KeyEvent): Boolean {
        if (!ready || host == null) return false
        val usage = hidUsage(e.keyCode)
        if (usage == null) return false
        synchronized(pressed) {
            val mod = modifierBit(e.keyCode)
            if (mod != 0) {
                if (e.action == KeyEvent.ACTION_DOWN) modifiers = modifiers or mod else modifiers = modifiers and mod.inv()
            } else {
                if (e.action == KeyEvent.ACTION_DOWN) {
                    if (pressed.size < 6) pressed.add(usage)
                } else pressed.remove(usage)
            }
            sendKeyboard()
        }
        return true
    }

    private fun modifierBit(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_CTRL_LEFT -> 0x01
        KeyEvent.KEYCODE_SHIFT_LEFT -> 0x02
        KeyEvent.KEYCODE_ALT_LEFT -> 0x04
        KeyEvent.KEYCODE_META_LEFT -> 0x08
        KeyEvent.KEYCODE_CTRL_RIGHT -> 0x10
        KeyEvent.KEYCODE_SHIFT_RIGHT -> 0x20
        KeyEvent.KEYCODE_ALT_RIGHT -> 0x40
        KeyEvent.KEYCODE_META_RIGHT -> 0x80
        else -> 0
    }

    private fun hidUsage(k: Int): Int? {
        if (k in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) return 0x04 + (k - KeyEvent.KEYCODE_A)
        if (k in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) return 0x1E + (k - KeyEvent.KEYCODE_1)
        return when (k) {
            KeyEvent.KEYCODE_0 -> 0x27
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 0x28
            KeyEvent.KEYCODE_ESCAPE -> 0x29
            KeyEvent.KEYCODE_DEL -> 0x2A
            KeyEvent.KEYCODE_TAB -> 0x2B
            KeyEvent.KEYCODE_SPACE -> 0x2C
            KeyEvent.KEYCODE_MINUS -> 0x2D
            KeyEvent.KEYCODE_EQUALS -> 0x2E
            KeyEvent.KEYCODE_LEFT_BRACKET -> 0x2F
            KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x30
            KeyEvent.KEYCODE_BACKSLASH -> 0x31
            KeyEvent.KEYCODE_SEMICOLON -> 0x33
            KeyEvent.KEYCODE_APOSTROPHE -> 0x34
            KeyEvent.KEYCODE_GRAVE -> 0x35
            KeyEvent.KEYCODE_COMMA -> 0x36
            KeyEvent.KEYCODE_PERIOD -> 0x37
            KeyEvent.KEYCODE_SLASH -> 0x38
            KeyEvent.KEYCODE_CAPS_LOCK -> 0x39
            KeyEvent.KEYCODE_F1 -> 0x3A
            in KeyEvent.KEYCODE_F2..KeyEvent.KEYCODE_F12 -> 0x3B + (k - KeyEvent.KEYCODE_F2)
            KeyEvent.KEYCODE_PRINT_SCREEN -> 0x46
            KeyEvent.KEYCODE_SCROLL_LOCK -> 0x47
            KeyEvent.KEYCODE_PAUSE -> 0x48
            KeyEvent.KEYCODE_INSERT -> 0x49
            KeyEvent.KEYCODE_HOME -> 0x4A
            KeyEvent.KEYCODE_PAGE_UP -> 0x4B
            KeyEvent.KEYCODE_FORWARD_DEL -> 0x4C
            KeyEvent.KEYCODE_MOVE_END -> 0x4D
            KeyEvent.KEYCODE_PAGE_DOWN -> 0x4E
            KeyEvent.KEYCODE_DPAD_RIGHT -> 0x4F
            KeyEvent.KEYCODE_DPAD_LEFT -> 0x50
            KeyEvent.KEYCODE_DPAD_DOWN -> 0x51
            KeyEvent.KEYCODE_DPAD_UP -> 0x52
            KeyEvent.KEYCODE_NUM_LOCK -> 0x53
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 0x54
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 0x55
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 0x56
            KeyEvent.KEYCODE_NUMPAD_ADD -> 0x57
            KeyEvent.KEYCODE_NUMPAD_1 -> 0x59
            KeyEvent.KEYCODE_NUMPAD_2 -> 0x5A
            KeyEvent.KEYCODE_NUMPAD_3 -> 0x5B
            KeyEvent.KEYCODE_NUMPAD_4 -> 0x5C
            KeyEvent.KEYCODE_NUMPAD_5 -> 0x5D
            KeyEvent.KEYCODE_NUMPAD_6 -> 0x5E
            KeyEvent.KEYCODE_NUMPAD_7 -> 0x5F
            KeyEvent.KEYCODE_NUMPAD_8 -> 0x60
            KeyEvent.KEYCODE_NUMPAD_9 -> 0x61
            KeyEvent.KEYCODE_NUMPAD_0 -> 0x62
            KeyEvent.KEYCODE_NUMPAD_DOT -> 0x63
            else -> null
        }
    }

    fun sendMouseMove(dx: Int, dy: Int, wheel: Int) {
        val d = host ?: return
        val x = dx.coerceIn(-127, 127).toByte()
        val y = dy.coerceIn(-127, 127).toByte()
        val w = wheel.coerceIn(-127, 127).toByte()
        send(REPORT_MOUSE, byteArrayOf(mouseButtons.toByte(), x, y, w))
    }

    fun sendMouseButtons(buttons: Int) {
        mouseButtons = buttons and 0x07
        sendMouseMove(0, 0, 0)
    }

    private fun sendKeyboard() {
        val keys = ByteArray(6)
        synchronized(pressed) { pressed.take(6).forEachIndexed { i, v -> keys[i] = v.toByte() } }
        send(REPORT_KEYBOARD, byteArrayOf(modifiers.toByte(), 0) + keys)
    }

    private fun sendMouse() = sendMouseMove(0, 0, 0)

    private fun send(id: Int, data: ByteArray) {
        val h = hid ?: return
        val d = host ?: return
        if (!ready) return
        try { h.sendReport(d, id, data) } catch (_: Exception) {}
    }

    fun stop() {
        try { hid?.unregisterApp() } catch (_: Exception) {}
        hid = null; host = null; ready = false; listener = null
    }
}
