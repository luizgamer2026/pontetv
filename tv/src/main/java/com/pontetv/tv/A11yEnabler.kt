package com.pontetv.tv

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Liga o serviço de bloqueio de teclas direto nas configurações seguras, sem abrir tela nenhuma.
 * Só funciona depois de uma vez: adb shell pm grant com.pontetv.tv android.permission.WRITE_SECURE_SETTINGS
 * (a permissão fica guardada; o serviço é religado ao abrir o app e a cada vez que a TV liga).
 */
object A11yEnabler {
    fun canWrite(ctx: Context): Boolean =
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    fun enable(ctx: Context): Boolean {
        if (!canWrite(ctx)) return false
        return try {
            val id = ComponentName(ctx, KeyFilterService::class.java).flattenToString()
            val cur = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            if (!cur.split(':').contains(id)) {
                Settings.Secure.putString(
                    ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    if (cur.isEmpty()) id else "$cur:$id"
                )
            }
            Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (e: Exception) { false }
    }
}
