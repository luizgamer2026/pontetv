package com.pontetv.phone

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

class ControlActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private var tvId: String? = null
    private lateinit var titleView: TextView
    private lateinit var state: TextView
    private lateinit var devs: TextView
    private lateinit var bars: QualityBars
    private lateinit var a11y: LinearLayout
    private lateinit var imeCard: LinearLayout
    private lateinit var imeText: TextView
    private val listener: () -> Unit = { update() }

    private fun dp(v: Int) = UI.dp(this, v)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = Prefs(this)
        tvId = intent.getStringExtra(ControlService.X_ID)
        window.statusBarColor = UI.BG0
        window.navigationBarColor = UI.BG0
        build()
        val id = tvId
        if (id != null && !ControlService.running) {
            if (StreamService.running) StreamService.stop(this)
            startForegroundService(Intent(this, ControlService::class.java).putExtra(ControlService.X_ID, id))
        }
    }

    private fun build() {
        val sv = ScrollView(this).apply { background = UI.bg() }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(44), dp(20), dp(28)) }
        sv.addView(col)

        val card = UI.card(this)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(UI.text(this, "⌨️", 40f))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        titleView = UI.text(this, "", 22f, UI.TEXT, true)
        state = UI.text(this, "", 14f, UI.MUTED)
        t.addView(titleView); t.addView(state)
        top.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        bars = QualityBars(this)
        top.addView(bars, LinearLayout.LayoutParams(dp(44), dp(34)))
        card.addView(top)
        devs = UI.text(this, "", 14f, UI.TEXT)
        card.addView(devs, UI.lp(this, top = 14))
        col.addView(card)
        UI.appear(card, 0)

        a11y = UI.card(this, 0xFF3A2C0A.toInt())
        a11y.addView(UI.text(this, "Acessibilidade desativada", 15f, UI.WARN, true))
        a11y.addView(UI.text(this, "Necessária para o mouse (cursor + cliques) e os controles do jogo. Ative \"PonteTV\" em Acessibilidade. Se o Android bloquear (app fora da loja): Informações do app > ⋮ > Permitir configurações restritas.", 12f, UI.TEXT))
        a11y.addView(UI.button(this, "Abrir acessibilidade", false) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, UI.lp(this, top = 10))
        col.addView(a11y, UI.lp(this, top = 14))

        imeCard = UI.card(this, 0xFF1F2A55.toInt())
        imeCard.addView(UI.text(this, "Teclado PonteTV", 15f, UI.TEXT, true))
        imeText = UI.text(this, "", 12f, UI.MUTED)
        imeCard.addView(imeText)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(UI.button(this, "1. Ativar", false) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        row.addView(UI.button(this, "2. Escolher", false) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
        imeCard.addView(row, UI.lp(this, top = 10))
        col.addView(imeCard, UI.lp(this, top = 14))

        val tips = UI.card(this)
        tips.addView(UI.text(this, "Como usar", 14f, UI.TEXT, true))
        tips.addView(UI.text(this, "• Jogue/use pela tela do celular. O mouse da TV move um cursor aqui; clique = toque; roda = rolagem.\n• Ao abrir um jogo, o cursor some e o modo jogo liga sozinho (WASD, câmera pelo mouse).\n• Na TV: F8 liga/desliga o modo jogo manualmente · F6 solta/captura o mouse na TV · Ctrl+Shift+Q desconecta.\n• Mantenha o app PonteTV aberto na TV.", 12f, UI.MUTED))
        col.addView(tips, UI.lp(this, top = 14))

        col.addView(UI.button(this, "Alternar modo jogo (manual)", false) {
            InputAccessibilityService.instance?.toggleGame()
        }, UI.lp(this, top = 14))
        col.addView(UI.button(this, "Calibrar controles do jogo", false) {
            startActivity(Intent(this, CalibrateActivity::class.java))
        }, UI.lp(this, top = 10))
        col.addView(UI.button(this, "Transmitir a tela também (opcional, beta)", false) {
            val id = tvId
            if (id != null) {
                ControlService.stop(this)
                startActivity(Intent(this, ConnectedActivity::class.java).putExtra(StreamService.X_ID, id))
                finish()
            }
        }, UI.lp(this, top = 10))
        col.addView(UI.button(this, "Desconectar") {
            ControlService.stop(this)
            finish()
        }, UI.lp(this, top = 10))
        setContentView(sv)
    }

    override fun onResume() { super.onResume(); Session.addListener(listener); update() }
    override fun onPause() { Session.removeListener(listener); super.onPause() }

    private fun imeEnabled(): Boolean =
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).enabledInputMethodList.any { it.packageName == packageName }

    private fun imeSelected(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$packageName/") == true

    private fun update() {
        val s = Session
        titleView.text = if (s.tvName.isNotEmpty()) s.tvName else (tvId?.let { prefs.findPaired(it)?.name } ?: "TV")
        state.text = when (s.state) {
            Session.State.IDLE -> "Iniciando…"
            Session.State.CONNECTING -> "Conectando…"
            Session.State.CONTROL, Session.State.STREAMING -> if (s.game) "Conectado · modo jogo" else "Conectado · modo mouse"
            Session.State.RECONNECTING -> "Reconectando… (${s.message})"
            Session.State.STANDBY -> "Em espera"
            Session.State.ERROR -> "Erro: ${s.message}"
        }
        state.setTextColor(when (s.state) { Session.State.CONTROL -> UI.GREEN; Session.State.ERROR -> UI.BAD; else -> UI.WARN })
        bars.level = when {
            s.state != Session.State.CONTROL -> 0
            s.rtt < 0 -> 1
            s.rtt <= 25 -> 4
            s.rtt <= 50 -> 3
            s.rtt <= 100 -> 2
            else -> 1
        }
        devs.text = if (s.state == Session.State.CONTROL)
            "Teclado da TV: ${s.tvKb?.let { "✔ conectado ($it)" } ?: "✖ não detectado"}\nMouse da TV: ${s.tvMouse?.let { "✔ conectado ($it)" } ?: "✖ não detectado"}" +
            (if (s.rtt >= 0) "\nResposta da rede: ${s.rtt} ms" else "")
        else ""
        a11y.visibility = if (InputAccessibilityService.instance == null) View.VISIBLE else View.GONE
        imeText.text = when {
            imeSelected() -> "Em uso ✔ — o teclado da TV digita nos campos de texto."
            imeEnabled() -> "Ativado, mas não selecionado. Toque em 2. Escolher (ou escolha-o ao tocar num campo de texto)."
            else -> "Para digitar com o teclado da TV de forma confiável: 1. Ativar \"PonteTV\" e 2. Escolher."
        }
    }
}
