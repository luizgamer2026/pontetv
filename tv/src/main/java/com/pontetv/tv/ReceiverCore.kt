package com.pontetv.tv

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.pontetv.common.Bin
import com.pontetv.common.Proto
import com.pontetv.common.Wire
import com.pontetv.common.localIpv4
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class RxState { WAITING, STREAMING, LOST }

/** Servidor do receptor: aceita pareamento (PIN/QR) e sessões de transmissão autorizadas. */
object ReceiverCore {
    interface Listener {
        fun onState(s: RxState, phone: String)
        fun onVideoSize(w: Int, h: Int)
        fun onPairing()
        fun onDevices()
    }

    private lateinit var app: Context
    private lateinit var prefs: TvPrefs
    private val main = Handler(Looper.getMainLooper())
    @Volatile var listener: Listener? = null
    @Volatile var port = 0
    @Volatile var pin = ""
    @Volatile var token = ""
    @Volatile var state = RxState.WAITING
    @Volatile var phoneName = ""
    @Volatile var videoW = 0
    @Volatile var videoH = 0
    @Volatile var kbName: String? = null
    @Volatile var mouseName: String? = null
    @Volatile var fpsNow = 0
    @Volatile var mbpsNow = 0f
    @Volatile var latMs = -1
    @Volatile var audMs = -1
    lateinit var tvName: String
    lateinit var kind: String
    private var started = false
    private var failures = 0
    private var seq = 0
    @Volatile private var active: Wire? = null
    @Volatile private var userStopped = false
    private val writer = Executors.newSingleThreadExecutor()

    val decoder = VideoDecoder { requestKeyframe() }
    val audio = AudioPlayer()
    val statFrames = AtomicInteger()
    val statBytes = AtomicLong()

    fun authorizedCount(): Int = prefs.count()
    fun clearAuthorized() { prefs.clear(); main.post { listener?.onPairing() } }
    fun setSurface(s: Surface?) = decoder.setSurface(s)

    fun start(ctx: Context) {
        if (started) return
        started = true
        app = ctx.applicationContext
        prefs = TvPrefs(app)
        tvName = prefs.tvName
        val um = app.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        kind = when {
            um.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION -> "tv"
            app.resources.configuration.smallestScreenWidthDp >= 600 -> "tablet"
            else -> "other"
        }
        rotate()
        Thread({ serve() }, "rx-server").apply { isDaemon = true; start() }
    }

    fun rotate() {
        pin = (100000 + SecureRandom().nextInt(900000)).toString()
        token = hex(8)
        failures = 0
        main.post { listener?.onPairing() }
    }

    private fun hex(n: Int): String {
        val b = ByteArray(n); SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    fun ips(): List<String> = localIpv4()
    fun pairingChanged() { main.post { listener?.onPairing() } }

    fun qrPayload(): String {
        val direct = DirectGroup.active
        val ip = if (direct) DirectGroup.ownerIp else (ips().firstOrNull() ?: "0.0.0.0")
        var s = "pontetv://pair?h=$ip&p=$port&t=$token&i=${prefs.tvId}&n=${Uri.encode(tvName)}&k=$kind"
        if (direct) s += "&ws=${Uri.encode(DirectGroup.ssid)}&wp=${Uri.encode(DirectGroup.pass)}"
        return s
    }

    private fun post(s: RxState) {
        state = s
        val name = phoneName
        main.post { listener?.onState(s, name) }
    }

    /** Chamado pelo InputWatcher quando um teclado/mouse é plugado ou removido. */
    fun devicesChanged(kb: String?, ms: String?) {
        kbName = kb; mouseName = ms
        main.post { listener?.onDevices() }
        sendDevices()
    }

    private fun sendDevices() {
        val w = active ?: return
        val j = JSONObject().put("kb", kbName ?: "").put("mouse", mouseName ?: "")
        writer.execute { try { w.send(Proto.T_DEVICES, j.toString().toByteArray()) } catch (e: Exception) { w.close() } }
    }

    private fun serve() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        try { ss.bind(InetSocketAddress(Proto.DEFAULT_PORT)) } catch (e: Exception) { ss.bind(InetSocketAddress(0)) }
        port = ss.localPort
        pairingChanged()
        registerNsd()
        while (true) {
            val s = try { ss.accept() } catch (e: Exception) { continue }
            Thread({ handle(s) }, "rx-conn").apply { isDaemon = true; start() }
        }
    }

    private fun registerNsd() {
        try {
            val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = "PonteTV-$tvName"
                serviceType = Proto.SERVICE_TYPE
                port = this@ReceiverCore.port
                setAttribute("id", prefs.tvId)
                setAttribute("kind", kind)
                setAttribute("name", tvName)
                ips().firstOrNull()?.let { setAttribute("ip", it) }
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
            })
        } catch (_: Exception) {}
    }

    private fun handle(s: Socket) {
        val w = Wire(s)
        try {
            s.tcpNoDelay = true
            s.soTimeout = 5000
            val hello = w.read()
            if (hello.type != Proto.T_HELLO) { w.close(); return }
            val j = JSONObject(String(hello.data))
            val mode = j.optString("mode")
            val id = j.optString("id")
            val name = j.optString("name", "Celular")
            val secret = j.optString("secret")
            if (mode == "pair") {
                if (secret.isNotEmpty() && (secret == pin || secret == token)) {
                    val key = hex(16)
                    prefs.authorize(id, name, key)
                    w.send(Proto.T_HELLO_OK, JSONObject().put("id", prefs.tvId).put("name", tvName)
                        .put("key", key).put("kind", kind).toString().toByteArray())
                    rotate()
                } else {
                    failures++
                    w.send(Proto.T_HELLO_FAIL, JSONObject().put("reason", "Código incorreto").toString().toByteArray())
                    if (failures >= 5) rotate()
                }
                w.close()
            } else if (mode == "stream") {
                if (!prefs.isAuthorized(id, secret)) {
                    w.send(Proto.T_HELLO_FAIL, JSONObject().put("reason", "Celular não pareado. Pareie de novo.").toString().toByteArray())
                    w.close(); return
                }
                val dm = app.resources.displayMetrics
                val maxLong = maxOf(dm.widthPixels, dm.heightPixels).coerceAtMost(1920)
                w.send(Proto.T_HELLO_OK, JSONObject().put("id", prefs.tvId).put("name", tvName)
                    .put("kind", kind).put("maxLong", maxLong).toString().toByteArray())
                session(w, name)
            } else w.close()
        } catch (e: Exception) {
            w.close()
        }
    }

    private fun session(w: Wire, name: String) {
        active?.close()
        active = w
        userStopped = false
        phoneName = name
        ClockSync.has = false
        AvSync.reset()
        val my = ++seq
        w.socket.soTimeout = 6000
        post(RxState.STREAMING)
        sendDevices()
        Thread({ statsLoop(w) }, "rx-stats").apply { isDaemon = true; start() }
        var phoneStopped = false
        try {
            while (true) {
                val p = w.read()
                when (p.type) {
                    Proto.T_VIDEO_CONFIG -> {
                        val bb = ByteBuffer.wrap(p.data)
                        val cw = bb.int; val ch = bb.int
                        val csd = p.data.copyOfRange(8, p.data.size)
                        videoW = cw; videoH = ch
                        main.post { listener?.onVideoSize(cw, ch) }
                        decoder.configure(cw, ch, csd)
                    }
                    Proto.T_VIDEO_FRAME -> {
                        val bb = ByteBuffer.wrap(p.data)
                        val pts = bb.long
                        val key = bb.get().toInt() == 1
                        statFrames.incrementAndGet(); statBytes.addAndGet(p.data.size.toLong())
                        decoder.queue(p.data, 9, p.data.size - 9, pts, key)
                    }
                    Proto.T_AUDIO_CONFIG -> {
                        val bb = ByteBuffer.wrap(p.data)
                        audio.configure(bb.int, bb.int)
                    }
                    Proto.T_AUDIO_DATA -> {
                        val pts = ByteBuffer.wrap(p.data).long
                        audio.write(p.data.copyOfRange(8, p.data.size), pts)
                    }
                    Proto.T_CLOCK -> {
                        ClockSync.deltaNs = ByteBuffer.wrap(p.data).long
                        ClockSync.has = true
                    }
                    Proto.T_PING -> w.send(Proto.T_PONG, p.data, Bin.long(System.nanoTime()))
                    Proto.T_STOP -> { phoneStopped = true; break }
                }
            }
        } catch (_: Exception) {
        } finally {
            w.close()
            if (active === w) {
                active = null
                audio.release()
                ClockSync.has = false
                if (phoneStopped || userStopped) post(RxState.WAITING)
                else {
                    post(RxState.LOST)
                    main.postDelayed({
                        if (seq == my && state == RxState.LOST && active == null) post(RxState.WAITING)
                    }, 45_000)
                }
            }
        }
    }

    /** 1x por segundo: fecha as estatísticas, ajusta o sincronismo A/V e informa o celular. */
    private fun statsLoop(w: Wire) {
        try {
            while (active === w) {
                Thread.sleep(1000)
                val f = statFrames.getAndSet(0)
                val b = statBytes.getAndSet(0)
                fpsNow = f; mbpsNow = b * 8 / 1_000_000f
                AvSync.update()
                latMs = decoder.latencyMs(); audMs = audio.latencyMs()
                val bb = ByteBuffer.allocate(20).putInt(latMs).putInt(audMs).putInt(f)
                    .putInt(decoder.takeDrops()).putInt(AvSync.offsetMs)
                w.send(Proto.T_STATS, bb.array())
            }
        } catch (e: Exception) { w.close() }
    }

    fun sendInput(b: ByteArray) {
        val w = active ?: return
        writer.execute { try { w.send(Proto.T_INPUT, b) } catch (e: Exception) { w.close() } }
    }

    fun requestKeyframe() {
        val w = active ?: return
        writer.execute { try { w.send(Proto.T_REQ_KEYFRAME) } catch (e: Exception) { w.close() } }
    }

    /** Encerrado pelo usuário na TV: avisa o celular (que não tentará reconectar). */
    fun stopStreaming() {
        val w = active
        userStopped = true
        writer.execute {
            try { w?.send(Proto.T_STOP) } catch (_: Exception) {}
            w?.close()
        }
        if (w == null) post(RxState.WAITING)
    }
}
