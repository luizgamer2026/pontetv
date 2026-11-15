package com.pontetv.tv

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class TvActivity : Activity(), ReceiverCore.Listener, SurfaceHolder.Callback, StreamSurface.Callbacks {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var waiting: LinearLayout
    private lateinit var stream: FrameLayout
    private lateinit var box: FrameLayout
    private lateinit var surface: StreamSurface
    private lateinit var cursor: CursorView
    private lateinit var qr: ImageView
    private lateinit var pinText: TextView
    private lateinit var statusText: TextView
    private lateinit var netText: TextView
    private lateinit var devText: TextView
    private lateinit var forgetBtn: TextView
    private lateinit var directBtn: TextView
    private lateinit var statsText: TextView
    private lateinit var hintText: TextView
    private lateinit var lostText: TextView
    private var showStats = false
    private var captured = false
    private var softGame = false
    private var lastBack = 0L
    private var lastQr = ""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ReceiverCore.start(this)
        InputWatcher.start(this)

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        waiting = buildWaiting()
        root.addView(waiting, FrameLayout.LayoutParams(-1, -1))

        stream = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()); visibility = View.GONE }
        surface = StreamSurface(this).also { it.cb = this; it.holder.addCallback(this) }
        cursor = CursorView(this)
        box = FrameLayout(this)
        box.addView(surface, FrameLayout.LayoutParams(-1, -1))
        box.addView(cursor, FrameLayout.LayoutParams(-1, -1))
        stream.addView(box, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        statsText = label("", 14f, 0xFFFFFFFF.toInt()).apply {
            setBackgroundColor(0x99000000.toInt()); setPadding(dp(10), dp(6), dp(10), dp(6)); visibility = View.GONE
        }
        stream.addView(statsText, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        hintText = label("F8 modo jogo  ·  F9 estatísticas  ·  Ctrl+Shift+Q encerrar  ·  VOLTAR do controle 2x encerra", 14f, 0xFFFFFFFF.toInt()).apply {
            setBackgroundColor(0x99000000.toInt()); setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        stream.addView(hintText, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(24) })
        lostText = label("Conexão perdida — aguardando o celular reconectar…", 24f, 0xFFFFB020.toInt()).apply {
            setBackgroundColor(0xCC000000.toInt()); setPadding(dp(24), dp(14), dp(24), dp(14)); visibility = View.GONE
        }
        stream.addView(lostText, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(stream, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        immersive()
    }

    private fun label(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun tvButton(text: String, onClick: () -> Unit): TextView {
        val v = label(text, 17f, 0xFFEAF0FF.toInt(), true)
        v.gravity = Gravity.CENTER
        v.setPadding(dp(18), dp(12), dp(18), dp(12))
        fun bg(f: Boolean) = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat(); setColor(if (f) 0xFF5B8CFF.toInt() else 0xFF2A3560.toInt())
        }
        v.background = bg(false)
        v.isFocusable = true; v.isClickable = true
        v.setOnClickListener { onClick() }
        v.setOnFocusChangeListener { view, f ->
            view.background = bg(f)
            view.animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f).setDuration(120).start()
        }
        return v
    }

    private fun buildWaiting(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(48), dp(24), dp(48), dp(24))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF0A0F1F.toInt(), 0xFF17224A.toInt()))
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(label("PonteTV", 40f, 0xFFEAF0FF.toInt(), true))
        left.addView(label("Receptor de tela", 18f, 0xFF8FA0C8.toInt()))
        left.addView(label("1. No celular, abra o PonteTV e escolha TV\n2. Escaneie o QR code ou digite o código", 16f, 0xFFEAF0FF.toInt()),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(20) })
        devText = label("", 16f, 0xFFEAF0FF.toInt())
        left.addView(devText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        statusText = label("Aguardando conexão do celular…", 18f, 0xFF22D3A6.toInt(), true)
        left.addView(statusText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        netText = label("", 13f, 0xFF8FA0C8.toInt())
        left.addView(netText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val newCode = tvButton("Novo código") { ReceiverCore.rotate() }
        directBtn = tvButton("Wi-Fi Direct") { toggleDirect() }
        forgetBtn = tvButton("Esquecer celulares") { ReceiverCore.clearAuthorized() }
        btns.addView(newCode)
        btns.addView(directBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        btns.addView(forgetBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        left.addView(btns, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
        newCode.requestFocus()
        root.addView(left, LinearLayout.LayoutParams(0, -2, 1f))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val card = FrameLayout(this).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = dp(18).toFloat() }
        }
        qr = ImageView(this)
        card.addView(qr, FrameLayout.LayoutParams(dp(230), dp(230)))
        right.addView(card)
        pinText = label("", 36f, 0xFFEAF0FF.toInt(), true).apply { typeface = Typeface.MONOSPACE; letterSpacing = 0.15f }
        right.addView(pinText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        right.addView(label("código de pareamento", 13f, 0xFF8FA0C8.toInt()))
        root.addView(right, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(32) })
        return root
    }

    // ---------- Wi-Fi Direct ----------
    private fun toggleDirect() {
        if (DirectGroup.active) {
            DirectGroup.stop(); refreshPairing(); ReceiverCore.pairingChanged(); return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 7)
            return
        }
        statusText.text = "Criando grupo Wi-Fi Direct…"
        DirectGroup.start(this) {
            if (DirectGroup.error.isNotEmpty()) Toast.makeText(this, DirectGroup.error, Toast.LENGTH_LONG).show()
            refreshPairing()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 7 && res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) toggleDirect()
        else if (code == 7) Toast.makeText(this, "Wi-Fi Direct precisa da permissão de localização (exigência do Android).", Toast.LENGTH_LONG).show()
    }

    private fun refreshPairing() {
        val payload = ReceiverCore.qrPayload()
        if (payload != lastQr && ReceiverCore.port != 0) {
            lastQr = payload
            qr.setImageBitmap(QrGen.make(payload))
        }
        val p = ReceiverCore.pin
        pinText.text = if (p.length == 6) p.substring(0, 3) + " " + p.substring(3) else p
        val ips = ReceiverCore.ips()
        netText.text = when {
            DirectGroup.active -> "Wi-Fi Direct: ${DirectGroup.ssid}  ·  ${DirectGroup.ownerIp}:${ReceiverCore.port}\nEscaneie o QR no celular (ele troca de rede sozinho)"
            ips.isEmpty() -> "Sem rede local — conecte a TV ao Wi-Fi/cabo ou ligue o Wi-Fi Direct"
            else -> "Rede: ${ips.first()}:${ReceiverCore.port}  ·  ${ReceiverCore.tvName}"
        }
        forgetBtn.text = "Esquecer celulares (${ReceiverCore.authorizedCount()})"
        directBtn.text = if (DirectGroup.active) "Desligar Wi-Fi Direct" else "Wi-Fi Direct"
        onDevices()
    }

    // ---------- ReceiverCore.Listener ----------
    override fun onPairing() { refreshPairing() }

    override fun onDevices() {
        val kb = ReceiverCore.kbName; val ms = ReceiverCore.mouseName
        devText.text = "Teclado da TV: ${if (kb != null) "✔ $kb" else "✖ não detectado"}\nMouse da TV: ${if (ms != null) "✔ $ms" else "✖ não detectado"}"
    }

    override fun onVideoSize(w: Int, h: Int) { layoutBox() }

    private fun layoutBox() {
        val vw = ReceiverCore.videoW; val vh = ReceiverCore.videoH
        if (vw <= 0 || vh <= 0) return
        val rw = stream.width; val rh = stream.height
        if (rw == 0 || rh == 0) { stream.post { layoutBox() }; return }
        val s = minOf(rw / vw.toFloat(), rh / vh.toFloat())
        box.layoutParams = FrameLayout.LayoutParams((vw * s).toInt(), (vh * s).toInt(), Gravity.CENTER)
    }

    override fun onState(s: RxState, phone: String) {
        when (s) {
            RxState.WAITING -> {
                if (captured) surface.releasePointerCapture()
                if (softGame) { softGame = false }
                stream.visibility = View.GONE
                waiting.visibility = View.VISIBLE
                lostText.visibility = View.GONE
                statusText.text = "Aguardando conexão do celular…"
                refreshPairing()
            }
            RxState.STREAMING -> {
                waiting.visibility = View.GONE
                stream.visibility = View.VISIBLE
                lostText.visibility = View.GONE
                hintText.visibility = View.VISIBLE
                main.removeCallbacks(hideHint)
                main.postDelayed(hideHint, 7000)
                layoutBox()
                surface.requestFocus()
            }
            RxState.LOST -> lostText.visibility = View.VISIBLE
        }
    }

    private val hideHint = Runnable { hintText.visibility = View.GONE }

    private val statsTick = object : Runnable {
        override fun run() {
            if (showStats && ReceiverCore.state != RxState.WAITING) {
                val lat = if (ReceiverCore.latMs >= 0) "${ReceiverCore.latMs} ms" else "—"
                val mode = if (captured) "modo jogo" else if (softGame) "modo jogo (alt.)" else "modo normal"
                statsText.text = "${ReceiverCore.videoW}×${ReceiverCore.videoH} · ${ReceiverCore.fpsNow} fps · ${"%.1f".format(ReceiverCore.mbpsNow)} Mbps · " +
                    "captura→TV $lat · A/V ${AvSync.offsetMs} ms · $mode\n" +
                    "Teclado ${if (ReceiverCore.kbName != null) "✔" else "✖"}  Mouse ${if (ReceiverCore.mouseName != null) "✔" else "✖"}"
            }
            main.postDelayed(this, 1000)
        }
    }

    // ---------- ciclo de vida ----------
    override fun onResume() {
        super.onResume()
        ReceiverCore.listener = this
        refreshPairing()
        onState(ReceiverCore.state, ReceiverCore.phoneName)
        main.removeCallbacks(statsTick)
        main.postDelayed(statsTick, 1000)
    }

    override fun onPause() {
        main.removeCallbacks(statsTick)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    // ---------- Surface ----------
    override fun surfaceCreated(h: SurfaceHolder) { ReceiverCore.setSurface(h.surface) }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) { ReceiverCore.setSurface(null) }

    // ---------- mouse ----------
    override fun onMouse(e: MotionEvent, v: View) {
        if (softGame) { InputSender.softRelative(e); return }
        val (x, y) = InputSender.absolute(e, v)
        cursor.setPos(x * v.width, y * v.height)
    }

    override fun onCaptured(e: MotionEvent) { InputSender.captured(e) }

    override fun onCapture(has: Boolean) {
        captured = has
        if (has) softGame = false
        cursor.visibility = if (has || softGame) View.INVISIBLE else View.VISIBLE
        InputSender.mode(has || softGame)
    }

    /** F8: tenta a captura de ponteiro; se a TV não liberar, cai no modo alternativo por eixos relativos. */
    private fun toggleGame() {
        if (softGame) {
            softGame = false
            cursor.visibility = View.VISIBLE
            InputSender.mode(false)
            return
        }
        if (surface.hasPointerCapture()) { surface.releasePointerCapture(); return }
        surface.requestFocus()
        surface.requestPointerCapture()
        main.postDelayed({
            if (!surface.hasPointerCapture() && ReceiverCore.state != RxState.WAITING && !softGame) {
                softGame = true
                cursor.visibility = View.INVISIBLE
                InputSender.mode(true)
                Toast.makeText(this, "Captura de mouse indisponível nesta TV: usando modo jogo alternativo.", Toast.LENGTH_LONG).show()
            }
        }, 1200)
    }

    // ---------- teclado ----------
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (ReceiverCore.state == RxState.WAITING) return super.dispatchKeyEvent(e)
        val d = e.device
        val kb = d != null && (d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC ||
            e.unicodeChar != 0 || e.keyCode == KeyEvent.KEYCODE_ESCAPE)
        if (kb) {
            val down = e.action == KeyEvent.ACTION_DOWN
            if (e.keyCode == KeyEvent.KEYCODE_F8 || e.keyCode == KeyEvent.KEYCODE_F9) {
                if (down && e.repeatCount == 0) {
                    if (e.keyCode == KeyEvent.KEYCODE_F8) toggleGame()
                    else { showStats = !showStats; statsText.visibility = if (showStats) View.VISIBLE else View.GONE }
                }
                return true
            }
            if (e.keyCode == KeyEvent.KEYCODE_Q && e.isCtrlPressed && e.isShiftPressed) {
                if (down) ReceiverCore.stopStreaming()
                return true
            }
            InputSender.key(e)
            return true
        }
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) {
                val now = SystemClock.uptimeMillis()
                if (now - lastBack < 2000) ReceiverCore.stopStreaming()
                else { lastBack = now; Toast.makeText(this, "Pressione VOLTAR de novo para encerrar a transmissão", Toast.LENGTH_SHORT).show() }
            }
            return true
        }
        return super.dispatchKeyEvent(e)
    }
}
