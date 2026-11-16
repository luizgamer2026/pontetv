package com.pontetv.tv

import kotlin.math.abs

/** Relógio do celular estimado na TV: relógio_celular = relógio_TV + delta (enviado pelo celular). */
object ClockSync {
    @Volatile var deltaNs = 0L
    @Volatile var has = false
    fun phoneNowUs(): Long = (System.nanoTime() + deltaNs) / 1000
}

/**
 * Sincronismo A/V por timestamp (relógio do celular). Mede a latência de captura até a saída de
 * vídeo e de áudio e atrasa o lado que está mais adiantado: vídeo até 60 ms (limite das filas do
 * decoder, que só tem poucos buffers numa TV com ~900 MB de RAM), áudio até 150 ms.
 */
object AvSync {
    @Volatile var videoDelayMs = 0
    @Volatile var audioDelayMs = 0
    @Volatile var offsetMs = 0

    fun reset() { videoDelayMs = 0; audioDelayMs = 0; offsetMs = 0 }

    fun update() {
        val v = ReceiverCore.decoder.rawLatencyMs()
        val a = ReceiverCore.audio.rawLatencyMs()
        if (!ClockSync.has || v < 0 || a < 0) { reset(); return }
        val diff = (a - v).toInt()
        val nv = 0 // atraso de vídeo desligado: agendar quadros causa trepidação; só o áudio é atrasado
        val na = if (diff < 0) minOf(-diff, 150) else 0
        if (abs(nv - videoDelayMs) > 8 || (nv == 0) != (videoDelayMs == 0)) videoDelayMs = nv
        if (abs(na - audioDelayMs) > 8 || (na == 0) != (audioDelayMs == 0)) audioDelayMs = na
        offsetMs = ((a + audioDelayMs) - (v + videoDelayMs)).toInt()
    }
}
