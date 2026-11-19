package com.pontetv.tv

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
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
    private lateinit var hudStats: TextView
    private lateinit var statusText: TextView
    private lateinit var btText: TextView
    private lateinit var devText: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var btnControl: TextView
    private lateinit var btnEnable: TextView
    private lateinit var btnMode: TextView
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
        val btns2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnMode = tvButton("Modo: Bluetooth clássico") { toggleMode() }
        btns2.addView(btnMode)
        col.addView(btns2, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
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
        hudStats = label("", 16f, 0xFF8FA3C8.toInt()).apply { gravity = Gravity.CENTER }
        hud.addView(hudStats, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
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

    /** Liga o serviço direto se o app tiver WRITE_SECURE_SETTINGS (dado uma vez por ADB). */
    private fun tryEnableService(): Boolean = A11yEnabler.enable(this)

    private var a11yTry = 0

    private fun isStub(n: String) = n.contains("Stub", true)

    /** Telas de Acessibilidade que ESTA TV tem de verdade. Os "Stubs" (que só mostram o erro "nenhuma ação pode ser executada") ficam de fora. */
    private fun a11yCandidates(): List<Intent> {
        val out = LinkedHashMap<String, Intent>()
        fun add(pkg: String, cls: String) {
            if (isStub(cls)) return
            if (!out.containsKey("$pkg/$cls")) out["$pkg/$cls"] = Intent().setClassName(pkg, cls)
        }
        try {
            for (ri in packageManager.queryIntentActivities(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), 0))
                add(ri.activityInfo.packageName, ri.activityInfo.name)
        } catch (_: Exception) {}
        add("com.android.tv.settings", "com.android.tv.settings.accessibility.AccessibilityActivity")
        add("com.android.settings", "com.android.settings.accessibility.AccessibilitySettings")
        add("com.android.settings", "com.android.settings.Settings\$AccessibilitySettingsActivity")
        try {
            for (ai in packageManager.getInstalledApplications(0)) {
                if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || !ai.packageName.contains("settings", true)) continue
                val pi = try { packageManager.getPackageInfo(ai.packageName, PackageManager.GET_ACTIVITIES) } catch (_: Exception) { continue }
                val acts = pi.activities ?: continue
                for (a in acts) if (a.exported && a.name.contains("accessib", true)) add(a.packageName, a.name)
            }
        } catch (_: Exception) {}
        fun score(i: Intent): Int {
            val n = i.component?.className ?: ""
            return when {
                n.contains("AccessibilitySettings") -> 0
                n.contains("AccessibilityActivity") -> 1
                else -> 2
            }
        }
        return out.values.filter { it.resolveActivity(packageManager) != null }.sortedBy { score(it) }
    }

    /** Lista (para diagnóstico) o que a TV tem de tela com "Accessib" ou "Stub" nos apps de configurações. */
    private fun a11yDump(): String {
        val lines = ArrayList<String>()
        try {
            for (ai in packageManager.getInstalledApplications(0)) {
                if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || !ai.packageName.contains("settings", true)) continue
                val pi = try { packageManager.getPackageInfo(ai.packageName, PackageManager.GET_ACTIVITIES) } catch (_: Exception) { continue }
                for (a in pi.activities ?: continue) {
                    if (a.name.contains("accessib", true) || isStub(a.name))
                        lines.add("${ai.packageName.substringAfterLast('.')}: ${a.name.substringAfterLast('.')}${if (a.exported) "" else " (interna)"}")
                }
            }
        } catch (_: Exception) {}
        return if (lines.isEmpty()) "nenhuma" else lines.take(12).joinToString("\n")
    }

    /** Abre o app de Configurações da TV (fallback quando nenhuma tela de Acessibilidade abre direto). */
    private fun openSettingsMain(): Boolean {
        val intents = ArrayList<Intent>()
        for (pkg in listOf("com.android.tv.settings", "com.android.settings"))
            packageManager.getLaunchIntentForPackage(pkg)?.let { intents.add(it) }
        try {
            val q = Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_SETTINGS")
            for (ri in packageManager.queryIntentActivities(q, 0))
                intents.add(Intent().setClassName(ri.activityInfo.packageName, ri.activityInfo.name))
        } catch (_: Exception) {}
        for (i in intents) {
            if (isStub(i.component?.className ?: "")) continue
            try { startActivity(i); return true } catch (_: Exception) {}
        }
        return false
    }

    private fun openAccessibility() {
        if (tryEnableService()) {
            Toast.makeText(this, "Bloqueio de teclas ligado.", Toast.LENGTH_SHORT).show()
            main.postDelayed({ update() }, 1000)
            return
        }
        val list = a11yCandidates()
        if (a11yTry < list.size) {
            for (k in a11yTry until list.size) {
                try {
                    startActivity(list[k])
                    a11yTry = k + 1
                    Toast.makeText(
                        this,
                        "Abrindo Acessibilidade (${k + 1}/${list.size}): ${list[k].component?.shortClassName}. Se não abrir a tela certa, toque de novo para tentar a próxima.",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                } catch (_: Exception) {}
            }
        }
        // nenhuma tela direta funcionou: abre as Configurações da TV e mostra o caminho
        a11yTry = 0
        if (openSettingsMain()) {
            Toast.makeText(this, "Abri as Configurações. Vá em Preferências do dispositivo > Acessibilidade > PonteTV Hub e ligue.", Toast.LENGTH_LONG).show()
        } else showAdbHelp()
    }

    private fun localIp(): String = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            ?.hostAddress ?: "IP_DA_TV"
    } catch (e: Exception) { "IP_DA_TV" }

    /** Passo a passo (uma vez só) para TVs sem a tela de Acessibilidade: liga o serviço por ADB, sem abrir tela nenhuma. */
    private fun showAdbHelp() {
        val ip = localIp()
        val msg = "Não consegui abrir a tela de Acessibilidade sozinho.\n\n" +
            "Jeito manual: Configurações > Preferências do dispositivo > Acessibilidade > PonteTV Hub > ligar.\n\n" +
            "Jeito automático (uma vez só, e o app religa sozinho a cada vez que a TV ligar), pelo Termux do celular na mesma rede da TV:\n" +
            "1) Na TV: Configurações > Sobre > toque 7x em \"Compilação\" > Opções do desenvolvedor > ligue a Depuração USB (e a Depuração por rede, se existir).\n" +
            "2) No Termux: pkg install android-tools\n" +
            "3) No Termux: bash ativar-bloqueio.sh $ip   (o arquivo vem na pasta tools do projeto)\n" +
            "    ou, na mão: adb connect $ip:5555 e depois adb shell pm grant com.pontetv.tv android.permission.WRITE_SECURE_SETTINGS\n" +
            "4) Aceite o aviso de depuração na TV e volte aqui.\n\n" +
            "Telas encontradas nesta TV:\n" + a11yDump()
        try {
            AlertDialog.Builder(this)
                .setTitle("Ligar o bloqueio de teclas por ADB")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .setNeutralButton("Abrir Configurações") { _, _ ->
                    try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
                }
                .setNegativeButton("Tentar outra tela") { _, _ -> openAccessibility() }
                .show()
        } catch (e: Exception) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    /** Troca entre Bluetooth clássico e BLE (precisa soltar o controle e parear de novo no celular). */
    private fun toggleMode() {
        if (engaged) release(true)
        val want = !HidLink.bleMode
        if (!HidLink.setBle(want)) Toast.makeText(this, HidLink.status, Toast.LENGTH_LONG).show()
        else if (want) Toast.makeText(this, "Modo BLE: no celular, esqueça a TV antiga e use Procurar TV de novo.", Toast.LENGTH_LONG).show()
        update()
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
        KeyFilterService.instance?.setFilter(true)
        panel.visibility = View.GONE
        hud.visibility = View.VISIBLE
        root.pointerIcon = PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL)
        root.requestFocus()
        capturePointer()
        HidLink.wake()
        updateHud()
        main.removeCallbacks(hudTick)
        main.postDelayed(hudTick, 2000)
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
            updateStats()
            main.postDelayed(this, 2000)
        }
    }

    private fun release(byUser: Boolean) {
        if (!engaged) return
        engaged = false
        InputBridge.capturing = false
        KeyFilterService.instance?.setFilter(false)
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
            (if (soft) "\n(modo alternativo de mouse: esta TV não liberou a captura)" else "") +
            "\n\nF8 solta o teclado e o mouse para usar na TV (ou VOLTAR do controle remoto)"
        updateStats()
    }

    /** Só este TextView muda a cada 2 s (pouco trabalho de layout na thread que recebe o mouse). */
    private fun updateStats() {
        val t = "Bluetooth: ${HidLink.statRate} envios/s · envio mais lento ${HidLink.statWorstMs} ms\n" +
            (if (HidLink.bleMode) "Modo BLE · intervalo de conexão: ${BleHid.boostInfo}"
             else "Mouse: até ${HidLink.moveHz} Hz (F9 troca) · Keepalive: ${if (HidLink.keepAlive) "ligado" else "desligado"} (F10 liga/desliga)")
        if (hudStats.text.toString() != t) hudStats.text = t
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
        else "Bloqueio de teclas na TV: desligado. Toque em \"Bloquear teclas na TV\"; se a TV não tiver a tela de Acessibilidade, o app mostra o passo a passo por ADB."
        btnMode.text = if (HidLink.bleMode) "Modo: BLE (toque para voltar ao clássico)" else "Modo: Bluetooth clássico (toque para testar BLE)"
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
            KeyFilterService.instance?.setFilter(false)
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
