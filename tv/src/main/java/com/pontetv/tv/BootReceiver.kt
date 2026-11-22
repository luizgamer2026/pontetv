package com.pontetv.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Ao ligar a TV (ou atualizar o app), religa o serviço de bloqueio de teclas se a TV o tiver desligado. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        try { A11yEnabler.enable(ctx) } catch (_: Throwable) {}
    }
}
