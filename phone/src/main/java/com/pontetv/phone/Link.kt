package com.pontetv.phone

import com.pontetv.common.Bin
import com.pontetv.common.Proto
import com.pontetv.common.Wire
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class LinkStats(val kbps: Int, val fps: Int, val drops: Int, val maxQ: Int, val rtt: Int)

/**
 * Conexão do celular com a TV (TCP, TCP_NODELAY). Fila de envio curta: se a rede atrasar,
 * os quadros P pendentes são descartados e um novo quadro-chave é pedido ao encoder
 * (mantém a imagem "ao vivo" em vez de acumular atraso).
 */
class Link(
    private val host: String, private val port: Int,
    private val phoneId: String, private val phoneName: String, private val key: String,
    private val cb: Callback, private val role: String = "stream"
) {
    interface Callback {
        fun onConnected(tvName: String, tvMaxLong: Int)
        fun onTvStats(latMs: Int, audMs: Int, fps: Int, drops: Int)
        fun onTvDevices(kb: String?, mouse: String?)
        fun onCommand(json: String)
        fun onAppsRequest()
        fun onLost(reason: String)
        fun onInput(data: ByteArray)
        fun onKeyframeRequest()
        fun onRemoteStop()
        fun onGiveUp(reason: String)
    }

    private class Out(val type: Int, val head: ByteArray, val body: ByteArray, val video: Boolean)

    @Volatile private var running = true
    @Volatile private var wire: Wire? = null
    @Volatile private var waitKey = true
    @Volatile var rttMs = -1
    private val q = LinkedBlockingDeque<Out>()
    private val samples = ArrayList<Pair<Long, Long>>() // (rtt, delta) para sincronizar relógios
    @Volatile private var udp: UdpOut? = null
    @Volatile private var useUdp = false
    @Volatile private var udpOk = false
    @Volatile private var udpStart = 0L
    val transport: String get() = if (useUdp) "UDP" else "TCP"

    fun sendControl(type: Int, data: ByteArray) {
        if (wire != null) q.add(Out(type, data, ByteArray(0), false))
    }
    @Volatile private var cfgVideo: Pair<ByteArray, ByteArray>? = null
    @Volatile private var cfgAudio: ByteArray? = null

    private val bytes = AtomicLong()
    private val frames = AtomicInteger()
    private val drops = AtomicInteger()
    @Volatile private var maxQ = 0

    fun start() { Thread({ loop() }, "link").apply { isDaemon = true; start() } }

    fun stop(sendStop: Boolean) {
        running = false
        val w = wire
        Thread {
            try { if (sendStop) w?.send(Proto.T_STOP) } catch (_: Exception) {}
            w?.close()
        }.start()
    }

    fun setVideoConfig(w: Int, h: Int, csd: ByteArray) {
        val head = Bin.videoConfigHead(w, h)
        cfgVideo = head to csd
        if (wire != null) {
            q.removeIf { it.video }
            waitKey = true
            q.add(Out(Proto.T_VIDEO_CONFIG, head, csd, false))
            cb.onKeyframeRequest()
        }
    }

    fun setAudioConfig(rate: Int, ch: Int) {
        val b = Bin.audioConfig(rate, ch)
        cfgAudio = b
        if (wire != null) q.add(Out(Proto.T_AUDIO_CONFIG, b, ByteArray(0), false))
    }

    fun offerVideo(pts: Long, key: Boolean, data: ByteArray) {
        if (wire == null) { waitKey = true; return }
        frames.incrementAndGet()
        if (waitKey && !key) { drops.incrementAndGet(); return }
        if (useUdp) {
            if (key) waitKey = false
            if (udpStart == 0L) udpStart = System.nanoTime()
            udp?.sendFrame(pts, key, data)
            bytes.addAndGet(data.size.toLong())
            return
        }
        var vc = 0
        for (o in q) if (o.video) vc++
        if (vc > maxQ) maxQ = vc
        if (vc >= 6) {
            q.removeIf { it.video }
            drops.addAndGet(vc)
            if (!key) { waitKey = true; cb.onKeyframeRequest(); return }
        }
        if (key) waitKey = false
        q.add(Out(Proto.T_VIDEO_FRAME, Bin.frameHead(pts, key), data, true))
    }

    fun offerAudio(pts: Long, data: ByteArray) {
        if (wire == null) return
        if (useUdp) { udp?.sendAudio(pts, data); bytes.addAndGet(data.size.toLong()); return }
        var ac = 0
        for (o in q) if (!o.video && o.type == Proto.T_AUDIO_DATA) ac++
        if (ac >= 80) {
            val it = q.iterator()
            while (it.hasNext()) { val o = it.next(); if (o.type == Proto.T_AUDIO_DATA) { it.remove(); break } }
        }
        q.add(Out(Proto.T_AUDIO_DATA, Bin.long(pts), data, false))
    }

    fun takeStats(seconds: Float): LinkStats {
        val b = bytes.getAndSet(0); val f = frames.getAndSet(0); val d = drops.getAndSet(0)
        val mq = maxQ; maxQ = 0
        return LinkStats((b * 8 / 1000 / seconds).toInt(), (f / seconds).toInt(), d, mq, rttMs)
    }

    private fun loop() {
        var fails = 0
        var reason = "sem conexão"
        while (running) {
            var w: Wire? = null
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.sendBufferSize = 128 * 1024
                s.connect(InetSocketAddress(host, port), 3000)
                s.soTimeout = 6000
                val ww = Wire(s)
                w = ww
                ww.send(Proto.T_HELLO, JSONObject().put("mode", "stream").put("role", role).put("id", phoneId)
                    .put("name", phoneName).put("secret", key).toString().toByteArray())
                val r = ww.read()
                val j = JSONObject(String(r.data))
                if (r.type != Proto.T_HELLO_OK) {
                    ww.close()
                    cb.onGiveUp(j.optString("reason", "TV recusou a conexão"))
                    return
                }
                fails = 0
                q.clear(); samples.clear(); waitKey = true; rttMs = -1
                wire = ww
                cfgVideo?.let { ww.send(Proto.T_VIDEO_CONFIG, it.first, it.second) }
                cfgAudio?.let { ww.send(Proto.T_AUDIO_CONFIG, it) }
                val up = j.optInt("udpPort", 0)
                udp?.close(); udp = null; useUdp = false; udpOk = false; udpStart = 0L
                if (up > 0) { try { udp = UdpOut(host, up); useUdp = true } catch (_: Exception) {} }
                cb.onConnected(j.optString("name", host), j.optInt("maxLong", 0))
                Thread({ writer(ww) }, "link-w").apply { isDaemon = true; start() }
                Thread({ pinger(ww) }, "link-p").apply { isDaemon = true; start() }
                readLoop(ww)
                reason = "conexão encerrada"
            } catch (e: Exception) {
                reason = e.message ?: e.javaClass.simpleName
            }
            wire = null
            useUdp = false
            udp?.close(); udp = null
            w?.close()
            if (!running) break
            cb.onLost(reason)
            fails++
            if (fails > 30) { cb.onGiveUp("TV não respondeu: $reason"); return }
            try { Thread.sleep(1000) } catch (_: InterruptedException) {}
        }
    }

    private fun writer(w: Wire) {
        try {
            while (running && wire === w) {
                val o = q.poll(200, TimeUnit.MILLISECONDS) ?: continue
                w.send(o.type, o.head, o.body)
                bytes.addAndGet((o.head.size + o.body.size).toLong())
            }
        } catch (e: Exception) { w.close() }
    }

    private fun pinger(w: Wire) {
        try {
            while (running && wire === w) {
                w.send(Proto.T_PING, Bin.long(System.nanoTime()))
                if (useUdp && !udpOk && udpStart != 0L && System.nanoTime() - udpStart > 3_000_000_000L) {
                    useUdp = false; waitKey = true; cb.onKeyframeRequest() // UDP não chegou: volta para TCP
                }
                Thread.sleep(1000)
            }
        } catch (e: Exception) { w.close() }
    }

    private fun readLoop(w: Wire) {
        while (running) {
            val p = w.read()
            when (p.type) {
                Proto.T_PONG -> {
                    val bb = ByteBuffer.wrap(p.data)
                    val t0 = bb.long
                    val rtt = System.nanoTime() - t0
                    rttMs = (rtt / 1_000_000).toInt()
                    if (p.data.size >= 16) {
                        val t1 = bb.long
                        samples.add(rtt to (t0 + rtt / 2 - t1))
                        if (samples.size > 10) samples.removeAt(0)
                        val best = samples.minByOrNull { it.first }!!
                        w.send(Proto.T_CLOCK, Bin.long(best.second))
                    }
                }
                Proto.T_UDP_OK -> udpOk = true
                Proto.T_CMD -> cb.onCommand(String(p.data))
                Proto.T_APPS_REQ -> cb.onAppsRequest()
                Proto.T_STATS -> if (p.data.size >= 16) {
                    val bb = ByteBuffer.wrap(p.data)
                    cb.onTvStats(bb.int, bb.int, bb.int, bb.int)
                }
                Proto.T_DEVICES -> try {
                    val j = JSONObject(String(p.data))
                    cb.onTvDevices(j.optString("kb").ifEmpty { null }, j.optString("mouse").ifEmpty { null })
                } catch (_: Exception) {}
                Proto.T_PING -> w.send(Proto.T_PONG, p.data)
                Proto.T_INPUT -> cb.onInput(p.data)
                Proto.T_REQ_KEYFRAME -> cb.onKeyframeRequest()
                Proto.T_STOP -> { running = false; cb.onRemoteStop(); return }
            }
        }
    }
}
