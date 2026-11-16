package com.pontetv.phone

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Teclado "invisível" que recebe as teclas do teclado da TV e as entrega ao campo de texto em foco
 * como eventos reais (commitText / KeyEvent / ações do editor). Bem mais confiável que escrever via
 * acessibilidade. Precisa ser ativado e escolhido uma vez em Configurações > Teclado.
 */
class PonteIme : InputMethodService() {
    companion object { @Volatile var instance: PonteIme? = null }

    override fun onCreate() { super.onCreate(); instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    override fun onCreateInputView(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF17224A.toInt())
            setPadding(UI.dp(context, 14), UI.dp(context, 8), UI.dp(context, 14), UI.dp(context, 8))
        }
        bar.addView(UI.text(this, "PonteTV · digite no teclado da TV", 13f, UI.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(TextView(this).apply {
            text = "Trocar teclado"; textSize = 13f; setTextColor(UI.ACCENT)
            setOnClickListener { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
        })
        return bar
    }

    /** Devolve true se havia um campo de texto ativo e a tecla foi tratada. */
    fun handleKey(down: Boolean, code: Int, meta: Int, uni: Int): Boolean {
        val ic = currentInputConnection ?: return false
        if (!down) return true
        val ctrl = meta and KeyEvent.META_CTRL_ON != 0
        when (code) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_CAPS_LOCK -> return true
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val ei = currentInputEditorInfo
                val action = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
                val multi = (ei?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
                if (!multi && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) ic.performEditorAction(action)
                else key(code, meta)
                return true
            }
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_TAB -> { key(code, meta); return true }
        }
        if (ctrl) {
            when (code) {
                KeyEvent.KEYCODE_A -> ic.performContextMenuAction(android.R.id.selectAll)
                KeyEvent.KEYCODE_C -> ic.performContextMenuAction(android.R.id.copy)
                KeyEvent.KEYCODE_V -> ic.performContextMenuAction(android.R.id.paste)
                KeyEvent.KEYCODE_X -> ic.performContextMenuAction(android.R.id.cut)
                KeyEvent.KEYCODE_Z -> key(code, meta)
            }
            return true
        }
        if (uni > 0) { ic.commitText(String(Character.toChars(uni)), 1); return true }
        return true
    }

    private fun key(code: Int, meta: Int) {
        val ic = currentInputConnection ?: return
        val t = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
    }
}
