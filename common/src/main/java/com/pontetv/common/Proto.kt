package com.pontetv.common

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer

/** Protocolo próprio sobre TCP: [tipo:1][tamanho:4][payload]. O celular conecta na TV. */
object Proto {
    const val SERVICE_TYPE = "_pontetv._tcp."
    const val DEFAULT_PORT = 48750

    const val T_HELLO = 1
    const val T_HELLO_OK = 2
    const val T_HELLO_FAIL = 3
    const val T_VIDEO_CONFIG = 10
    const val T_VIDEO_FRAME = 11
    const val T_AUDIO_CONFIG = 12
    const val T_AUDIO_DATA = 13
    const val T_PING = 20
    const val T_PONG = 21
    const val T_INPUT = 30
    const val T_STOP = 40
    const val T_REQ_KEYFRAME = 41
    const val T_DEVICES = 50 // TV -> celular: teclado/mouse detectados
    const val T_CLOCK = 51   // celular -> TV: diferença de relógio (celular = TV + delta)
    const val T_STATS = 52   // TV -> celular: latência medida, fps, descartes
    const val T_UDP_OK = 53  // TV -> celular: recebeu pacotes UDP (senão o celular volta para TCP)
    const val T_GAME = 54    // celular -> TV: app em primeiro plano é um jogo (1/0)
    const val T_CMD = 55     // TV -> celular: comando JSON (home/back/recents/notifications/launch)
    const val T_APPS = 56    // celular -> TV: lista JSON de apps (pkg, label, ícone base64)
    const val T_APPS_REQ = 57 // TV -> celular: pede a lista de apps

    // UDP (vídeo/áudio): [tipo][...]; vídeo = id(4) idx(2) count(2) flags(1) pts(8) + dados
    const val U_VIDEO = 1
    const val U_AUDIO = 2
    const val U_FEC = 3
    const val UDP_PAYLOAD = 1200
    const val UDP_PORT = 48751

    const val I_KEY = 1
    const val I_MOUSE_REL = 2
    const val I_MOUSE_ABS = 3
    const val I_MOUSE_BTN = 4
    const val I_SCROLL = 5
    const val I_MODE = 6

    const val BTN_LEFT = 1
    const val BTN_RIGHT = 2
    const val BTN_MIDDLE = 4

    const val MAX_PAYLOAD = 6 * 1024 * 1024
}

class Packet(val type: Int, val data: ByteArray)

class Wire(val socket: Socket) {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
    private val lock = Any()

    fun send(type: Int, vararg parts: ByteArray) {
        var n = 0
        for (p in parts) n += p.size
        synchronized(lock) {
            output.writeByte(type)
            output.writeInt(n)
            for (p in parts) output.write(p)
            output.flush()
        }
    }

    fun read(): Packet {
        val t = input.readUnsignedByte()
        val n = input.readInt()
        if (n < 0 || n > Proto.MAX_PAYLOAD) throw IOException("payload inválido: $n")
        val b = ByteArray(n)
        input.readFully(b)
        return Packet(t, b)
    }

    fun close() {
        try { socket.close() } catch (_: Exception) {}
    }
}

object Bin {
    fun long(v: Long): ByteArray = ByteBuffer.allocate(8).putLong(v).array()
    fun frameHead(pts: Long, key: Boolean): ByteArray =
        ByteBuffer.allocate(9).putLong(pts).put(if (key) 1 else 0).array()
    fun videoConfigHead(w: Int, h: Int): ByteArray = ByteBuffer.allocate(8).putInt(w).putInt(h).array()
    fun audioConfig(rate: Int, ch: Int): ByteArray = ByteBuffer.allocate(8).putInt(rate).putInt(ch).array()
}

/** Mensagens de entrada (TV -> celular). */
object InputMsg {
    fun key(down: Boolean, keyCode: Int, meta: Int, unicode: Int, repeat: Int): ByteArray =
        ByteBuffer.allocate(18).put(Proto.I_KEY.toByte()).put((if (down) 0 else 1).toByte())
            .putInt(keyCode).putInt(meta).putInt(unicode).putInt(repeat).array()

    fun rel(dx: Float, dy: Float): ByteArray =
        ByteBuffer.allocate(9).put(Proto.I_MOUSE_REL.toByte()).putFloat(dx).putFloat(dy).array()

    fun abs(x: Float, y: Float): ByteArray =
        ByteBuffer.allocate(9).put(Proto.I_MOUSE_ABS.toByte()).putFloat(x).putFloat(y).array()

    fun button(btn: Int, down: Boolean, x: Float, y: Float): ByteArray =
        ByteBuffer.allocate(11).put(Proto.I_MOUSE_BTN.toByte()).put(btn.toByte())
            .put((if (down) 1 else 0).toByte()).putFloat(x).putFloat(y).array()

    fun scroll(h: Float, v: Float, x: Float, y: Float): ByteArray =
        ByteBuffer.allocate(17).put(Proto.I_SCROLL.toByte()).putFloat(h).putFloat(v).putFloat(x).putFloat(y).array()

    fun mode(game: Boolean): ByteArray = byteArrayOf(Proto.I_MODE.toByte(), if (game) 1 else 0)
}

object H264 {
    private val START = byteArrayOf(0, 0, 0, 1)

    /** Divide um buffer Annex-B em NALs, cada um com start code de 4 bytes. */
    fun split(d: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        val lens = ArrayList<Int>()
        var i = 0
        while (i + 3 <= d.size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0) {
                if (d[i + 2].toInt() == 1) { starts.add(i + 3); lens.add(3); i += 3; continue }
                if (i + 3 < d.size && d[i + 2].toInt() == 0 && d[i + 3].toInt() == 1) {
                    starts.add(i + 4); lens.add(4); i += 4; continue
                }
            }
            i++
        }
        val out = ArrayList<ByteArray>()
        for (k in starts.indices) {
            val s = starts[k]
            val e = if (k + 1 < starts.size) starts[k + 1] - lens[k + 1] else d.size
            if (e > s) out.add(START + d.copyOfRange(s, e))
        }
        return out
    }

    fun type(nal: ByteArray): Int = nal[4].toInt() and 0x1f

    /** Extrai SPS+PPS de um quadro-chave (para encoders que não emitem CODEC_CONFIG separado). */
    fun extractConfig(frame: ByteArray): ByteArray? {
        val ps = split(frame).filter { type(it) == 7 || type(it) == 8 }
        if (ps.none { type(it) == 7 }) return null
        var out = ByteArray(0)
        for (n in ps) out += n
        return out
    }
}

fun localIpv4(): List<String> {
    val res = ArrayList<Pair<Int, String>>()
    val ifs = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
    for (nif in ifs) {
        if (!nif.isUp || nif.isLoopback) continue
        for (a in nif.inetAddresses) {
            if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                val pri = when {
                    nif.name.startsWith("wlan") -> 0
                    nif.name.startsWith("eth") -> 1
                    else -> 2
                }
                res.add(pri to (a.hostAddress ?: continue))
            }
        }
    }
    return res.sortedBy { it.first }.map { it.second }
}
