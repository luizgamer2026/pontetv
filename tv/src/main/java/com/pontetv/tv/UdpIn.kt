package com.pontetv.tv

import android.os.SystemClock
import com.pontetv.common.Proto
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Recepção UDP de vídeo/áudio. Remonta quadros a partir de pacotes de 1200 bytes; recupera 1 pacote
 * perdido por quadro com a paridade XOR; quadro incompleto é descartado e o decoder pede novo
 * quadro-chave (sem esperar retransmissão: a imagem continua "ao vivo").
 */
class UdpIn(private val port: Int, private val onFirst: () -> Unit) {
    private class Frame(val id: Int, val count: Int, val key: Boolean, val pts: Long) {
        val parts = arrayOfNulls<ByteArray>(count)
        var got = 0
        var fec: ByteArray? = null
        var total = -1
        val t0 = SystemClock.elapsedRealtime()
    }

    private var sock: DatagramSocket? = null
    private val frames = TreeMap<Int, Frame>()
    private var lastDelivered = -1
    private var firstSeen = false
    @Volatile private var doReset = false
    @Volatile private var running = false
    private val lost = AtomicInteger()
    private val recovered = AtomicInteger()

    fun takeLost(): Int = lost.getAndSet(0)
    fun reset() { doReset = true }

    /** Abre o socket e devolve a porta efetiva. */
    fun start(): Int {
        val s = DatagramSocket(null)
        s.reuseAddress = true
        try { s.bind(InetSocketAddress(port)) } catch (e: Exception) { s.bind(InetSocketAddress(0)) }
        try { s.receiveBufferSize = 1 shl 20 } catch (_: Exception) {}
        s.soTimeout = 20
        sock = s
        running = true
        Thread({ loop(s) }, "udp-in").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
        return s.localPort
    }

    private fun loop(s: DatagramSocket) {
        val buf = ByteArray(1500)
        val pk = DatagramPacket(buf, buf.size)
        while (running) {
            if (doReset) { frames.clear(); lastDelivered = -1; firstSeen = false; doReset = false }
            try {
                pk.length = buf.size
                s.receive(pk)
            } catch (e: SocketTimeoutException) {
                pump(); continue
            } catch (e: Exception) {
                if (!running) break
                continue
            }
            val n = pk.length
            if (n < 13) continue
            if (!firstSeen) { firstSeen = true; onFirst() }
            try {
                when (buf[0].toInt()) {
                    Proto.U_VIDEO -> if (n >= 18) video(buf, n)
                    Proto.U_FEC -> if (n >= 20) fec(buf, n)
                    Proto.U_AUDIO -> {
                        val bb = ByteBuffer.wrap(buf, 1, 12)
                        bb.int
                        val pts = bb.long
                        ReceiverCore.audio.write(buf.copyOfRange(13, n), pts)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun video(buf: ByteArray, n: Int) {
        val bb = ByteBuffer.wrap(buf, 1, 17)
        val id = bb.int
        val idx = bb.short.toInt() and 0xFFFF
        val cnt = bb.short.toInt() and 0xFFFF
        val flags = bb.get().toInt()
        val pts = bb.long
        if (cnt == 0 || idx >= cnt || id <= lastDelivered) return
        val f = frames.getOrPut(id) { Frame(id, cnt, flags and 1 != 0, pts) }
        if (idx < f.count && f.parts[idx] == null) {
            f.parts[idx] = buf.copyOfRange(18, n)
            f.got++
        }
        pump()
    }

    private fun fec(buf: ByteArray, n: Int) {
        val bb = ByteBuffer.wrap(buf, 1, 19)
        val id = bb.int
        val cnt = bb.short.toInt() and 0xFFFF
        val flags = bb.get().toInt()
        val pts = bb.long
        val total = bb.int
        if (cnt == 0 || id <= lastDelivered) return
        val f = frames.getOrPut(id) { Frame(id, cnt, flags and 1 != 0, pts) }
        f.fec = buf.copyOfRange(20, n)
        f.total = total
        pump()
    }

    private fun assemble(f: Frame): ByteArray? {
        val p = Proto.UDP_PAYLOAD
        if (f.got == f.count) {
            var len = 0
            for (x in f.parts) len += x!!.size
            val out = ByteArray(len)
            var o = 0
            for (x in f.parts) { System.arraycopy(x!!, 0, out, o, x.size); o += x.size }
            return out
        }
        val fecData = f.fec
        if (f.got == f.count - 1 && fecData != null && f.total > 0) {
            val miss = f.parts.indexOfFirst { it == null }
            val x = fecData.copyOf(p)
            for (part in f.parts) if (part != null) for (j in part.indices) x[j] = (x[j].toInt() xor part[j].toInt()).toByte()
            val missLen = if (miss == f.count - 1) f.total - p * (f.count - 1) else p
            if (missLen <= 0 || missLen > p) return null
            f.parts[miss] = x.copyOf(missLen)
            f.got++
            recovered.incrementAndGet()
            return assemble(f)
        }
        return null
    }

    private fun pump() {
        while (frames.isNotEmpty()) {
            val f = frames.firstEntry().value
            if (f.id <= lastDelivered) { frames.remove(f.id); continue }
            val data = assemble(f)
            if (data != null) {
                ReceiverCore.statFrames.incrementAndGet()
                ReceiverCore.statBytes.addAndGet(data.size.toLong())
                ReceiverCore.decoder.queue(data, 0, data.size, f.pts, f.key)
                lastDelivered = f.id
                frames.remove(f.id)
                continue
            }
            if (frames.size >= 3 || SystemClock.elapsedRealtime() - f.t0 > 60) {
                lost.incrementAndGet()
                val nextKey = frames.higherEntry(f.id)?.value?.key == true
                if (!nextKey) ReceiverCore.decoder.markLoss()
                lastDelivered = f.id
                frames.remove(f.id)
                continue
            }
            break
        }
    }
}
