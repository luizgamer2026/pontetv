package com.pontetv.tv

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager

/** Dica de desempenho (Android 12+): avisa ao sistema qual thread é crítica para ele não deixar a CPU "dormir" nela. */
object Adpf {
    fun create(ctx: Context?, tid: Int): Any? {
        if (Build.VERSION.SDK_INT < 31 || ctx == null) return null
        return try {
            ctx.getSystemService(PerformanceHintManager::class.java)?.createHintSession(intArrayOf(tid), 2_000_000L)
        } catch (e: Throwable) { null }
    }

    fun report(session: Any?, ns: Long) {
        if (session == null || Build.VERSION.SDK_INT < 31) return
        try { (session as PerformanceHintManager.Session).reportActualWorkDuration(ns) } catch (_: Throwable) {}
    }
}
