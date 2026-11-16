package com.pontetv.tv

import android.view.KeyEvent

/**
 * Converte a tecla física do teclado USB da TV no "uso HID" que um teclado de verdade enviaria.
 * Usa o código de varredura (scancode) da tecla, não a letra: assim a tecla física chega igual
 * ao celular, e quem decide ç, acentos e AltGr é o layout configurado no celular (ABNT2, por exemplo).
 */
object HidKeys {
    // Tabela do kernel Linux (hid_keyboard): uso HID (índice) -> código evdev (valor).
    private val HID_TO_EVDEV = intArrayOf(
        0, 0, 0, 0, 30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38,
        50, 49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44, 2, 3,
        4, 5, 6, 7, 8, 9, 10, 11, 28, 1, 14, 15, 57, 12, 13, 26,
        27, 43, 43, 39, 40, 41, 51, 52, 53, 58, 59, 60, 61, 62, 63, 64,
        65, 66, 67, 68, 87, 88, 99, 70, 119, 110, 102, 104, 111, 107, 109, 106,
        105, 108, 103, 69, 98, 55, 74, 78, 96, 79, 80, 81, 75, 76, 77, 71,
        72, 73, 82, 83, 86, 127, 116, 117, 183, 184, 185, 186, 187, 188, 189, 190,
        191, 192, 193, 194, 134, 138, 130, 132, 128, 129, 131, 137, 133, 135, 136, 113,
        115, 114, 0, 0, 0, 121, 0, 89, 93, 124, 92, 94, 95, 0, 0, 0,
        122, 123, 90, 91, 85, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        29, 42, 56, 125, 97, 54, 100, 126, 164, 166, 165, 163, 161, 115, 114, 113,
        150, 158, 159, 128, 136, 177, 178, 176, 142, 152, 173, 140
    )

    private val evdevToHid = IntArray(256)
    private val keycodeToHid = HashMap<Int, Int>()

    init {
        // de trás para frente: quando dois usos têm o mesmo evdev, vale o menor uso
        for (u in HID_TO_EVDEV.indices.reversed()) {
            val k = HID_TO_EVDEV[u]
            if (k in 1..255) evdevToHid[k] = u
        }
        for (i in 0..25) keycodeToHid[KeyEvent.KEYCODE_A + i] = 0x04 + i
        for (i in 1..9) keycodeToHid[KeyEvent.KEYCODE_0 + i] = 0x1D + i
        keycodeToHid[KeyEvent.KEYCODE_0] = 0x27
        for (i in 0..11) keycodeToHid[KeyEvent.KEYCODE_F1 + i] = 0x3A + i
        for (i in 1..9) keycodeToHid[KeyEvent.KEYCODE_NUMPAD_0 + i] = 0x58 + i
        keycodeToHid[KeyEvent.KEYCODE_NUMPAD_0] = 0x62
        val m = mapOf(
            KeyEvent.KEYCODE_ENTER to 0x28, KeyEvent.KEYCODE_ESCAPE to 0x29, KeyEvent.KEYCODE_DEL to 0x2A,
            KeyEvent.KEYCODE_TAB to 0x2B, KeyEvent.KEYCODE_SPACE to 0x2C, KeyEvent.KEYCODE_MINUS to 0x2D,
            KeyEvent.KEYCODE_EQUALS to 0x2E, KeyEvent.KEYCODE_LEFT_BRACKET to 0x2F,
            KeyEvent.KEYCODE_RIGHT_BRACKET to 0x30, KeyEvent.KEYCODE_BACKSLASH to 0x31,
            KeyEvent.KEYCODE_SEMICOLON to 0x33, KeyEvent.KEYCODE_APOSTROPHE to 0x34,
            KeyEvent.KEYCODE_GRAVE to 0x35, KeyEvent.KEYCODE_COMMA to 0x36, KeyEvent.KEYCODE_PERIOD to 0x37,
            KeyEvent.KEYCODE_SLASH to 0x38, KeyEvent.KEYCODE_CAPS_LOCK to 0x39,
            KeyEvent.KEYCODE_SYSRQ to 0x46, KeyEvent.KEYCODE_SCROLL_LOCK to 0x47, KeyEvent.KEYCODE_BREAK to 0x48,
            KeyEvent.KEYCODE_INSERT to 0x49, KeyEvent.KEYCODE_MOVE_HOME to 0x4A, KeyEvent.KEYCODE_PAGE_UP to 0x4B,
            KeyEvent.KEYCODE_FORWARD_DEL to 0x4C, KeyEvent.KEYCODE_MOVE_END to 0x4D,
            KeyEvent.KEYCODE_PAGE_DOWN to 0x4E, KeyEvent.KEYCODE_DPAD_RIGHT to 0x4F,
            KeyEvent.KEYCODE_DPAD_LEFT to 0x50, KeyEvent.KEYCODE_DPAD_DOWN to 0x51, KeyEvent.KEYCODE_DPAD_UP to 0x52,
            KeyEvent.KEYCODE_NUM_LOCK to 0x53, KeyEvent.KEYCODE_NUMPAD_DIVIDE to 0x54,
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY to 0x55, KeyEvent.KEYCODE_NUMPAD_SUBTRACT to 0x56,
            KeyEvent.KEYCODE_NUMPAD_ADD to 0x57, KeyEvent.KEYCODE_NUMPAD_ENTER to 0x58,
            KeyEvent.KEYCODE_NUMPAD_DOT to 0x63, KeyEvent.KEYCODE_MENU to 0x65,
            KeyEvent.KEYCODE_NUMPAD_COMMA to 0x85, KeyEvent.KEYCODE_RO to 0x87,
            KeyEvent.KEYCODE_CTRL_LEFT to 0xE0, KeyEvent.KEYCODE_SHIFT_LEFT to 0xE1,
            KeyEvent.KEYCODE_ALT_LEFT to 0xE2, KeyEvent.KEYCODE_META_LEFT to 0xE3,
            KeyEvent.KEYCODE_CTRL_RIGHT to 0xE4, KeyEvent.KEYCODE_SHIFT_RIGHT to 0xE5,
            KeyEvent.KEYCODE_ALT_RIGHT to 0xE6, KeyEvent.KEYCODE_META_RIGHT to 0xE7
        )
        keycodeToHid.putAll(m)
    }

    /** Uso HID da tecla (0 = tecla sem equivalente, ignorada). */
    fun usage(e: KeyEvent): Int {
        val sc = e.scanCode
        if (sc in 1..255) {
            val u = evdevToHid[sc]
            if (u != 0) return u
        }
        return keycodeToHid[e.keyCode] ?: 0
    }
}
