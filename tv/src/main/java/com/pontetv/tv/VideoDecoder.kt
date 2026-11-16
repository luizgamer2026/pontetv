package com.pontetv.tv

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.pontetv.common.H264
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Decodificação H.264 por hardware direto para a Surface. Mede a latência captura→saída do decoder
 * (relógios sincronizados) e, se o áudio estiver atrasado, agenda a exibição do quadro (AvSync).
 */
class VideoDecoder(private val requestKey: () -> Unit) {
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var cfg: Triple<Int, Int, ByteArray>? = null
    @Volatile private var needKey = true
    private var lastReq = 0L
    @Volatile private var vRaw = -1f
    private val drops = AtomicInteger()

    fun rawLatencyMs(): Float = vRaw

    /** Quadro perdido na rede: descarta os P seguintes até o próximo quadro-chave. */
    fun markLoss() { needKey = true; drops.incrementAndGet(); askKey() }
    fun latencyMs(): Int = if (vRaw < 0) -1 else (vRaw + AvSync.videoDelayMs).toInt()
    fun takeDrops(): Int = drops.getAndSet(0)

    @Synchronized fun setSurface(s: Surface?) {
        surface = s
        if (s == null) release() else startIfReady()
    }

    @Synchronized fun configure(w: Int, h: Int, csd: ByteArray) {
        cfg = Triple(w, h, csd)
        release()
        startIfReady()
    }

    @Synchronized private fun startIfReady() {
        val s = surface ?: return
        val (w, h, csd) = cfg ?: return
        if (codec != null || !s.isValid) return
        try {
            val nals = H264.split(csd)
            val sps = nals.firstOrNull { H264.type(it) == 7 }
            val pps = nals.firstOrNull { H264.type(it) == 8 }
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
            if (sps != null) f.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            if (pps != null) f.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            // TV com ~900 MB de RAM: buffer de entrada proporcional ao quadro
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minOf(1 shl 20, maxOf(256 * 1024, w * h)))
            f.setInteger(MediaFormat.KEY_PRIORITY, 0)
            f.setInteger("low-latency", 1) // KEY_LOW_LATENCY (Android 11+ na TV); ignorado se não houver suporte
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(f, s, null, 0)
            c.start()
            codec = c
            needKey = true
            vRaw = -1f
            Thread({ outLoop(c) }, "dec-out").apply { isDaemon = true; start() }
            askKey()
        } catch (e: Exception) {
            codec = null
        }
    }

    @Synchronized fun release() {
        val c = codec
        codec = null
        if (c != null) {
            try { c.stop() } catch (_: Exception) {}
            try { c.release() } catch (_: Exception) {}
        }
    }

    private fun askKey() {
        val now = System.currentTimeMillis()
        if (now - lastReq < 200) return
        lastReq = now
        requestKey()
    }

    fun queue(data: ByteArray, off: Int, len: Int, pts: Long, key: Boolean) {
        val c = synchronized(this) { codec } ?: return
        if (needKey && !key) { drops.incrementAndGet(); return }
        try {
            val i = c.dequeueInputBuffer(8_000)
            if (i < 0) { needKey = true; drops.incrementAndGet(); askKey(); return }
            val b = c.getInputBuffer(i) ?: return
            b.clear()
            b.put(data, off, len)
            c.queueInputBuffer(i, 0, len, pts, 0)
            if (key) needKey = false
        } catch (e: Exception) {
            needKey = true
        }
    }

    private fun outLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            if (synchronized(this) { codec } !== c) break
            val i = try { c.dequeueOutputBuffer(info, 10_000) } catch (e: Exception) { break }
            if (i >= 0) {
                try {
                    if (ClockSync.has) {
                        val raw = (ClockSync.phoneNowUs() - info.presentationTimeUs) / 1000f
                        if (raw in 0f..5000f) vRaw = if (vRaw < 0) raw else vRaw * 0.9f + raw * 0.1f
                    }
                    val d = AvSync.videoDelayMs
                    if (d > 0 && ClockSync.has) c.releaseOutputBuffer(i, System.nanoTime() + d * 1_000_000L)
                    else c.releaseOutputBuffer(i, true)
                } catch (e: Exception) { break }
            }
        }
    }
}
