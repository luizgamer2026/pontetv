package com.pontetv.phone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class DevicesActivity : ComponentActivity() {
    companion object { const val X_KIND = "kind" }

    private lateinit var prefs: Prefs
    private lateinit var nsd: NsdHelper
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private var kind = "tv"
    private var found: List<Found> = emptyList()

    private val scan = registerForActivityResult(ScanContract()) { r ->
        val t = r.contents
        if (t != null) onQr(t)
    }

    private fun dp(v: Int) = UI.dp(this, v)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = Prefs(this)
        kind = intent.getStringExtra(X_KIND) ?: "tv"
        window.statusBarColor = UI.BG0
        window.navigationBarColor = UI.BG0

        val sv = ScrollView(this).apply { background = UI.bg() }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(44), dp(20), dp(28)) }
        sv.addView(col)
        val title = when (kind) { "tv" -> "Conectar à TV"; "tablet" -> "Conectar ao tablet"; else -> "Outros dispositivos" }
        col.addView(UI.text(this, title, 26f, UI.TEXT, true))
        status = UI.text(this, "Procurando na rede local…", 13f, UI.MUTED)
        col.addView(status, UI.lp(this, top = 4))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(UI.button(this, "Escanear QR") { startScan() }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(7) })
        row.addView(UI.button(this, "IP manual", false) { manualDialog() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(7) })
        col.addView(row, UI.lp(this, top = 18))

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list, UI.lp(this, top = 14))
        col.addView(UI.text(this, "No receptor aparece um QR code e um código de 6 dígitos. Toque no dispositivo encontrado e digite o código, ou escaneie o QR. Para conexão direta sem roteador, ligue \"Wi-Fi Direct\" na TV e escaneie o QR novo.", 12f, UI.MUTED), UI.lp(this, top = 18))
        setContentView(sv)

        nsd = NsdHelper(this) { f -> found = f; render() }
    }

    override fun onResume() { super.onResume(); nsd.start(); render() }
    override fun onPause() { nsd.stop(); super.onPause() }

    private fun matches(k: String) = kind == "other" || k == kind

    private fun render() {
        list.removeAllViews()
        val shown = found.filter { matches(it.kind) }
        val savedOnly = prefs.paired().filter { matches(it.kind) && shown.none { f -> f.id == it.id } }
        status.text = if (shown.isEmpty()) "Nenhum receptor encontrado ainda. Abra o app na TV e confirme que está na mesma rede." else "${shown.size} encontrado(s)"
        var i = 0
        for (f in shown) {
            val saved = prefs.findPaired(f.id)
            row(f.name, "${f.host}:${f.port}", f.kind, if (saved != null) "Salvo · conectar" else "Pedir código", i++) {
                if (saved != null) open(saved.copy(host = f.host, port = f.port)) else pinDialog(f.host, f.port)
            }
        }
        for (p in savedOnly) row(p.name, "${p.host}:${p.port} (salvo)", p.kind, "Conectar", i++) { open(p) }
    }

    private fun row(name: String, sub: String, k: String, action: String, idx: Int, onClick: () -> Unit) {
        val c = UI.card(this)
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(UI.text(this, if (k == "tv") "📺" else "📱", 28f))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        t.addView(UI.text(this, name, 17f, UI.TEXT, true))
        t.addView(UI.text(this, sub, 12f, UI.MUTED))
        t.addView(UI.text(this, action, 12f, UI.GREEN, true))
        r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(r)
        UI.pressable(c, onClick)
        list.addView(c, UI.lp(this, top = 8))
        UI.appear(c, idx)
    }

    private fun startScan() {
        scan.launch(ScanOptions().setPrompt("Aponte para o QR code da TV").setBeepEnabled(false)
            .setOrientationLocked(false).setDesiredBarcodeFormats(ScanOptions.QR_CODE))
    }

    private fun onQr(text: String) {
        try {
            val u = Uri.parse(text)
            if (u.scheme != "pontetv") throw IllegalArgumentException()
            val h = u.getQueryParameter("h")!!
            val p = u.getQueryParameter("p")!!.toInt()
            val t = u.getQueryParameter("t")!!
            val ws = u.getQueryParameter("ws")
            val wp = u.getQueryParameter("wp")
            if (ws != null && wp != null) {
                status.text = "Conectando ao Wi-Fi Direct da TV… confirme o pedido do Android."
                DirectWifi.connect(this, ws, wp) { err ->
                    runOnUiThread {
                        if (err == null) pair(h, p, t) else status.text = "Wi-Fi Direct falhou: ${err.message}"
                    }
                }
            } else pair(h, p, t)
        } catch (e: Exception) {
            status.text = "QR inválido. Use o QR mostrado pelo receptor PonteTV."
        }
    }

    private fun pinDialog(host: String, port: Int) {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; hint = "Código de 6 dígitos"
            setTextColor(UI.TEXT); setHintTextColor(UI.MUTED); textSize = 22f; gravity = Gravity.CENTER
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Código exibido na TV").setView(et)
            .setPositiveButton("Conectar") { _, _ -> pair(host, port, et.text.toString().trim()) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun manualDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val ip = EditText(this).apply { hint = "IP da TV (ex.: 192.168.0.20)"; setTextColor(UI.TEXT); setHintTextColor(UI.MUTED); inputType = InputType.TYPE_CLASS_PHONE }
        val code = EditText(this).apply { hint = "Código de 6 dígitos"; setTextColor(UI.TEXT); setHintTextColor(UI.MUTED); inputType = InputType.TYPE_CLASS_NUMBER }
        box.addView(ip); box.addView(code)
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Conexão manual").setView(box)
            .setPositiveButton("Conectar") { _, _ ->
                val parts = ip.text.toString().trim().split(":")
                val port = parts.getOrNull(1)?.toIntOrNull() ?: com.pontetv.common.Proto.DEFAULT_PORT
                pair(parts[0], port, code.text.toString().trim())
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun pair(host: String, port: Int, secret: String) {
        status.text = "Conectando a $host…"
        Thread {
            val r = Pairing.pair(prefs, host, port, secret)
            runOnUiThread {
                r.onSuccess { p -> prefs.savePaired(p); open(p) }
                r.onFailure { e -> status.text = "Falhou: ${e.message}" }
            }
        }.start()
    }

    private fun open(p: Paired) {
        prefs.savePaired(p)
        startActivity(Intent(this, ControlActivity::class.java).putExtra(ControlService.X_ID, p.id))
    }
}
