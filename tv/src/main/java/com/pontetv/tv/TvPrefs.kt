package com.pontetv.tv

import android.content.Context

class TvPrefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("tv", Context.MODE_PRIVATE)

    /** Endereço Bluetooth do último celular conectado (a TV reconecta sozinha nele). */
    var lastHost: String?
        get() = sp.getString("lastHost", null)
        set(v) { sp.edit().putString("lastHost", v).apply() }

    /** Ritmo do mouse escolhido com F9 (índice em HidLink.GAPS_MS). */
    var rateIdx: Int
        get() = sp.getInt("rateIdx", 0)
        set(v) { sp.edit().putInt("rateIdx", v).apply() }

    /** Keepalive do link Bluetooth (F10 liga/desliga). */
    var keepAlive: Boolean
        get() = sp.getBoolean("keepAlive", true)
        set(v) { sp.edit().putBoolean("keepAlive", v).apply() }

    /** Modo do bloqueio de teclas: 0 = desligado, 1 = inteligente (padrão), 2 = forte. Vale antes e depois de conectar o celular. */
    var filterMode: Int
        get() = sp.getInt("filterMode", 1)
        set(v) { sp.edit().putInt("filterMode", v).apply() }

    /** Último modo ligado (1 ou 2): é para ele que Scroll Lock / ↑ ↓ voltam quando o bloqueio estava desligado. */
    var lastOnMode: Int
        get() = sp.getInt("lastOnMode", 1).coerceIn(1, 2)
        set(v) { sp.edit().putInt("lastOnMode", v).apply() }

    /** true = transporte BLE (HOGP); false = Bluetooth clássico. */
    var ble: Boolean
        get() = sp.getBoolean("ble", false)
        set(v) { sp.edit().putBoolean("ble", v).apply() }
}
