package com.pontetv.phone

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity

class SettingsActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private fun dp(v: Int) = UI.dp(this, v)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = Prefs(this)
        window.statusBarColor = UI.BG0
        window.navigationBarColor = UI.BG0
        val sv = ScrollView(this).apply { background = UI.bg() }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(44), dp(20), dp(28)) }
        sv.addView(col)
        col.addView(UI.text(this, "Transmissão", 26f, UI.TEXT, true))
        col.addView(UI.text(this, "Valem na próxima transmissão. O ajuste automático reduz bitrate/resolução se a rede piorar.", 12f, UI.MUTED), UI.lp(this, top = 4))

        chips(col, "Resolução máxima (lado maior)", listOf("540p" to 960, "720p" to 1280, "1080p" to 1920), prefs.maxLong) { prefs.maxLong = it }
        chips(col, "Quadros por segundo", listOf("30" to 30, "60" to 60), prefs.fps) { prefs.fps = it }
        chips(col, "Bitrate máximo", listOf("4 Mbps" to 4000, "8 Mbps" to 8000, "12 Mbps" to 12000, "16 Mbps" to 16000), prefs.bitrate) { prefs.bitrate = it }
        chips(col, "Áudio do celular na TV", listOf("Ligado" to 1, "Desligado" to 0), if (prefs.audio) 1 else 0) { prefs.audio = it == 1 }

        val c = UI.card(this)
        val lbl = UI.text(this, "Sensibilidade do mouse no modo jogo: ${"%.1f".format(prefs.sens)}", 14f, UI.TEXT, true)
        c.addView(lbl)
        val sb = SeekBar(this).apply {
            max = 55; progress = ((prefs.sens - 0.5f) * 10).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    prefs.sens = 0.5f + p / 10f
                    lbl.text = "Sensibilidade do mouse no modo jogo: ${"%.1f".format(prefs.sens)}"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        c.addView(sb, UI.lp(this, top = 8))
        col.addView(c, UI.lp(this, top = 14))
        val g = UI.card(this)
        g.addView(UI.text(this, "Apps tratados como jogo (extra)", 14f, UI.TEXT, true))
        g.addView(UI.text(this, "Jogos com categoria \"Jogo\" já são detectados. Para outros, digite os pacotes separados por vírgula (ex.: com.mojang.minecraftpe).", 12f, UI.MUTED))
        val et = android.widget.EditText(this).apply {
            setText(prefs.extraGamesRaw); setTextColor(UI.TEXT); setHintTextColor(UI.MUTED); hint = "pacote1, pacote2"
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { prefs.extraGamesRaw = s?.toString() ?: "" }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        g.addView(et, UI.lp(this, top = 8))
        col.addView(g, UI.lp(this, top = 14))
        setContentView(sv)
    }

    private fun chips(parent: LinearLayout, label: String, opts: List<Pair<String, Int>>, current: Int, set: (Int) -> Unit) {
        val c = UI.card(this)
        c.addView(UI.text(this, label, 14f, UI.TEXT, true))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val views = ArrayList<TextView>()
        fun paint(sel: Int) {
            for ((i, v) in views.withIndex()) {
                val on = opts[i].second == sel
                v.background = UI.rounded(this, if (on) UI.ACCENT else 0xFF2A3560.toInt(), 14)
                v.setTextColor(if (on) 0xFF04101F.toInt() else UI.TEXT)
            }
        }
        for ((name, value) in opts) {
            val v = UI.text(this, name, 13f, UI.TEXT, true).apply {
                gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(10))
            }
            UI.pressable(v) { set(value); paint(value) }
            views.add(v)
            row.addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        }
        paint(current)
        c.addView(row, UI.lp(this, top = 10))
        parent.addView(c, UI.lp(this, top = 14))
    }
}
