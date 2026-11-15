package com.pontetv.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private val listener: () -> Unit = { render() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = Prefs(this)
        window.statusBarColor = UI.BG0
        window.navigationBarColor = UI.BG0
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        Session.addListener(listener)
        render()
    }

    override fun onPause() {
        Session.removeListener(listener)
        super.onPause()
    }

    private fun dp(v: Int) = UI.dp(this, v)

    private fun bigCard(emoji: String, title: String, sub: String, badge: String?, onClick: () -> Unit): LinearLayout {
        val c = UI.card(this)
        c.background = UI.rounded(this, UI.CARD, 26)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(UI.text(this, emoji, 44f))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(UI.text(this, title, 24f, UI.TEXT, true))
        if (badge != null) row.addView(UI.text(this, badge, 11f, 0xFF04101F.toInt(), true).apply {
            background = UI.rounded(this@MainActivity, UI.GREEN, 10)
            setPadding(dp(8), dp(3), dp(8), dp(3))
        }, UI.lp(this, -2, -2).apply { leftMargin = dp(10) })
        col.addView(row)
        col.addView(UI.text(this, sub, 13f, UI.MUTED))
        top.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(top)
        UI.pressable(c, onClick)
        return c
    }

    private fun smallCard(emoji: String, title: String, sub: String, onClick: () -> Unit): LinearLayout {
        val c = UI.card(this)
        c.addView(UI.text(this, emoji, 32f))
        c.addView(UI.text(this, title, 17f, UI.TEXT, true))
        c.addView(UI.text(this, sub, 12f, UI.MUTED))
        UI.pressable(c, onClick)
        return c
    }

    private fun open(kind: String) {
        startActivity(Intent(this, DevicesActivity::class.java).putExtra(DevicesActivity.X_KIND, kind))
    }

    private fun render() {
        val sv = ScrollView(this).apply { background = UI.bg() }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(44), dp(20), dp(28))
        }
        sv.addView(col)
        var i = 0
        fun add(v: android.view.View, top: Int = 14) { col.addView(v, UI.lp(this, top = top)); UI.appear(v, i++) }

        add(UI.text(this, "PonteTV", 32f, UI.TEXT, true), 0)
        add(UI.text(this, "Transmita a tela do celular e controle com o teclado e o mouse da TV.", 14f, UI.MUTED), 4)

        if (Session.active) {
            val c = UI.card(this, 0xFF14533F.toInt())
            c.addView(UI.text(this, "● Transmitindo para ${Session.tvName}", 16f, UI.TEXT, true))
            c.addView(UI.text(this, "Toque para ver o estado e encerrar", 12f, UI.GREEN))
            UI.pressable(c) { startActivity(Intent(this, ConnectedActivity::class.java)) }
            add(c, 18)
        }

        add(bigCard("📺", "TV", "Android TV · tela + teclado e mouse da TV", "NOVO") { open("tv") }, 20)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(smallCard("📱", "Tablet", "Outro Android com o receptor") { open("tablet") }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(7) })
        row.addView(smallCard("🔗", "Outros", "Qualquer receptor na rede") { open("other") }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(7) })
        add(row)

        val saved = prefs.paired()
        if (saved.isNotEmpty()) {
            add(UI.text(this, "Dispositivos salvos", 13f, UI.MUTED, true), 26)
            for (p in saved) {
                val c = UI.card(this)
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                r.addView(UI.text(this, if (p.kind == "tv") "📺" else "📱", 26f))
                val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
                t.addView(UI.text(this, p.name, 16f, UI.TEXT, true))
                t.addView(UI.text(this, "${p.host}:${p.port}", 12f, UI.MUTED))
                r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
                r.addView(UI.button(this, "Conectar") { connect(p) })
                c.addView(r)
                c.setOnLongClickListener { forget(p); true }
                add(c, 8)
            }
            add(UI.text(this, "Segure um dispositivo para esquecê-lo.", 11f, UI.MUTED), 4)
        }

        add(UI.text(this, "Controles", 13f, UI.MUTED, true), 26)
        val c = UI.card(this)
        val ok = InputAccessibilityService.instance != null
        c.addView(UI.text(this, if (ok) "Acessibilidade: ativada ✓" else "Acessibilidade: desativada", 15f, if (ok) UI.GREEN else UI.WARN, true))
        c.addView(UI.text(this, "Necessária para o teclado e o mouse da TV controlarem o celular.", 12f, UI.MUTED))
        if (!ok) c.addView(UI.button(this, "Abrir configurações de acessibilidade", false) {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, UI.lp(this, top = 10))
        add(c, 8)
        add(UI.button(this, "Calibrar controles do jogo", false) { startActivity(Intent(this, CalibrateActivity::class.java)) }, 10)
        add(UI.button(this, "Configurações de transmissão", false) { startActivity(Intent(this, SettingsActivity::class.java)) }, 10)

        setContentView(sv)
    }

    private fun connect(p: Paired) {
        startActivity(Intent(this, ConnectedActivity::class.java).putExtra(StreamService.X_ID, p.id))
    }

    private fun forget(p: Paired) {
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Esquecer ${p.name}?")
            .setPositiveButton("Esquecer") { _, _ -> prefs.removePaired(p.id); render() }
            .setNegativeButton("Cancelar", null).show()
    }
}
