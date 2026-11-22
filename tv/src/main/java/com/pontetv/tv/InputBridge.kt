package com.pontetv.tv

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/** Converte o teclado e o mouse USB da TV em relatórios HID para o celular. Só roda na thread principal. */
object InputBridge {
    private val keys = ArrayList<Int>()
    private var mods = 0
    private var buttons = 0
    private var accX = 0f
    private var accY = 0f
    private var accV = 0f
    private var accH = 0f

    /** true enquanto o teclado/mouse da TV estão controlando o celular. */
    @Volatile var capturing = false

    /** Diagnóstico: pior tempo entre o evento nascer (teclado/mouse USB) e o app da TV processá-lo. Alto = a TV está sobrecarregada. */
    @Volatile var deliveryMaxMs = 0
    @Volatile var deliveryMaxAt = 0L
    @Volatile var deliveryMaxKind = ""
    /** Quantas entregas demoraram 200 ms ou mais (e quando foi a última). */
    @Volatile var spikes = 0
    @Volatile var lastSpikeAt = 0L
    /** Diagnóstico do teclado: eventos recebidos, quando/qual foi o último, teclas sem mapeamento HID e teclas que o app acha que estão apertadas. */
    @Volatile var keyEvents = 0
    @Volatile var lastKeyAt = 0L
    @Volatile var lastKeyName = ""
    @Volatile var unmapped = 0
    @Volatile var lastUnmapped = ""
    @Volatile var overflow = 0   // vezes que passou de 6 teclas ao mesmo tempo
    private val names = HashMap<Int, String>()
    fun resetDiag() {
        deliveryMaxMs = 0; deliveryMaxAt = 0L; deliveryMaxKind = ""; spikes = 0; lastSpikeAt = 0L
        keyEvents = 0; lastKeyAt = 0L; lastKeyName = ""; unmapped = 0; lastUnmapped = ""; overflow = 0
    }

    /** Teclas apertadas segundo o estado interno do app (o que ele manda ao celular agora). Só na thread principal. */
    fun heldText(): String {
        val sb = StringBuilder()
        val m = arrayOf("Ctrl", "Shift", "Alt", "Meta", "CtrlD", "ShiftD", "AltD", "MetaD")
        for (i in 0..7) if ((mods and (1 shl i)) != 0) sb.append(m[i]).append(' ')
        for (u in keys) sb.append(names[u] ?: "%02x".format(u)).append(' ')
        return if (sb.isEmpty()) "nenhuma" else sb.toString().trim()
    }
    private fun noteDelivery(eventTime: Long, kind: String) {
        val now = SystemClock.uptimeMillis()
        val d = (now - eventTime).toInt()
        if (d >= 200) { spikes++; lastSpikeAt = now }
        if (d > deliveryMaxMs) { deliveryMaxMs = d; deliveryMaxAt = now; deliveryMaxKind = kind }
    }

    /** A tecla deste evento está apertada agora? (um "soltar" que chega sem o dispositivo — USB que sumiu — precisa ser aceito.) */
    fun isHeld(e: KeyEvent): Boolean {
        val u = HidKeys.usage(e)
        if (u == 0) return false
        return if (u in 0xE0..0xE7) (mods and (1 shl (u - 0xE0))) != 0 else keys.contains(u)
    }

    fun isKeyboard(e: KeyEvent): Boolean {
        val d = e.device ?: return false
        return d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
            (d.sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
    }

    // ---------------- teclado ----------------
    fun key(e: KeyEvent) {
        noteDelivery(e.eventTime, "tecla")
        if (e.action != KeyEvent.ACTION_DOWN && e.action != KeyEvent.ACTION_UP) return
        val down = e.action == KeyEvent.ACTION_DOWN
        val u = HidKeys.usage(e)
        val nm = KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")
        keyEvents++; lastKeyAt = SystemClock.uptimeMillis(); lastKeyName = (if (down) "↓" else "↑") + nm
        // quem repete a tecla é o celular; mas uma repetição de tecla que o app não tem como apertada é um "apertar" que se perdeu: aceita
        if (down && e.repeatCount > 0 && isHeld(e)) return
        if (u == 0) { unmapped++; lastUnmapped = nm; return }
        if (u in 0xE0..0xE7) {
            val bit = 1 shl (u - 0xE0)
            mods = if (down) (mods or bit) else (mods and bit.inv())
        } else if (down) {
            names[u] = nm
            if (!keys.contains(u)) {
                // 6 teclas é o limite do relatório HID. Antes, com 6 "presas" por "soltar" perdido, o teclado parava de aceitar teclas novas:
                // agora a mais antiga sai e a nova entra (nunca fica morto).
                if (keys.size >= 6) { keys.removeAt(0); overflow++ }
                keys.add(u)
            }
        } else {
            keys.removeAll { it == u }
        }
        sendKeys()
    }

    private fun sendKeys() {
        val r = ByteArray(HidDescriptor.KEYBOARD_SIZE)
        r[0] = mods.toByte()
        for (i in keys.indices) r[2 + i] = keys[i].toByte()
        HidLink.send(HidDescriptor.ID_KEYBOARD, r)
    }

    // ---------------- mouse ----------------
    /** Ponteiro capturado: o Android entrega só deslocamentos relativos (o cursor some da TV). */
    fun captured(e: MotionEvent) {
        noteDelivery(e.eventTime, "mouse")
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                var dx = e.x
                var dy = e.y
                for (i in 0 until e.historySize) { dx += e.getHistoricalX(i); dy += e.getHistoricalY(i) }
                accX += dx; accY += dy
                flush()
            }
            MotionEvent.ACTION_SCROLL -> {
                accV += e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                accH += e.getAxisValue(MotionEvent.AXIS_HSCROLL)
                flush()
            }
        }
        buttons(e)
    }

    /** Para TVs que não liberam a captura: usa os eixos relativos que o Android informa em todo evento de mouse. */
    fun softRelative(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.historySize) {
                    accX += e.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_X, 0, i)
                    accY += e.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_Y, 0, i)
                }
                accX += e.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                accY += e.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                flush()
            }
            MotionEvent.ACTION_SCROLL -> {
                accV += e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                accH += e.getAxisValue(MotionEvent.AXIS_HSCROLL)
                flush()
            }
        }
        buttons(e)
    }

    private fun buttons(e: MotionEvent) {
        val cur = e.buttonState and 0x1F // esquerdo, direito, meio, voltar, avançar (mesma ordem do HID)
        if (cur != buttons) {
            flush()
            buttons = cur
            sendMouse(0, 0, 0, 0)
        }
    }

    /** Manda o movimento acumulado na hora; quem dita o ritmo e soma o excesso é o HidLink. */
    private fun flush() {
        var guard = 0
        while (guard++ < 64) {
            val ix = accX.toInt().coerceIn(-127, 127)
            val iy = accY.toInt().coerceIn(-127, 127)
            val iv = accV.toInt().coerceIn(-127, 127)
            val ih = accH.toInt().coerceIn(-127, 127)
            if (ix == 0 && iy == 0 && iv == 0 && ih == 0) break
            accX -= ix; accY -= iy; accV -= iv; accH -= ih
            sendMouse(ix, iy, iv, ih)
        }
    }

    private fun sendMouse(x: Int, y: Int, v: Int, h: Int) {
        HidLink.sendMouse(buttons, x, y, v, h)
    }

    /** Solta todas as teclas e botões no celular (ao parar de controlar, trocar de app ou desconectar). */
    fun releaseAll() {
        keys.clear(); mods = 0; buttons = 0
        accX = 0f; accY = 0f; accV = 0f; accH = 0f
        sendKeys()
        sendMouse(0, 0, 0, 0)
    }
}
