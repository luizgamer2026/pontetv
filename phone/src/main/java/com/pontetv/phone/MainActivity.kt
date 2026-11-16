package com.pontetv.phone

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 28)
            setBackgroundColor(0xFF0A0F1F.toInt())
        }
        fun line(text: String, size: Float, color: Int, top: Int = 12) {
            root.addView(TextView(this).apply {
                this.text = text; textSize = size; setTextColor(color)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = top })
        }
        line("PonteTV", 30f, 0xFFEAF0FF.toInt(), 0)
        line("Teclado e mouse Bluetooth HID", 20f, 0xFF22D3A6.toInt())
        line("1. Deixe o aplicativo PonteTV HID aberto na Android TV, com o teclado e o mouse conectados à TV.", 16f, 0xFFEAF0FF.toInt())
        line("2. No celular, abra as configurações de Bluetooth e procure por “PonteTV HID”.", 16f, 0xFFEAF0FF.toInt())
        line("3. Toque para parear. Depois disso, o Android deverá reconhecer os periféricos como teclado e mouse Bluetooth nativos.", 16f, 0xFFEAF0FF.toInt())
        line("Não há transmissão de tela, áudio, Wi-Fi ou serviço de acessibilidade. A TV envia relatórios HID diretamente pelo Bluetooth.", 14f, 0xFF8FA0C8.toInt())
        val btn = TextView(this).apply {
            text = "Abrir configurações Bluetooth"; textSize = 16f; setTextColor(0xFF04101F.toInt())
            gravity = android.view.Gravity.CENTER; setPadding(18, 16, 18, 16)
            setBackgroundColor(0xFF5B8CFF.toInt())
            setOnClickListener { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        }
        root.addView(btn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 24 })
        setContentView(root)
    }
}
