package com.pontetv.phone

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Tela translúcida: arraste os pontos sobre os botões do jogo (que aparece por baixo). */
class CalibrateActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var pos: MutableMap<String, Pair<Float, Float>>

    @Suppress("DEPRECATION")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = Prefs(this)
        pos = KeyMap.load(prefs)
        window.setBackgroundDrawable(ColorDrawable(0x88000000.toInt()))
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val root = FrameLayout(this)
        root.addView(MarkerView(this, pos), FrameLayout.LayoutParams(-1, -1))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        bar.addView(UI.button(this, "Salvar") { KeyMap.save(prefs, pos); InputAccessibilityService.instance?.reset(); finish() })
        bar.addView(UI.button(this, "Padrão", false) { pos.clear(); pos.putAll(KeyMap.defaults); root.getChildAt(0).invalidate() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = UI.dp(this@CalibrateActivity, 10) })
        bar.addView(UI.button(this, "Cancelar", false) { finish() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = UI.dp(this@CalibrateActivity, 10) })
        root.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = UI.dp(this@CalibrateActivity, 12) })
        setContentView(root)
    }
}

class MarkerView(c: Context, private val pos: MutableMap<String, Pair<Float, Float>>) : View(c) {
    private var drag: String? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA5B8CFF.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = 0xFFFFFFFF.toInt() }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = UI.dp(c, 12).toFloat(); textAlign = Paint.Align.CENTER; setShadowLayer(6f, 0f, 0f, 0xFF000000.toInt()) }
    private val r = UI.dp(c, 20).toFloat()

    override fun onDraw(cv: Canvas) {
        for ((k, v) in pos) {
            val x = v.first * width; val y = v.second * height
            if (k == "JOY") cv.drawCircle(x, y, 0.16f * minOf(width, height), ring)
            if (k == "LOOK") cv.drawRect(x - 0.15f * width, y - 0.15f * width, x + 0.15f * width, y + 0.15f * width, ring)
            cv.drawCircle(x, y, r, fill); cv.drawCircle(x, y, r, ring)
            cv.drawText(KeyMap.labels[k] ?: k, x, y + r + txt.textSize + 4, txt)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                var best: String? = null; var bd = r * 2.6f
                for ((k, v) in pos) {
                    val d = Math.hypot((v.first * width - e.x).toDouble(), (v.second * height - e.y).toDouble()).toFloat()
                    if (d < bd) { bd = d; best = k }
                }
                drag = best
            }
            MotionEvent.ACTION_MOVE -> drag?.let {
                pos[it] = (e.x / width).coerceIn(0f, 1f) to (e.y / height).coerceIn(0f, 1f); invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = null
        }
        return true
    }
}
