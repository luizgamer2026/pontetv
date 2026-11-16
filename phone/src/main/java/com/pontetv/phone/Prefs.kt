package com.pontetv.phone

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Paired(val id: String, val name: String, val host: String, val port: Int, val key: String, val kind: String)

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("pontetv", Context.MODE_PRIVATE)

    val deviceId: String
        get() {
            var v = sp.getString("id", null)
            if (v == null) { v = UUID.randomUUID().toString(); sp.edit().putString("id", v).apply() }
            return v
        }
    val deviceName: String get() = Build.MODEL ?: "Android"

    var maxLong: Int
        get() = sp.getInt("maxLong", 1280)
        set(v) { sp.edit().putInt("maxLong", v).apply() }
    var fps: Int
        get() = sp.getInt("fps", 60)
        set(v) { sp.edit().putInt("fps", v).apply() }
    var bitrate: Int
        get() = sp.getInt("bitrate", 8000)
        set(v) { sp.edit().putInt("bitrate", v).apply() }
    var audio: Boolean
        get() = sp.getBoolean("audio", true)
        set(v) { sp.edit().putBoolean("audio", v).apply() }
    var sens: Float
        get() = sp.getFloat("sens", 2f)
        set(v) { sp.edit().putFloat("sens", v).apply() }
    var extraGamesRaw: String
        get() = sp.getString("extraGames", "") ?: ""
        set(v) { sp.edit().putString("extraGames", v).apply() }
    fun extraGames(): Set<String> =
        extraGamesRaw.split(",", " ", "\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    var keymap: String?
        get() = sp.getString("keymap", null)
        set(v) { sp.edit().putString("keymap", v).apply() }

    fun paired(): List<Paired> {
        val a = try { JSONArray(sp.getString("paired", "[]")) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<Paired>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out.add(Paired(o.getString("id"), o.getString("name"), o.getString("host"), o.getInt("port"), o.getString("key"), o.optString("kind", "tv")))
        }
        return out
    }

    fun savePaired(p: Paired) {
        val list = paired().filter { it.id != p.id } + p
        val a = JSONArray()
        for (x in list) a.put(JSONObject().put("id", x.id).put("name", x.name).put("host", x.host).put("port", x.port).put("key", x.key).put("kind", x.kind))
        sp.edit().putString("paired", a.toString()).apply()
    }

    fun removePaired(id: String) {
        val a = JSONArray()
        for (x in paired().filter { it.id != id }) a.put(JSONObject().put("id", x.id).put("name", x.name).put("host", x.host).put("port", x.port).put("key", x.key).put("kind", x.kind))
        sp.edit().putString("paired", a.toString()).apply()
    }

    fun findPaired(id: String): Paired? = paired().firstOrNull { it.id == id }
}

/** Posições (fração da tela em paisagem) dos controles virtuais do jogo. */
object KeyMap {
    val labels = linkedMapOf(
        "JOY" to "Direção (WASD)", "LOOK" to "Câmera (mouse)", "JUMP" to "Pular (Espaço)",
        "SNEAK" to "Agachar (Shift)", "ATTACK" to "Atacar (botão esq.)", "USE" to "Usar (botão dir.)",
        "INV" to "Inventário (E)", "HB1" to "Slot 1", "HB9" to "Slot 9"
    )
    val defaults: Map<String, Pair<Float, Float>> = mapOf(
        "JOY" to (0.16f to 0.70f), "LOOK" to (0.70f to 0.45f), "JUMP" to (0.88f to 0.60f),
        "SNEAK" to (0.80f to 0.80f), "ATTACK" to (0.78f to 0.62f), "USE" to (0.92f to 0.40f),
        "INV" to (0.55f to 0.90f), "HB1" to (0.30f to 0.93f), "HB9" to (0.70f to 0.93f)
    )

    fun load(p: Prefs): MutableMap<String, Pair<Float, Float>> {
        val m = HashMap(defaults)
        val s = p.keymap ?: return m
        try {
            val o = JSONObject(s)
            for (k in defaults.keys) if (o.has(k)) {
                val a = o.getJSONArray(k)
                m[k] = a.getDouble(0).toFloat() to a.getDouble(1).toFloat()
            }
        } catch (_: Exception) {}
        return m
    }

    fun save(p: Prefs, m: Map<String, Pair<Float, Float>>) {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, JSONArray().put(v.first.toDouble()).put(v.second.toDouble()))
        p.keymap = o.toString()
    }
}
