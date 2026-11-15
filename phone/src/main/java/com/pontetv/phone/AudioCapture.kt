package com.pontetv.phone

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection

/** Captura o áudio interno do celular (AudioPlaybackCapture, Android 10+): PCM 16-bit 48 kHz estéreo. */
class AudioCapture(private val projection: MediaProjection, private val sink: (Long, ByteArray) -> Unit) {
    companion object { const val RATE = 48000; const val CH = 2 }

    private var rec: AudioRecord? = null
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        return try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val chunk = RATE / 50 * CH * 2 // 20 ms
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord.Builder().setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(min, chunk * 4))
                .setAudioPlaybackCaptureConfig(cfg).build()
            rec = r
            running = true
            r.startRecording()
            Thread({
                val buf = ByteArray(chunk)
                while (running) {
                    var off = 0
                    while (running && off < chunk) {
                        val n = r.read(buf, off, chunk - off)
                        if (n <= 0) { running = false; break }
                        off += n
                    }
                    if (running) sink(System.nanoTime() / 1000, buf.copyOf())
                }
            }, "audio-cap").apply { isDaemon = true; start() }
            true
        } catch (e: Exception) {
            stop()
            false
        }
    }

    fun stop() {
        running = false
        try { rec?.stop() } catch (_: Exception) {}
        try { rec?.release() } catch (_: Exception) {}
        rec = null
    }
}
