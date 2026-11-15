package com.pontetv.tv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class AudioPlayer {
    private class Chunk(val d: ByteArray, val pts: Long, val tEnq: Long)

    private var track: AudioTrack? = null
    private val q = LinkedBlockingQueue<Chunk>()
    @Volatile private var running = false
    @Volatile private var aRaw = -1f
    private var rate = 48000
    private var ch = 2
    private var written = 0L

    fun rawLatencyMs(): Float = aRaw
    fun latencyMs(): Int = if (aRaw < 0) -1 else (aRaw + AvSync.audioDelayMs).toInt()

    @Synchronized fun configure(r: Int, channels: Int) {
        release()
        try {
            rate = r; ch = channels; written = 0; aRaw = -1f
            val mask = if (ch >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate).setChannelMask(mask).build())
                .setBufferSizeInBytes(maxOf(min, rate * ch * 2 / 12))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            t.play()
            track = t
            running = true
            q.clear()
            Thread({ loop(t) }, "audio-out").apply { isDaemon = true; start() }
        } catch (e: Exception) { track = null }
    }

    private fun loop(t: AudioTrack) {
        while (running) {
            val c = q.poll(100, TimeUnit.MILLISECONDS) ?: continue
            val maxQ = 8 + AvSync.audioDelayMs / 20
            while (q.size > maxQ) q.poll() // mantém o áudio "ao vivo"
            try {
                val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                val queuedMs = (written - head).coerceAtLeast(0) * 1000f / rate
                if (ClockSync.has) {
                    val raw = (ClockSync.phoneNowUs() - c.pts) / 1000f + queuedMs
                    if (raw in 0f..5000f) aRaw = if (aRaw < 0) raw else aRaw * 0.9f + raw * 0.1f
                }
                val wait = (c.tEnq + AvSync.audioDelayMs * 1_000_000L - System.nanoTime()) / 1_000_000L
                if (wait > 0) Thread.sleep(wait)
                t.write(c.d, 0, c.d.size)
                written += c.d.size / (2 * ch)
            } catch (_: Exception) { break }
        }
    }

    fun write(d: ByteArray, pts: Long) { if (running) q.offer(Chunk(d, pts, System.nanoTime())) }

    @Synchronized fun release() {
        running = false
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
        q.clear()
        aRaw = -1f
    }
}
