package com.pontetv.phone

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("pontetv", Context.MODE_PRIVATE)

    /** Endereço Bluetooth da última TV pareada por este app (aparece no topo da lista). */
    var lastTv: String?
        get() = sp.getString("lastTv", null)
        set(v) { sp.edit().putString("lastTv", v).apply() }
}
