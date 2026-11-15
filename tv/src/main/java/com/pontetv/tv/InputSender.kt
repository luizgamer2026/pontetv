package com.pontetv.tv

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.pontetv.common.InputMsg
import com.pontetv.common.Proto

/** Converte eventos do teclado/mouse USB da TV em mensagens para o celular. */
object InputSender {
    private val main = Handler(Looper.getMainLooper())
    private var prevButtons = 0
    private var accDx = 0f
    private var accDy = 0f
    private var lastRel = 0L
    private var flushPending = false
    private var lastAbs = 0L
    private val flushRunnable = Runnable { flushPending = false; flushRel() }

    fun mode(game: Boolean) { ReceiverCore.sendInput(InputMsg.mode(game)) }

    fun key(e: KeyEvent) {
        flushRel()
        var u = e.unicodeChar
        if (u and KeyCharacterMap.COMBINING_ACCENT != 0) u = 0
        ReceiverCore.sendInput(InputMsg.key(e.action == KeyEvent.ACTION_DOWN, e.keyCode, e.metaState, u, e.repeatCount))
    }

    /** Modo absoluto (cursor sobre a imagem): posição normalizada 0..1. */
    fun absolute(e: MotionEvent, v: View): Pair<Float, Float> {
        val x = (e.x / v.width.toFloat()).coerceIn(0f, 1f)
        val y = (e.y / v.height.toFloat()).coerceIn(0f, 1f)
        if (e.actionMasked == MotionEvent.ACTION_SCROLL) {
            ReceiverCore.sendInput(InputMsg.scroll(e.getAxisValue(MotionEvent.AXIS_HSCROLL), e.getAxisValue(MotionEvent.AXIS_VSCROLL), x, y))
            return x to y
        }
        buttons(e, x, y)
        val now = SystemClock.uptimeMillis()
        if ((e.actionMasked == MotionEvent.ACTION_HOVER_MOVE || e.actionMasked == MotionEvent.ACTION_MOVE) && now - lastAbs >= 8) {
            lastAbs = now
            ReceiverCore.sendInput(InputMsg.abs(x, y))
        }
        return x to y
    }

    /**
     * Modo jogo alternativo, para TVs que não liberam a captura de ponteiro: usa os eixos relativos
     * (AXIS_RELATIVE_X/Y) que o Android informa em eventos de mouse mesmo com o cursor parado na borda.
     */
    fun softRelative(e: MotionEvent) {
        if (e.actionMasked == MotionEvent.ACTION_SCROLL) {
            ReceiverCore.sendInput(InputMsg.scroll(e.getAxisValue(MotionEvent.AXIS_HSCROLL), e.getAxisValue(MotionEvent.AXIS_VSCROLL), -1f, -1f))
            return
        }
        if (e.actionMasked == MotionEvent.ACTION_HOVER_MOVE || e.actionMasked == MotionEvent.ACTION_MOVE) {
            accDx += e.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
            accDy += e.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
            val now = SystemClock.uptimeMillis()
            if (now - lastRel >= 6) flushRel()
            else if (!flushPending) { flushPending = true; main.postDelayed(flushRunnable, 8) }
        }
        buttons(e, -1f, -1f)
    }

    /** Modo jogo (captura de ponteiro): deslocamentos relativos. */
    fun captured(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                var dx = e.x; var dy = e.y
                for (i in 0 until e.historySize) { dx += e.getHistoricalX(i); dy += e.getHistoricalY(i) }
                accDx += dx; accDy += dy
                val now = SystemClock.uptimeMillis()
                if (now - lastRel >= 6) flushRel()
                else if (!flushPending) { flushPending = true; main.postDelayed(flushRunnable, 8) }
            }
            MotionEvent.ACTION_SCROLL ->
                ReceiverCore.sendInput(InputMsg.scroll(e.getAxisValue(MotionEvent.AXIS_HSCROLL), e.getAxisValue(MotionEvent.AXIS_VSCROLL), -1f, -1f))
        }
        flushRel()
        buttons(e, -1f, -1f)
    }

    private fun flushRel() {
        if (accDx == 0f && accDy == 0f) return
        ReceiverCore.sendInput(InputMsg.rel(accDx, accDy))
        accDx = 0f; accDy = 0f
        lastRel = SystemClock.uptimeMillis()
    }

    private fun buttons(e: MotionEvent, x: Float, y: Float) {
        val cur = e.buttonState
        val changed = cur xor prevButtons
        if (changed != 0) {
            flushRel()
            val map = listOf(
                MotionEvent.BUTTON_PRIMARY to Proto.BTN_LEFT,
                MotionEvent.BUTTON_SECONDARY to Proto.BTN_RIGHT,
                MotionEvent.BUTTON_TERTIARY to Proto.BTN_MIDDLE
            )
            for ((bit, id) in map) if (changed and bit != 0) {
                ReceiverCore.sendInput(InputMsg.button(id, cur and bit != 0, x, y))
            }
        }
        prevButtons = cur
    }
}
