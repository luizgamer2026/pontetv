package com.pontetv.tv

import android.content.Context

class TvPrefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("tv", Context.MODE_PRIVATE)

    /** Endereço Bluetooth do último celular conectado (a TV reconecta sozinha nele). */
    var lastHost: String?
        get() = sp.getString("lastHost", null)
        set(v) { sp.edit().putString("lastHost", v).apply() }
}
