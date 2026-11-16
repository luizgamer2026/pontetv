package com.pontetv.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.pontetv.common.Proto
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Converte teclado/mouse vindos da TV em ações no celular.
 * Limite do Android: sem root/Shizuku não existe API para criar um mouse/teclado "de verdade".
 * Aqui: modo normal = mouse como dedo (toque/arrasto/rolagem) + digitação em campos de texto;
 * modo jogo = WASD vira joystick virtual, mouse vira arrasto de câmera, teclas viram toques
 * em posições calibradas.
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
    private var lastLookMs = 0L
    private var slot = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs(this)
        map = KeyMap.load(prefs)
    }

    private var autoGame = false
    private val gameCache = HashMap<String, Boolean>()

    /** Detecta o app em primeiro plano; se for jogo, avisa a TV (ela esconde o mouse e entra no modo jogo). */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
        if (pkg == packageName || pkg == "com.android.systemui" || pkg == ime) return
        val g = isGame(pkg)
        if (g != autoGame) { autoGame = g; sendGame() }
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

    /** Comandos da barra de tarefas/lançador da TV. */
    fun postCommand(json: String) {
        main.post {
            try {
                val o = JSONObject(json)
                when (o.optString("cmd")) {
                    "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                    "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                    "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                    "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                    "launch" -> {
                        val i = packageManager.getLaunchIntentForPackage(o.optString("pkg"))
                        if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED); startActivity(i) }
                    }
                    else -> {}
                }
            } catch (_: Exception) {}
        }
    }
    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun post(data: ByteArray) { main.post { handle(data) } }

    fun reset() { main.post { setGame(false) } }

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
                    if (game) { lookDx += dx; lookDy += dy; kick() }
                }
                Proto.I_MOUSE_ABS -> {
                    val x = b.float; val y = b.float
                    if (!game) contacts.lastOrNull { it.name == "mouse" && !it.releasing }?.let {
                        it.tx = clampX(x * sw); it.ty = clampY(y * sh); kick()
                    }
                }
                Proto.I_MOUSE_BTN -> {
                    val btn = b.get().toInt(); val down = b.get().toInt() == 1
                    mouseBtn(btn, down, b.float, b.float)
                }
                Proto.I_SCROLL -> scroll(b.float, b.float, b.float, b.float)
                Proto.I_MODE -> setGame(b.get().toInt() == 1)
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
        for (c in contacts) c.releasing = true
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
        val px = clampX(x * sw); val py = clampY(y * sh)
        when (btn) {
            Proto.BTN_LEFT -> if (down) {
                contacts.add(Contact("mouse", px, py)); kick()
            } else {
                contacts.lastOrNull { it.name == "mouse" && !it.releasing }?.let { it.releasing = true; kick() }
            }
            Proto.BTN_RIGHT -> if (down) {
                // clique direito = toque longo
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
        val px = clampX((if (x in 0f..1f) x else 0.5f) * sw)
        val py = clampY((if (y in 0f..1f) y else 0.5f) * sh)
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

    private fun pumpLook(now: Long) {
        if (!game) return
        val dx = lookDx; val dy = lookDy
        lookDx = 0f; lookDy = 0f
        val look = contacts.lastOrNull { it.name == "look" && !it.releasing }
        if (dx != 0f || dy != 0f) {
            lastLookMs = now
            val (cx, cy) = pt("LOOK")
            val s = prefs.sens
            var c = look
            if (c == null) { c = Contact("look", cx, cy); contacts.add(c); lookOffX = 0f; lookOffY = 0f }
            lookOffX += dx * s; lookOffY += dy * s
            val range = 0.15f * sw
            if (abs(lookOffX) > range || abs(lookOffY) > range) {
                c.tx = clampX(cx + lookOffX.coerceIn(-range, range)); c.ty = clampY(cy + lookOffY.coerceIn(-range, range))
                c.releasing = true
            } else {
                c.tx = clampX(cx + lookOffX); c.ty = clampY(cy + lookOffY)
            }
        } else if (look != null && now - lastLookMs > 120) {
            look.releasing = true
        }
    }

    // ---------------- teclado: modo texto/desktop ----------------
    private fun textKey(down: Boolean, code: Int, meta: Int, uni: Int) {
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
            uni != 0 && !ctrl && uni and 0x80000000.toInt() == 0 -> editText { it + uni.toChar() }
        }
    }

    private fun focusedInput(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    private fun editText(fn: (String) -> String) {
        val n = focusedInput() ?: return
        val cur = if (n.isShowingHintText) "" else (n.text?.toString() ?: "")
        val nt = fn(cur)
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
        pumpLook(now)
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
