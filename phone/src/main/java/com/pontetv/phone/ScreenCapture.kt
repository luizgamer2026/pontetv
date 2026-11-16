package com.pontetv.phone

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import com.pontetv.common.H264
import java.util.concurrent.CountDownLatch

/** MediaProjection -> VirtualDisplay -> MediaCodec (H.264, hardware, baixa latência). */
class ScreenCapture(
    private val ctx: Context, private val projection: MediaProjection, private val sink: Sink,
    maxLongInit: Int, private val fps: Int, startBitrateKbps: Int
) {
    interface Sink {
        fun onConfig(w: Int, h: Int, csd: ByteArray)
        fun onFrame(pts: Long, key: Boolean, data: ByteArray)
    }

    private val ht = HandlerThread("cap").also { it.start() }
    private val hd = Handler(ht.looper)
    private val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var encoder: MediaCodec? = null
    private var inSurface: Surface? = null
    private var vd: VirtualDisplay? = null
    private var drain: Thread? = null
    @Volatile private var maxLong = maxLongInit
    @Volatile private var bitrate = startBitrateKbps
    @Volatile private var scale = 1f
    @Volatile var outW = 0
    @Volatile var outH = 0
    @Volatile private var configSent = false
    @Volatile private var stopped = false
    @Volatile var paused = false
    private val dpi = ctx.resources.displayMetrics.densityDpi

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) hd.post { applySize() }
        }
    }

    fun start() {
        val latch = CountDownLatch(1)
        var err: Exception? = null
        hd.post {
            try { setup() } catch (e: Exception) { err = e }
            latch.countDown()
        }
        latch.await()
        err?.let { throw it }
    }

    @Suppress("DEPRECATION")
    private fun size(): Pair<Int, Int> {
        val m = DisplayMetrics()
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
        val sw = m.widthPixels; val sh = m.heightPixels
        val s = minOf(1f, maxLong * scale / maxOf(sw, sh).toFloat())
        val w = maxOf(16, (sw * s).toInt() and 15.inv())
        val h = maxOf(16, (sh * s).toInt() and 15.inv())
        return w to h
    }

    private fun setup() {
        val (w, h) = size()
        val (c, s) = makeEncoder(w, h)
        encoder = c; inSurface = s; outW = w; outH = h; configSent = false
        vd = projection.createVirtualDisplay(
            "PonteTV", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, s, null, null
        )
        startDrain(c, w, h)
        dm.registerDisplayListener(listener, hd)
    }

    private fun applySize() {
        if (stopped || paused) return
        val (w, h) = size()
        if (w == outW && h == outH) return
        val old = encoder; val oldS = inSurface
        val (c, s) = makeEncoder(w, h)
        encoder = c; inSurface = s; outW = w; outH = h; configSent = false
        vd?.resize(w, h, dpi)
        vd?.setSurface(s)
        try { drain?.join(300) } catch (_: Exception) {}
        try { old?.stop() } catch (_: Exception) {}
        try { old?.release() } catch (_: Exception) {}
        try { oldS?.release() } catch (_: Exception) {}
        startDrain(c, w, h)
    }

    private fun makeEncoder(w: Int, h: Int): Pair<MediaCodec, Surface> {
        return try { build(w, h, true) } catch (e: Exception) { build(w, h, false) }
    }

    private fun build(w: Int, h: Int, full: Boolean): Pair<MediaCodec, Surface> {
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate * 1000)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        if (full) {
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            f.setInteger(MediaFormat.KEY_PRIORITY, 0)
            f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            f.setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
            f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LATENCY, 1)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val s = c.createInputSurface()
            c.start()
            return c to s
        } catch (e: Exception) {
            c.release()
            throw e
        }
    }

    private fun startDrain(c: MediaCodec, w: Int, h: Int) {
        val t = Thread({ drainLoop(c, w, h) }, "enc-drain")
        t.isDaemon = true
        drain = t
        t.start()
    }

    private fun drainLoop(c: MediaCodec, w: Int, h: Int) {
        val info = MediaCodec.BufferInfo()
        while (!stopped && c === encoder) {
            val i = try { c.dequeueOutputBuffer(info, 10_000) } catch (e: Exception) { break }
            if (i >= 0) {
                try {
                    val buf = c.getOutputBuffer(i)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        buf.get(data)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            configSent = true
                            sink.onConfig(w, h, data)
                        } else {
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            if (key && !configSent) {
                                val cfg = H264.extractConfig(data)
                                if (cfg != null) { configSent = true; sink.onConfig(w, h, cfg) }
                            }
                            // pts precisa estar na base System.nanoTime (usada para medir a latência);
                            // se o encoder usar outra base, troca pelo horário atual.
                            val nowUs = System.nanoTime() / 1000
                            val pts = if (kotlin.math.abs(nowUs - info.presentationTimeUs) < 2_000_000) info.presentationTimeUs else nowUs
                            sink.onFrame(pts, key, data)
                        }
                    }
                } finally {
                    try { c.releaseOutputBuffer(i, false) } catch (_: Exception) {}
                }
            }
        }
    }

    fun requestKeyFrame() {
        try {
            encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: Exception) {}
    }

    fun setBitrate(kbps: Int) {
        bitrate = kbps
        try {
            encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, kbps * 1000) })
        } catch (_: Exception) {}
    }

    fun setMaxLong(v: Int) {
        if (v > 0 && v != maxLong) { maxLong = v; hd.post { applySize() } }
    }

    fun setScale(s: Float) {
        scale = s
        hd.post { applySize() }
    }

    /** Espera: libera o encoder mas mantém o VirtualDisplay/MediaProjection (não pede permissão de novo). */
    fun pause() {
        val latch = CountDownLatch(1)
        hd.post {
            try {
                val old = encoder; val oldS = inSurface
                encoder = null; inSurface = null
                try { vd?.setSurface(null) } catch (_: Exception) {}
                try { drain?.join(300) } catch (_: Exception) {}
                try { old?.stop() } catch (_: Exception) {}
                try { old?.release() } catch (_: Exception) {}
                try { oldS?.release() } catch (_: Exception) {}
                paused = true
            } finally { latch.countDown() }
        }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
    }

    fun resume() {
        hd.post {
            if (stopped || !paused) return@post
            paused = false
            val (w, h) = size()
            val (c, s) = makeEncoder(w, h)
            encoder = c; inSurface = s; outW = w; outH = h; configSent = false
            vd?.resize(w, h, dpi)
            vd?.setSurface(s)
            startDrain(c, w, h)
        }
    }

    fun stop() {
        stopped = true
        hd.post {
            try { dm.unregisterDisplayListener(listener) } catch (_: Exception) {}
            try { vd?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { inSurface?.release() } catch (_: Exception) {}
            encoder = null
            ht.quitSafely()
        }
    }
}
