package com.pontetv.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.pontetv.common.Proto
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Converte teclado/mouse vindos da TV em ações no celular.
 * Sem root/Shizuku não existe API para criar um mouse/teclado "de verdade". Aqui:
 *  - Modo mouse: um cursor desenhado na tela do celular (overlay de acessibilidade) que segue o mouse
 *    da TV; clique = toque no ponto do cursor; arrastar, rolagem e toque longo (botão direito).
 *  - Modo jogo: WASD = joystick virtual, mouse = câmera (um dedo que fica apoiado e se move), teclas e
 *    botões = toques em posições calibradas. Liga sozinho quando o app em primeiro plano é um jogo.
 *  - Texto: pelo teclado PonteTV (IME) quando selecionado; senão por acessibilidade (com espelho local).
 */
class InputAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: InputAccessibilityService? = null
        private const val CHUNK = 32L
    }

    private class Contact(val name: String, var x: Float, var y: Float) {
        var tx = x
        var ty = y
        var stroke: GestureDescription.StrokeDescription? = null
        var releasing = false
        var releaseAt = 0L
    }

    private class Snap(val c: Contact, val ex: Float, val ey: Float, val rel: Boolean)

    private class PointerView(c: Context) : View(c) {
        private var px = -200f
        private var py = -200f
        private var pending = false
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF000000.toInt(); strokeWidth = 3f }
        private val path = Path()

        fun setPos(x: Float, y: Float) {
            px = x; py = y
            if (!pending) { pending = true; postOnAnimation { pending = false; invalidate() } }
        }

        override fun onDraw(cv: Canvas) {
            val s = resources.displayMetrics.density * 14f
            path.reset()
            path.moveTo(px, py); path.lineTo(px, py + s * 1.4f); path.lineTo(px + s * 0.4f, py + s * 1.05f)
            path.lineTo(px + s * 0.8f, py + s * 1.5f); path.lineTo(px + s * 1.0f, py + s * 1.3f)
            path.lineTo(px + s * 0.62f, py + s * 0.9f); path.lineTo(px + s * 1.0f, py + s * 0.9f); path.close()
            cv.drawPath(path, fill); cv.drawPath(path, line)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var map: Map<String, Pair<Float, Float>> = KeyMap.defaults
    private var sw = 1080f
    private var sh = 2400f
    private val contacts = ArrayList<Contact>()
    private var busy = false
    private var game = false
    private val keysDown = HashSet<Int>()
    private var lookDx = 0f
    private var lookDy = 0f
    private var lookOffX = 0f
    private var lookOffY = 0f
    private var slot = 0

    private var controlMode = false
    private var autoGame = false
    private var manualGame = false
    private val gameCache = HashMap<String, Boolean>()
    private var curX = 0f
    private var curY = 0f
    private var overlay: PointerView? = null

    private var mirrorText: String? = null
    private var mirrorKey: String? = null
    private var mirrorAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs(this)
        map = KeyMap.load(prefs)
        if (ControlService.running && Session.state == Session.State.CONTROL) controlOn(true)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        showOverlay(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun post(data: ByteArray) { main.post { handle(data) } }

    /** Fim de sessão: solta tudo, esconde o cursor, sai do modo controle. */
    fun reset() {
        main.post {
            controlMode = false; manualGame = false
            showOverlay(false)
            setGame(false)
        }
    }

    fun controlOn(on: Boolean) {
        main.post {
            if (!on) { controlMode = false; manualGame = false; showOverlay(false); setGame(false); return@post }
            controlMode = true; manualGame = false
            refreshScreen()
            curX = sw / 2f; curY = sh / 2f
            map = KeyMap.load(prefs)
            applyMode()
        }
    }

    fun toggleGame() { main.post { if (controlMode) { manualGame = !manualGame; applyMode() } } }

    /** Modo efetivo no controle: jogo em primeiro plano OU forçado manualmente. */
    private fun applyMode() {
        if (!controlMode) return
        val g = autoGame || manualGame
        setGame(g)
        showOverlay(!g)
    }

    @Suppress("DEPRECATION")
    private fun refreshScreen() {
        val d = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        d.getRealMetrics(m)
        sw = m.widthPixels.toFloat(); sh = m.heightPixels.toFloat()
    }

    private fun pt(name: String): Pair<Float, Float> {
        val f = map[name] ?: KeyMap.defaults.getValue(name)
        return f.first * sw to f.second * sh
    }

    // ---------------- cursor na tela do celular ----------------
    private fun showOverlay(show: Boolean) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (show && overlay == null) {
            val v = PointerView(this)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            try { wm.addView(v, lp); overlay = v; v.setPos(curX, curY) } catch (_: Exception) {}
        } else if (!show && overlay != null) {
            try { wm.removeView(overlay) } catch (_: Exception) {}
            overlay = null
        }
    }

    private fun moveCursor(dx: Float, dy: Float) {
        refreshScreen()
        curX = (curX + dx * prefs.ptrSpeed).coerceIn(0f, sw - 1f)
        curY = (curY + dy * prefs.ptrSpeed).coerceIn(0f, sh - 1f)
        overlay?.setPos(curX, curY)
        contacts.lastOrNull { it.name == "mouse" && !it.releasing }?.let { it.tx = clampX(curX); it.ty = clampY(curY); kick() }
    }

    // ---------------- detecção de jogo ----------------
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
        if (pkg == packageName || pkg == "com.android.systemui" || pkg == ime) return
        val g = isGame(pkg)
        if (g != autoGame) {
            autoGame = g
            if (controlMode) applyMode() else sendGame()
        }
    }

    private fun isGame(pkg: String): Boolean = gameCache.getOrPut(pkg) {
        try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags and 0x02000000) != 0 || prefs.extraGames().contains(pkg)
        } catch (e: Exception) { false }
    }

    private fun sendGame() {
        StreamService.current?.sendControl(Proto.T_GAME, byteArrayOf(if (autoGame) 1 else 0))
    }

    fun resendGame() { main.post { gameCache.clear(); sendGame() } }

    /** Comandos vindos da TV (barra de tarefas, F8 em modo controle). */
    fun postCommand(json: String) {
        main.post {
            try {
                val o = JSONObject(json)
                when (o.optString("cmd")) {
                    "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                    "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                    "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                    "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                    "toggle_game" -> if (controlMode) { manualGame = !manualGame; applyMode() }
                    "launch" -> {
                        val i = packageManager.getLaunchIntentForPackage(o.optString("pkg"))
                        if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED); startActivity(i) }
                    }
                    else -> {}
                }
            } catch (_: Exception) {}
        }
    }

    // ---------------- mensagens da TV ----------------
    private fun handle(data: ByteArray) {
        if (data.isEmpty()) return
        refreshScreen()
        val b = ByteBuffer.wrap(data)
        try {
            when (b.get().toInt()) {
                Proto.I_KEY -> {
                    val down = b.get().toInt() == 0
                    val code = b.int; val meta = b.int; val uni = b.int; val rep = b.int
                    if (game) gameKey(down, code, rep) else textKey(down, code, meta, uni)
                }
                Proto.I_MOUSE_REL -> {
                    val dx = b.float; val dy = b.float
                    if (game) { lookDx += dx; lookDy += dy; kick() } else moveCursor(dx, dy)
                }
                Proto.I_MOUSE_ABS -> {
                    val x = b.float; val y = b.float
                    if (!game) {
                        curX = clampX(x * sw); curY = clampY(y * sh)
                        overlay?.setPos(curX, curY)
                        contacts.lastOrNull { it.name == "mouse" && !it.releasing }?.let { it.tx = curX; it.ty = curY; kick() }
                    }
                }
                Proto.I_MOUSE_BTN -> {
                    val btn = b.get().toInt(); val down = b.get().toInt() == 1
                    mouseBtn(btn, down, b.float, b.float)
                }
                Proto.I_SCROLL -> scroll(b.float, b.float, b.float, b.float)
                Proto.I_MODE -> if (!controlMode) setGame(b.get().toInt() == 1)
            }
        } catch (_: Exception) {}
    }

    private fun setGame(g: Boolean) {
        if (g == game) return
        releaseAll()
        game = g
        Session.game = g
        if (g) { map = KeyMap.load(prefs); refreshScreen() }
        Session.changed()
    }

    private fun releaseAll() {
        for (c in contacts) {
            if (c.name == "look" && !c.releasing) { c.tx = clampX(c.tx + 48f); c.ty = clampY(c.ty) } // sair arrastando (não vira toque)
            c.releasing = true
        }
        keysDown.clear()
        lookDx = 0f; lookDy = 0f
        kick()
    }

    // ---------------- mouse ----------------
    private fun mouseBtn(btn: Int, down: Boolean, x: Float, y: Float) {
        if (game) {
            when (btn) {
                Proto.BTN_LEFT -> hold("attack", "ATTACK", down)
                Proto.BTN_RIGHT -> hold("use", "USE", down)
            }
            return
        }
        val px = if (x < 0f) curX else clampX(x * sw)
        val py = if (y < 0f) curY else clampY(y * sh)
        if (x >= 0f) { curX = px; curY = py; overlay?.setPos(curX, curY) }
        when (btn) {
            Proto.BTN_LEFT -> if (down) {
                contacts.add(Contact("mouse", px, py)); kick()
            } else {
                contacts.lastOrNull { it.name == "mouse" && !it.releasing }?.let { it.releasing = true; kick() }
            }
            Proto.BTN_RIGHT -> if (down) {
                contacts.add(Contact("rclick", px, py).also { it.releaseAt = SystemClock.uptimeMillis() + 650 }); kick()
            }
        }
    }

    private fun scroll(h: Float, v: Float, x: Float, y: Float) {
        if (game) {
            if (v != 0f) { slot = ((slot + (if (v > 0) -1 else 1)) % 9 + 9) % 9; tapHotbar(slot) }
            return
        }
        if (h == 0f && v == 0f) return
        val px = if (x in 0f..1f) clampX(x * sw) else curX
        val py = if (y in 0f..1f) clampY(y * sh) else curY
        val dist = 140f * resources.displayMetrics.density
        val c = Contact("scroll", px, py)
        c.tx = clampX(px - h * dist); c.ty = clampY(py + v * dist)
        c.releaseAt = 1
        contacts.add(c); kick()
    }

    // ---------------- teclado: modo jogo ----------------
    private fun gameKey(down: Boolean, code: Int, rep: Int) {
        val dir = when (code) {
            KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_DPAD_UP -> KeyEvent.KEYCODE_W
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_DPAD_LEFT -> KeyEvent.KEYCODE_A
            KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_DPAD_DOWN -> KeyEvent.KEYCODE_S
            KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_DPAD_RIGHT -> KeyEvent.KEYCODE_D
            else -> -1
        }
        if (dir != -1) {
            if (down) keysDown.add(dir) else keysDown.remove(dir)
            updateJoystick()
            return
        }
        if (rep > 0) return
        when (code) {
            KeyEvent.KEYCODE_SPACE -> hold("jump", "JUMP", down)
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> hold("sneak", "SNEAK", down)
            KeyEvent.KEYCODE_E -> if (down) tapPt("INV")
            KeyEvent.KEYCODE_ESCAPE -> if (down) performGlobalAction(GLOBAL_ACTION_BACK)
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> if (down) { slot = code - KeyEvent.KEYCODE_1; tapHotbar(slot) }
        }
    }

    private fun hold(name: String, ptName: String, down: Boolean) {
        if (down) {
            if (contacts.none { it.name == name && !it.releasing }) {
                val (x, y) = pt(ptName)
                contacts.add(Contact(name, x, y)); kick()
            }
        } else {
            contacts.lastOrNull { it.name == name && !it.releasing }?.let { it.releasing = true; kick() }
        }
    }

    private fun tapPt(ptName: String) {
        val (x, y) = pt(ptName)
        contacts.add(Contact("tap", x, y).also { it.releaseAt = SystemClock.uptimeMillis() + 40 }); kick()
    }

    private fun tapHotbar(i: Int) {
        val (x1, y1) = pt("HB1"); val (x9, y9) = pt("HB9")
        val t = i.coerceIn(0, 8) / 8f
        contacts.add(Contact("tap", x1 + (x9 - x1) * t, y1 + (y9 - y1) * t).also { it.releaseAt = SystemClock.uptimeMillis() + 40 })
        kick()
    }

    private fun updateJoystick() {
        var dx = 0f; var dy = 0f
        if (KeyEvent.KEYCODE_D in keysDown) dx += 1f
        if (KeyEvent.KEYCODE_A in keysDown) dx -= 1f
        if (KeyEvent.KEYCODE_S in keysDown) dy += 1f
        if (KeyEvent.KEYCODE_W in keysDown) dy -= 1f
        val j = contacts.lastOrNull { it.name == "joy" && !it.releasing }
        if (dx == 0f && dy == 0f) { j?.let { it.releasing = true; kick() }; return }
        val len = sqrt(dx * dx + dy * dy)
        val (cx, cy) = pt("JOY")
        val r = 0.16f * minOf(sw, sh)
        val tx = clampX(cx + dx / len * r); val ty = clampY(cy + dy / len * r)
        if (j == null) contacts.add(Contact("joy", tx, ty)) else { j.tx = tx; j.ty = ty }
        kick()
    }

    /**
     * Câmera: UM dedo que fica apoiado na área da câmera e se move com o mouse (como arrastar sem soltar).
     * Soltar com pouco movimento viraria um toque (era o que abria apps/ações sozinho); por isso o dedo só
     * é solto ao chegar perto do limite da área (movimento grande) ou ao sair do modo jogo.
     */
    private fun pumpLook() {
        if (!game) return
        val dx = lookDx; val dy = lookDy
        lookDx = 0f; lookDy = 0f
        if (dx == 0f && dy == 0f) return
        val (cx, cy) = pt("LOOK")
        var c = contacts.lastOrNull { it.name == "look" && !it.releasing }
        if (c == null) { c = Contact("look", cx, cy); contacts.add(c); lookOffX = 0f; lookOffY = 0f }
        lookOffX += dx * prefs.sens; lookOffY += dy * prefs.sens
        val range = 0.15f * sw
        c.tx = clampX(cx + lookOffX.coerceIn(-range, range)); c.ty = clampY(cy + lookOffY.coerceIn(-range, range))
        if (abs(lookOffX) > range || abs(lookOffY) > range) c.releasing = true // recentraliza no próximo movimento
    }

    // ---------------- teclado: modo mouse/texto ----------------
    private fun textKey(down: Boolean, code: Int, meta: Int, uni: Int) {
        // 1) Teclado PonteTV (IME): entrega como eventos reais ao campo de texto em foco
        val ime = PonteIme.instance
        if (ime != null && code != KeyEvent.KEYCODE_ESCAPE && code != KeyEvent.KEYCODE_META_LEFT && code != KeyEvent.KEYCODE_META_RIGHT &&
            !(code == KeyEvent.KEYCODE_TAB && meta and KeyEvent.META_ALT_ON != 0)) {
            if (ime.handleKey(down, code, meta, uni)) return
        }
        if (!down) return
        val ctrl = meta and KeyEvent.META_CTRL_ON != 0
        val alt = meta and KeyEvent.META_ALT_ON != 0
        when {
            code == KeyEvent.KEYCODE_ESCAPE -> performGlobalAction(GLOBAL_ACTION_BACK)
            code == KeyEvent.KEYCODE_META_LEFT || code == KeyEvent.KEYCODE_META_RIGHT -> performGlobalAction(GLOBAL_ACTION_HOME)
            code == KeyEvent.KEYCODE_TAB && alt -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            ctrl && code == KeyEvent.KEYCODE_V -> focusedInput()?.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            ctrl && code == KeyEvent.KEYCODE_C -> focusedInput()?.performAction(AccessibilityNodeInfo.ACTION_COPY)
            ctrl && code == KeyEvent.KEYCODE_X -> focusedInput()?.performAction(AccessibilityNodeInfo.ACTION_CUT)
            ctrl && code == KeyEvent.KEYCODE_A -> focusedInput()?.let { n ->
                val len = n.text?.length ?: 0
                val a = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
                }
                n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, a)
            }
            code == KeyEvent.KEYCODE_DEL -> editText { it.dropLast(1) }
            code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val n = focusedInput()
                if (n != null) {
                    if (Build.VERSION.SDK_INT >= 30 &&
                        n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return
                    editText { it + "\n" }
                }
            }
            uni != 0 && !ctrl && uni and 0x80000000.toInt() == 0 -> editText { it + String(Character.toChars(uni)) }
        }
    }

    private fun focusedInput(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    /** Escrita por acessibilidade com espelho local: evita perder/duplicar letras quando se digita rápido. */
    private fun editText(fn: (String) -> String) {
        val n = focusedInput() ?: return
        val key = "${n.windowId}:${n.viewIdResourceName}:${n.className}"
        val now = SystemClock.uptimeMillis()
        val base = if (mirrorKey == key && now - mirrorAt < 800 && mirrorText != null) mirrorText!!
        else (if (n.isShowingHintText) "" else (n.text?.toString() ?: ""))
        val nt = fn(base)
        mirrorText = nt; mirrorKey = key; mirrorAt = now
        val a = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, nt) }
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, a)
        val s = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, nt.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, nt.length)
        }
        n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, s)
    }

    // ---------------- motor de toques contínuos ----------------
    private fun clampX(v: Float) = v.coerceIn(1f, sw - 2f)
    private fun clampY(v: Float) = v.coerceIn(1f, sh - 2f)

    private fun kick() { if (!busy) main.post { tick() } }

    private fun tick() {
        if (busy) return
        val now = SystemClock.uptimeMillis()
        pumpLook()
        for (c in contacts) if (c.releaseAt != 0L && now >= c.releaseAt) c.releasing = true
        if (contacts.isEmpty()) return

        val b = GestureDescription.Builder()
        val snaps = ArrayList<Snap>()
        val maxStrokes = minOf(8, GestureDescription.getMaxStrokeCount())
        for (c in contacts) {
            if (snaps.size >= maxStrokes) break
            val p = Path()
            p.moveTo(c.x, c.y)
            if (c.tx != c.x || c.ty != c.y) p.lineTo(c.tx, c.ty)
            val cont = !c.releasing
            val s = c.stroke?.continueStroke(p, 0, CHUNK, cont)
                ?: GestureDescription.StrokeDescription(p, 0, CHUNK, cont)
            c.stroke = s
            b.addStroke(s)
            snaps.add(Snap(c, c.tx, c.ty, c.releasing))
        }
        busy = true
        val ok = try {
            dispatchGesture(b.build(), object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { done(snaps, true) }
                override fun onCancelled(g: GestureDescription?) { done(snaps, false) }
            }, main)
        } catch (e: Exception) { false }
        if (!ok) { busy = false; contacts.clear() }
    }

    private fun done(snaps: List<Snap>, ok: Boolean) {
        busy = false
        for (s in snaps) {
            if (s.rel) { contacts.remove(s.c); continue }
            if (ok) { s.c.x = s.ex; s.c.y = s.ey }
            else { s.c.stroke = null; s.c.x = s.c.tx; s.c.y = s.c.ty }
        }
        if (contacts.isNotEmpty() || (game && (lookDx != 0f || lookDy != 0f))) {
            if (ok) tick() else main.postDelayed({ tick() }, 30)
        }
    }
}
