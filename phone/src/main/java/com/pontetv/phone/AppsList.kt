package com.pontetv.phone

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Lista de apps do celular (nome + ícone 64 px) para o lançador da TV. */
object AppsList {
    fun build(ctx: Context): String {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
            .take(160)
        val arr = JSONArray()
        for (r in list) {
            val o = JSONObject().put("pkg", r.activityInfo.packageName).put("label", r.loadLabel(pm).toString())
            try {
                val d = r.loadIcon(pm)
                val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, 64, 64)
                d.draw(Canvas(bmp))
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 90, bos)
                o.put("icon", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
            } catch (_: Exception) {}
            arr.put(o)
        }
        return arr.toString()
    }
}
