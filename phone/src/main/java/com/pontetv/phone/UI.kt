package com.pontetv.phone

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

object UI {
    val BG0 = 0xFF0A0F1F.toInt()
    val BG1 = 0xFF17224A.toInt()
    val CARD = 0xFF1B2447.toInt()
    val ACCENT = 0xFF5B8CFF.toInt()
    val GREEN = 0xFF22D3A6.toInt()
    val TEXT = 0xFFEAF0FF.toInt()
    val MUTED = 0xFF8FA0C8.toInt()
    val WARN = 0xFFFFB020.toInt()
    val BAD = 0xFFFF5A6A.toInt()

    fun dp(c: Context, v: Number): Int = (v.toFloat() * c.resources.displayMetrics.density).toInt()

    fun bg(): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG0, BG1))

    fun rounded(c: Context, color: Int, r: Number = 20): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(c, r).toFloat() }

    fun text(c: Context, s: CharSequence, sp: Float, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(c).apply {
            text = s; textSize = sp; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    fun lp(
        c: Context, w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        top: Int = 0, weight: Float = 0f
    ): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(c, top) }

    fun card(c: Context, color: Int = CARD): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(c, color, 22)
        setPadding(dp(c, 18), dp(c, 16), dp(c, 18), dp(c, 16))
    }

    @SuppressLint("ClickableViewAccessibility")
    fun pressable(v: View, onClick: () -> Unit) {
        v.isClickable = true
        v.setOnClickListener { onClick() }
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
    }

    fun button(c: Context, label: String, filled: Boolean = true, onClick: () -> Unit): TextView =
        text(c, label, 15f, if (filled) 0xFF04101F.toInt() else ACCENT, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(c, 16), dp(c, 13), dp(c, 16), dp(c, 13))
            background = if (filled) rounded(c, ACCENT, 16) else rounded(c, 0x00000000, 16).apply { setStroke(dp(c, 2), ACCENT) }
            pressable(this, onClick)
        }

    fun appear(v: View, i: Int) {
        v.alpha = 0f; v.translationY = 36f
        v.animate().alpha(1f).translationY(0f).setStartDelay(i * 70L).setDuration(380).start()
    }
}

class QualityBars(c: Context) : View(c) {
    var level = 0
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val gap = w * 0.08f; val bw = (w - gap * 3) / 4f
        val col = when (level) { 4, 3 -> UI.GREEN; 2 -> UI.WARN; else -> UI.BAD }
        for (i in 0 until 4) {
            val bh = h * (0.3f + 0.7f * i / 3f)
            p.color = if (i < level) col else 0xFF2A3560.toInt()
            val l = i * (bw + gap)
            cv.drawRoundRect(l, h - bh, l + bw, h, bw / 3, bw / 3, p)
        }
    }
}
