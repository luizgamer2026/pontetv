package com.pontetv.tv

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import java.util.concurrent.Executors

/**
 * Liga o serviço de bloqueio de teclas direto nas configurações seguras, sem abrir tela nenhuma.
 * Só funciona depois de uma vez: adb shell pm grant com.pontetv.tv android.permission.WRITE_SECURE_SETTINGS
 * (a permissão fica guardada; o serviço é religado ao abrir o app e a cada vez que a TV liga).
 */
object A11yEnabler {
    private val ex = Executors.newSingleThreadExecutor()
    @Volatile private var lastKick = 0L

    fun canWrite(ctx: Context): Boolean =
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    private fun id(ctx: Context) = ComponentName(ctx, KeyFilterService::class.java).flattenToString()

    fun enable(ctx: Context): Boolean {
        if (!canWrite(ctx)) return false
        return try {
            val id = id(ctx)
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

    /** Igual a [enable], mas fora da thread que chamou (gravar nas configurações seguras é uma chamada ao sistema). [done] roda na thread de trabalho. */
    fun enableAsync(ctx: Context, done: ((Boolean) -> Unit)? = null) {
        val app = ctx.applicationContext
        ex.execute { val r = enable(app); done?.invoke(r) }
    }

    /**
     * O serviço pode ficar "ligado na lista" e não estar rodando (o Android não religa sozinho um serviço de acessibilidade que caiu,
     * e [enable] não grava nada quando o nome já está na lista). Aqui tira da lista, espera um instante e põe de novo: o sistema religa.
     * Só roda se o serviço não está conectado, no máximo uma vez a cada 15 s (a menos que [force]).
     */
    fun kick(ctx: Context, force: Boolean) {
        if (!canWrite(ctx) || KeyFilterService.instance != null) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastKick < 15_000L) return
        lastKick = now
        val app = ctx.applicationContext
        ex.execute {
            try {
                val id = id(app)
                val cur = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                val parts = cur.split(':').filter { it.isNotEmpty() }
                if (parts.contains(id)) {
                    Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, parts.filter { it != id }.joinToString(":"))
                    Thread.sleep(600)
                }
                enable(app)
            } catch (_: Throwable) {}
        }
    }
}
