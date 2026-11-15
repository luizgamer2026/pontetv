package com.pontetv.tv

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.util.UUID

class TvPrefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("tv", Context.MODE_PRIVATE)
    private val cr = ctx.applicationContext.contentResolver

    val tvId: String
        get() {
            var v = sp.getString("id", null)
            if (v == null) { v = UUID.randomUUID().toString(); sp.edit().putString("id", v).apply() }
            return v
        }

    val tvName: String
        get() {
            val n: String? = try { Settings.Global.getString(cr, "device_name") } catch (e: Exception) { null }
            return if (!n.isNullOrBlank()) n else (Build.MODEL ?: "Android TV")
        }

    private fun auth(): JSONObject = try { JSONObject(sp.getString("auth", "{}")!!) } catch (e: Exception) { JSONObject() }

    @Synchronized fun authorize(phoneId: String, name: String, key: String) {
        val a = auth()
        a.put(phoneId, JSONObject().put("key", key).put("name", name))
        sp.edit().putString("auth", a.toString()).apply()
    }

    @Synchronized fun isAuthorized(phoneId: String, key: String): Boolean =
        key.isNotEmpty() && auth().optJSONObject(phoneId)?.optString("key") == key

    fun count(): Int = auth().length()
    @Synchronized fun clear() { sp.edit().putString("auth", "{}").apply() }
}
