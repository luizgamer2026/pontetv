package com.pontetv.tv

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException

/**
 * Modo "USB direto": o app lê o teclado USB da TV pela porta USB, sem passar pelo sistema Android da TV.
 *
 * Por quê: o bloqueio de teclas por acessibilidade faz TODA tecla (e todo movimento do mouse) passar pelo system_server da TV e esperar até
 * 500 ms pela resposta do serviço; numa TV de ~900 MB isso acumulava até ~2 s de atraso e o teclado "morria". Aqui o app pega a interface HID
 * do teclado (claimInterface com force: o Android solta o driver dele), lê os relatórios de 8 bytes (modificadores + 6 teclas) e os manda
 * como estão pelo Bluetooth. Como o sistema nunca vê as teclas, F1…F12/Windows/Esc não agem na TV e não há nada para bloquear.
 *
 * Só roda enquanto o teclado controla o celular (start/stop). Ao soltar (F8/VOLTAR), o app devolve a interface (releaseInterface) e o Android
 * religa o driver do teclado. Se algo falhar (sem permissão, sem teclado, erro), o teclado continua funcionando pelo caminho normal do Android.
 * O mouse continua pelo Android (captura do ponteiro): assim a roda de rolagem e os botões extras seguem funcionando.
 */
object UsbDirect {
    private const val ACTION_PERM = "com.pontetv.tv.USB_PERMISSION"

    /** Preferência do usuário (F11 ou botão do painel). */
    @Volatile var enabled = true
    /** true = o app está lendo o teclado direto neste instante. */
    @Volatile var active = false
    @Volatile var status = "parado"
    @Volatile var reports = 0
    @Volatile var lastReportAt = 0L
    @Volatile var errors = 0

    private var app: Context? = null
    private var um: UsbManager? = null
    private val mainH = Handler(Looper.getMainLooper())
    // uma thread só: sessões nunca se sobrepõem (a nova espera a antiga devolver o teclado)
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "PonteTV-USB").apply { isDaemon = true } }
    @Volatile private var wantActive = false
    @Volatile private var gen = 0
    private var askedName: String? = null
    private var askedAt = 0L
    @Volatile private var deniedName: String? = null

    fun init(ctx: Context) {
        if (app != null) return
        val a = ctx.applicationContext
        app = a
        um = a.getSystemService(Context.USB_SERVICE) as? UsbManager
        val f = IntentFilter().apply {
            addAction(ACTION_PERM)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(a, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(c: Context?, intent: Intent?) {
            val i = intent ?: return
            val d = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            when (i.action) {
                ACTION_PERM -> {
                    val ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (d != null) { if (ok) { askedName = null; deniedName = null } else deniedName = d.deviceName }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (d != null && (d.deviceName == askedName || d.deviceName == deniedName)) { askedName = null; deniedName = null }
                }
            }
        }
    }

    /** Começa a ler o teclado direto (chamado quando o teclado passa a controlar o celular). Não bloqueia. */
    fun start() {
        if (!enabled) { status = "desligado"; return }
        if (wantActive) return
        wantActive = true
        val g = ++gen
        status = "procurando teclado USB…"
        exec.execute { session(g) }
    }

    /** Para e devolve o teclado ao Android (soltar/F8/app saiu da frente). Não bloqueia: a thread devolve em até ~200 ms. */
    fun stop() {
        wantActive = false
        gen++
        if (!active) status = "parado"
    }

    /** Ao abrir o app: pede a permissão do USB logo (assim a caixa do Android não aparece no meio do jogo). */
    fun prepare() {
        if (!enabled) return
        exec.execute {
            try {
                val m = um ?: return@execute
                val f = find(m) ?: return@execute
                if (!m.hasPermission(f.dev)) askPermission(m, f.dev)
            } catch (_: Throwable) {}
        }
    }

    private class Found(val dev: UsbDevice, val itf: UsbInterface, val ep: UsbEndpoint)

    /** Primeiro teclado "boot" (HID, subclasse 1, protocolo 1) com endpoint de interrupção de entrada. */
    private fun find(m: UsbManager): Found? {
        for (d in m.deviceList.values) {
            for (k in 0 until d.interfaceCount) {
                val itf = d.getInterface(k)
                if (itf.interfaceClass != UsbConstants.USB_CLASS_HID || itf.interfaceSubclass != 1 || itf.interfaceProtocol != 1) continue
                for (j in 0 until itf.endpointCount) {
                    val ep = itf.getEndpoint(j)
                    if (ep.direction == UsbConstants.USB_DIR_IN && ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) return Found(d, itf, ep)
                }
            }
        }
        return null
    }

    private fun askPermission(m: UsbManager, d: UsbDevice) {
        if (deniedName == d.deviceName) { status = "acesso USB negado (aceite na caixa do Android ou desligue o USB direto)"; return }
        status = "aguardando você aceitar o acesso USB na TV"
        val now = SystemClock.uptimeMillis()
        if (askedName == d.deviceName && now - askedAt < 60_000L) return
        askedName = d.deviceName; askedAt = now
        val a = app ?: return
        val pi = PendingIntent.getBroadcast(a, 0, Intent(ACTION_PERM).setPackage(a.packageName), PendingIntent.FLAG_MUTABLE)
        try { m.requestPermission(d, pi) } catch (_: Throwable) {}
    }

    private fun alive(g: Int) = wantActive && gen == g
    private fun nap(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    private fun session(g: Int) {
        while (alive(g)) {
            val m = um
            if (m == null) { status = "esta TV não tem USB host"; nap(1000); continue }
            val f = try { find(m) } catch (_: Throwable) { null }
            if (f == null) { status = "procurando teclado USB…"; nap(500); continue }
            if (!m.hasPermission(f.dev)) { askPermission(m, f.dev); nap(500); continue }
            runOn(g, m, f)
            nap(300)
        }
        active = false
    }

    private fun runOn(g: Int, m: UsbManager, f: Found) {
        val c = try { m.openDevice(f.dev) } catch (_: Throwable) { null }
        if (c == null) { status = "não consegui abrir o teclado USB"; nap(1000); return }
        var claimed = false
        try {
            // pegar a interface faz o Android "remover" o teclado: avisa o InputWatcher para não contar como USB soltando
            InputWatcher.quietUntil = SystemClock.uptimeMillis() + 4000
            claimed = c.claimInterface(f.itf, true)
            if (!claimed) { status = "não consegui pegar o teclado (outro app usando?)"; nap(1000); return }
            c.controlTransfer(0x21, 0x0B, 0, f.itf.id, null, 0, 300) // SET_PROTOCOL: boot (relatório fixo de 8 bytes)
            c.controlTransfer(0x21, 0x0A, 0, f.itf.id, null, 0, 300) // SET_IDLE: só manda quando muda
            active = true
            status = "lendo ${f.dev.productName ?: "o teclado"} direto pelo USB"
            if (!readLoop(g, c, f.ep)) { errors++; status = "o teclado USB parou de responder" }
        } catch (t: Throwable) {
            errors++
            status = "erro: ${t.javaClass.simpleName}"
        } finally {
            active = false
            // nenhuma tecla pode ficar presa no celular
            try { HidLink.send(HidDescriptor.ID_KEYBOARD, ByteArray(HidDescriptor.KEYBOARD_SIZE)) } catch (_: Throwable) {}
            InputWatcher.quietUntil = SystemClock.uptimeMillis() + 4000
            try { if (claimed) c.releaseInterface(f.itf) } catch (_: Throwable) {} // devolve o teclado ao Android
            try { c.close() } catch (_: Throwable) {}
        }
    }

    /** F8 solta, F9/Insert ritmo, F10/Pause keepalive, F11 modo USB, Scroll Lock bloqueio (códigos de uso HID). */
    private fun cmdFor(u: Int): Int = when (u) {
        0x41 -> HubCommands.RELEASE_TOGGLE
        0x42, 0x49 -> HubCommands.RATE
        0x43, 0x48 -> HubCommands.KEEPALIVE
        0x44 -> HubCommands.USB
        0x47 -> HubCommands.FILTER
        else -> 0
    }

    /** true = foi pedido para parar; false = o dispositivo caiu/parou de responder. */
    private fun readLoop(g: Int, c: UsbDeviceConnection, ep: UsbEndpoint): Boolean {
        val size = if (ep.maxPacketSize in 8..64) ep.maxPacketSize else 8
        val buf = ByteBuffer.allocate(size)
        val req = UsbRequest()
        if (!req.initialize(c, ep)) return false
        var prev = ByteArray(8)
        var sent = ByteArray(8)
        var queued = false
        var bad = 0
        try {
            while (alive(g)) {
                if (!queued) {
                    buf.clear()
                    if (!req.queue(buf)) { if (++bad >= 3) return false; nap(20); continue }
                    queued = true
                }
                val done: UsbRequest? = try { c.requestWait(200L) } catch (_: TimeoutException) { continue } catch (_: Exception) { null }
                queued = false
                if (done == null) { if (++bad >= 3) return false; continue }
                bad = 0
                val n = buf.position()
                if (n < 3) continue
                val cur = ByteArray(8)
                System.arraycopy(buf.array(), 0, cur, 0, if (n < 8) n else 8)
                reports++
                lastReportAt = SystemClock.uptimeMillis()
                // teclas de comando do hub: agem aqui e NÃO vão para o celular
                var cmd = 0
                val out = cur.copyOf()
                for (i in 2..7) {
                    val u = cur[i].toInt() and 0xFF
                    if (u == 0) continue
                    val cm = cmdFor(u)
                    if (cm != 0) {
                        out[i] = 0
                        var wasDown = false
                        for (k in 2..7) if ((prev[k].toInt() and 0xFF) == u) wasDown = true
                        if (!wasDown) cmd = cm
                    }
                }
                prev = cur
                if (!out.contentEquals(sent)) {
                    sent = out
                    HidLink.send(HidDescriptor.ID_KEYBOARD, out.copyOf())
                }
                if (cmd != 0) { val cc = cmd; mainH.post { HubCommands.handler?.invoke(cc) } }
            }
            return true
        } finally {
            try { req.cancel() } catch (_: Throwable) {}
            try { req.close() } catch (_: Throwable) {}
        }
    }
}
