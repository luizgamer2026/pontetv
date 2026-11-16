package com.pontetv.tv

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.PointerIcon
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Hub: o teclado e o mouse USB ligados na TV viram um teclado/mouse Bluetooth do celular.
 * Enquanto "controlando", o mouse some da TV (captura de ponteiro) e as teclas não chegam à TV.
 */
class TvActivity : Activity(), HidLink.Listener, InputWatcher.Listener {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var panel: ScrollView
    private lateinit var hud: LinearLayout
    private lateinit var hudTitle: TextView
    private lateinit var hudSub: TextView
    private lateinit var statusText: TextView
    private lateinit var btText: TextView
    private lateinit var devText: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var btnControl: TextView
    private lateinit var btnEnable: TextView
    private lateinit var a11yText: TextView
    private var engaged = false
    private var manualOff = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        HidLink.init(this)
        build()
        InputWatcher.start(this)
        requestBtPermissions()
    }

    // ---------------- permissões ----------------
    private fun btPerms(): List<String> =
        if (Build.VERSION.SDK_INT >= 31)
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN)
        else emptyList()

    private fun requestBtPermissions() {
        val need = btPerms().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) HidLink.start() else requestPermissions(need.toTypedArray(), 5)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 5) {
            if (res.isNotEmpty() && res.all { it == PackageManager.PERMISSION_GRANTED }) HidLink.start()
            else Toast.makeText(this, "Sem a permissão de Bluetooth o hub não funciona.", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- interface ----------------
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

    private fun build() {
        root = FrameLayout(this).apply {
            setBackgroundColor(0xFF0A0F1F.toInt())
            isFocusable = true
            isFocusableInTouchMode = true
        }
        // mouse capturado (cursor some da TV) -> celular
        root.setOnCapturedPointerListener { _, e ->
            if (engaged) { if (InputWatcher.mouse == null) InputWatcher.noteMouse(e.device?.name); InputBridge.captured(e) }
            true
        }
        // TVs que não liberam a captura: eixos relativos
        root.setOnGenericMotionListener { _, e ->
            if (InputWatcher.mouse == null && e.isFromSource(InputDevice.SOURCE_MOUSE)) InputWatcher.noteMouse(e.device?.name)
            if (engaged && !root.hasPointerCapture() && e.isFromSource(InputDevice.SOURCE_MOUSE)) { InputBridge.softRelative(e); true } else false
        }
        root.setOnTouchListener { _, e ->
            if (InputWatcher.mouse == null && e.isFromSource(InputDevice.SOURCE_MOUSE)) InputWatcher.noteMouse(e.device?.name)
            if (engaged && !root.hasPointerCapture() && e.isFromSource(InputDevice.SOURCE_MOUSE)) { InputBridge.softRelative(e); true } else false
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(28), dp(48), dp(24))
        }
        col.addView(label("PonteTV Hub", 40f, 0xFFEAF0FF.toInt(), true))
        col.addView(label("Teclado e mouse da TV como se estivessem ligados no celular (Bluetooth)", 17f, 0xFF8FA0C8.toInt()))
        col.addView(label("1. Toque em \"Tornar visível\" aqui na TV\n2. No celular, abra o PonteTV e toque nesta TV\n3. Aceite o pareamento nos dois aparelhos", 16f, 0xFFEAF0FF.toInt()),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        statusText = label("", 20f, 0xFF22D3A6.toInt(), true)
        col.addView(statusText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        btText = label("", 14f, 0xFF8FA0C8.toInt())
        col.addView(btText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        devText = label("", 16f, 0xFFEAF0FF.toInt())
        col.addView(devText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        a11yText = label("", 14f, 0xFF8FA0C8.toInt())
        col.addView(a11yText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })

        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val visible = tvButton("Tornar visível") { makeDiscoverable() }
        val settings = tvButton("Bluetooth da TV") { openBtSettings() }
        val keys = tvButton("Bloquear teclas na TV") { openAccessibility() }
        btnEnable = tvButton("Ligar Bluetooth") { enableBt() }
        btnControl = tvButton("Controlar celular") { manualOff = false; engage() }
        btns.addView(visible)
        btns.addView(settings, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        btns.addView(keys, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        btns.addView(btnEnable, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        btns.addView(btnControl, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        col.addView(btns, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
        visible.requestFocus()

        col.addView(label("Celulares já pareados (escolha um para conectar)", 14f, 0xFF8FA0C8.toInt(), true),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(20) })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })

        panel = ScrollView(this).apply { addView(col) }
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))

        hud = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setBackgroundColor(0xFF000000.toInt())
        }
        hudTitle = label("", 34f, 0xFF22D3A6.toInt(), true).apply { gravity = Gravity.CENTER }
        hudSub = label("", 18f, 0xFFEAF0FF.toInt()).apply { gravity = Gravity.CENTER }
        hud.addView(hudTitle)
        hud.addView(hudSub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        root.addView(hud, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        immersive()
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    // ---------------- Bluetooth da TV ----------------
    private fun makeDiscoverable() {
        try {
            startActivityForResult(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300), 11
            )
        } catch (e: Exception) { openBtSettings() }
    }

    private fun openBtSettings() {
        try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        catch (e: Exception) { Toast.makeText(this, "Abra o Bluetooth nas configurações da TV.", Toast.LENGTH_LONG).show() }
    }

    private val a11yId: String by lazy { ComponentName(this, KeyFilterService::class.java).flattenToString() }

    /** Liga o serviço direto, se o app tiver recebido WRITE_SECURE_SETTINGS por ADB (não depende da tela de Acessibilidade). */
    private fun tryEnableService(): Boolean {
        if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return false
        return try {
            val cur = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            if (!cur.split(':').contains(a11yId)) {
                Settings.Secure.putString(
                    contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    if (cur.isEmpty()) a11yId else "$cur:$a11yId"
                )
            }
            Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (e: Exception) { false }
    }

    private fun openAccessibility() {
        if (tryEnableService()) {
            Toast.makeText(this, "Bloqueio de teclas ligado.", Toast.LENGTH_SHORT).show()
            main.postDelayed({ update() }, 1000)
            return
        }
        // algumas TVs não têm a tela padrão de Acessibilidade: tenta as telas que existem em cada fabricante
        val tries = listOf(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.accessibility.AccessibilityActivity"),
            Intent().setClassName("com.android.settings", "com.android.settings.accessibility.AccessibilitySettings"),
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$AccessibilitySettingsActivity"),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (i in tries) {
            try { startActivity(i); return } catch (_: Exception) {}
        }
        Toast.makeText(this, "Esta TV não abre a Acessibilidade. Ligue por ADB (comando na tela do app).", Toast.LENGTH_LONG).show()
    }

    private fun enableBt() {
        try { startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 12) }
        catch (e: Exception) { openBtSettings() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        update()
    }

    // ---------------- controle: teclado/mouse da TV -> celular ----------------
    private fun engage() {
        if (engaged || !HidLink.connected) return
        engaged = true
        InputBridge.capturing = true
        panel.visibility = View.GONE
        hud.visibility = View.VISIBLE
        root.pointerIcon = PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL)
        root.requestFocus()
        capturePointer()
        HidLink.wake()
        updateHud()
        main.removeCallbacks(hudTick)
        main.postDelayed(hudTick, 1000)
        main.postDelayed({ if (engaged) updateHud() }, 1300)
    }

    /** Captura o mouse e pede ao Android que entregue cada movimento assim que chega (sem juntar por quadro de tela). */
    private fun capturePointer() {
        root.requestPointerCapture()
        if (Build.VERSION.SDK_INT >= 31) {
            try { root.requestUnbufferedDispatch(InputDevice.SOURCE_MOUSE_RELATIVE or InputDevice.SOURCE_MOUSE) } catch (_: Exception) {}
        }
    }

    /** A cada segundo atualiza o HUD com o diagnóstico do envio Bluetooth. */
    private val hudTick = object : Runnable {
        override fun run() {
            if (!engaged) return
            updateHud()
            main.postDelayed(this, 1000)
        }
    }

    private fun release(byUser: Boolean) {
        if (!engaged) return
        engaged = false
        InputBridge.capturing = false
        main.removeCallbacks(hudTick)
        if (byUser) manualOff = true
        InputBridge.releaseAll()
        if (root.hasPointerCapture()) root.releasePointerCapture()
        root.pointerIcon = null
        hud.visibility = View.GONE
        panel.visibility = View.VISIBLE
        if (byUser) Toast.makeText(this, "Teclado e mouse soltos: voltaram a controlar a TV. F8 ou \"Controlar celular\" para voltar.", Toast.LENGTH_LONG).show()
        update()
    }

    private fun updateHud() {
        val h = HidLink.host
        hudTitle.text = "Teclado e mouse conectados ao celular"
        val soft = engaged && !root.hasPointerCapture()
        hudSub.text = "${h?.let { HidLink.nameOf(it) } ?: ""}\n\n" +
            "Teclado da TV: ${InputWatcher.kb ?: "não detectado"}\nMouse da TV: ${InputWatcher.mouse ?: "não detectado"}" +
            "\nBluetooth: ${HidLink.statRate} envios/s · envio mais lento ${HidLink.statWorstMs} ms" +
            "\nMouse: até ${HidLink.moveHz} Hz (F9 troca) · Keepalive: ${if (HidLink.keepAlive) "ligado" else "desligado"} (F10 liga/desliga)" +
            (if (soft) "\n(modo alternativo de mouse: esta TV não liberou a captura)" else "") +
            "\n\nF8 solta o teclado e o mouse para usar na TV (ou VOLTAR do controle remoto)"
    }

    override fun onHidState() { update() }

    override fun onDevices(kb: String?, mouse: String?) { update() }

    private fun update() {
        if (!::statusText.isInitialized) return
        val h = HidLink.host
        when {
            !HidLink.supported -> { statusText.text = HidLink.status; statusText.setTextColor(0xFFFF5A6A.toInt()) }
            !HidLink.hasAdapter() -> { statusText.text = "Esta TV não tem Bluetooth."; statusText.setTextColor(0xFFFF5A6A.toInt()) }
            !HidLink.btEnabled() -> { statusText.text = "Bluetooth da TV desligado."; statusText.setTextColor(0xFFFFB020.toInt()) }
            h != null -> { statusText.text = "✔ Conectado ao celular ${HidLink.nameOf(h)}"; statusText.setTextColor(0xFF22D3A6.toInt()) }
            else -> {
                statusText.text = HidLink.status.ifEmpty { "Aguardando o celular…" }
                statusText.setTextColor(0xFFFFB020.toInt())
            }
        }
        btText.text = "Nome Bluetooth desta TV: ${HidLink.adapterName()}"
        devText.text = "Teclado da TV: ${InputWatcher.kb?.let { "detectado ($it)" } ?: "não detectado"}\n" +
            "Mouse da TV: ${InputWatcher.mouse?.let { "detectado ($it)" } ?: "não detectado"}"
        a11yText.text = if (KeyFilterService.instance != null)
            "Bloqueio de teclas na TV: ligado (Esc, F1…F12 e Windows vão só para o celular)"
        else "Bloqueio de teclas na TV: desligado. Ligue em \"Bloquear teclas na TV\" para Esc, F1…F12 e Windows não afetarem a TV.\n" +
            "Se a TV não abrir a Acessibilidade, rode no ADB: adb shell pm grant com.pontetv.tv android.permission.WRITE_SECURE_SETTINGS (e toque no botão de novo)"
        btnEnable.visibility = if (HidLink.hasAdapter() && !HidLink.btEnabled()) View.VISIBLE else View.GONE
        btnControl.visibility = if (HidLink.connected && !engaged) View.VISIBLE else View.GONE

        listBox.removeAllViews()
        val bonded = HidLink.bonded()
        if (bonded.isEmpty()) listBox.addView(label("Nenhum aparelho pareado ainda.", 14f, 0xFF8FA0C8.toInt()))
        for (d in bonded.take(8)) {
            val b = tvButton("Conectar a ${HidLink.nameOf(d)}") { HidLink.connect(d) }
            listBox.addView(b, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        }

        if (HidLink.connected && !engaged && !manualOff && hasWindowFocus()) engage()
        if (!HidLink.connected && engaged) release(false)
        if (engaged) updateHud()
    }

    // ---------------- ciclo de vida ----------------
    override fun onResume() {
        super.onResume()
        HidLink.listener = this
        InputWatcher.listener = this
        if (KeyFilterService.instance == null && tryEnableService()) main.postDelayed({ update() }, 1000)
        update()
    }

    override fun onPause() {
        // fora da frente a TV não recebe mais o teclado/mouse: solta tudo no celular
        if (engaged) {
            engaged = false
            InputBridge.capturing = false
            main.removeCallbacks(hudTick)
            InputBridge.releaseAll()
            if (root.hasPointerCapture()) root.releasePointerCapture()
            root.pointerIcon = null
            hud.visibility = View.GONE
            panel.visibility = View.VISIBLE
        }
        HidLink.listener = null
        InputWatcher.listener = null
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            immersive()
            if (engaged && !root.hasPointerCapture()) capturePointer()
            else update()
        }
    }

    // ---------------- teclado ----------------
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val kb = InputBridge.isKeyboard(e)
        if (kb && e.keyCode == KeyEvent.KEYCODE_F8 && (engaged || HidLink.connected)) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                if (engaged) release(true) else { manualOff = false; engage() }
            }
            return true
        }
        if (engaged) {
            if (kb && (e.keyCode == KeyEvent.KEYCODE_F9 || e.keyCode == KeyEvent.KEYCODE_F10)) {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                    if (e.keyCode == KeyEvent.KEYCODE_F9) HidLink.cycleRate() else HidLink.toggleKeepAlive()
                    updateHud()
                }
                return true // não vai para o celular
            }
            if (kb) { InputBridge.key(e); return true }
            if (e.keyCode == KeyEvent.KEYCODE_BACK) {
                // o botão direito do mouse também vira VOLTAR no Android: só o controle remoto solta
                val fromMouse = ((e.device?.sources ?: 0) and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                if (!fromMouse && e.action == KeyEvent.ACTION_UP) release(true)
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }
}
