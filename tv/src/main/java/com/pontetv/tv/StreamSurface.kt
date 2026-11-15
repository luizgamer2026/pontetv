package com.pontetv.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.SurfaceView
import android.view.View

class StreamSurface(c: Context) : SurfaceView(c) {
    interface Callbacks {
        fun onMouse(e: MotionEvent, v: View)
        fun onCaptured(e: MotionEvent)
        fun onCapture(has: Boolean)
    }

    var cb: Callbacks? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        pointerIcon = PointerIcon.getSystemIcon(c, PointerIcon.TYPE_NULL)
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_MOUSE)) { cb?.onMouse(e, this); return true }
        return super.onGenericMotionEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_MOUSE)) { cb?.onMouse(e, this); return true }
        return super.onTouchEvent(e)
    }

    override fun onCapturedPointerEvent(e: MotionEvent): Boolean {
        cb?.onCaptured(e)
        return true
    }

    override fun onPointerCaptureChange(hasCapture: Boolean) {
        super.onPointerCaptureChange(hasCapture)
        cb?.onCapture(hasCapture)
    }
}

/** Cursor desenhado pelo app (o cursor do sistema é escondido sobre a transmissão). */
class CursorView(c: Context) : View(c) {
    private var cx = -1f
    private var cy = -1f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF000000.toInt(); strokeWidth = 3f }
    private val path = Path()

    fun setPos(x: Float, y: Float) { cx = x; cy = y; invalidate() }

    override fun onDraw(cv: Canvas) {
        if (cx < 0) return
        val s = resources.displayMetrics.density * 16f
        path.reset()
        path.moveTo(cx, cy); path.lineTo(cx, cy + s * 1.4f); path.lineTo(cx + s * 0.4f, cy + s * 1.05f)
        path.lineTo(cx + s * 0.8f, cy + s * 1.5f); path.lineTo(cx + s * 1.0f, cy + s * 1.3f)
        path.lineTo(cx + s * 0.62f, cy + s * 0.9f); path.lineTo(cx + s * 1.0f, cy + s * 0.9f); path.close()
        cv.drawPath(path, fill); cv.drawPath(path, line)
    }
}
