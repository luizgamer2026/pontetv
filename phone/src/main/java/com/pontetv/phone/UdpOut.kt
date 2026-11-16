package com.pontetv.phone

import com.pontetv.common.Proto
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Envio de vídeo/áudio por UDP: sem espera por retransmissão (o TCP trava a imagem inteira por um pacote perdido).
 * Cada quadro é dividido em pacotes de 1200 bytes + 1 pacote de paridade XOR (recupera 1 pacote perdido).
 */
class UdpOut(host: String, private val port: Int) {
    private val sock = DatagramSocket().apply { sendBufferSize = 512 * 1024 }
    private val addr = InetAddress.getByName(host)
    private var frameId = 0
    private var audioSeq = 0
    private val xor = ByteArray(Proto.UDP_PAYLOAD)
    private val buf = ByteArray(18 + Proto.UDP_PAYLOAD)

    fun sendFrame(pts: Long, key: Boolean, data: ByteArray) {
        val p = Proto.UDP_PAYLOAD
        val n = (data.size + p - 1) / p
        if (n <= 0) return
        val id = frameId++
        val flags = if (key) 1 else 0
        java.util.Arrays.fill(xor, 0)
        try {
            for (i in 0 until n) {
                val off = i * p
                val len = minOf(p, data.size - off)
                ByteBuffer.wrap(buf).put(Proto.U_VIDEO.toByte()).putInt(id).putShort(i.toShort())
                    .putShort(n.toShort()).put(flags.toByte()).putLong(pts)
                System.arraycopy(data, off, buf, 18, len)
                if (n > 1) for (j in 0 until len) xor[j] = (xor[j].toInt() xor data[off + j].toInt()).toByte()
                sock.send(DatagramPacket(buf, 18 + len, addr, port))
            }
            if (n > 1) {
                val fb = ByteArray(20 + p)
                ByteBuffer.wrap(fb).put(Proto.U_FEC.toByte()).putInt(id).putShort(n.toShort())
                    .put(flags.toByte()).putLong(pts).putInt(data.size)
                System.arraycopy(xor, 0, fb, 20, p)
                sock.send(DatagramPacket(fb, fb.size, addr, port))
            }
        } catch (_: Exception) {}
    }

    fun sendAudio(pts: Long, data: ByteArray) {
        try {
            val b = ByteArray(13 + data.size)
            ByteBuffer.wrap(b).put(Proto.U_AUDIO.toByte()).putInt(audioSeq++).putLong(pts)
            System.arraycopy(data, 0, b, 13, data.size)
            sock.send(DatagramPacket(b, b.size, addr, port))
        } catch (_: Exception) {}
    }

    fun close() { try { sock.close() } catch (_: Exception) {} }
}
