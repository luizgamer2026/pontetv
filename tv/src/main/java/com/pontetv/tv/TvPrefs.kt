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
}
