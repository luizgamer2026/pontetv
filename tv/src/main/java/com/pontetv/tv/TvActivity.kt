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
import android.os.Process
import android.os.SystemClock
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
    private lateinit var btnFilter: TextView
    private lateinit var a11yText: TextView
    private var engaged = false
    private var manualOff = false
    private var focusLost = 0
    private var focusLostAt = 0L
    private var focusLastMs = 0
    private var lastFocusAt = 0L
    private var pauses = 0
    private var lastPauseAt = 0L
    private var lastPauseMs = 0
    private var pausedAt = 0L
    private var origPrio = Int.MIN_VALUE
    private val prefs by lazy { TvPrefs(this) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun versionName(): String =
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Diag.mode = prefs.filterMode.coerceIn(0, 2)
        HidLink.init(this)
        build()
        InputWatcher.onTopology = { scheduleRecapture() }
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
        col.addView(label("PonteTV Hub  v${versionName()}", 40f, 0xFFEAF0FF.toInt(), true))
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
        btnControl = tvButton("Controlar celular") { manualOff = false; resetDiag(); engage() }
        btns.addView(visible)
        btns.addView(settings, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        btns.addView(keys, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        btns.addView(btnEnable, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        btns.addView(btnControl, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        col.addView(btns, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
        val btns2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnMode = tvButton("Modo: Bluetooth clássico") { toggleMode() }
        btnFilter = tvButton("Bloqueio de teclas: Inteligente") { cycleFilterMode() }
        btns2.addView(btnMode)
        btns2.addView(btnFilter, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
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
        // o painel de diagnóstico muda a cada 2 s: com a acessibilidade ligada isso gera eventos que cada app manda ao sistema; este painel não precisa deles
        hud.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
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

    private var a11yPress = 0

    private fun openAccessibility() {
        if (tryEnableService()) {
            A11yEnabler.kick(this, true) // estava na lista mas parado? religa
            if (Diag.mode == 0) setMode(prefs.lastOnMode)
            Toast.makeText(this, "Bloqueio de teclas ligado.", Toast.LENGTH_SHORT).show()
            main.postDelayed({ update() }, 1000)
            return
        }
        a11yPress++
        // 1º toque: abre as Configurações da TV (sempre funciona) e mostra o caminho até a Acessibilidade
        if (a11yPress == 1 && openSettingsMain()) {
            Toast.makeText(
                this,
                "Configurações abertas: Preferências do dispositivo > Acessibilidade > PonteTV Hub > ligar. Se preferir, volte e toque de novo aqui para tentar abrir a tela direto.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        // toques seguintes: tenta abrir a tela de Acessibilidade direto, uma por toque
        val list = a11yCandidates()
        if (a11yTry < list.size) {
            for (k in a11yTry until list.size) {
                try {
                    startActivity(list[k])
                    a11yTry = k + 1
                    Toast.makeText(
                        this,
                        "Tentando abrir direto (${k + 1}/${list.size}): ${list[k].component?.shortClassName}. Se não abrir, toque de novo.",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                } catch (_: Exception) {}
            }
        }
        a11yTry = 0
        a11yPress = 0
        showAdbHelp()
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

    private fun modeLabel(m: Int) = when (m) { 1 -> "inteligente"; 2 -> "forte"; else -> "desligado" }

    /**
     * Muda o modo do bloqueio de teclas (0 desligado · 1 inteligente · 2 forte). Vale na hora, antes ou depois de conectar o celular,
     * e fica salvo: ao conectar, o bloqueio entra no modo que está aqui.
     */
    private fun setMode(m: Int) {
        Diag.mode = m
        prefs.filterMode = m
        if (m != 0) {
            prefs.lastOnMode = m
            Diag.autoOff = false; Diag.cooldownMs = 0L; Diag.armedAt = SystemClock.uptimeMillis()
            if (KeyFilterService.instance == null) A11yEnabler.enableAsync(this) { main.postDelayed({ update() }, 1000) }
        }
        KeyFilterService.refresh()
        if (engaged) updateHud() else update()
    }

    private fun cycleFilterMode() = setMode(when (Diag.mode) { 1 -> 2; 2 -> 0; else -> 1 })

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
        boostMain()
        // a cada vez que o teclado é pego, o bloqueio começa do zero (sem pausa, sem espera) e com o modo que o usuário deixou salvo
        Diag.autoOff = false; Diag.cooldownMs = 0L; Diag.armedAt = SystemClock.uptimeMillis()
        Diag.start(this)
        if (Diag.filterEnabled && KeyFilterService.instance == null) A11yEnabler.enableAsync(this) // serviço fora do ar: liga (a conferência de 1 s religa de vez se precisar)
        KeyFilterService.refresh()
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

    /**
     * Enquanto controla o celular, a thread principal é a que recebe o teclado/mouse da TV: sobe a prioridade dela
     * (como já é feito na thread de envio) para outro processo da TV não atrasar a entrega. A principal tem o mesmo número do processo.
     */
    private fun boostMain() {
        try {
            val tid = Process.myPid()
            if (origPrio == Int.MIN_VALUE) origPrio = Process.getThreadPriority(tid)
            Process.setThreadPriority(tid, Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (_: Throwable) {}
    }

    private fun unboostMain() {
        try {
            if (origPrio != Int.MIN_VALUE) { Process.setThreadPriority(Process.myPid(), origPrio); origPrio = Int.MIN_VALUE }
        } catch (_: Throwable) {}
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
        Diag.stop()
        unboostMain()
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
        hudSub.text = "${if (h != null) HidLink.hostName else ""}\n\n" +
            "Teclado da TV: ${InputWatcher.kb ?: "não detectado"}\nMouse da TV: ${InputWatcher.mouse ?: "não detectado"}" +
            (if (soft) "\n(modo alternativo de mouse: esta TV não liberou a captura)" else "") +
            "\n\nF8 solta o teclado e o mouse para usar na TV (ou VOLTAR do controle remoto)"
        updateStats()
    }

    /** Só este TextView muda a cada 2 s (pouco trabalho de layout na thread que recebe o mouse). */
    private fun updateStats() {
        val svc = KeyFilterService.instance
        val modeName = modeLabel(Diag.mode)
        val filt = when {
            !Diag.filterEnabled -> "DESLIGADO (F1…F12/Esc também agem na TV; Scroll Lock liga)"
            svc == null -> "serviço de acessibilidade não está rodando (sem bloqueio); tentando religar…"
            Diag.autoOff -> "PAUSADO ($modeName): a TV atrasou ${Diag.autoOffMs} ms (${ago(Diag.autoOffAt)}); volta sozinho com tudo parado (espera ${Diag.cooldownMs / 1000}s)"
            KeyFilterService.filterOn -> "LIGADO ($modeName)"
            else -> "ligando… ($modeName)"
        }
        val stack = Diag.worstStack.let { if (it.length > 190) it.take(190) + "…" else it }
        val t = "Bluetooth: ${HidLink.statRate} envios/s · envio mais lento ${HidLink.statWorstMs} ms\n" +
            "Travadas — fila: ${HidLink.statLagMax} ms · envio: ${HidLink.statStallMax} ms · quedas do link: ${HidLink.linkDrops} · " +
            "foco perdido: ${focusLost}x${if (focusLost > 0) " (última ${focusLastMs} ms)" else ""}\n" +
            "Na TV — entrega das teclas/mouse (pior): ${InputBridge.deliveryMaxMs} ms" +
            (if (InputBridge.deliveryMaxAt != 0L) " (${InputBridge.deliveryMaxKind}, ${ago(InputBridge.deliveryMaxAt)})" else "") +
            " · demoras ≥200 ms: ${InputBridge.spikes}x${if (InputBridge.spikes > 0) " (última ${ago(InputBridge.lastSpikeAt)})" else ""}" +
            " · USB removido/religado: ${InputWatcher.removals}x\n" +
            "App na TV — thread principal parada (pior): ${Diag.mainLagMax} ms" +
            (if (Diag.mainLagMaxAt != 0L) " (${ago(Diag.mainLagMaxAt)})" else "") +
            " · paradas ≥250 ms: ${Diag.mainStalls}x" +
            (if (stack.isNotEmpty()) "\n   onde ficou presa: $stack" else "") + "\n" +
            "TV — RAM livre: ${if (Diag.availMb >= 0) "${Diag.availMb} MB" else "?"} · pouca memória: ${if (Diag.lowMemory) "SIM" else "não"}" +
            " (avisos do sistema: ${Diag.trimCount}x) · GC que bloqueou o app: ${Diag.gcBlockMs} ms (${Diag.gcBlockCount}x)\n" +
            "Bloqueio de teclas: $filt\n" +
            "   chegada ao serviço (pior): ${KeyFilterService.entryMaxMs} ms${if (KeyFilterService.entryMaxAt != 0L) " (${ago(KeyFilterService.entryMaxAt)})" else ""}" +
            " · resposta do serviço: até ${KeyFilterService.maxMs} ms em ${KeyFilterService.calls} teclas · chegaram ≥350 ms: ${KeyFilterService.lateKeys}x" +
            " · pausas: ${Diag.autoOffCount}x (religou ${Diag.rearms}x) · descartadas (duplicada/atrasada): ${InputBridge.dupes}x · filtro recusado: ${KeyFilterService.applyFails}x" +
            " (Scroll Lock ou ↑ ↓ liga/desliga)\n" +
            "Teclado — eventos recebidos: ${InputBridge.keyEvents}" +
            (if (InputBridge.lastKeyAt != 0L) " (última ${InputBridge.lastKeyName}, ${ago(InputBridge.lastKeyAt)})" else "") +
            " · apertadas agora (app): ${InputBridge.heldText()}" +
            " · sem mapeamento: ${InputBridge.unmapped}x${if (InputBridge.unmapped > 0) " (${InputBridge.lastUnmapped})" else ""}" +
            " · passou de 6 teclas: ${InputBridge.overflow}x · recusadas pelo Bluetooth: ${HidLink.kbFail}x\n" +
            "Entrada na TV agora: ${InputWatcher.present.ifEmpty { "nenhuma" }}" +
            (InputWatcher.recent().let { ev -> if (ev.isEmpty()) "" else "\nÚltimos: " + ev.joinToString(" · ") { "${it.second} (${ago(it.first)})" } }) + "\n" +
            "Há quanto tempo — envio travado: ${ago(HidLink.lastStallAt)}${if (HidLink.lastStallAt != 0L) " (${HidLink.lastStallMs} ms)" else ""}" +
            " · queda do link: ${ago(HidLink.lastDropAt)} · USB mexeu: ${ago(InputWatcher.lastChangeAt)} · foco: ${ago(lastFocusAt)}\n" +
            "App saiu da frente (menu/aviso da TV): ${pauses}x${if (pauses > 0) " (última ${ago(lastPauseAt)}, ficou ${lastPauseMs} ms fora)" else ""}" +
            (if (HidLink.bootProtocol) " · PROTOCOLO BOOT" else "") +
            (if (HidLink.workerRestarts > 0) " · thread de envio reiniciada ${HidLink.workerRestarts}x" else "") +
            (if (HidLink.lastError.isNotEmpty()) " · erro: ${HidLink.lastError}" else "") + "\n" +
            (if (HidLink.bleMode) "Modo BLE · intervalo de conexão: ${BleHid.boostInfo}"
             else "Mouse: até ${HidLink.moveHz} Hz (← → no controle remoto ou Insert) · Keepalive: ${if (HidLink.keepAlive) "ligado" else "desligado"} (OK no controle ou Pause)")
        if (hudStats.text.toString() != t) hudStats.text = t
    }

    override fun onHidState() { update() }

    override fun onDevices(kb: String?, mouse: String?) { update() }

    private fun update() {
        if (!::statusText.isInitialized) return
        if (engaged) {
            // jogando: o painel está escondido. Nada de perguntar ao Bluetooth da TV (chamadas ao outro processo que podem
            // demorar) nem de refazer a lista de celulares: a thread principal fica livre para receber teclado e mouse.
            if (!HidLink.connected) release(false) else updateHud()
            return
        }
        render()
        // o estado do Bluetooth da TV é lido fora da thread principal; a tela se refaz só se algo mudou
        HidLink.refreshSnapshot { if (!engaged) render() }
    }

    /** Desenha o painel (fora de jogo) com a cópia do estado do Bluetooth: nenhuma chamada ao Bluetooth aqui. */
    private fun render() {
        val h = HidLink.host
        when {
            !HidLink.supported -> { statusText.text = HidLink.status; statusText.setTextColor(0xFFFF5A6A.toInt()) }
            !HidLink.hasAdapter() -> { statusText.text = "Esta TV não tem Bluetooth."; statusText.setTextColor(0xFFFF5A6A.toInt()) }
            !HidLink.snapEnabled -> { statusText.text = "Bluetooth da TV desligado."; statusText.setTextColor(0xFFFFB020.toInt()) }
            h != null -> { statusText.text = "✔ Conectado ao celular ${HidLink.hostName}"; statusText.setTextColor(0xFF22D3A6.toInt()) }
            else -> {
                statusText.text = HidLink.status.ifEmpty { "Aguardando o celular…" }
                statusText.setTextColor(0xFFFFB020.toInt())
            }
        }
        btText.text = "Nome Bluetooth desta TV: ${HidLink.snapName}"
        devText.text = "Teclado da TV: ${InputWatcher.kb?.let { "detectado ($it)" } ?: "não detectado"}\n" +
            "Mouse da TV: ${InputWatcher.mouse?.let { "detectado ($it)" } ?: "não detectado"}"
        val svcOn = KeyFilterService.instance != null
        a11yText.text = when {
            Diag.mode == 0 -> "Bloqueio de teclas: DESLIGADO por você (Esc, F1…F12 e Windows também agem na TV). Toque em \"Bloqueio de teclas\" para ligar."
            svcOn -> "Bloqueio de teclas: ${modeLabel(Diag.mode).uppercase()}, serviço ligado (Esc, F1…F12 e Windows vão só para o celular quando conectar)"
            else -> "Bloqueio de teclas: ${modeLabel(Diag.mode)}, mas o serviço de acessibilidade está DESLIGADO. Toque em \"Bloquear teclas na TV\" (abre as Configurações). Para ligar sozinho a cada vez que a TV ligar, use o ADB uma vez (toque 3x para ver o passo a passo)."
        }
        btnFilter.text = "Bloqueio de teclas: ${modeLabel(Diag.mode).replaceFirstChar { it.uppercase() }} (toque para mudar)"
        btnMode.text = if (HidLink.bleMode) "Modo: BLE (toque para voltar ao clássico)" else "Modo: Bluetooth clássico (toque para testar BLE)"
        btnEnable.visibility = if (HidLink.hasAdapter() && !HidLink.snapEnabled) View.VISIBLE else View.GONE
        btnControl.visibility = if (HidLink.connected && !engaged) View.VISIBLE else View.GONE

        listBox.removeAllViews()
        val bonded = HidLink.snapBonded
        if (bonded.isEmpty()) listBox.addView(label("Nenhum aparelho pareado ainda.", 14f, 0xFF8FA0C8.toInt()))
        for ((d, nm) in bonded) {
            val b = tvButton("Conectar a $nm") { HidLink.connect(d) }
            listBox.addView(b, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        }

        if (HidLink.connected && !engaged && !manualOff && hasWindowFocus()) engage()
        if (!HidLink.connected && engaged) release(false)
        if (engaged) updateHud()
    }

    // ---------------- ciclo de vida ----------------
    override fun onResume() {
        super.onResume()
        if (pausedAt != 0L) { lastPauseMs = (SystemClock.uptimeMillis() - pausedAt).toInt(); pausedAt = 0L }
        HidLink.listener = this
        InputWatcher.listener = this
        HubCommands.handler = { handleCommand(it) }
        if (KeyFilterService.instance == null) {
            A11yEnabler.enableAsync(this) { main.postDelayed({ update() }, 1000) }
            main.postDelayed({ if (KeyFilterService.instance == null && Diag.filterEnabled) A11yEnabler.kick(this, false) }, 4000)
        }
        update()
    }

    override fun onPause() {
        // fora da frente a TV não recebe mais o teclado/mouse: solta tudo no celular
        if (engaged) {
            // o app saiu da frente (menu/aviso/outro app da TV por cima): registra para o diagnóstico
            pauses++
            pausedAt = SystemClock.uptimeMillis()
            lastPauseAt = pausedAt
            engaged = false
            InputBridge.capturing = false
            KeyFilterService.instance?.setFilter(false)
            Diag.stop()
            unboostMain()
            main.removeCallbacks(hudTick)
            InputBridge.releaseAll()
            if (root.hasPointerCapture()) root.releasePointerCapture()
            root.pointerIcon = null
            hud.visibility = View.GONE
            panel.visibility = View.VISIBLE
        }
        HidLink.listener = null
        InputWatcher.listener = null
        HubCommands.handler = null
        super.onPause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Diag.trimCount++
        Diag.trimLevel = level
    }

    /**
     * O USB do teclado/mouse soltou e voltou (a TV recria o dispositivo): pede a captura do mouse de novo, para o mouse novo
     * não ficar fora dela. Soltar a captura dispara o onPointerCaptureChanged, que captura outra vez.
     */
    private val recapture = Runnable {
        if (engaged && hasWindowFocus()) { if (root.hasPointerCapture()) root.releasePointerCapture() else capturePointer() }
    }
    private fun scheduleRecapture() {
        if (!engaged) return
        main.removeCallbacks(recapture)
        main.postDelayed(recapture, 400)
    }

    private fun ago(t: Long): String = if (t == 0L) "nunca" else "${(SystemClock.uptimeMillis() - t) / 1000}s atrás"

    private fun resetDiag() { HidLink.resetStats(); InputBridge.resetDiag(); InputWatcher.removals = 0; InputWatcher.lastChangeAt = 0L; focusLost = 0; focusLastMs = 0; lastFocusAt = 0L; pauses = 0; lastPauseAt = 0L; lastPauseMs = 0; Diag.reset(); KeyFilterService.resetDiag() }

    /** Se o mouse perdeu a captura (janela da TV por cima, etc.), pega de volta assim que der. */
    override fun onPointerCaptureChanged(hasCapture: Boolean) {
        super.onPointerCaptureChanged(hasCapture)
        if (!hasCapture && engaged && hasWindowFocus()) {
            main.postDelayed({ if (engaged && hasWindowFocus() && !root.hasPointerCapture()) capturePointer() }, 100)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && engaged) {
            // uma janela da TV (menu, aviso, fonte de entrada…) tomou o foco: o teclado e o mouse não chegam mais ao app.
            // Solta tudo no celular para nenhuma tecla ficar "presa" até o foco voltar.
            InputBridge.releaseAll()
            focusLost++
            focusLostAt = SystemClock.uptimeMillis()
            lastFocusAt = focusLostAt
        }
        if (hasFocus && focusLostAt != 0L) {
            focusLastMs = (SystemClock.uptimeMillis() - focusLostAt).toInt()
            focusLostAt = 0L
        }
        if (hasFocus) {
            immersive()
            if (engaged && !root.hasPointerCapture()) capturePointer()
            else update()
        }
    }

    // ---------------- teclado ----------------
    private fun handleCommand(cmd: Int) {
        when (cmd) {
            HubCommands.RELEASE_TOGGLE ->
                if (engaged) release(true) else if (HidLink.connected) { manualOff = false; resetDiag(); engage() }
            HubCommands.RATE -> { HidLink.cycleRate(); if (engaged) updateHud() }
            HubCommands.KEEPALIVE -> { HidLink.toggleKeepAlive(); if (engaged) updateHud() }
            HubCommands.FILTER -> setMode(if (Diag.mode == 0) prefs.lastOnMode else 0)
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val kb = InputBridge.isKeyboard(e)
        val down = e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0
        val cmd = if (kb) HubCommands.forKey(e.keyCode) else 0
        if (cmd != 0) {
            val usable = when (cmd) {
                HubCommands.RELEASE_TOGGLE -> engaged || HidLink.connected
                HubCommands.FILTER -> true   // Scroll Lock liga/desliga o bloqueio também antes de conectar o celular
                else -> engaged
            }
            if (usable) { if (down && HubCommands.fresh(e)) handleCommand(cmd); return true }
        }
        if (engaged) {
            if (kb || (e.action == KeyEvent.ACTION_UP && InputBridge.isHeld(e))) { InputBridge.key(e); return true }
            when (e.keyCode) {
                // controle remoto: ← → trocam o ritmo do mouse, OK liga/desliga o keepalive
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) handleCommand(HubCommands.RATE); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { if (down) handleCommand(HubCommands.KEEPALIVE); return true }
                // controle remoto: ↑ ↓ ligam/desligam o bloqueio de teclas do sistema (teste de travadas)
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) handleCommand(HubCommands.FILTER); return true }
                KeyEvent.KEYCODE_BACK -> {
                    // o botão direito do mouse também vira VOLTAR no Android: só o controle remoto solta
                    val fromMouse = ((e.device?.sources ?: 0) and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                    if (!fromMouse && e.action == KeyEvent.ACTION_UP) release(true)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }
}
