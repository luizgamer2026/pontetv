package com.pontetv.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class ConnectedActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private var tvId: String? = null
    private lateinit var titleView: TextView
    private lateinit var state: TextView
    private lateinit var stats: TextView
    private lateinit var devs: TextView
    private lateinit var bars: QualityBars
    private lateinit var a11y: LinearLayout
    private lateinit var overlay: LinearLayout
    private val listener: () -> Unit = { update() }

    private val projLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data
        if (r.resultCode == RESULT_OK && d != null) {
            startForegroundService(
                Intent(this, StreamService::class.java)
                    .putExtra(StreamService.X_ID, tvId)
                    .putExtra(StreamService.X_CODE, r.resultCode)
                    .putExtra(StreamService.X_DATA, d)
            )
        } else finish()
    }
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { askProjection() }

    private fun dp(v: Int) = UI.dp(this, v)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = Prefs(this)
        tvId = intent.getStringExtra(StreamService.X_ID)
        window.statusBarColor = UI.BG0
        window.navigationBarColor = UI.BG0
        build()
        val id = tvId
        if (StreamService.running) {
            // serviço já tem a permissão de captura: só reconecta (sem o aviso do Android)
            if (Session.state == Session.State.STANDBY && id != null) {
                startService(Intent(this, StreamService::class.java).setAction(StreamService.ACT_CONNECT).putExtra(StreamService.X_ID, id))
            }
        } else if (id != null) {
            val need = ArrayList<String>()
            if (prefs.audio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS)
            if (need.isEmpty()) askProjection() else permLauncher.launch(need.toTypedArray())
        }
    }

    private fun askProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun build() {
        val sv = ScrollView(this).apply { background = UI.bg() }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(44), dp(20), dp(28)) }
        sv.addView(col)

        val card = UI.card(this)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(UI.text(this, "📺", 44f))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        titleView = UI.text(this, "", 22f, UI.TEXT, true)
        state = UI.text(this, "", 14f, UI.MUTED)
        t.addView(titleView); t.addView(state)
        top.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        bars = QualityBars(this)
        top.addView(bars, LinearLayout.LayoutParams(dp(44), dp(34)))
        card.addView(top)
        stats = UI.text(this, "", 13f, UI.MUTED)
        card.addView(stats, UI.lp(this, top = 14))
        devs = UI.text(this, "", 13f, UI.MUTED)
        card.addView(devs, UI.lp(this, top = 10))
        col.addView(card)
        UI.appear(card, 0)

        a11y = UI.card(this, 0xFF3A2C0A.toInt())
        a11y.addView(UI.text(this, "Acessibilidade desativada", 15f, UI.WARN, true))
        a11y.addView(UI.text(this, "Sem ela a TV só mostra a tela; teclado e mouse não controlam o celular. Ative \"PonteTV\" em Acessibilidade. Se o Android bloquear (app instalado fora da loja), abra Informações do app > ⋮ > Permitir configurações restritas.", 12f, UI.TEXT))
        a11y.addView(UI.button(this, "Abrir acessibilidade", false) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, UI.lp(this, top = 10))
        col.addView(a11y, UI.lp(this, top = 14))

        overlay = UI.card(this, 0xFF1F2A55.toInt())
        overlay.addView(UI.text(this, "Abrir apps pela TV", 15f, UI.TEXT, true))
        overlay.addView(UI.text(this, "Para a barra de tarefas da TV abrir apps do celular, permita \"Exibir sobre outros apps\" ao PonteTV.", 12f, UI.MUTED))
        overlay.addView(UI.button(this, "Permitir", false) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }, UI.lp(this, top = 10))
        col.addView(overlay, UI.lp(this, top = 14))

        val tips = UI.card(this)
        tips.addView(UI.text(this, "Como usar", 14f, UI.TEXT, true))
        tips.addView(UI.text(this, "• Abra seu jogo: se o mouse da TV estiver conectado, o cursor some e o modo jogo liga sozinho.\n• Na TV: F8 liga/desliga o modo jogo · F10 barra de tarefas (ou leve o mouse à borda de baixo).\n• Toque em Calibrar e arraste os pontos sobre os botões do jogo.\n• Se a TV encerrar, o app fica em espera e reconectar não pede o aviso de captura.", 12f, UI.MUTED))
        col.addView(tips, UI.lp(this, top = 14))

        col.addView(UI.button(this, "Calibrar controles do jogo", false) {
            startActivity(Intent(this, CalibrateActivity::class.java))
        }, UI.lp(this, top = 14))
        col.addView(UI.button(this, "Encerrar tudo") {
            StreamService.stop(this)
            finish()
        }, UI.lp(this, top = 10))
        setContentView(sv)
    }

    override fun onResume() { super.onResume(); Session.addListener(listener); update() }
    override fun onPause() { Session.removeListener(listener); super.onPause() }

    private fun update() {
        val s = Session
        titleView.text = if (s.tvName.isNotEmpty()) s.tvName else (tvId?.let { prefs.findPaired(it)?.name } ?: "TV")
        state.text = when (s.state) {
            Session.State.IDLE -> "Iniciando…"
            Session.State.CONNECTING -> "Conectando…"
            Session.State.STREAMING -> (if (s.game) "Transmitindo · modo jogo" else "Transmitindo") + (if (s.transport.isNotEmpty()) " · ${s.transport}" else "")
            Session.State.RECONNECTING -> "Reconectando… (${s.message})"
            Session.State.STANDBY -> "Em espera${if (s.message.isNotEmpty()) " · ${s.message}" else ""}"
            Session.State.ERROR -> "Erro: ${s.message}"
        }
        state.setTextColor(when (s.state) { Session.State.STREAMING -> UI.GREEN; Session.State.ERROR -> UI.BAD; else -> UI.WARN })
        bars.level = when {
            s.state != Session.State.STREAMING -> 0
            s.rtt < 0 -> 1
            s.e2eMs > 200 -> 2
            s.rtt <= 25 && s.drops == 0 && s.tvDrops == 0 -> 4
            s.rtt <= 50 -> 3
            s.rtt <= 100 -> 2
            else -> 1
        }
        stats.text = if (s.state == Session.State.STREAMING)
            "Resolução ${s.width}×${s.height} · ${s.fps} fps · ${s.kbps / 1000f} Mbps (alvo ${s.targetKbps / 1000f})\n" +
            "Rede (ida e volta): ${if (s.rtt >= 0) "${s.rtt} ms" else "medindo…"} · perdas na TV: ${s.tvDrops}/s\n" +
            "Captura → TV: ${if (s.e2eMs >= 0) "${s.e2eMs} ms" else "medindo…"} · áudio: ${if (s.audMs >= 0) "${s.audMs} ms" else "—"}"
        else ""
        devs.text = if (s.state == Session.State.STREAMING)
            "Teclado da TV: ${s.tvKb?.let { "✔ $it" } ?: "✖ não detectado"}\nMouse da TV: ${s.tvMouse?.let { "✔ $it" } ?: "✖ não detectado"}"
        else ""
        a11y.visibility = if (InputAccessibilityService.instance == null) View.VISIBLE else View.GONE
        overlay.visibility = if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
    }
}
