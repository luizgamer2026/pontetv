package com.pontetv.phone

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private lateinit var finder: BtFinder
    private lateinit var statusCard: LinearLayout
    private lateinit var listBox: LinearLayout
    private lateinit var scanBtn: TextView
    private lateinit var scanInfo: TextView
    private val inputListener: () -> Unit = { render() }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) startSearch()
        else Toast.makeText(this, "Sem a permissão de Bluetooth não dá para procurar a TV.", Toast.LENGTH_LONG).show()
    }

    private fun dp(v: Int) = UI.dp(this, v)

    private fun btPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPerms() = btPerms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = Prefs(this)
        finder = BtFinder(this) { render() }
        window.statusBarColor = UI.BG0
        window.navigationBarColor = UI.BG0
        buildScreen()
        InputStatus.start(this)
    }

    override fun onStart() {
        super.onStart()
        finder.register()
        InputStatus.addListener(inputListener)
        InputStatus.scan()
        if (finder.supported() && hasPerms() && finder.enabled() && !finder.scanning) finder.start()
        render()
    }

    override fun onStop() {
        InputStatus.removeListener(inputListener)
        finder.unregister()
        super.onStop()
    }

    private fun buildScreen() {
        val sv = ScrollView(this).apply { background = UI.bg() }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(44), dp(20), dp(28))
        }
        sv.addView(col)

        col.addView(UI.text(this, "PonteTV", 32f, UI.TEXT, true))
        col.addView(UI.text(this, "O teclado e o mouse ligados na TV funcionam neste celular, como se estivessem ligados nele.", 14f, UI.MUTED), UI.lp(this, top = 4))

        statusCard = UI.card(this)
        col.addView(statusCard, UI.lp(this, top = 20))

        col.addView(UI.text(this, "Procurar a TV", 13f, UI.MUTED, true), UI.lp(this, top = 26))
        scanBtn = UI.button(this, "Procurar TV") { startSearch() }
        col.addView(scanBtn, UI.lp(this, top = 8))
        scanInfo = UI.text(this, "", 12f, UI.MUTED)
        col.addView(scanInfo, UI.lp(this, top = 8))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox, UI.lp(this, top = 6))

        val how = UI.card(this)
        how.addView(UI.text(this, "Como conectar", 14f, UI.TEXT, true))
        how.addView(UI.text(this,
            "1. Na TV, abra o PonteTV Hub e toque em \"Tornar visível\".\n" +
            "2. Aqui, toque na TV da lista e aceite o pareamento no celular e na TV.\n" +
            "3. A TV conecta sozinha. O cursor do Android aparece neste celular e some da TV.\n" +
            "4. Nas próximas vezes basta abrir o PonteTV Hub na TV.", 12f, UI.MUTED))
        col.addView(how, UI.lp(this, top = 20))

        col.addView(UI.text(this, "Ajustes", 13f, UI.MUTED, true), UI.lp(this, top = 26))
        col.addView(UI.button(this, "Layout do teclado (ABNT2, ç, acentos)", false) {
            open(Settings.ACTION_HARD_KEYBOARD_SETTINGS)
        }, UI.lp(this, top = 8))
        col.addView(UI.text(this, "Em \"Teclado físico\", escolha o layout igual ao do teclado da TV (Português do Brasil ABNT2). A tecla chega ao celular como tecla física; quem escreve ç e acentos é esse layout.", 12f, UI.MUTED), UI.lp(this, top = 6))
        col.addView(UI.button(this, "Velocidade do ponteiro", false) {
            open(Settings.ACTION_INPUT_METHOD_SETTINGS)
        }, UI.lp(this, top = 12))
        col.addView(UI.button(this, "Bluetooth do celular", false) {
            open(Settings.ACTION_BLUETOOTH_SETTINGS)
        }, UI.lp(this, top = 10))
        setContentView(sv)
    }

    private fun open(action: String) {
        try { startActivity(Intent(action)) }
        catch (e: Exception) { Toast.makeText(this, "Não foi possível abrir essa tela nesta versão do Android.", Toast.LENGTH_LONG).show() }
    }

    private fun startSearch() {
        if (!finder.supported()) { Toast.makeText(this, "Este celular não tem Bluetooth.", Toast.LENGTH_LONG).show(); return }
        if (!hasPerms()) { permLauncher.launch(btPerms()); return }
        if (!finder.enabled()) {
            try { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) {}
            Toast.makeText(this, "Ligue o Bluetooth e toque em Procurar TV de novo.", Toast.LENGTH_LONG).show()
            return
        }
        finder.start()
    }

    private fun render() {
        if (!::statusCard.isInitialized) return
        // ---- estado do teclado e do mouse ----
        val kb = InputStatus.kb
        val ms = InputStatus.mouse
        statusCard.removeAllViews()
        when {
            kb != null && ms != null -> {
                statusCard.background = UI.rounded(this, 0xFF14533F.toInt(), 22)
                statusCard.addView(UI.text(this, "✔ Teclado e mouse conectados", 19f, UI.TEXT, true))
                statusCard.addView(UI.text(this, "Teclado: $kb\nMouse: $ms", 13f, UI.GREEN), UI.lp(this, top = 6))
            }
            kb != null || ms != null -> {
                statusCard.background = UI.rounded(this, 0xFF3A2C0A.toInt(), 22)
                statusCard.addView(UI.text(this, if (kb != null) "Teclado conectado, falta o mouse" else "Mouse conectado, falta o teclado", 17f, UI.WARN, true))
                statusCard.addView(UI.text(this, (kb?.let { "Teclado: $it" } ?: "Mouse: $ms"), 13f, UI.TEXT), UI.lp(this, top = 6))
            }
            else -> {
                statusCard.background = UI.rounded(this, UI.CARD, 22)
                statusCard.addView(UI.text(this, "Teclado e mouse não conectados", 17f, UI.TEXT, true))
                statusCard.addView(UI.text(this, "Abra o PonteTV Hub na TV e pareie pela lista abaixo.", 13f, UI.MUTED), UI.lp(this, top = 6))
            }
        }

        // ---- busca ----
        scanBtn.text = if (finder.scanning) "Procurando…" else "Procurar TV"
        listBox.removeAllViews()
        val items = finder.list()
        scanInfo.text = when {
            !finder.supported() -> "Este celular não tem Bluetooth."
            !hasPerms() -> "Toque em Procurar TV e permita o Bluetooth."
            !finder.enabled() -> "Bluetooth desligado."
            items.isEmpty() -> if (finder.scanning) "Procurando aparelhos por perto…" else "Nada encontrado. Na TV, toque em \"Tornar visível\" e procure de novo."
            else -> "Toque na sua TV."
        }
        for (e in items) {
            val c = UI.card(this)
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(UI.text(this, if (e.likelyTv) "📺" else "📶", 26f))
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            t.addView(UI.text(this, e.name, 16f, UI.TEXT, true))
            val sub = when {
                e.device.address == prefs.lastTv && e.bonded -> "Pareada · sua TV"
                e.bonded -> "Pareada"
                else -> "Toque para parear"
            }
            t.addView(UI.text(this, sub, 12f, if (e.bonded) UI.GREEN else UI.MUTED))
            r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            c.addView(r)
            UI.pressable(c) { onDevice(e) }
            listBox.addView(c, UI.lp(this, top = 8))
        }
    }

    private fun onDevice(e: BtFinder.Entry) {
        if (e.bonded) {
            Toast.makeText(this, "Já pareada. Abra o PonteTV Hub na TV: ela conecta sozinha (ou toque em \"Conectar a\" seu celular lá).", Toast.LENGTH_LONG).show()
            return
        }
        prefs.lastTv = e.device.address
        finder.stop()
        if (finder.bond(e.device)) Toast.makeText(this, "Aceite o pareamento aqui e na TV.", Toast.LENGTH_LONG).show()
        else Toast.makeText(this, "Não foi possível iniciar o pareamento.", Toast.LENGTH_LONG).show()
    }
}
